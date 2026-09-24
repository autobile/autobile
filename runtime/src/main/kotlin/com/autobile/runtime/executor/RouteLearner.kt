package com.autobile.runtime.executor

import com.autobile.ai.task.AgentAction
import com.autobile.ai.task.AgentActionType
import com.autobile.core.common.Ids
import com.autobile.core.model.ActionSpec
import com.autobile.core.model.Direction
import com.autobile.core.model.ExpectedState
import com.autobile.core.model.FallbackPolicy
import com.autobile.core.model.Locator
import com.autobile.core.model.LocatorKind
import com.autobile.core.model.ResolverKind
import com.autobile.core.model.ScreenSemantics
import com.autobile.core.model.SkillStep
import com.autobile.core.model.StepIntent
import com.autobile.core.model.TargetSemantics
import com.autobile.core.model.UiNode
import com.autobile.core.model.ValidationMode
import com.autobile.core.model.ValidationSpec
import com.autobile.core.model.ValueRef

/**
 * Turns what the goal agent did into steps that replay without it.
 *
 * An agent turn costs a model call and seconds of latency. Once it has found a route that
 * works, repeating the reasoning on every later run is waste: the route can be written
 * down the way a demonstration is, and the next run replays it with no inference at all,
 * falling back to the agent only where the screen differs again.
 *
 * Only actions that can be found again by meaning are learned: an element with an id, a
 * label or a description, a text field with a stable identity, back, a wait, or opening
 * an app. A tap at a coordinate on a canvas is not a route, it is one frame of a game,
 * so a pursuit containing one produces nothing to learn.
 */
internal object RouteLearner {

    /** A replayable step for [action], or null when it cannot be replayed by meaning. */
    fun stepFor(
        action: AgentAction,
        element: UiNode?,
        packageName: String,
        windowTitle: String,
        openedPackage: String?,
    ): SkillStep? = when (action.type) {
        AgentActionType.TAP -> element?.takeIf { it.hasIdentity() }?.let { node ->
            elementStep(node, packageName, windowTitle, StepIntent.SELECT_ITEM, ActionSpec.Click, "Tap")
        }

        AgentActionType.LONG_PRESS -> element?.takeIf { it.hasIdentity() }?.let { node ->
            val duration = action.durationMs.takeIf { it > 0 } ?: DEFAULT_LONG_PRESS_MS
            elementStep(node, packageName, windowTitle, StepIntent.SELECT_ITEM, ActionSpec.LongPress(duration), "Long-press")
        }

        AgentActionType.INPUT_TEXT -> element?.takeIf { it.editable && it.hasIdentity() && action.text.isNotEmpty() }
            ?.let { node ->
                elementStep(
                    node,
                    packageName,
                    windowTitle,
                    StepIntent.ENTER_TEXT,
                    ActionSpec.InputText(ValueRef.Literal(action.text), action.clearExisting),
                    "Enter text in",
                )
            }

        AgentActionType.SCROLL -> element?.takeIf { it.scrollable && !it.resourceId.isNullOrBlank() }?.let { node ->
            val direction = when (action.direction.trim().lowercase()) {
                "up" -> Direction.UP
                "left" -> Direction.LEFT
                "right" -> Direction.RIGHT
                else -> Direction.DOWN
            }
            elementStep(node, packageName, windowTitle, StepIntent.SCROLL_TO, ActionSpec.Scroll(direction), "Scroll")
        }

        AgentActionType.BACK -> SkillStep(
            id = Ids.step(),
            intent = StepIntent.GO_BACK,
            target = TargetSemantics("Previous screen"),
            preferredResolver = ResolverKind.DIRECT_API,
            action = ActionSpec.Back,
            validation = ValidationSpec(mode = ValidationMode.NONE),
            description = "Go back",
        )

        AgentActionType.WAIT -> SkillStep(
            id = Ids.step(),
            intent = StepIntent.WAIT,
            target = TargetSemantics("Wait for the app"),
            preferredResolver = ResolverKind.DIRECT_API,
            action = ActionSpec.Wait(action.durationMs.takeIf { it > 0 } ?: DEFAULT_WAIT_MS),
            validation = ValidationSpec(mode = ValidationMode.NONE),
            description = "Wait",
        )

        AgentActionType.OPEN_APP -> openedPackage?.let(::launchStep)

        AgentActionType.SWIPE, AgentActionType.NONE -> null
    }

    fun launchStep(packageName: String): SkillStep = SkillStep(
        id = Ids.step(),
        intent = StepIntent.LAUNCH_APP,
        target = TargetSemantics(packageName.substringAfterLast('.')),
        preferredResolver = ResolverKind.DIRECT_API,
        action = ActionSpec.LaunchApp(packageName),
        expectedState = ExpectedState(requiredPackage = packageName),
        validation = ValidationSpec(mode = ValidationMode.STRUCTURAL),
        description = "Open ${packageName.substringAfterLast('.')}",
    )

    /**
     * Marks a learned step as one that may not be needed next time.
     *
     * A pop-up the agent closed once may never appear again. As an optional step with no
     * reasoning allowed, its absence costs one lookup of the recorded locator and nothing
     * more; with reasoning allowed, a missing pop-up would send every later run looking
     * for it with a model.
     */
    fun asInterruption(step: SkillStep): SkillStep = step.copy(
        optional = true,
        fallback = FallbackPolicy(
            allowSemanticSearch = false,
            allowDeviceAi = false,
            allowLocalLlm = false,
            allowCloudAi = false,
            allowVision = false,
            maxRetries = 0,
            onFailure = com.autobile.core.model.FailureAction.SKIP,
        ),
        description = "If shown: ${step.description}",
    )

    private fun elementStep(
        node: UiNode,
        packageName: String,
        windowTitle: String,
        intent: StepIntent,
        action: ActionSpec,
        verb: String,
    ): SkillStep {
        // A field is named by what identifies it, not by what someone typed into it.
        val label = if (node.editable) {
            listOfNotNull(node.hint, node.contentDescription, node.resourceId?.substringAfterLast('/'))
                .firstOrNull { it.isNotBlank() }.orEmpty()
        } else {
            node.label()
        }
        val locators = buildList {
            node.resourceId?.takeIf { it.isNotBlank() }?.let { add(Locator(LocatorKind.RESOURCE_ID, it, packageName, 1f)) }
            if (!node.editable) node.text?.takeIf { it.isNotBlank() }?.let { add(Locator(LocatorKind.TEXT, it, strength = 0.7f)) }
            node.contentDescription?.takeIf { it.isNotBlank() }?.let {
                add(Locator(LocatorKind.CONTENT_DESCRIPTION, it, strength = 0.8f))
            }
        }
        return SkillStep(
            id = Ids.step(),
            intent = intent,
            target = TargetSemantics(
                intentLabel = label,
                description = label,
                locators = locators,
                screen = ScreenSemantics(label = windowTitle.ifBlank { packageName }, packageName = packageName),
            ),
            preferredResolver = ResolverKind.ACCESSIBILITY_NODE,
            action = action,
            expectedState = ExpectedState(requiredPackage = packageName),
            // The agent already confirmed where this route leads. Asking a model to agree
            // again after every replayed tap would put back the cost learning removes.
            validation = ValidationSpec(mode = ValidationMode.NONE),
            description = "$verb \"$label\"",
        )
    }

    private const val DEFAULT_LONG_PRESS_MS = 600L
    private const val DEFAULT_WAIT_MS = 1_000L
}
