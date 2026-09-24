package com.autobile.runtime.executor

import android.graphics.Bitmap
import com.autobile.ai.context.ContextMinimizer
import com.autobile.ai.router.AiRuntimeRouter
import com.autobile.ai.task.AgentAction
import com.autobile.ai.task.AgentActionType
import com.autobile.ai.task.AgentTurn
import com.autobile.ai.task.AgentTurnContext
import com.autobile.ai.task.AgentTurnStatus
import com.autobile.ai.task.AiTasks
import com.autobile.core.common.Ids
import com.autobile.core.common.Logx
import com.autobile.core.common.TimeSource
import com.autobile.core.model.ActionSpec
import com.autobile.core.model.Direction
import com.autobile.core.model.ExecutionEvent
import com.autobile.core.model.ExecutionEventType
import com.autobile.core.model.InferenceErrorKind
import com.autobile.core.model.InferenceRequirements
import com.autobile.core.model.PerceptionResult
import com.autobile.core.model.ResolverKind
import com.autobile.core.model.RiskCategory
import com.autobile.core.model.RiskVerdict
import com.autobile.core.model.RuntimeTier
import com.autobile.core.model.ScreenSnapshot
import com.autobile.core.model.SemanticSkill
import com.autobile.core.model.SkillStep
import com.autobile.core.model.StepIntent
import com.autobile.core.model.TargetSemantics
import com.autobile.core.model.UiNode
import com.autobile.runtime.control.ActionResult
import com.autobile.runtime.control.ScreenActuator
import com.autobile.runtime.perception.ScreenObserver
import com.autobile.runtime.perception.ScreenshotCapture
import com.autobile.runtime.perception.ScreenshotMasking
import com.autobile.runtime.perception.VisualChangeDetector
import com.autobile.runtime.risk.RiskEngine
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * What the goal agent is asked to achieve, and everything the user said about how.
 *
 * @property taskApps the apps the task is about, primary first. Empty for a request that
 *   names no app yet: the agent then opens one itself.
 * @property focus a narrower target inside [objective], such as the recorded step a
 *   recovery has to get past, so the agent does not run ahead of the remaining route.
 */
data class AgentMission(
    val objective: String,
    val completionCriteria: String,
    val taskApps: List<String>,
    val guidance: List<String> = emptyList(),
    val runInstruction: String = "",
    val referenceRoute: List<String> = emptyList(),
    val focus: String = "",
    val knownValues: Map<String, String> = emptyMap(),
    val maxActions: Int = GoalAgent.DEFAULT_ACTION_BUDGET,
    val allowCloud: Boolean = true,
    val allowVision: Boolean = true,
)

/** How a pursuit ended. Exactly one of [completed], [blocked], [awaitingReasoning] or none holds. */
data class AgentOutcome(
    val completed: Boolean,
    val message: String,
    val actionsPerformed: Int,
    val cloudCalls: Int,
    val deviceAiCalls: Int,
    val tier: RuntimeTier,
    val confidence: Float = 0f,
    /** Android refused perception, or the kill switch or a policy denied the run. */
    val blocked: Boolean = false,
    /** No runtime could make the decision; the same run can succeed once one can. */
    val awaitingReasoning: Boolean = false,
    val cancelled: Boolean = false,
    /**
     * Whether any runtime answered at all. A pursuit that never got a decision changed
     * nothing on screen, so the failure that led to it is the one worth reporting.
     */
    val decided: Boolean = false,
    /**
     * The route this pursuit took, as steps that replay without a model, when it reached
     * the goal and every action it performed can be found again by meaning. Empty
     * otherwise: a route with even one coordinate tap in it is not a route.
     */
    val learnedRoute: List<SkillStep> = emptyList(),
)

/**
 * Pursues a goal on a live screen, one observed turn at a time.
 *
 * This is what lets Autobile operate an app it was never shown, finish a game whose
 * moves were never recorded, and get past a reward pop-up that appeared after the
 * demonstration. Each turn observes the screen, asks a model for the next few actions,
 * checks every one of them, performs the ones that pass, and reports what happened on
 * the following turn.
 *
 * The model proposes; this class disposes. A proposal that would touch a system bar,
 * leave for an app nobody named, act on Autobile itself, or commit a purchase or a
 * message without the user's approval is not performed. Crucially, a rejected or failed
 * proposal no longer ends the run: it becomes feedback for the next turn, because on a
 * real phone the first idea is often wrong and the second is usually right. Only a
 * sustained failure to make progress, an exhausted budget, a protected screen, or the
 * user stopping it ends a pursuit early.
 *
 * Completion is never taken on a single say-so. After a turn reports the criteria as
 * met, the screen is observed again: if it is unchanged the report stands, and if
 * anything moved the model has to report completion again on the new screen.
 */
