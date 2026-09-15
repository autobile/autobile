package com.autobile.runtime.resolver

import android.graphics.Bitmap
import com.autobile.ai.context.ContextMinimizer
import com.autobile.ai.router.AiRuntimeRouter
import com.autobile.ai.task.AiTasks
import com.autobile.core.model.InferenceRequirements
import com.autobile.core.model.LocatorKind
import com.autobile.core.model.ResolverKind
import com.autobile.core.model.RuntimeTier
import com.autobile.core.model.ScreenSnapshot
import com.autobile.core.model.TargetSemantics
import com.autobile.core.model.UiNode
import com.autobile.core.model.boundingBox
import com.autobile.runtime.perception.ScreenshotMasking
import com.autobile.runtime.perception.ScreenshotCapture

/**
 * Finds the element a step is talking about.
 *
 * Resolution is staged from cheapest to most capable, and it stops at the first stage
 * that produces a confident answer:
 *
 * 1. **Cached locators** — the resource id or hierarchy path recorded when the skill was
 *    compiled. Free, and correct for the overwhelming majority of runs.
 * 2. **Textual matching** — the step's description and synonyms against on-screen labels.
 *    Still free, and handles the common case of a renamed or relocated control.
 * 3. **Inference** — a model picks from the shortlist. Reached only when meaning has to
 *    be interpreted rather than matched.
 * 4. **Vision** — the same question against a screenshot, for interfaces whose labels do
 *    not survive into the accessibility tree.
 *
 * The staging is what keeps a stable automation free of inference calls entirely, and it
 * is why a skill keeps working after a redesign that a recorded coordinate would not
 * survive.
 */