class GoalAgent(
    private val perception: ScreenObserver,
    private val controller: ScreenActuator,
    private val router: AiRuntimeRouter,
    private val riskEngine: RiskEngine,
    private val minimizer: ContextMinimizer = ContextMinimizer(),
    private val maskScreenshots: () -> Boolean = { false },
    private val time: TimeSource = TimeSource.System,
    private val ownPackage: String = "com.autobile",
    /** Maps an app name the model used to an installed, launchable package. */
    private val resolveApp: (String) -> String? = { null },
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) {

    suspend fun pursue(
        mission: AgentMission,
        skill: SemanticSkill,
        step: SkillStep,
        index: Int,
        observer: ExecutionObserver,
        localOnly: Boolean,
    ): AgentOutcome = Pursuit(mission, skill, step, index, observer, localOnly || !mission.allowCloud).run()

    private inner class Pursuit(
        private val mission: AgentMission,
        private val skill: SemanticSkill,
        private val step: SkillStep,
        private val index: Int,
        private val observer: ExecutionObserver,
        private val localOnly: Boolean,
    ) {
        private val budget = mission.maxActions.coerceIn(1, MAX_ACTION_BUDGET)
        private val taskApps = LinkedHashSet(mission.taskApps.filter { it.isNotBlank() && it != ownPackage })
        private val recent = ArrayDeque<String>()
        private val feedback = mutableListOf<String>()
        private val declined = mutableSetOf<String>()
        private var memory = ""
        private var actionsPerformed = 0
        private var cloudCalls = 0
        private var deviceAiCalls = 0
        private var lastTier = RuntimeTier.DETERMINISTIC
        private var preferredTier: RuntimeTier? = null
        private var lastConfidence = 0f
        private var previousFrame: Bitmap? = null
        private var previousSignature: String? = null
        private var awaitingEffect = false
        private var decisions = 0
        private var lastTurnRejected = false
        private var completionSignature: String? = null
        private var completionFrame: Bitmap? = null
        private val route = mutableListOf<SkillStep>()
        private var routeReplayable = true

        suspend fun run(): AgentOutcome {
            val requirements = InferenceRequirements(localOnly = localOnly)
            var canSee = mission.allowVision && router.hasRuntimeFor(requirements.copy(needsVision = true))
            if (!canSee && !router.hasRuntimeFor(requirements)) {
                return end(message = NO_RUNTIME, awaitingReasoning = true)
            }

            var turns = 0
            var confirmations = 0
            var blockedTurns = 0
            var rejectedTurns = 0
            var inferenceFailures = 0
            var unchangedTurns = 0
            var outsideTurns = 0
            var relaunches = 0

            while (actionsPerformed < budget && turns < budget + EXTRA_TURNS) {
                if (observer.isCancelled()) return end(STOPPED, cancelled = true)
                turns++

                val snapshot = when (val observed = perception.observe(OBSERVE_SETTLE_MS)) {
                    is PerceptionResult.Success -> observed.snapshot
                    is PerceptionResult.BlockedSecureWindow -> return end(PROTECTED_SCREEN, blocked = true)
                    is PerceptionResult.Unavailable -> return end(observed.reason, blocked = true)
                }

                // Autobile's own interface is never something to act on. A run started
                // from the app begins here, which is normal: open the task app for it.
                val inTaskApp = snapshot.packageName in taskApps
                val onAutobile = snapshot.packageName == ownPackage
                outsideTurns = if (inTaskApp || taskApps.isEmpty()) 0 else outsideTurns + 1
                // Starting somewhere else is not a detour worth a model call: open the
                // task app. Later, a few turns outside are allowed, because a dialog or a
                // sign-in sheet can belong to another package and back usually closes it.
                val startedElsewhere = turns == 1 && outsideTurns > 0
                if ((onAutobile || startedElsewhere || outsideTurns > MAX_TURNS_OUTSIDE) && taskApps.isNotEmpty()) {
                    if (relaunches >= MAX_RELAUNCHES) {
                        return end("Could not keep ${taskApps.first()} in front")
                    }
                    relaunches++
                    outsideTurns = 0
                    val primary = taskApps.first()
                    val opened = controller.launchApp(primary)
                    record(ExecutionEventType.ACTION_EXECUTED, "returned to $primary: ${opened.describe}", opened.succeeded)
                    if (!opened.succeeded) return end(opened.describe)
                    route += RouteLearner.launchStep(primary)
                    feedback += "The executor reopened $primary because another app was in front."
                    delay(APP_LAUNCH_SETTLE_MS)
                    resetEffectTracking()
                    continue
                }

                val elements = minimizer.agentElements(snapshot)
                // A screenshot is the slowest part of a turn to send and to read. A screen
                // whose controls are all named is decided from their list; pixels are sent
                // when there is little to name, when the last actions changed nothing the
                // list can see, or when a completion claim has to be checked against them.
                val readable = elements.count { it.isActionable() && it.label().isNotBlank() } >= MIN_READABLE_ELEMENTS
                val stalled = awaitingEffect && previousSignature == snapshot.signature()
                val wantPixels = !readable || stalled || lastTurnRejected || completionFrame != null
                val capture = if (canSee && wantPixels) perception.captureScreenshot() else null
                if (capture is ScreenshotCapture.SecureWindowBlocked) return end(PROTECTED_SCREEN, blocked = true)
                val frame = (capture as? ScreenshotCapture.Success)?.bitmap
                if (capture is ScreenshotCapture.Unavailable && snapshot.nodes.isEmpty()) {
                    inferenceFailures++
                    if (inferenceFailures >= MAX_INFERENCE_FAILURES) {
                        return end(capture.reason, awaitingReasoning = true)
                    }
                    delay(RETRY_BACKOFF_MS * inferenceFailures)
                    continue
                }

                val changed = noteEffectOfLastTurn(snapshot, frame)
                if (changed == false) {
                    unchangedTurns++
                    if (unchangedTurns >= MAX_UNCHANGED_TURNS) {
                        return end("The screen stopped responding to the agent's actions")
                    }
                } else if (changed == true) {
                    unchangedTurns = 0
                }

                // Completion was reported on the previous look. If nothing has moved since,
                // that judgement stands on this fresh observation too, and asking a model
                // to repeat it would only add a round trip.
                if (confirmations == 1 && completionSignature == snapshot.signature() &&
                    (completionFrame == null || frame == null || !VisualChangeDetector.changed(completionFrame!!, frame))
                ) {
                    record(
                        ExecutionEventType.VALIDATION_RESULT,
                        "completion confirmation 2/$REQUIRED_CONFIRMATIONS: screen unchanged since completion was reported",
                        success = true,
                    )
                    return end(COMPLETED, completed = true)
                }
                completionSignature = null
                completionFrame = null
                lastTurnRejected = false

                val prompt = AiTasks.agentTurnPrompt(
                    AgentTurnContext(
                        objective = mission.objective,
                        completionCriteria = mission.completionCriteria,
                        foregroundApp = snapshot.packageName.ifBlank { "unknown" },
                        inTaskApp = inTaskApp,
                        taskApps = taskApps.toList(),
                        turnNumber = turns,
                        actionsLeft = budget - actionsPerformed,
                        elements = minimizer.renderAgentElements(
                            elements,
                            snapshot.screenWidth,
                            snapshot.screenHeight,
                        ),
                        hasImage = frame != null,
                        isAutobile = onAutobile,
                        runInstruction = mission.runInstruction,
                        guidance = mission.guidance,
                        referenceRoute = mission.referenceRoute,
                        focus = mission.focus,
                        knownValues = mission.knownValues,
                        recentActions = recent.toList(),
                        memory = memory,
                        feedback = feedback.toList(),
                        currentDateTime = now(),
                    ),
                )
                feedback.clear()

                val image = frame?.let {
                    minimizer.cropForInference(
                        if (maskScreenshots()) ScreenshotMasking.mask(it, snapshot) else it,
                        null,
                        maxDimension = MAX_IMAGE_DIMENSION,
                    )
                }
                val routed = router.infer(
                    label = LABEL,
                    schema = AiTasks.agentTurn,
                    prompt = prompt,
                    systemInstruction = SYSTEM_INSTRUCTION,
                    image = image,
                    requirements = requirements.copy(
                        needsVision = image != null,
                        minConfidence = MIN_TURN_CONFIDENCE,
                        estimatedInputTokens = minimizer.estimateTokens(prompt),
                    ),
                    maxOutputTokens = MAX_OUTPUT_TOKENS,
                    preferredTier = preferredTier,
                )
                if (routed.usedCloud) cloudCalls++
                if (routed.tier == RuntimeTier.DEVICE_AI && routed.isSuccess) deviceAiCalls++
                val turn = routed.value
                if (turn == null) {
                    val error = routed.result.error
                    inferenceFailures++
                    val nothingToAsk = routed.attempts.isNotEmpty() && routed.attempts.all { it.skipped }
                    if (nothingToAsk || inferenceFailures >= MAX_INFERENCE_FAILURES) {
                        return end(
                            message = error?.message ?: NO_RUNTIME,
                            awaitingReasoning = error?.kind != InferenceErrorKind.PARSE_FAILED,
                        )
                    }
                    if (error?.kind == InferenceErrorKind.PARSE_FAILED) {
                        feedback += "Your previous reply could not be used (${error.message}). Reply with the JSON fields only."
                    } else {
                        // A runtime that cannot take the picture right now — refused
                        // modality, background restriction, a payload too large — can
                        // still read the element list. Only a screen with no elements
                        // needs pixels to be operated at all.
                        if (image != null && snapshot.nodes.isNotEmpty() && error?.kind in VISION_REFUSALS) {
                            canSee = false
                        }
                        delay(RETRY_BACKOFF_MS * inferenceFailures)
                    }
                    // The screen has not been acted on, so the next observation is not
                    // evidence about any action.
                    awaitingEffect = false
                    continue
                }
                inferenceFailures = 0
                decisions++
                lastTier = routed.tier
                lastConfidence = turn.confidence
                preferredTier = routed.tier.takeUnless { it == RuntimeTier.DETERMINISTIC }
                if (turn.memory.isNotBlank()) memory = turn.memory.take(MEMORY_LIMIT)
                turn.progress.takeIf { it.isNotBlank() }?.let { observer.onAgentProgress(index, it) }
                record(
                    ExecutionEventType.RESOLVER_SELECTED,
                    "agent via ${routed.tier.diagnosticName}: ${turn.describe()}",
                    tier = routed.tier,
                )

                when (turn.status) {
                    AgentTurnStatus.COMPLETE -> {
                        blockedTurns = 0
                        confirmations++
                        val confirmed = confirmations >= REQUIRED_CONFIRMATIONS
                        record(
                            ExecutionEventType.VALIDATION_RESULT,
                            "completion confirmation $confirmations/$REQUIRED_CONFIRMATIONS: ${turn.reason}",
                            success = confirmed,
                        )
                        if (confirmed) return end(COMPLETED, completed = true)
                        completionSignature = snapshot.signature()
                        completionFrame = frame
                        awaitingEffect = false
                        delay(COMPLETION_RECHECK_MS)
                    }

                    AgentTurnStatus.BLOCKED -> {
                        confirmations = 0
                        blockedTurns++
                        if (blockedTurns >= MAX_BLOCKED_TURNS) {
                            return end(turn.reason.ifBlank { "The agent found no way to continue" })
                        }
                        feedback += "You reported blocked (${turn.reason}). Look again: a dialog may need closing, " +
                            "the content may need scrolling, back may return to a usable screen, or another app may need opening."
                        awaitingEffect = false
                    }

                    AgentTurnStatus.ACT -> {
                        confirmations = 0
                        blockedTurns = 0
                        when (val batch = perform(turn, snapshot, elements)) {
                            is Batch.Stop -> return batch.outcome
                            is Batch.Done -> {
                                rejectedTurns = if (batch.performed == 0) rejectedTurns + 1 else 0
                                lastTurnRejected = batch.performed == 0
                                if (rejectedTurns >= MAX_REJECTED_TURNS) {
                                    return end("The agent kept proposing actions that could not be performed")
                                }
                                awaitingEffect = batch.performed > 0
                                if (awaitingEffect) {
                                    previousFrame = frame
                                    previousSignature = snapshot.signature()
                                }
                            }
                        }
                    }
                }
            }
            return end("Reached the $budget-action limit before the goal was confirmed")
        }

        /** Performs a turn's actions in order, stopping at the first one that does not go through. */
        private suspend fun perform(turn: AgentTurn, snapshot: ScreenSnapshot, elements: List<UiNode>): Batch {
            var performed = 0
            for ((position, action) in turn.actions.withIndex()) {
                if (observer.isCancelled()) return Batch.Stop(end(STOPPED, cancelled = true))
                if (actionsPerformed >= budget) break
                // Everything after the first action was chosen against the screen as it
                // was before the first one ran. If that screen has gone, so has the plan.
                if (position > 0) {
                    val current = (perception.observe(BATCH_SETTLE_MS) as? PerceptionResult.Success)?.snapshot
                    if (current == null || current.packageName != snapshot.packageName) {
                        feedback += "Stopped after ${position} of ${turn.actions.size} actions because the screen changed."
                        break
                    }
                }

                val rejection = reject(action, snapshot, elements)
                if (rejection != null) {
                    feedback += "Not performed: ${action.summary()}: $rejection"
                    record(ExecutionEventType.ACTION_EXECUTED, "rejected ${action.summary()}: $rejection", success = false)
                    break
                }
                when (val gate = gate(action, snapshot, elements)) {
                    Gate.Allowed -> Unit
                    is Gate.Declined -> {
                        feedback += "The user declined: ${action.summary()}. Do not try it again."
                        declined += action.riskKey(elements)
                        break
                    }
                    is Gate.Denied -> return Batch.Stop(end(gate.reason, blocked = true))
                }

                val result = execute(action, snapshot, elements)
                record(ExecutionEventType.ACTION_EXECUTED, "${action.summary()}: ${result.describe}", result.succeeded)
                if (!result.succeeded) {
                    feedback += "Failed: ${action.summary()}: ${result.describe}"
                    break
                }
                val learned = RouteLearner.stepFor(
                    action = action,
                    element = elements.getOrNull(action.element),
                    packageName = snapshot.packageName,
                    windowTitle = snapshot.windowTitle,
                    openedPackage = if (action.type == AgentActionType.OPEN_APP) resolvePackage(action.app) else null,
                )
                if (learned == null) routeReplayable = false else route += learned
                performed++
                actionsPerformed++
                recent += "${action.summary()} (${action.label.ifBlank { turn.progress }.take(60)})"
                while (recent.size > RECENT_ACTION_LIMIT) recent.removeFirst()
            }
            return Batch.Done(performed)
        }

        /** Why [action] must not be performed on this screen, or null when it may. */
        private fun reject(action: AgentAction, snapshot: ScreenSnapshot, elements: List<UiNode>): String? {
            val element = elements.getOrNull(action.element)
            val onAutobile = snapshot.packageName == ownPackage
            val outside = taskApps.isNotEmpty() && snapshot.packageName !in taskApps
            return when (action.type) {
                AgentActionType.NONE -> "unknown action type; use tap, long_press, swipe, input_text, scroll, back, wait or open_app"
                AgentActionType.WAIT, AgentActionType.BACK -> null
                AgentActionType.OPEN_APP -> if (action.app.isBlank()) "open_app needs the app name" else null
                else -> when {
                    onAutobile -> "Autobile's own screen is never operated; use open_app"
                    outside -> "${snapshot.packageName} is not one of the task apps; use back or open_app"
                    action.element >= 0 && element == null -> "there is no element ${action.element}"
                    element != null && !element.enabled -> "element ${action.element} is disabled"
                    element == null && !action.hasPoint -> "give an element number or x and y"
                    element == null && action.type != AgentActionType.SWIPE && !isInsideApp(action.x, action.y) ->
                        "(${action.x.fmt()}, ${action.y.fmt()}) is on an Android system bar; choose a point inside the app"
                    action.type == AgentActionType.SWIPE && element == null && !action.hasEndPoint ->
                        "swipe needs endX and endY"
                    action.type == AgentActionType.INPUT_TEXT && action.text.isEmpty() -> "input_text needs text"
                    action.riskKey(elements) in declined -> "the user already declined this"
                    else -> null
                }
            }
        }

        /** Applies app policy and, for anything that commits, the user's confirmation. */
        private suspend fun gate(action: AgentAction, snapshot: ScreenSnapshot, elements: List<UiNode>): Gate {
            val targetPackage = when (action.type) {
                AgentActionType.OPEN_APP -> resolvePackage(action.app) ?: return Gate.Allowed
                AgentActionType.BACK, AgentActionType.WAIT -> return Gate.Allowed
                else -> snapshot.packageName
            }
            val proxy = proxyStep(action, elements)
            val categories = action.declaredRisk() +
                riskEngine.categorise(proxy).filter { it in KEYWORD_GATED_CATEGORIES }
            val decision = riskEngine.evaluate(
                skill = skill,
                step = proxy,
                targetPackage = targetPackage,
                // The run itself was already approved. What still needs the user is an
                // action that commits something the user would want to see first.
                userConfirmedThisRun = categories.isEmpty(),
            )
            if (decision.verdict == RiskVerdict.ALLOW) return Gate.Allowed
            record(ExecutionEventType.RISK_DECISION, "${decision.verdict.name}: ${action.summary()} ${decision.reason}".trim())
            return when (decision.verdict) {
                RiskVerdict.ALLOW -> Gate.Allowed
                RiskVerdict.DENY -> if (action.type == AgentActionType.OPEN_APP) {
                    feedback += "${action.app} may not be operated: ${decision.reason}"
                    Gate.Declined
                } else {
                    Gate.Denied(decision.reason)
                }
                RiskVerdict.CONFIRM -> {
                    val approved = observer.requestConfirmation(proxy, decision.copy(categories = categories + decision.categories))
                    record(
                        ExecutionEventType.USER_CONFIRMATION,
                        if (approved) "approved" else "declined",
                        success = approved,
                    )
                    if (approved) Gate.Allowed else Gate.Declined
                }
            }
        }

        private suspend fun execute(action: AgentAction, snapshot: ScreenSnapshot, elements: List<UiNode>): ActionResult {
            val element = elements.getOrNull(action.element)
            return when (action.type) {
                AgentActionType.TAP -> if (element != null) {
                    observer.onTargetResolved(index, element)
                    controller.click(element)
                } else {
                    controller.tapRatio(action.x, action.y)
                }

                AgentActionType.LONG_PRESS -> {
                    val duration = action.durationMs.takeIf { it > 0 }?.coerceIn(MIN_PRESS_MS, MAX_GESTURE_MS) ?: LONG_PRESS_MS
                    if (element != null) {
                        controller.longPress(element, duration)
                    } else {
                        controller.longPressRatio(action.x, action.y, duration)
                    }
                }

                AgentActionType.SWIPE -> {
                    val (startX, startY) = element?.let { centreOf(it, snapshot) } ?: (action.x to action.y)
                    controller.swipeRatio(
                        startX.coerceIn(SWIPE_MIN_X, SWIPE_MAX_X),
                        startY.coerceIn(SWIPE_MIN_Y, SWIPE_MAX_Y),
                        action.endX.coerceIn(SWIPE_MIN_X, SWIPE_MAX_X),
                        action.endY.coerceIn(SWIPE_MIN_Y, SWIPE_MAX_Y),
                        action.durationMs.takeIf { it > 0 }?.coerceIn(MIN_SWIPE_MS, MAX_GESTURE_MS) ?: SWIPE_MS,
                    )
                }

                AgentActionType.INPUT_TEXT -> when {
                    element != null -> controller.inputText(element, action.text, action.clearExisting)
                    action.hasPoint -> {
                        val focused = controller.tapRatio(action.x, action.y)
                        if (!focused.succeeded) {
                            focused
                        } else {
                            delay(TEXT_FOCUS_SETTLE_MS)
                            controller.inputTextAtFocus(action.text, action.clearExisting)
                        }
                    }
                    else -> controller.inputTextAtFocus(action.text, action.clearExisting)
                }

                AgentActionType.SCROLL -> {
                    val direction = when (action.direction.trim().lowercase()) {
                        "up" -> Direction.UP
                        "left" -> Direction.LEFT
                        "right" -> Direction.RIGHT
                        else -> Direction.DOWN
                    }
                    val container = element?.takeIf { it.scrollable } ?: snapshot.nodes.firstOrNull { it.scrollable && it.visible }
                    controller.scroll(container, direction)
                }

                AgentActionType.BACK -> controller.pressBack()

                AgentActionType.WAIT -> {
                    val millis = action.durationMs.takeIf { it > 0 }?.coerceIn(MIN_WAIT_MS, MAX_WAIT_MS) ?: DEFAULT_WAIT_MS
                    delay(millis)
                    ActionResult.Performed("waited $millis ms")
                }

                AgentActionType.OPEN_APP -> {
                    val packageName = resolvePackage(action.app)
                        ?: return ActionResult.Failed("no installed app matches \"${action.app}\"")
                    controller.launchApp(packageName).also { opened ->
                        if (opened.succeeded) {
                            taskApps += packageName
                            delay(APP_LAUNCH_SETTLE_MS)
                        }
                    }
                }

                AgentActionType.NONE -> ActionResult.Failed("no action")
            }
        }

        private fun resolvePackage(app: String): String? {
            val name = app.trim()
            if (name.isEmpty()) return null
            val resolved = taskApps.firstOrNull { it.equals(name, ignoreCase = true) }
                ?: resolveApp(name)
                ?: name.takeIf { PACKAGE_NAME.matches(it) }
            return resolved?.takeIf { it != ownPackage }
        }

        /**
         * Compares the screen with the one the last actions were chosen against.
         *
         * Null when there is nothing to compare, because the last turn performed no
         * action. Pixels are preferred when both frames exist: a game board redraws
         * without changing its accessibility tree at all.
         */
        private fun noteEffectOfLastTurn(snapshot: ScreenSnapshot, frame: Bitmap?): Boolean? {
            if (!awaitingEffect) return null
            awaitingEffect = false
            val before = previousFrame
            val changed = if (before != null && frame != null) {
                VisualChangeDetector.changed(before, frame)
            } else {
                previousSignature != snapshot.signature()
            }
            previousFrame = null
            if (recent.isNotEmpty()) {
                val effect = if (changed) "visible change" else "no visible change"
                recent[recent.lastIndex] = "${recent.last()} => $effect"
            }
            if (!changed) feedback += "Your last actions had no visible effect. Choose a different control or approach."
            return changed
        }

        private fun resetEffectTracking() {
            awaitingEffect = false
            previousFrame = null
            previousSignature = null
        }

        private fun centreOf(node: UiNode, snapshot: ScreenSnapshot): Pair<Float, Float>? {
            val width = snapshot.screenWidth.takeIf { it > 0 } ?: return null
            val height = snapshot.screenHeight.takeIf { it > 0 } ?: return null
            return node.bounds.centerX.toFloat() / width to node.bounds.centerY.toFloat() / height
        }

        private suspend fun record(
            type: ExecutionEventType,
            message: String,
            success: Boolean? = null,
            tier: RuntimeTier? = null,
        ) {
            observer.onEvent(
                ExecutionEvent(
                    id = Ids.event(),
                    taskId = observer.taskId,
                    timestamp = time.nowMillis(),
                    type = type,
                    stepId = step.id,
                    stepIndex = index,
                    message = Logx.redact(message),
                    tier = tier,
                    resolver = ResolverKind.VISION.takeIf { type == ExecutionEventType.RESOLVER_SELECTED },
                    success = success,
                ),
            )
        }

        private fun end(
            message: String,
            completed: Boolean = false,
            blocked: Boolean = false,
            awaitingReasoning: Boolean = false,
            cancelled: Boolean = false,
        ) = AgentOutcome(
            completed = completed,
            message = message,
            actionsPerformed = actionsPerformed,
            cloudCalls = cloudCalls,
            deviceAiCalls = deviceAiCalls,
            tier = lastTier,
            confidence = lastConfidence,
            blocked = blocked,
            awaitingReasoning = awaitingReasoning,
            cancelled = cancelled,
            decided = decisions > 0,
            learnedRoute = if (completed && routeReplayable) route.toList() else emptyList(),
        )

        private fun now(): String = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(
            Instant.ofEpochMilli(time.nowMillis()).atZone(zone()),
        )
    }

    /**
     * A one-action step standing in for what the agent proposed, so that the risk engine
     * and the confirmation prompt judge the agent exactly as they judge a recorded step.
     */
    private fun proxyStep(action: AgentAction, elements: List<UiNode>): SkillStep {
        val label = action.targetLabel(elements)
        val categories = action.declaredRisk()
        val intent = when {
            RiskCategory.MESSAGE_SEND in categories -> StepIntent.SEND
            RiskCategory.EXTERNAL_POST in categories -> StepIntent.SHARE
            RiskCategory.DELETE in categories -> StepIntent.DELETE
            action.type == AgentActionType.INPUT_TEXT -> StepIntent.ENTER_TEXT
            action.type == AgentActionType.OPEN_APP -> StepIntent.LAUNCH_APP
            action.type == AgentActionType.SCROLL -> StepIntent.SCROLL_TO
            else -> StepIntent.SELECT_ITEM
        }
        val spec = when (action.type) {
            AgentActionType.OPEN_APP -> ActionSpec.LaunchApp(action.app)
            AgentActionType.SCROLL -> ActionSpec.Scroll()
            AgentActionType.LONG_PRESS -> ActionSpec.LongPress()
            else -> ActionSpec.Click
        }
        return SkillStep(
            id = "agent-${Ids.step()}",
            intent = intent,
            target = TargetSemantics(intentLabel = label),
            preferredResolver = ResolverKind.VISION,
            action = spec,
            description = label,
        )
    }

    private sealed interface Batch {
        data class Done(val performed: Int) : Batch
        data class Stop(val outcome: AgentOutcome) : Batch
    }

    private sealed interface Gate {
        data object Allowed : Gate
        data object Declined : Gate
        data class Denied(val reason: String) : Gate
    }

    companion object {
        const val LABEL = "agent-turn"
        const val DEFAULT_ACTION_BUDGET = 300
        const val MAX_ACTION_BUDGET = 600

        /**
         * Kept separate from [AiTasks.SYSTEM_INSTRUCTION], which tells a model to answer
         * narrow questions and never guess. An agent has to commit to its best reading of
         * a screen, and is kept safe by the checks around it rather than by abstaining.
         */
        const val SYSTEM_INSTRUCTION: String =
            "You are a careful phone-operating agent. You see the current screen of an Android phone and choose " +
                "the next actions that move the user's task forward. Answer only with the requested JSON. " +
                "Base every action on what is visible now; never invent elements. The executor verifies each action " +
                "and tells you what happened."

        private const val OBSERVE_SETTLE_MS = 250L
        private const val BATCH_SETTLE_MS = 220L
        private const val APP_LAUNCH_SETTLE_MS = 1_200L
        private const val COMPLETION_RECHECK_MS = 700L
        private const val TEXT_FOCUS_SETTLE_MS = 180L
        private const val RETRY_BACKOFF_MS = 1_000L
        private const val MIN_PRESS_MS = 200L
        private const val LONG_PRESS_MS = 600L
        private const val MIN_SWIPE_MS = 50L
        private const val SWIPE_MS = 300L
        private const val MAX_GESTURE_MS = 2_000L
        private const val MIN_WAIT_MS = 100L
        private const val DEFAULT_WAIT_MS = 1_000L
        private const val MAX_WAIT_MS = 5_000L
        private const val MAX_IMAGE_DIMENSION = 1_024
        private const val MAX_OUTPUT_TOKENS = 1_024
        private const val MEMORY_LIMIT = 1_500
        private const val RECENT_ACTION_LIMIT = 12
        private const val REQUIRED_CONFIRMATIONS = 2
        private const val EXTRA_TURNS = 40
        private const val MAX_INFERENCE_FAILURES = 3
        private const val MAX_BLOCKED_TURNS = 2
        private const val MAX_REJECTED_TURNS = 5
        private const val MAX_UNCHANGED_TURNS = 8
        private const val MAX_TURNS_OUTSIDE = 2
        private const val MIN_READABLE_ELEMENTS = 3
        private const val MAX_RELAUNCHES = 4

        /**
         * Self-reported certainty is a poor gate for a turn: models report 0.6 on moves
         * they get right, and the router treating that as a failure is what used to end
         * games after one cautious answer. Safety comes from the checks on each action.
         */
        private const val MIN_TURN_CONFIDENCE = 0.1f

        // Status bar at the top, navigation bar or gesture handle at the bottom. A tap
        // there presses Home, Back or Recents on behalf of the model.
        private const val MIN_TAP_Y = 0.03f
        private const val MAX_TAP_Y = 0.945f
        private const val MIN_TAP_X = 0.01f
        private const val MAX_TAP_X = 0.99f

        // Swipes starting at a side edge are consumed as the system back gesture.
        private const val SWIPE_MIN_X = 0.07f
        private const val SWIPE_MAX_X = 0.93f
        private const val SWIPE_MIN_Y = 0.06f
        private const val SWIPE_MAX_Y = 0.92f

        /**
         * Categories the label of an agent action is checked for by keyword, whatever the
         * model declared. Limited to the ones whose words rarely name anything else:
         * "cancel", "remove" and "erase" are how dialogs close and puzzles are played,
         * and prompting for each would stall every unattended run on its first pop-up.
         * The rest rely on the risk the model declares for the action.
         */
        private val KEYWORD_GATED_CATEGORIES = setOf(
            RiskCategory.PURCHASE,
            RiskCategory.PAYMENT,
            RiskCategory.TRANSFER,
            RiskCategory.SUBSCRIPTION,
        )

        private val VISION_REFUSALS = setOf(
            InferenceErrorKind.UNSUPPORTED,
            InferenceErrorKind.POLICY_BLOCKED,
            InferenceErrorKind.DEVICE_BACKGROUND_RESTRICTED,
            InferenceErrorKind.REQUEST_TOO_LARGE,
        )

        private const val STOPPED = "Stopped"
        private const val COMPLETED = "Goal confirmed on two fresh observations"
        private const val PROTECTED_SCREEN = "The screen is protected and cannot be read"
        private const val NO_RUNTIME = "No reasoning runtime is available to decide the next action"
        private val PACKAGE_NAME = Regex("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+")

        internal fun isInsideApp(x: Float, y: Float): Boolean =
            x in MIN_TAP_X..MAX_TAP_X && y in MIN_TAP_Y..MAX_TAP_Y
    }
}