class ExecutionResolver(
    private val router: AiRuntimeRouter,
    private val minimizer: ContextMinimizer = ContextMinimizer(),
    /**
     * Whether a screenshot has sensitive regions painted out before it is sent.
     *
     * A setting rather than a constant because the user owns the decision, and a
     * function rather than a value because they may change it between runs.
     */
    private val maskScreenshots: () -> Boolean = { true },
) {

    suspend fun resolve(
        target: TargetSemantics,
        snapshot: ScreenSnapshot,
        /**
         * Supplies a screenshot, only if the ladder gets far enough to need one.
         *
         * Lazy on purpose. Capturing is slow and rate-limited by the platform, and the
         * common case never reaches it: a step that matches by recorded id has no use
         * for a picture of the screen, and paying for one on every step would tax the
         * fast path to serve the rare one.
         */
        screenshot: suspend () -> ScreenshotCapture? = { null },
        allowInference: Boolean = true,
        allowVision: Boolean = true,
        localOnly: Boolean = false,
        /**
         * True when the step types. A typing step can only act on something that accepts
         * text, so this is a constraint on what may be matched at all, not a preference
         * applied after the other strategies have had their say.
         */
        requireEditable: Boolean = false,
        /** Skip a node that already failed to reach the expected state. */
        forceVision: Boolean = false,
        /** Visual points that were already acted on without reaching the expected state. */
        avoidedVisualPoints: List<Pair<Float, Float>> = emptyList(),
    ): Resolution {
        if (forceVision) {
            return lookOrGiveUp(
                target,
                snapshot,
                screenshot,
                allowVision,
                localOnly,
                "The accessibility action did not reach the expected state",
                avoidedVisualPoints,
            )
        }

        // What the step means constrains what can answer it. Taking a match that cannot
        // do the job, because its label happened to fit, is what a recorded macro does:
        // the run then dispatches text at a layout and is told the target does not accept
        // text input, having had a field on the same screen all along.
        val usable: (UiNode) -> Boolean = { !requireEditable || it.editable }

        matchByLocator(target, snapshot, usable)?.let { return it }
        matchByText(target, snapshot, usable)?.let { return it }
        if (requireEditable) matchEditableField(snapshot)?.let { return it }

        if (!allowInference) {
            return Resolution.NeedsReasoning("No deterministic match for \"${target.intentLabel}\"")
        }

        val candidates = minimizer.relevantNodes(snapshot, target.matchTerms()).filter(usable)
        if (candidates.isEmpty()) {
            return lookOrGiveUp(
                target,
                snapshot,
                screenshot,
                allowVision,
                localOnly,
                "No candidate elements on this screen",
                avoidedVisualPoints,
            )
        }

        val prompt = AiTasks.elementMatchPrompt(
            targetDescription = target.description.ifBlank { target.intentLabel },
            synonyms = target.synonyms,
            renderedNodes = minimizer.renderNodes(candidates),
        )

        val routed = router.infer(
            label = "element-match",
            schema = AiTasks.elementMatch,
            prompt = prompt,
            systemInstruction = AiTasks.SYSTEM_INSTRUCTION,
            requirements = InferenceRequirements(
                minConfidence = INFERENCE_CONFIDENCE_THRESHOLD,
                localOnly = localOnly,
                estimatedInputTokens = minimizer.estimateTokens(prompt),
            ),
        )

        routed.value?.takeIf { it.found && it.index in candidates.indices }?.let { match ->
            return Resolution.Found(
                node = candidates[match.index],
                resolver = ResolverKind.ACCESSIBILITY_NODE,
                tier = routed.tier,
                confidence = minOf(match.confidence, routed.confidence),
                explanation = match.reason.ifBlank { "matched by meaning" },
                usedCloud = routed.usedCloud,
                cloudWasDecisive = routed.cloudWasDecisive,
            )
        }

        val capture = if (allowVision) screenshot() else null
        val picture = (capture as? ScreenshotCapture.Success)?.bitmap
        if (picture == null) {
            if (capture is ScreenshotCapture.SecureWindowBlocked) return checkNotNull(capture.blockedResolution())
            val unresolved = unresolved(routed.result.error, target)
            // A runtime outage is the reason this step cannot proceed even if pixels
            // are unavailable too. Preserve it as deferrable instead of converting a
            // temporary provider outage into a permanent-looking screen failure.
            if (unresolved is Resolution.NeedsReasoning) return unresolved
            capture?.blockedResolution()?.let { return it }
            return unresolved
        }

        // Still within the tree: the same elements, looked at rather than read. Cheap,
        // because the answer stays an element with everything known about it.
        val visually = resolveVisually(target, snapshot, picture, candidates, localOnly)
        if (visually is Resolution.Found) return visually

        // Nothing the accessibility tree offers can answer this. The tree being
        // non-empty was never the point — it is full of scrolling layouts and wrappers
        // that name nothing a person would recognise, and choosing the best of them is
        // how a step ends up typing into a ScrollView. Looking at the screen is what
        // remains, and it is what the specification asks for at this rung.
        return lookOrGiveUp(
            target,
            snapshot,
            { ScreenshotCapture.Success(picture) },
            allowVision,
            localOnly,
            visuallyUnresolved(visually, target),
            avoidedVisualPoints,
        )
    }

    /** The screenshot as it is fit to leave the device. */
    private fun prepared(screenshot: Bitmap, snapshot: ScreenSnapshot): Bitmap =
        if (maskScreenshots()) ScreenshotMasking.mask(screenshot, snapshot) else screenshot

    private fun visuallyUnresolved(resolution: Resolution, target: TargetSemantics): String = when (resolution) {
        is Resolution.NotFound -> resolution.reason
        is Resolution.NeedsReasoning -> resolution.reason
        else -> "Nothing on this screen offers \"${target.intentLabel}\""
    }

    /**
     * The last rung: ask about the screen itself, or say plainly that nothing could.
     *
     * Reached whenever the accessibility tree has failed, not only when it is empty.
     * Identifying a target from what an app exposes is the fast and precise path, and it
     * is tried to exhaustion first — but when it has nothing to offer, refusing to look
     * is refusing to operate the app at all.
     */
    private suspend fun lookOrGiveUp(
        target: TargetSemantics,
        snapshot: ScreenSnapshot,
        screenshot: suspend () -> ScreenshotCapture?,
        allowVision: Boolean,
        localOnly: Boolean,
        reasonIfBlind: String,
        avoidedVisualPoints: List<Pair<Float, Float>>,
    ): Resolution {
        if (!allowVision) return Resolution.NotFound(reasonIfBlind)
        return when (val capture = screenshot()) {
            null -> Resolution.NotFound(reasonIfBlind)
            is ScreenshotCapture.Success -> resolveByLooking(
                target,
                snapshot,
                capture.bitmap,
                localOnly,
                avoidedVisualPoints,
            )
            is ScreenshotCapture.SecureWindowBlocked -> Resolution.VisionBlocked(
                "Screen capture is blocked for ${capture.packageName.ifBlank { "this protected app" }}",
                secureWindow = true,
            )
            is ScreenshotCapture.Unavailable -> Resolution.VisionBlocked(capture.reason, secureWindow = false)
        }
    }

    private fun ScreenshotCapture.blockedResolution(): Resolution.VisionBlocked? = when (this) {
        is ScreenshotCapture.Success -> null
        is ScreenshotCapture.SecureWindowBlocked -> Resolution.VisionBlocked(
            "Screen capture is blocked for ${packageName.ifBlank { "this protected app" }}",
            secureWindow = true,
        )
        is ScreenshotCapture.Unavailable -> Resolution.VisionBlocked(reason, secureWindow = false)
    }

    /**
     * Last resort: ask about the screenshot itself.
     *
     * Some interfaces — canvas-drawn views, games, embedded web content — expose little
     * or nothing through the accessibility tree, and pixels are the only description
     * available. The shortlist is still supplied so the answer maps back to a real,
     * actionable node rather than to raw coordinates.
     */
    /**
     * The field a typing step means, when nothing named it.
     *
     * A text field often has no label of its own — its contents are the value and its
     * placeholder disappears once something is typed — so a screen with exactly one
     * place to type leaves no ambiguity to reason about. The focused field wins where
     * there are several, because that is the one the keyboard is attached to.
     */
    private fun matchEditableField(snapshot: ScreenSnapshot): Resolution? {
        val editable = snapshot.nodes.filter { it.editable && it.enabled && it.visible }
        val node = editable.firstOrNull { it.focused } ?: editable.singleOrNull() ?: return null
        return Resolution.Found(
            node = node,
            resolver = ResolverKind.ACCESSIBILITY_NODE,
            tier = RuntimeTier.DETERMINISTIC,
            confidence = if (node.focused) 0.9f else 0.75f,
            explanation = "the field on this screen",
            usedCloud = false,
            cloudWasDecisive = false,
        )
    }

    /**
     * Finds where to act by looking, with no element to choose from.
     *
     * The answer is a place on screen rather than a node, so the step acts on
     * coordinates. That is what a macro does, and here it is the right thing: when an
     * app draws its own interface there is nothing else to aim at, and refusing to
     * operate those apps at all is a worse answer than aiming carefully.
     */
    private suspend fun resolveByLooking(
        target: TargetSemantics,
        snapshot: ScreenSnapshot,
        screenshot: Bitmap,
        localOnly: Boolean,
        avoidedVisualPoints: List<Pair<Float, Float>>,
    ): Resolution {
        val routed = router.infer(
            label = "point-match",
            schema = AiTasks.pointMatch,
            prompt = AiTasks.pointMatchPrompt(
                targetDescription = target.description.ifBlank { target.intentLabel },
                synonyms = target.synonyms,
                avoidedPoints = avoidedVisualPoints,
            ),
            systemInstruction = AiTasks.SYSTEM_INSTRUCTION,
            // Scaled but never cropped: the answer is a position within the picture, so
            // removing part of it would move everything the answer refers to.
            image = minimizer.cropForInference(prepared(screenshot, snapshot), null),
            requirements = InferenceRequirements(
                needsVision = true,
                minConfidence = INFERENCE_CONFIDENCE_THRESHOLD,
                localOnly = localOnly,
            ),
        )
        val match = routed.value
        return if (match != null && match.found) {
            Resolution.FoundPoint(
                xRatio = match.xRatio,
                yRatio = match.yRatio,
                tier = routed.tier,
                confidence = minOf(match.confidence, routed.confidence),
                explanation = match.reason.ifBlank { "located by looking at the screen" },
                usedCloud = routed.usedCloud,
            )
        } else if (match == null) {
            unresolved(routed.result.error, target, visually = true)
        } else {
            Resolution.NotFound("Nothing on this screen offers \"${target.intentLabel}\"")
        }
    }

    private suspend fun resolveVisually(
        target: TargetSemantics,
        snapshot: ScreenSnapshot,
        screenshot: Bitmap,
        candidates: List<UiNode>,
        localOnly: Boolean,
    ): Resolution {
        val prompt = AiTasks.elementMatchPrompt(
            targetDescription = target.description.ifBlank { target.intentLabel },
            synonyms = target.synonyms,
            renderedNodes = minimizer.renderNodes(candidates),
        )
        val routed = router.infer(
            label = "element-match-visual",
            schema = AiTasks.elementMatch,
            prompt = prompt,
            systemInstruction = AiTasks.SYSTEM_INSTRUCTION,
            // Narrowed to the elements actually in question, so the rest of the screen
            // is not sent merely because it happened to be on it.
            image = minimizer.cropForInference(prepared(screenshot, snapshot), candidates.boundingBox()),
            requirements = InferenceRequirements(
                needsVision = true,
                minConfidence = INFERENCE_CONFIDENCE_THRESHOLD,
                localOnly = localOnly,
            ),
        )
        val match = routed.value
        return if (match != null && match.found && match.index in candidates.indices) {
            Resolution.Found(
                node = candidates[match.index],
                resolver = ResolverKind.VISION,
                tier = routed.tier,
                confidence = minOf(match.confidence, routed.confidence),
                explanation = match.reason.ifBlank { "matched visually" },
                usedCloud = routed.usedCloud,
                cloudWasDecisive = routed.cloudWasDecisive,
            )
        } else {
            unresolved(routed.result.error, target, visually = true)
        }
    }

    /**
     * Separates "the element is not here" from "nothing was available to decide".
     *
     * They look identical at the call site and mean opposite things. A model that looked
     * at the screen and found no match will find no match again, so the step has failed.
     * An offline device, an exhausted quota or a busy model has not judged anything yet,
     * and reporting that as a failure would mark a perfectly good automation as broken
     * for reasons that resolve on their own.
     */
    private fun unresolved(
        error: com.autobile.core.model.InferenceError?,
        target: TargetSemantics,
        visually: Boolean = false,
    ): Resolution {
        val suffix = if (visually) ", including visually" else ""
        return if (error?.meansNoRuntimeAvailable == true) {
            Resolution.NeedsReasoning(
                "No runtime is available to identify \"${target.intentLabel}\" right now",
            )
        } else {
            Resolution.NotFound("No element matches \"${target.intentLabel}\"$suffix")
        }
    }

    /**
     * Matches a cached locator against the live tree.
     *
     * Only the locators that identify an element rather than merely describe it are
     * trusted here. A resource id is assigned by the developer and survives layout
     * changes; a hierarchy path survives renaming. Text is handled at the next stage,
     * where a weaker match can be scored instead of accepted outright.
     */
    private fun matchByLocator(
        target: TargetSemantics,
        snapshot: ScreenSnapshot,
        usable: (UiNode) -> Boolean,
    ): Resolution.Found? {
        val byStrength = target.locators.sortedByDescending { it.strength }

        for (locator in byStrength.filter { it.kind == LocatorKind.RESOURCE_ID }) {
            snapshot.nodes.firstOrNull { it.visible && usable(it) && it.resourceId == locator.value }?.let { node ->
                return found(node, "resource id")
            }
        }

        for (locator in byStrength.filter { it.kind == LocatorKind.HIERARCHY_PATH }) {
            snapshot.nodes.firstOrNull { it.visible && usable(it) && it.hierarchyPath() == locator.value }?.let { node ->
                // A path only identifies the right element if what sits there still
                // looks like the recorded target, otherwise a reordered list silently
                // resolves to the wrong row.
                if (labelsAreCompatible(node, target)) return found(node, "hierarchy path")
            }
        }
        return null
    }

    /**
     * Scores on-screen labels against the target's known names.
     *
     * Exact and case-insensitive equality resolve immediately. Anything weaker is only
     * accepted when it is unambiguous: if two elements score equally well, the choice is
     * a judgement call and is handed to a reasoning tier rather than guessed.
     */
    private fun matchByText(
        target: TargetSemantics,
        snapshot: ScreenSnapshot,
        usable: (UiNode) -> Boolean,
    ): Resolution.Found? {
        val terms = target.matchTerms().map { it.lowercase() }.filter { it.isNotBlank() }
        if (terms.isEmpty()) return null

        val actionable = snapshot.nodes.filter { it.visible && it.enabled && usable(it) }
        if (actionable.isEmpty()) return null

        val scored = actionable
            .map { node -> node to textScore(node, terms) }
            .filter { it.second > 0.0 }
            .sortedByDescending { it.second }

        val best = scored.firstOrNull() ?: return null
        if (best.second >= EXACT_MATCH_SCORE) return found(best.first, "exact label", confidence = 0.95f)

        val runnerUp = scored.getOrNull(1)
        val isUnambiguous = runnerUp == null || best.second - runnerUp.second >= AMBIGUITY_MARGIN
        if (best.second >= STRONG_MATCH_SCORE && isUnambiguous) {
            return found(best.first, "label match", confidence = 0.8f)
        }
        return null
    }

    private fun textScore(node: UiNode, terms: List<String>): Double {
        val label = node.label().lowercase().trim()
        if (label.isEmpty()) return 0.0
        var score = 0.0
        for (term in terms) {
            when {
                label == term -> score += EXACT_MATCH_SCORE
                label.replace(WHITESPACE, "") == term.replace(WHITESPACE, "") -> score += EXACT_MATCH_SCORE - 1
                label.startsWith(term) || label.endsWith(term) -> score += 4.0
                label.contains(term) -> score += 3.0
            }
        }
        if (node.clickable) score += 0.5
        return score
    }

    /**
     * Guards a positional match against a wholesale content change: the path is only
     * trusted if the element there still shares vocabulary with the recorded target.
     */
    private fun labelsAreCompatible(node: UiNode, target: TargetSemantics): Boolean {
        val label = node.label().lowercase()
        if (label.isBlank()) return true
        return target.matchTerms().any { label.contains(it.lowercase()) || it.lowercase().contains(label) }
    }

    private fun found(node: UiNode, how: String, confidence: Float = 1f) = Resolution.Found(
        node = node,
        resolver = ResolverKind.ACCESSIBILITY_NODE,
        tier = RuntimeTier.DETERMINISTIC,
        confidence = confidence,
        explanation = how,
        usedCloud = false,
        cloudWasDecisive = false,
    )

    private companion object {
        const val EXACT_MATCH_SCORE = 10.0
        const val STRONG_MATCH_SCORE = 3.0
        const val AMBIGUITY_MARGIN = 1.5
        const val INFERENCE_CONFIDENCE_THRESHOLD = 0.6f
        val WHITESPACE = Regex("\\s+")
    }
}

sealed interface Resolution {
    data class Found(
        val node: UiNode,
        val resolver: ResolverKind,
        val tier: RuntimeTier,
        val confidence: Float,
        val explanation: String,
        val usedCloud: Boolean,
        val cloudWasDecisive: Boolean,
    ) : Resolution {
        /** True when no inference was needed, which is the intended common case. */
        val wasDeterministic: Boolean get() = tier == RuntimeTier.DETERMINISTIC
    }

    /**
     * A place on screen to act, with no element behind it.
     *
     * Produced only when nothing nameable is on screen. The step acts on coordinates,
     * which is fragile by nature, so it carries its own confidence and is never the
     * first thing tried.
     */
    data class FoundPoint(
        val xRatio: Float,
        val yRatio: Float,
        val tier: RuntimeTier,
        val confidence: Float,
        val explanation: String,
        val usedCloud: Boolean,
    ) : Resolution

    /** The element is absent, and no tier believes otherwise. */
    data class NotFound(val reason: String) : Resolution

    /** A decision is needed but reasoning was not permitted for this attempt. */
    data class NeedsReasoning(val reason: String) : Resolution

    /** Visual recovery was required but Android could not provide a screenshot. */
    data class VisionBlocked(val reason: String, val secureWindow: Boolean) : Resolution
}