private fun AgentTurn.describe(): String = buildString {
    append(status.name.lowercase())
    if (observation.isNotBlank()) append(" | ").append(observation)
    if (actions.isNotEmpty()) append(" | ").append(actions.joinToString("; ") { it.summary() })
    if (reason.isNotBlank()) append(" | ").append(reason)
}

private fun AgentAction.summary(label: String = this.label): String = buildString {
    append(type.name.lowercase())
    when {
        element >= 0 -> append(" #").append(element)
        hasPoint -> append(" @").append(x.fmt()).append(',').append(y.fmt())
    }
    if (type == AgentActionType.SWIPE && hasEndPoint) append("→").append(endX.fmt()).append(',').append(endY.fmt())
    if (type == AgentActionType.SCROLL && direction.isNotBlank()) append(' ').append(direction.lowercase())
    if (type == AgentActionType.OPEN_APP) append(' ').append(app)
    if (label.isNotBlank()) append(" \"").append(label.take(40)).append('"')
}

private fun AgentAction.targetLabel(elements: List<UiNode>): String =
    elements.getOrNull(element)?.label()?.takeIf { it.isNotBlank() } ?: label.ifBlank { app }

/** The same proposal made twice is the same proposal, whichever way it was phrased. */
private fun AgentAction.riskKey(elements: List<UiNode>): String =
    "${type.name}:${targetLabel(elements).lowercase()}"

private fun AgentAction.declaredRisk(): Set<RiskCategory> {
    val value = risk.trim().lowercase()
    if (value.isEmpty() || value == "none") return emptySet()
    return RiskCategory.entries.filter { it.name.lowercase() == value }.toSet()
}

/** Cheap identity of a screen's contents, for telling a change from no change. */
private fun ScreenSnapshot.signature(): String = buildString {
    append(packageName).append('|').append(windowTitle).append('|').append(nodes.size)
    nodes.forEach { node ->
        if (node.visible) {
            append('|').append(node.label()).append(node.checked).append(node.selected).append(node.bounds.top)
        }
    }
}

private fun Float.fmt(): String = "%.2f".format(java.util.Locale.ROOT, this)
