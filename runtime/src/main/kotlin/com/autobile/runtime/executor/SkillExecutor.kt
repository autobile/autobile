package com.autobile.runtime.executor

import android.graphics.Bitmap
import com.autobile.ai.context.ContextMinimizer
import com.autobile.ai.router.AiRuntimeRouter
import com.autobile.ai.task.AiTasks
import com.autobile.core.common.Ids
import com.autobile.core.common.Logx
import com.autobile.core.common.TimeSource
import com.autobile.core.data.SkillPatchCandidate
import com.autobile.core.data.SkillStore
import com.autobile.runtime.EnglishRuntimeVocabulary
import com.autobile.runtime.RuntimeVocabulary
import com.autobile.core.model.ActionSpec
import com.autobile.core.model.AgentTask
import com.autobile.core.model.ExecutionEvent
import com.autobile.core.model.ExecutionEventType
import com.autobile.core.model.AutonomyLevel
import com.autobile.core.model.ExecutionStrategy
import com.autobile.core.model.PatchAuthor
import com.autobile.core.model.SkillVersionRecord
import com.autobile.core.model.ExpectedState
import com.autobile.core.model.InferenceRequirements
import com.autobile.core.model.OutcomeStatus
import com.autobile.core.model.PerceptionResult
import com.autobile.core.model.ResolverKind
import com.autobile.core.model.RiskVerdict
import com.autobile.core.model.RuntimeTier
import com.autobile.core.model.ScreenSnapshot
import com.autobile.core.model.SemanticSkill
import com.autobile.core.model.SkillStep
import com.autobile.core.model.StepIntent
import com.autobile.core.model.StepResult
import com.autobile.core.model.TargetSemantics
import com.autobile.core.model.TaskOutcome
import com.autobile.core.model.ValidationMode
import com.autobile.core.model.ValidationOutcome
import com.autobile.core.model.ValidationSpec
import com.autobile.core.model.ValueType
import com.autobile.runtime.control.ActionResult
import com.autobile.runtime.control.ScreenActuator
import com.autobile.runtime.edit.StepChange
import com.autobile.runtime.edit.applyStepChanges
import com.autobile.runtime.perception.ScreenObserver
import com.autobile.runtime.perception.ScreenshotCapture
import com.autobile.runtime.perception.VisualChangeDetector
import com.autobile.runtime.recovery.RecoveryMove
import com.autobile.runtime.recovery.SelfHealingEngine
import com.autobile.runtime.resolver.ExecutionResolver
import com.autobile.runtime.resolver.Resolution
import com.autobile.runtime.risk.RiskEngine
import com.autobile.runtime.validation.ValidationEngine
import kotlinx.coroutines.delay

/**
 * Runs a compiled skill.
 *
 * A recorded route runs step by step. Each step follows the same cycle: observe, check
 * risk, resolve the target, act, validate, and recover if validation failed. That keeps
 * the ordinary run auditable, interruptible, and free of inference when nothing changed.
 *
 * When the route stops fitting the screen, or the user asked for something it cannot
 * express, the [GoalAgent] works toward the goal instead. The executor still owns that
 * loop: the model proposes a few actions per turn, and every one of them is checked
 * against the screen, the app policy and the risk engine before it is performed.
 *
 * Progress is reported through [ExecutionObserver] as it happens rather than returned at
 * the end, because a user watching their phone operate itself needs to see what it is
 * doing while it does it.
 */
class SkillExecutor(
    private val perception: ScreenObserver,
    private val controller: ScreenActuator,
    private val resolver: ExecutionResolver,
    private val validation: ValidationEngine,
    private val healing: SelfHealingEngine,
    private val riskEngine: RiskEngine,
    private val router: AiRuntimeRouter,
    private val skillStore: SkillStore,
    private val minimizer: ContextMinimizer = ContextMinimizer(),
    private val maskScreenshots: () -> Boolean = { false },
    private val time: TimeSource = TimeSource.System,
    private val words: RuntimeVocabulary = EnglishRuntimeVocabulary,
    /**
     * Autobile's own package.
     *
     * An automation operates other apps. If Autobile's interface is what is on screen
     * when a step looks for its target, the run has lost its place, and the one thing
     * that must not happen next is the agent driving its own interface.
     */
    private val ownPackage: String = "com.autobile",
    /** Maps an app name to an installed package, for a goal that names another app. */
    private val resolveApp: (String) -> String? = { null },
) {

    /**
     * The loop that takes over when a recorded route stops matching the screen, and the
     * one that drives a run outright when there is no route worth replaying.
     */
    private val agent = GoalAgent(
        perception = perception,
        controller = controller,
        router = router,
        riskEngine = riskEngine,
        minimizer = minimizer,
        maskScreenshots = maskScreenshots,
        time = time,
        ownPackage = ownPackage,
        resolveApp = resolveApp,
    )

    /**
     * @param runInstruction something the user asked for this run only. Recorded steps
     *   cannot honour an instruction they were not recorded with, so a run that carries
     *   one is driven by the goal agent from the start.
     */
    suspend fun execute(
        skill: SemanticSkill,
        task: AgentTask,
        observer: ExecutionObserver,
        localOnly: Boolean = false,
        userInputs: Map<String, String> = emptyMap(),
        runInstruction: String = "",
    ): TaskOutcome {
        val context = ExecutionContext(skill, task.triggerPayload, userInputs)
        observer.onEvent(event(task.id, ExecutionEventType.SKILL_MATCHED, message = skill.name))

        val goalDriven = skill.strategy == ExecutionStrategy.AGENT_FIRST ||
            runInstruction.isNotBlank() ||
            skill.steps.isEmpty()
        if (goalDriven) return executeGoal(skill, task, observer, localOnly, context, runInstruction.trim())

        val results = mutableListOf<StepResult>()
        var cloudCalls = 0
        var deviceAiCalls = 0
        var confirmedThisRun = false
        var agentRecoveries = 0
        val learned = mutableListOf<StepChange>()

        preconditionFailure(skill, localOnly)?.let { reason ->
            observer.onEvent(
                event(task.id, ExecutionEventType.PRECONDITION_CHECK, message = reason, success = false),
            )
            return TaskOutcome(
                taskId = task.id,
                status = OutcomeStatus.FAILED,
                goalValidated = false,
                completedSteps = 0,
                totalSteps = skill.steps.size,
                cloudCalls = 0,
                deviceAiCalls = 0,
                message = reason,
            )
        }

        for ((index, step) in skill.steps.withIndex()) {
            if (observer.isCancelled()) {
                return partial(task, skill, results, cloudCalls, deviceAiCalls, words.stopped(), OutcomeStatus.CANCELLED)
            }

            val startedAt = time.nowMillis()
            observer.onEvent(
                event(
                    task.id,
                    ExecutionEventType.STEP_STARTED,
                    stepId = step.id,
                    stepIndex = index,
                    message = step.describeForUser(),
                ),
            )
            observer.onStepStarted(index, step)

            val snapshot = when (val observed = perception.observe(step.validation.settleMs)) {
                is PerceptionResult.Success -> observed.snapshot
                is PerceptionResult.BlockedSecureWindow -> {
                    val message = words.screenProtected()
                    results += failedStep(step, index, startedAt, message, ValidationMode.NONE)
                    observer.onEvent(
                        event(task.id, ExecutionEventType.STEP_FAILED, step.id, index, message, success = false),
                    )
                    return partial(task, skill, results, cloudCalls, deviceAiCalls, message, OutcomeStatus.BLOCKED)
                }

                is PerceptionResult.Unavailable -> {
                    results += failedStep(step, index, startedAt, observed.reason, ValidationMode.NONE)
                    return partial(
                        task,
                        skill,
                        results,
                        cloudCalls,
                        deviceAiCalls,
                        observed.reason,
                        OutcomeStatus.BLOCKED,
                    )
                }
            }

            when (val gate = gateStep(skill, step, index, snapshot.packageName, confirmedThisRun, task, observer)) {
                is StepGate.Stop -> {
                    results += failedStep(step, index, startedAt, gate.reason, ValidationMode.NONE)
                    return partial(task, skill, results, cloudCalls, deviceAiCalls, gate.reason, gate.status)
                }
                is StepGate.Proceed -> confirmedThisRun = confirmedThisRun || gate.confirmed
            }

            var outcome = runStep(skill, step, index, snapshot, context, task, observer, localOnly)
            if (shouldHandToAgent(step, outcome)) {
                agentRecoveries++
                // The first failure is usually an interruption: a pop-up, a reward, a
                // moved button. Get past it and go back to the recorded route, which is
                // cheaper and more predictable. A second failure says the route itself no
                // longer fits, so the agent finishes the goal instead of limping step by
                // step through a path that has stopped existing.
                val takeOver = agentRecoveries > 1
                observer.onEvent(
                    event(
                        task.id,
                        ExecutionEventType.RECOVERY_STARTED,
                        step.id,
                        index,
                        if (takeOver) {
                            "handing the rest of the goal to the agent"
                        } else {
                            "recovering this step with the agent from the current screen"
                        },
                    ),
                )
                val mission = if (takeOver) {
                    goalMission(skill, context, runInstruction = "", completedSteps = index)
                } else {
                    stepRecoveryMission(skill, step, index, context)
                }
                val rescue = agent.pursue(mission, skill, step, index, observer, localOnly)
                val rescued = rescue.toStepOutcome(step, index, startedAt, recovered = true)
                val replaced = if (rescue.decided || rescue.blocked || rescue.cancelled) rescued else outcome
                outcome = replaced.copy(
                    cloudCalls = outcome.cloudCalls + rescued.cloudCalls,
                    deviceAiCalls = outcome.deviceAiCalls + rescued.deviceAiCalls,
                )
                observer.onEvent(
                    event(
                        task.id,
                        ExecutionEventType.RECOVERY_COMPLETED,
                        step.id,
                        index,
                        rescue.message,
                        success = rescue.completed,
                    ),
                )
                if (rescue.completed) {
                    learned += if (takeOver) {
                        takeOverChanges(skill, index, rescue.learnedRoute)
                    } else {
                        recoveryChanges(step, rescue.learnedRoute)
                    }
                }
                if (takeOver && rescue.completed) {
                    adoptLearnedRoute(skill, learned, observer)
                    cloudCalls += outcome.cloudCalls
                    deviceAiCalls += outcome.deviceAiCalls
                    results += outcome.result
                    return TaskOutcome(
                        taskId = task.id,
                        status = OutcomeStatus.SUCCESS,
                        goalValidated = true,
                        completedSteps = skill.steps.size,
                        totalSteps = skill.steps.size,
                        cloudCalls = cloudCalls,
                        deviceAiCalls = deviceAiCalls,
                        message = rescue.message,
                        stepResults = results,
                    )
                }
            }
            cloudCalls += outcome.cloudCalls
            deviceAiCalls += outcome.deviceAiCalls
            results += outcome.result

            if (!outcome.result.success && !step.optional) {
                val message = outcome.result.message.ifBlank { words.stepFailed() }
                observer.onEvent(
                    event(task.id, ExecutionEventType.STEP_FAILED, step.id, index, message, success = false),
                )
                // Nothing was available to make the decision, so the run is paused rather
                // than failed: the steps completed so far remain valid and the same
                // automation will succeed once a runtime is reachable again.
                val status = when {
                    outcome.cancelled -> OutcomeStatus.CANCELLED
                    outcome.blocked -> OutcomeStatus.BLOCKED
                    outcome.awaitingReasoning -> OutcomeStatus.DEFERRED
                    else -> OutcomeStatus.PARTIAL
                }
                return partial(task, skill, results, cloudCalls, deviceAiCalls, message, status)
            }
        }

        // Every step succeeding does not by itself mean the goal was reached, so the
        // skill's own post-conditions are checked before anything is reported as done.
        val finalSnapshot = (perception.observe() as? PerceptionResult.Success)?.snapshot
        val goalOutcome = finalSnapshot?.let {
            validation.validateGoal(skill.postconditions, it, localOnly)
        } ?: ValidationOutcome(ValidationMode.NONE, passed = skill.postconditions.isEmpty())

        observer.onEvent(
            event(
                task.id,
                ExecutionEventType.VALIDATION_RESULT,
                message = goalOutcome.reason,
                success = goalOutcome.passed,
            ),
        )

        // A goal nobody could check is not a goal that was missed. Every step did what
        // it was told and each confirmed its own screen, so reporting the automation as
        // broken would be its own kind of lie — on most phones nothing can answer a
        // semantic question, and that is the ordinary case, not a fault. The run is
        // recorded as done with the goal left unconfirmed, which is what happened.
        val everyStepWorked = results.all { it.success }
        val status = when {
            goalOutcome.passed -> OutcomeStatus.SUCCESS
            !goalOutcome.evaluated && everyStepWorked -> OutcomeStatus.SUCCESS
            else -> OutcomeStatus.PARTIAL
        }
        if (status == OutcomeStatus.SUCCESS) adoptLearnedRoute(skill, learned, observer)
        return TaskOutcome(
            taskId = task.id,
            status = status,
            goalValidated = goalOutcome.passed,
            completedSteps = results.count { it.success },
            totalSteps = skill.steps.size,
            cloudCalls = cloudCalls,
            deviceAiCalls = deviceAiCalls,
            message = goalOutcome.reason,
            stepResults = results,
        )
    }

    /**
     * Runs the whole goal through the agent, with the recorded steps as a reference route.
     *
     * The same risk gate a recorded step passes is applied once to the run as a whole, so
     * a blocked app, a watch-only automation and an ask-first automation behave exactly as
     * they do when replayed; every individual action is then gated again by the agent.
     */
    private suspend fun executeGoal(
        skill: SemanticSkill,
        task: AgentTask,
        observer: ExecutionObserver,
        localOnly: Boolean,
        context: ExecutionContext,
        runInstruction: String,
    ): TaskOutcome {
        val mission = goalMission(skill, context, runInstruction, completedSteps = 0)
        val step = SkillStep(
            id = GOAL_STEP_ID,
            intent = StepIntent.NAVIGATE,
            target = TargetSemantics(intentLabel = skill.goal),
            preferredResolver = ResolverKind.VISION,
            action = ActionSpec.VisualTask(mission.objective, mission.completionCriteria, mission.maxActions),
            validation = ValidationSpec(mode = ValidationMode.SEMANTIC, expectation = mission.completionCriteria, goalCritical = true),
            description = skill.goal,
        )
        val startedAt = time.nowMillis()
        observer.onEvent(event(task.id, ExecutionEventType.STEP_STARTED, step.id, 0, step.describeForUser()))
        observer.onStepStarted(0, step)

        fun finish(status: OutcomeStatus, message: String, result: StepResult, cloud: Int = 0, device: Int = 0) =
            TaskOutcome(
                taskId = task.id,
                status = status,
                goalValidated = status == OutcomeStatus.SUCCESS,
                completedSteps = if (result.success) 1 else 0,
                totalSteps = 1,
                cloudCalls = cloud,
                deviceAiCalls = device,
                message = message,
                stepResults = listOf(result),
            )

        val snapshot = when (val observed = perception.observe(step.validation.settleMs)) {
            is PerceptionResult.Success -> observed.snapshot
            is PerceptionResult.BlockedSecureWindow -> {
                val message = words.screenProtected()
                return finish(OutcomeStatus.BLOCKED, message, failedStep(step, 0, startedAt, message, ValidationMode.NONE))
            }
            is PerceptionResult.Unavailable ->
                return finish(OutcomeStatus.BLOCKED, observed.reason, failedStep(step, 0, startedAt, observed.reason, ValidationMode.NONE))
        }
        val gatedPackage = mission.taskApps.firstOrNull() ?: snapshot.packageName
        when (val gate = gateStep(skill, step, 0, gatedPackage, false, task, observer)) {
            is StepGate.Stop ->
                return finish(gate.status, gate.reason, failedStep(step, 0, startedAt, gate.reason, ValidationMode.NONE))
            is StepGate.Proceed -> Unit
        }

        val pursuit = agent.pursue(mission, skill, step, 0, observer, localOnly)
        // An instruction for one run is not how the automation normally works, so the
        // route it produced is not one to keep.
        if (pursuit.completed && runInstruction.isBlank() && pursuit.learnedRoute.isNotEmpty()) {
            val launch = mission.taskApps.firstOrNull()
                ?.takeIf { (pursuit.learnedRoute.first().action as? ActionSpec.LaunchApp) == null }
                ?.let { RouteLearner.launchStep(it) }
            adoptLearnedRoute(
                skill,
                listOf(StepChange.ReplaceAll(listOfNotNull(launch) + pursuit.learnedRoute, ExecutionStrategy.STEPS_FIRST)),
                observer,
            )
        }
        val outcome = pursuit.toStepOutcome(step, 0, startedAt, recovered = false)
        val status = when {
            outcome.result.success -> OutcomeStatus.SUCCESS
            outcome.cancelled -> OutcomeStatus.CANCELLED
            outcome.blocked -> OutcomeStatus.BLOCKED
            outcome.awaitingReasoning -> OutcomeStatus.DEFERRED
            else -> OutcomeStatus.PARTIAL
        }
        observer.onEvent(
            event(
                task.id,
                if (outcome.result.success) ExecutionEventType.VALIDATION_RESULT else ExecutionEventType.STEP_FAILED,
                step.id,
                0,
                outcome.result.message,
                success = outcome.result.success,
            ),
        )
        return finish(status, outcome.result.message, outcome.result, outcome.cloudCalls, outcome.deviceAiCalls)
    }

    /** Applies the risk engine to a step before it runs, asking the user when it says to. */
    private suspend fun gateStep(
        skill: SemanticSkill,
        step: SkillStep,
        index: Int,
        packageName: String,
        confirmedThisRun: Boolean,
        task: AgentTask,
        observer: ExecutionObserver,
    ): StepGate {
        val decision = riskEngine.evaluate(skill, step, packageName, confirmedThisRun)
        observer.onEvent(
            event(
                task.id,
                ExecutionEventType.RISK_DECISION,
                step.id,
                index,
                words.riskDecision(decision.verdict.name, decision.reason),
            ),
        )
        return when (decision.verdict) {
            RiskVerdict.DENY -> StepGate.Stop(decision.reason, OutcomeStatus.BLOCKED)
            RiskVerdict.CONFIRM -> {
                val approved = observer.requestConfirmation(step, decision)
                observer.onEvent(
                    event(
                        task.id,
                        ExecutionEventType.USER_CONFIRMATION,
                        step.id,
                        index,
                        if (approved) "approved" else "declined",
                        success = approved,
                    ),
                )
                if (approved) StepGate.Proceed(confirmed = true) else StepGate.Stop("You declined this step", OutcomeStatus.CANCELLED)
            }
            RiskVerdict.ALLOW -> StepGate.Proceed(confirmed = false)
        }
    }

    /**
     * Whether a step that could not be completed on its own should be handed to the agent.
     *
     * Almost always. A demonstration is one route through an app on one day; the agent is
     * what gets a run past everything the route did not contain. The exceptions are the
     * steps whose failure the agent cannot change: an app that does not open, an exit that
     * is its own outcome, a protected screen, and a run the user stopped.
     */
    private fun shouldHandToAgent(step: SkillStep, outcome: StepOutcome): Boolean {
        // A step that stalled for want of one runtime is still worth handing over: the
        // agent asks its own question, and reports awaiting reasoning itself if nothing
        // at all can answer it.
        if (outcome.result.success || outcome.blocked || outcome.cancelled) return false
        if (!step.fallback.allowDeviceAi && !step.fallback.allowCloudAi) return false
        if (step.fallback.onFailure == com.autobile.core.model.FailureAction.ABORT) return false
        return when (step.action) {
            is ActionSpec.VisualTask, is ActionSpec.LaunchApp, ActionSpec.Home -> false
            else -> true
        }
    }

    /** A mission that gets one recorded step done and hands back to the route. */
    private fun stepRecoveryMission(
        skill: SemanticSkill,
        failed: SkillStep,
        index: Int,
        context: ExecutionContext,
    ): AgentMission {
        val stepDescription = failed.describeForUser()
        val completion = sequenceOf(
            failed.validation.expectation,
            failed.expectedState.description,
        ).firstOrNull { it.isNotBlank() }
            ?: "The result of \"$stepDescription\" is visible on screen"
        return AgentMission(
            objective = skill.goal,
            completionCriteria = completion,
            taskApps = taskAppsFor(skill, failed),
            guidance = skill.guidance,
            referenceRoute = routeFor(skill, completedSteps = index, current = index),
            focus = "Recorded step ${index + 1}, \"$stepDescription\", could not be completed as recorded. " +
                "Get it done from the current screen, handling whatever is in the way, then report complete. " +
                "The steps after it are replayed automatically.",
            knownValues = context.knownValues(),
            maxActions = STEP_RECOVERY_ACTION_BUDGET,
            allowCloud = failed.fallback.allowCloudAi,
            allowVision = failed.fallback.allowVision,
        )
    }

    /** A mission for the whole goal, as far as it has not been done already. */
    private fun goalMission(
        skill: SemanticSkill,
        context: ExecutionContext,
        runInstruction: String,
        completedSteps: Int,
    ): AgentMission {
        val visualTasks = skill.steps.mapNotNull { it.action as? ActionSpec.VisualTask }
        val completion = buildList {
            visualTasks.mapTo(this) { it.completionCriteria }
            skill.postconditions.mapTo(this) { condition ->
                buildString {
                    append(condition.description)
                    if (condition.requiredTexts.isNotEmpty()) {
                        append(" (showing ").append(condition.requiredTexts.joinToString()).append(')')
                    }
                }
            }
        }.filter { it.isNotBlank() }.distinct().joinToString("; ")
            .ifBlank { "The outcome of \"${skill.goal}\" is visible on screen" }
        val objective = buildString {
            append(skill.goal)
            skill.description.takeIf { it.isNotBlank() && !it.equals(skill.goal, ignoreCase = true) }
                ?.let { append(". ").append(it) }
        }
        return AgentMission(
            objective = objective,
            completionCriteria = completion,
            taskApps = taskAppsFor(skill, null),
            guidance = skill.guidance,
            runInstruction = runInstruction,
            referenceRoute = routeFor(skill, completedSteps = completedSteps, current = -1),
            knownValues = context.knownValues(),
            maxActions = visualTasks.maxOfOrNull { it.maxActions }?.coerceAtLeast(GoalAgent.DEFAULT_ACTION_BUDGET)
                ?: GoalAgent.DEFAULT_ACTION_BUDGET,
            allowCloud = skill.steps.all { it.fallback.allowCloudAi },
            allowVision = skill.steps.all { it.fallback.allowVision },
        )
    }

    /** Apps a mission may act in: the step's own first, then the skill's. */
    private fun taskAppsFor(skill: SemanticSkill, step: SkillStep?): List<String> = buildList {
        step?.interactionPackage(skill)?.let(::add)
        step?.appToOpen(skill)?.let(::add)
        skill.steps.forEach { candidate ->
            (candidate.action as? ActionSpec.LaunchApp)?.packageName?.let(::add)
            candidate.expectedState.requiredPackage?.let(::add)
        }
        addAll(skill.runtimeRequirements.requiredPackages)
    }.filter { it.isNotBlank() && it != ownPackage }.distinct()

    /**
     * The demonstrated route as the agent reads it, marked with how far this run got.
     *
     * The words the user saw are preferred: steps may have been rewritten into a single
     * visual task by compilation or an edit, and the agent needs the route that was
     * actually shown to it.
     */
    private fun routeFor(skill: SemanticSkill, completedSteps: Int, current: Int): List<String> {
        if (skill.demonstration.isNotEmpty() && current < 0 && completedSteps == 0) {
            return skill.demonstration.take(MAX_ROUTE_LINES)
        }
        return skill.steps.take(MAX_ROUTE_LINES).mapIndexed { position, candidate ->
            val line = (candidate.action as? ActionSpec.VisualTask)?.objective ?: candidate.describeForUser()
            when {
                position == current -> "$line  <- current step"
                position < completedSteps -> "$line  (done)"
                else -> line
            }
        }
    }

    /**
     * What a recovery taught about one recorded step.
     *
     * Whatever the agent did before acting on the step's own target was in the way — a
     * pop-up, a tutorial, a sign-in sheet — and is kept in front of the step as optional,
     * reasoning-free steps, so the next run gets past it by replay and pays nothing when
     * it does not appear. The recorded step itself is kept: one recovery is not evidence
     * that its target moved, and a moved target is repaired by relocation, which already
     * proposes its own change.
     */
    private fun recoveryChanges(step: SkillStep, route: List<SkillStep>): List<StepChange> {
        val own = route.indexOfFirst { it.actsOnSameTargetAs(step) }
        // Only steps that look for something can be skipped when it is absent. A back
        // press or a wait always runs, so written down as "if shown" it would run on
        // every later replay whether anything was shown or not.
        val before = (if (own >= 0) route.take(own) else route)
            .filter { it.target.locators.isNotEmpty() }
            .map(RouteLearner::asInterruption)
        return if (before.isEmpty()) emptyList() else listOf(StepChange.InsertSteps(step.id, after = false, steps = before))
    }

    /** The agent finished the goal from [index]: its route replaces the rest of the steps. */
    private fun takeOverChanges(skill: SemanticSkill, index: Int, route: List<SkillStep>): List<StepChange> {
        if (route.isEmpty()) return emptyList()
        val failed = skill.steps.getOrNull(index) ?: return emptyList()
        return listOf(StepChange.ReplaceStep(failed.id, route)) +
            skill.steps.drop(index + 1).map { StepChange.Delete(it.id) }
    }

    /**
     * Writes a route the agent proved back into the automation, so the next run replays
     * it without a model.
     *
     * Applied straight away where the user has already trusted the automation to act on
     * its own and nothing in the route commits anything; otherwise kept as a proposal on
     * the automation's page, like any other repair. Skipped when the automation changed
     * while it ran, because the route was learned against a version that no longer exists.
     */
    private suspend fun adoptLearnedRoute(
        skill: SemanticSkill,
        changes: List<StepChange>,
        observer: ExecutionObserver,
    ) {
        if (changes.isEmpty()) return
        val current = skillStore.get(skill.id)?.takeIf { it.version == skill.version } ?: return
        val patched = applyStepChanges(current, changes) ?: return
        val learnedSteps = changes.flatMap { change ->
            when (change) {
                is StepChange.InsertSteps -> change.steps
                is StepChange.ReplaceStep -> change.steps
                is StepChange.ReplaceAll -> change.steps
                else -> emptyList()
            }
        }
        val summary = words.learnedRoute(learnedSteps.size)
        val commits = learnedSteps.any { riskEngine.categorise(it).isNotEmpty() }
        val version = current.version + 1
        val changedStepIds = learnedSteps.map { it.id }
        if (!commits && current.effectiveAutonomy() >= AutonomyLevel.L3_AUTONOMOUS_LOW_RISK) {
            skillStore.save(
                patched.copy(version = version),
                SkillVersionRecord(
                    version = version,
                    createdAt = time.nowMillis(),
                    author = PatchAuthor.SELF_HEAL,
                    reason = summary,
                    summary = summary,
                    changedStepIds = changedStepIds,
                ),
            )
            observer.onEvent(event(observer.taskId, ExecutionEventType.SKILL_PATCH_PROPOSED, message = "applied: $summary"))
            return
        }
        val candidate = SkillPatchCandidate(
            skillId = current.id,
            baseVersion = current.version,
            patchedSkill = patched.copy(version = version),
            summary = summary,
            createdAt = time.nowMillis(),
            requiresUserConfirmation = commits,
        )
        skillStore.savePatchCandidate(candidate)
        observer.onEvent(event(observer.taskId, ExecutionEventType.SKILL_PATCH_PROPOSED, message = summary))
        observer.onPatchProposed(candidate.id, summary, candidate.requiresUserConfirmation)
    }

    private fun AgentOutcome.toStepOutcome(
        step: SkillStep,
        index: Int,
        startedAt: Long,
        recovered: Boolean,
    ): StepOutcome = StepOutcome(
        result = StepResult(
            stepId = step.id,
            stepIndex = index,
            intent = step.intent,
            targetLabel = step.target.intentLabel,
            resolver = ResolverKind.VISION,
            tier = tier,
            success = completed,
            validation = ValidationOutcome(
                step.validation.mode,
                passed = completed,
                reason = message,
                confidence = confidence,
            ),
            startedAt = startedAt,
            finishedAt = time.nowMillis(),
            recovered = recovered && completed,
            message = message,
        ),
        cloudCalls = cloudCalls,
        deviceAiCalls = deviceAiCalls,
        awaitingReasoning = awaitingReasoning,
        blocked = blocked,
        cancelled = cancelled,
    )

    private suspend fun runStep(
        skill: SemanticSkill,
        step: SkillStep,
        index: Int,
        initialSnapshot: ScreenSnapshot,
        context: ExecutionContext,
        task: AgentTask,
        observer: ExecutionObserver,
        localOnly: Boolean,
    ): StepOutcome {
        val startedAt = time.nowMillis()
        var cloudCalls = 0
        var deviceAiCalls = 0
        var snapshot = initialSnapshot
        var recovered = false

        (step.action as? ActionSpec.VisualTask)?.let { visualTask ->
            val mission = AgentMission(
                objective = visualTask.objective,
                completionCriteria = visualTask.completionCriteria,
                taskApps = taskAppsFor(skill, step),
                guidance = skill.guidance,
                referenceRoute = skill.demonstration.take(MAX_ROUTE_LINES),
                knownValues = context.knownValues(),
                maxActions = visualTask.maxActions,
                allowCloud = step.fallback.allowCloudAi,
                allowVision = step.fallback.allowVision,
            )
            return agent.pursue(mission, skill, step, index, observer, localOnly)
                .toStepOutcome(step, index, startedAt, recovered = false)
        }

        // Steps that do not act on an element bypass resolution entirely.
        step.action.asContextFreeAction()?.let { action ->
            val actionResult = performContextFree(action, context)
            // Observe again before validating. These actions exist precisely to change
            // which screen is in front, so checking the snapshot taken before them can
            // never see what they did: a launch step would be judged against the screen
            // the user was on when it started.
            val afterAction = if (actionResult.succeeded) {
                (perception.observeStable(step.validation.timeoutMs) as? PerceptionResult.Success)
                    ?.snapshot ?: snapshot
            } else {
                snapshot
            }
            val validated = validateAfter(skill, step, afterAction, null, localOnly, observer, task, index)
            return StepOutcome(
                result = StepResult(
                    stepId = step.id,
                    stepIndex = index,
                    intent = step.intent,
                    targetLabel = step.target.intentLabel,
                    resolver = ResolverKind.DIRECT_API,
                    tier = RuntimeTier.DETERMINISTIC,
                    success = actionResult.succeeded && validated.passed,
                    validation = validated,
                    startedAt = startedAt,
                    finishedAt = time.nowMillis(),
                    message = actionResult.describe,
                ),
                cloudCalls = 0,
                deviceAiCalls = 0,
            )
        }

        var attempt = 0
        var awaitingReasoning = false
        var lastFailureReason: String? = null
        var openedTargetApp = false
        var forceVision = false
        val attemptedMoves = mutableListOf<String>()
        val attemptedVisualPoints = mutableListOf<Pair<Float, Float>>()
        val navigationSteps = mutableListOf<SkillStep>()

        while (attempt <= step.fallback.maxRetries) {
            val thisAttemptUsesVision = forceVision
            forceVision = false
            // Autobile's own screen is never something to act on. It carries the
            // automation's name, goal and step descriptions, so a label match against it
            // succeeds readily and the agent ends up tapping its own interface.
            //
            // But being here is normal, not a fault: every run started from the app
            // begins with Autobile in front. The answer is to open the app this step was
            // taught in, and only give up if there is nothing to open or it did not take.
            val interactionPackage = step.interactionPackage(skill)
            if (snapshot.packageName == ownPackage ||
                (interactionPackage != null && snapshot.packageName != interactionPackage && recovered)
            ) {
                val targetApp = interactionPackage ?: step.appToOpen(skill)
                if (openedTargetApp || targetApp == null) {
                    return failedOutcome(step, index, startedAt, words.ownScreenInFront(), cloudCalls, deviceAiCalls)
                }
                openedTargetApp = true
                val opened = performContextFree(ActionSpec.LaunchApp(targetApp), context)
                observer.onEvent(
                    event(
                        task.id,
                        ExecutionEventType.ACTION_EXECUTED,
                        step.id,
                        index,
                        opened.describe,
                        success = opened.succeeded,
                    ),
                )
                snapshot = (perception.observeStable(step.validation.timeoutMs) as? PerceptionResult.Success)
                    ?.snapshot ?: snapshot
                if (snapshot.packageName == ownPackage) {
                    return failedOutcome(step, index, startedAt, words.ownScreenInFront(), cloudCalls, deviceAiCalls)
                }
            }

            // Captured only if the resolver gets far enough to need it. A screenshot is
            // slow and rate-limited by the platform, and a step that matches by recorded
            // id — the ordinary case — never looks at one.
            var screenshotTaken: ScreenshotCapture? = null
            var screenshotReported = false
            val screenshot: suspend () -> ScreenshotCapture = {
                val capture = screenshotTaken ?: perception.captureScreenshot().also { screenshotTaken = it }
                if (!screenshotReported) {
                    screenshotReported = true
                    val (message, success) = when (capture) {
                        is ScreenshotCapture.Success ->
                            "fresh screenshot captured (${capture.bitmap.width}x${capture.bitmap.height})" to true
                        is ScreenshotCapture.SecureWindowBlocked ->
                            "screenshot blocked by protected window" to false
                        is ScreenshotCapture.Unavailable -> capture.reason to false
                    }
                    observer.onEvent(
                        event(
                            task.id,
                            ExecutionEventType.SCREEN_CAPTURED,
                            step.id,
                            index,
                            message,
                            success = success,
                            resolver = ResolverKind.VISION,
                        ),
                    )
                }
                capture
            }

            val resolution = resolver.resolve(
                target = step.target,
                snapshot = snapshot,
                screenshot = screenshot,
                allowInference = step.fallback.allowDeviceAi || step.fallback.allowCloudAi,
                allowVision = step.fallback.allowVision,
                localOnly = localOnly || !step.fallback.allowCloudAi,
                requireEditable = step.action is ActionSpec.InputText,
                forceVision = thisAttemptUsesVision,
                avoidedVisualPoints = attemptedVisualPoints,
            )

            awaitingReasoning = resolution is Resolution.NeedsReasoning

            if (resolution is Resolution.VisionBlocked) {
                lastFailureReason = resolution.reason
                if (resolution.secureWindow) {
                    observer.onEvent(
                        event(
                            task.id,
                            ExecutionEventType.STEP_FAILED,
                            step.id,
                            index,
                            resolution.reason,
                            success = false,
                        ),
                    )
                    return StepOutcome(
                        result = failedStep(step, index, startedAt, resolution.reason, step.validation.mode),
                        cloudCalls = cloudCalls,
                        deviceAiCalls = deviceAiCalls,
                        blocked = true,
                    )
                }
            }

            if (resolution is Resolution.FoundPoint) {
                if (resolution.usedCloud) cloudCalls++
                if (resolution.tier == RuntimeTier.DEVICE_AI) deviceAiCalls++
                observer.onEvent(
                    event(
                        task.id,
                        ExecutionEventType.RESOLVER_SELECTED,
                        step.id,
                        index,
                        "${ResolverKind.VISION} via ${resolution.tier.diagnosticName} (${resolution.explanation})",
                        tier = resolution.tier,
                        resolver = ResolverKind.VISION,
                    ),
                )
                val beforePixels = (screenshotTaken as? ScreenshotCapture.Success)?.bitmap
                var visualProgressed = false
                val actionResult = performAtPoint(step, resolution)
                observer.onEvent(
                    event(
                        task.id,
                        ExecutionEventType.ACTION_EXECUTED,
                        step.id,
                        index,
                        actionResult.describe,
                        success = actionResult.succeeded,
                    ),
                )
                if (actionResult.succeeded) {
                    snapshot = (perception.observeStable(step.validation.timeoutMs) as? PerceptionResult.Success)
                        ?.snapshot ?: snapshot
                    // Touching the place is often only half of it: a field reached this
                    // way still has to be typed into, and by now it has focus.
                    val typed = typeAfterTouch(step, snapshot, context)
                    val afterTyping = if (typed) {
                        (perception.observeStable(step.validation.timeoutMs) as? PerceptionResult.Success)
                            ?.snapshot ?: snapshot
                    } else {
                        snapshot
                    }
                    val stayedInTargetApp = step.remainsInExpectedApp(afterTyping, skill)
                    val wrote = confirmTextLanded(step, afterTyping, context)
                    val afterCapture = perception.captureScreenshot()
                    if (afterCapture is ScreenshotCapture.SecureWindowBlocked) {
                        val reason = words.screenProtected()
                        return StepOutcome(
                            result = failedStep(step, index, startedAt, reason, step.validation.mode),
                            cloudCalls = cloudCalls,
                            deviceAiCalls = deviceAiCalls,
                            blocked = true,
                        )
                    }
                    val afterPixels = (afterCapture as? ScreenshotCapture.Success)?.bitmap
                    val validated = validateAfterVisual(
                        skill,
                        step,
                        afterTyping,
                        afterPixels,
                        localOnly,
                        observer,
                        task,
                        index,
                    )
                    val pixelsChanged = beforePixels != null && afterPixels != null && when (step.action) {
                        ActionSpec.Click, is ActionSpec.Tap, is ActionSpec.LongPress, is ActionSpec.InputText ->
                            VisualChangeDetector.changedAround(
                                beforePixels,
                                afterPixels,
                                resolution.xRatio,
                                resolution.yRatio,
                            )

                        else -> VisualChangeDetector.changed(beforePixels, afterPixels)
                    }
                    visualProgressed = pixelsChanged
                    // Text confirmation is an extra requirement for typing steps. It
                    // must not make point-based click/press gestures impossible to
                    // complete: those actions have no text payload to confirm.
                    val isTextInput = step.action is ActionSpec.InputText
                    val textConfirmed = !isTextInput ||
                        wrote || (validated.evaluated && validated.passed)
                    val visuallyConfirmed = when {
                        step.validation.mode == ValidationMode.NONE ->
                            pixelsChanged || (isTextInput && textConfirmed)
                        afterTyping.nodes.isEmpty() ->
                            pixelsChanged || (isTextInput && validated.evaluated && validated.passed)
                        else -> true
                    }
                    if (typed && textConfirmed && stayedInTargetApp && validated.passed && visuallyConfirmed) {
                        return StepOutcome(
                            result = StepResult(
                                stepId = step.id,
                                stepIndex = index,
                                intent = step.intent,
                                targetLabel = step.target.intentLabel,
                                resolver = ResolverKind.VISION,
                                tier = resolution.tier,
                                success = true,
                                validation = validated,
                                startedAt = startedAt,
                                finishedAt = time.nowMillis(),
                                recovered = recovered,
                                message = resolution.explanation,
                            ),
                            cloudCalls = cloudCalls,
                            deviceAiCalls = deviceAiCalls,
                        )
                    }
                }
                // A visual action that did not prove its outcome must be grounded again
                // from fresh pixels. Returning to the same accessibility locator merely
                // repeats the failure that made this attempt visual in the first place.
                forceVision = step.fallback.allowVision
                if (visualProgressed) {
                    // A local change around the action proves that the game advanced,
                    // even when its completion post-condition has not passed yet. The
                    // next grounding sees a new frame, so old coordinates are no longer
                    // failed candidates and may legitimately be selected again.
                    attemptedVisualPoints.clear()
                } else {
                    attemptedVisualPoints += resolution.xRatio to resolution.yRatio
                }
            }

            if (resolution is Resolution.Found) {
                if (resolution.tier == RuntimeTier.DEVICE_AI) deviceAiCalls++
                if (resolution.usedCloud) cloudCalls++

                observer.onEvent(
                    event(
                        task.id,
                        ExecutionEventType.RESOLVER_SELECTED,
                        step.id,
                        index,
                        "${resolution.resolver} via ${resolution.tier.diagnosticName} (${resolution.explanation})",
                        tier = resolution.tier,
                        resolver = resolution.resolver,
                    ),
                )
                observer.onTargetResolved(index, resolution.node)

                val readAction = step.action as? ActionSpec.ReadValue
                val extracted: String?
                val actionResult: ActionResult
                if (readAction != null) {
                    val reading = readValue(
                        step,
                        snapshot,
                        (screenshot() as? ScreenshotCapture.Success)?.bitmap,
                        localOnly,
                    )
                    extracted = reading.value
                    if (reading.usedCloud) cloudCalls++
                    if (reading.tier == RuntimeTier.DEVICE_AI) deviceAiCalls++
                    reading.value?.let { context.putExtracted(readAction.outputVariable, it) }
                    actionResult = if (reading.value != null) {
                        ActionResult.Performed("read ${step.target.valueSemantics?.fieldName ?: "value"}")
                    } else {
                        ActionResult.Failed(reading.reason)
                    }
                } else {
                    extracted = null
                    actionResult = performOn(step, resolution, context)
                }

                observer.onEvent(
                    event(
                        task.id,
                        ExecutionEventType.ACTION_EXECUTED,
                        step.id,
                        index,
                        actionResult.describe,
                        success = actionResult.succeeded,
                    ),
                )

                if (actionResult.succeeded) {
                    snapshot = (perception.observeStable(step.validation.timeoutMs) as? PerceptionResult.Success)
                        ?.snapshot ?: snapshot
                    val wrote = confirmTextLanded(step, snapshot, context)
                    val validated = validateAfter(skill, step, snapshot, extracted, localOnly, observer, task, index)
                    if (wrote && validated.passed) {
                        if (recovered) {
                            persistRepair(skill, step, resolution, navigationSteps, observer)
                        }
                        return StepOutcome(
                            result = StepResult(
                                stepId = step.id,
                                stepIndex = index,
                                intent = step.intent,
                                targetLabel = step.target.intentLabel,
                                resolver = resolution.resolver,
                                tier = resolution.tier,
                                success = true,
                                validation = validated,
                                startedAt = startedAt,
                                finishedAt = time.nowMillis(),
                                recovered = recovered,
                                message = resolution.explanation,
                                extractedValue = extracted,
                            ),
                            cloudCalls = cloudCalls,
                            deviceAiCalls = deviceAiCalls,
                        )
                    }
                    // The framework accepting an action is not proof that the app
                    // changed. Do not repeat the same stale node action on the next
                    // attempt; look at the visible target and act at that point.
                    forceVision = step.fallback.allowVision
                } else {
                    forceVision = step.fallback.allowVision
                }
            }

            attempt++
            if (attempt > step.fallback.maxRetries) break
            if (step.fallback.onFailure == com.autobile.core.model.FailureAction.ABORT) break

            if (forceVision) {
                observer.onEvent(
                    event(task.id, ExecutionEventType.RECOVERY_STARTED, step.id, index, "visual fallback"),
                )
                recovered = true
                continue
            }

            observer.onEvent(
                event(task.id, ExecutionEventType.RECOVERY_STARTED, step.id, index, "attempt $attempt"),
            )
            val move = healing.proposeMove(
                skill = skill,
                step = step,
                snapshot = snapshot,
                attemptedMoves = attemptedMoves,
                localOnly = localOnly || !step.fallback.allowCloudAi,
            )
            val applied = applyRecoveryMove(move, snapshot, navigationSteps)
            attemptedMoves += move.reason.ifBlank { move::class.java.simpleName }
            if (move is RecoveryMove.Tap && move.usedCloud) cloudCalls++
            if (!applied) break

            recovered = true
            snapshot = (perception.observeStable() as? PerceptionResult.Success)?.snapshot ?: snapshot
            observer.onEvent(
                event(task.id, ExecutionEventType.RECOVERY_COMPLETED, step.id, index, move.reason),
            )
        }

        val message = if (awaitingReasoning) {
            words.awaitingRuntime(step.describeForUser())
        } else {
            lastFailureReason ?: words.couldNotComplete(step.describeForUser())
        }
        return StepOutcome(
            result = failedStep(step, index, startedAt, message, step.validation.mode),
            cloudCalls = cloudCalls,
            deviceAiCalls = deviceAiCalls,
            awaitingReasoning = awaitingReasoning,
        )
    }

    /**
     * The app this step belongs to, when the screen in front is not it.
     *
     * Taken from what the step itself expects before the skill's requirements, because a
     * skill can span more than one app and the requirement list does not say which one
     * this step needed. Autobile is never the answer.
     */
    private fun SkillStep.appToOpen(skill: SemanticSkill): String? = sequenceOf(
        expectedState.requiredPackage,
        expectedState.screen?.packageName,
        target.screen?.packageName,
    ).plus(skill.runtimeRequirements.requiredPackages.asSequence())
        .filterNotNull()
        .firstOrNull { it.isNotBlank() && it != ownPackage }

    /** The package containing the element this step is meant to manipulate. */
    private fun SkillStep.interactionPackage(skill: SemanticSkill): String? = sequenceOf(
        target.screen?.packageName,
        target.locators.firstNotNullOfOrNull { it.packageName },
    ).plus(skill.runtimeRequirements.requiredPackages.asSequence())
        .filterNotNull()
        .firstOrNull { it != ownPackage }

    private fun SkillStep.remainsInExpectedApp(snapshot: ScreenSnapshot, skill: SemanticSkill): Boolean {
        val required = expectedState.requiredPackage
            ?: expectedState.screen?.packageName
            ?: interactionPackage(skill)
        return required == null || snapshot.packageName == required
    }

    private fun SkillStep.expectedForValidation(skill: SemanticSkill): ExpectedState {
        // Leaving is itself the intended outcome of an explicit Home step. Inferring
        // the interaction package here would require the game to remain foregrounded
        // after Home and make a correctly gated exit impossible to validate.
        if (action is ActionSpec.Home || intent == StepIntent.GO_HOME) return expectedState
        if (expectedState.requiredPackage != null || expectedState.screen?.packageName != null) return expectedState
        val required = interactionPackage(skill) ?: return expectedState
        return expectedState.copy(requiredPackage = required)
    }

    private fun requiresEditable(step: SkillStep): Boolean = step.action is ActionSpec.InputText

    /**
     * Reads back what a typing step just wrote.
     *
     * The framework reporting that it set the text is not the same as the text being
     * there: a field can reject it, trim it, or be replaced by another as the screen
     * settles. Checking is cheap, certain, and needs no model — which matters, because
     * the alternative was asking one whether typing had worked.
     */
    private fun confirmTextLanded(
        step: SkillStep,
        snapshot: ScreenSnapshot,
        context: ExecutionContext,
    ): Boolean {
        val input = step.action as? ActionSpec.InputText ?: return true
        val expected = context.resolve(input.value)?.takeIf { it.isNotBlank() } ?: return true
        return snapshot.nodes.any { it.text?.contains(expected) == true }
    }

    /**
     * Types into whatever the touch just focused.
     *
     * A point says where something is, not what it is, so a typing step reached this way
     * has to find the field afterwards. Returns true when there was nothing to type.
     */
    private suspend fun typeAfterTouch(
        step: SkillStep,
        snapshot: ScreenSnapshot,
        context: ExecutionContext,
    ): Boolean {
        val input = step.action as? ActionSpec.InputText ?: return true
        val field = snapshot.nodes.firstOrNull { it.editable && it.focused }
            ?: snapshot.nodes.firstOrNull { it.editable }
        val value = context.resolve(input.value) ?: return false
        return if (field != null) {
            controller.inputText(field, value, input.clearExisting).succeeded
        } else {
            controller.inputTextAtFocus(value, input.clearExisting).succeeded
        }
    }

    /** Performs the step's declared gesture after vision located its target. */
    private suspend fun performAtPoint(step: SkillStep, point: Resolution.FoundPoint): ActionResult =
        when (val action = step.action) {
            ActionSpec.Click, is ActionSpec.Tap, is ActionSpec.InputText ->
                controller.tapRatio(point.xRatio, point.yRatio)

            is ActionSpec.LongPress ->
                controller.longPressRatio(point.xRatio, point.yRatio, action.durationMs)

            is ActionSpec.Swipe ->
                controller.swipe(action.direction, action.distanceRatio, action.durationMs)

            is ActionSpec.Scroll ->
                controller.scroll(null, action.direction)

            is ActionSpec.ReadValue ->
                ActionResult.Failed("A visually located value needs extraction, not a tap")

            else -> ActionResult.Failed("${action::class.simpleName} cannot act at a visual point")
        }

    /** A step that cannot proceed, with the reason kept rather than retried into noise. */
    private fun failedOutcome(
        step: SkillStep,
        index: Int,
        startedAt: Long,
        reason: String,
        cloudCalls: Int,
        deviceAiCalls: Int,
    ) = StepOutcome(
        result = failedStep(step, index, startedAt, reason, ValidationMode.NONE),
        cloudCalls = cloudCalls,
        deviceAiCalls = deviceAiCalls,
    )

    private suspend fun applyRecoveryMove(
        move: RecoveryMove,
        snapshot: ScreenSnapshot,
        navigationSteps: MutableList<SkillStep>,
    ): Boolean = when (move) {
        is RecoveryMove.Tap -> {
            val result = controller.click(move.node)
            if (result.succeeded) navigationSteps += healing.navigationStepFor(move.node, move.reason)
            result.succeeded
        }

        is RecoveryMove.Scroll -> controller.scroll(
            snapshot.nodes.firstOrNull { it.scrollable },
            move.direction,
        ).succeeded

        is RecoveryMove.Back -> controller.pressBack().succeeded
        is RecoveryMove.Wait -> {
            delay(move.millis)
            true
        }

        is RecoveryMove.GiveUp -> false
    }

    /** Stores the successful repair so the next run takes the fast path. */
    private suspend fun persistRepair(
        skill: SemanticSkill,
        step: SkillStep,
        resolution: Resolution.Found,
        navigationSteps: List<SkillStep>,
        observer: ExecutionObserver,
    ) {
        val repaired = healing.repairStep(step, resolution.node)
        val summary = buildString {
            append("Relocated \"").append(step.target.intentLabel).append("\"")
            if (navigationSteps.isNotEmpty()) {
                append(" behind ").append(navigationSteps.joinToString(" > ") { it.target.intentLabel })
            }
        }
        val candidate = healing.buildPatch(skill, step, repaired, navigationSteps, summary, time.nowMillis())
        skillStore.savePatchCandidate(candidate)
        observer.onEvent(
            event(
                taskId = observer.taskId,
                type = ExecutionEventType.SKILL_PATCH_PROPOSED,
                stepId = step.id,
                message = summary,
            ),
        )
        observer.onPatchProposed(candidate.id, summary, candidate.requiresUserConfirmation)
    }

    private suspend fun validateAfter(
        skill: SemanticSkill,
        step: SkillStep,
        snapshot: ScreenSnapshot,
        extracted: String?,
        localOnly: Boolean,
        observer: ExecutionObserver,
        task: AgentTask,
        index: Int,
    ): ValidationOutcome {
        val outcome = validation.validate(
            spec = step.validation,
            expected = step.expectedForValidation(skill),
            snapshot = snapshot,
            extractedValue = extracted,
            localOnly = localOnly,
        )
        observer.onEvent(
            event(
                task.id,
                ExecutionEventType.VALIDATION_RESULT,
                step.id,
                index,
                outcome.reason,
                success = outcome.passed,
            ),
        )
        return outcome
    }

    /** Validates against pixels when a visual action has no usable tree after it. */
    private suspend fun validateAfterVisual(
        skill: SemanticSkill,
        step: SkillStep,
        snapshot: ScreenSnapshot,
        screenshot: Bitmap?,
        localOnly: Boolean,
        observer: ExecutionObserver,
        task: AgentTask,
        index: Int,
    ): ValidationOutcome {
        val pixelsArePrimary = snapshot.nodes.isEmpty() &&
            screenshot != null &&
            step.validation.mode != ValidationMode.NONE
        val structural = if (pixelsArePrimary) {
            null
        } else {
            validation.validate(
                spec = step.validation,
                expected = step.expectedForValidation(skill),
                snapshot = snapshot,
                localOnly = localOnly,
            )
        }
        val outcome = if (pixelsArePrimary || (structural?.passed == false && screenshot != null)) {
            validation.validateVisual(
                step.validation,
                step.expectedForValidation(skill),
                snapshot,
                checkNotNull(screenshot),
                localOnly,
            )
        } else {
            checkNotNull(structural)
        }
        observer.onEvent(
            event(
                task.id,
                ExecutionEventType.VALIDATION_RESULT,
                step.id,
                index,
                outcome.reason,
                success = outcome.passed,
            ),
        )
        return outcome
    }

    /** Reads a value from the screen, preferring the text already in the tree. */
    private suspend fun readValue(
        step: SkillStep,
        snapshot: ScreenSnapshot,
        screenshot: android.graphics.Bitmap?,
        localOnly: Boolean,
    ): ValueReading {
        val semantics = step.target.valueSemantics
        val fieldName = semantics?.fieldName ?: step.target.intentLabel

        // The value often sits directly beside its label in the node tree, in which case
        // no inference is needed at all.
        neighbouringValue(snapshot, fieldName, semantics?.valueType)?.let {
            return ValueReading(it, RuntimeTier.DETERMINISTIC, usedCloud = false, reason = "read from the screen")
        }

        val description = minimizer.describeScreen(snapshot)
        val routed = router.infer(
            label = "value-extraction",
            schema = AiTasks.valueExtraction,
            prompt = AiTasks.valueExtractionPrompt(fieldName, semantics?.qualifiers.orEmpty(), description),
            systemInstruction = AiTasks.SYSTEM_INSTRUCTION,
            image = screenshot,
            requirements = InferenceRequirements(
                minConfidence = VALUE_CONFIDENCE_THRESHOLD,
                localOnly = localOnly,
                estimatedInputTokens = minimizer.estimateTokens(description),
            ),
        )
        val extracted = routed.value
        return if (extracted != null && extracted.found) {
            ValueReading(extracted.value, routed.tier, routed.usedCloud, "read by ${routed.tier.diagnosticName}")
        } else {
            ValueReading(null, routed.tier, routed.usedCloud, "\"$fieldName\" was not found on this screen")
        }
    }

    /**
     * Finds a value positioned next to its label.
     *
     * Looks to the right of the label first, then below it, which covers the two layouts
     * essentially every form uses.
     */
    private fun neighbouringValue(snapshot: ScreenSnapshot, fieldName: String, type: ValueType?): String? {
        val label = snapshot.nodes.firstOrNull {
            it.visible && it.label().equals(fieldName, ignoreCase = true)
        } ?: return null

        val candidates = snapshot.nodes.filter { node ->
            node.nodeId != label.nodeId && node.visible && !node.label().isBlank() &&
                !node.label().equals(fieldName, ignoreCase = true)
        }

        val sameRow = candidates.filter {
            it.bounds.left >= label.bounds.left &&
                kotlin.math.abs(it.bounds.centerY - label.bounds.centerY) <= ROW_TOLERANCE_PX
        }.minByOrNull { it.bounds.left }

        val below = candidates.filter {
            it.bounds.top >= label.bounds.bottom &&
                it.bounds.top - label.bounds.bottom <= COLUMN_TOLERANCE_PX &&
                kotlin.math.abs(it.bounds.centerX - label.bounds.centerX) <= ROW_TOLERANCE_PX * 2
        }.minByOrNull { it.bounds.top }

        val value = (sameRow ?: below)?.label() ?: return null
        if (type == ValueType.NUMBER || type == ValueType.CURRENCY) {
            if (validation.parseNumber(value) == null) return null
        }
        return value
    }

    private suspend fun performOn(
        step: SkillStep,
        resolution: Resolution.Found,
        context: ExecutionContext,
    ): ActionResult = when (val action = step.action) {
        is ActionSpec.Click -> controller.click(resolution.node)
        is ActionSpec.LongPress -> controller.longPress(resolution.node, action.durationMs)
        is ActionSpec.Tap -> controller.tapRatio(action.xRatio, action.yRatio)
        is ActionSpec.Swipe -> controller.swipe(action.direction, action.distanceRatio, action.durationMs)
        is ActionSpec.Scroll -> controller.scroll(resolution.node, action.direction)
        is ActionSpec.InputText -> {
            val value = context.resolve(action.value)
            if (value == null) {
                ActionResult.Failed(words.couldNotDetermineText())
            } else {
                controller.inputText(resolution.node, value, action.clearExisting)
            }
        }

        is ActionSpec.VisualTask, is ActionSpec.ReadValue, is ActionSpec.Wait, is ActionSpec.Back,
        is ActionSpec.Home, is ActionSpec.LaunchApp,
        -> ActionResult.Failed(words.actionNeedsAnElement())
    }

    private suspend fun performContextFree(action: ActionSpec, context: ExecutionContext): ActionResult =
        when (action) {
            is ActionSpec.Back -> controller.pressBack()
            is ActionSpec.Home -> controller.pressHome()
            is ActionSpec.LaunchApp -> controller.launchApp(action.packageName, action.activity).also {
                if (it.succeeded) delay(APP_LAUNCH_SETTLE_MS)
            }

            is ActionSpec.Wait -> {
                delay(action.millis)
                ActionResult.Performed("waited ${action.millis} ms")
            }

            else -> ActionResult.Failed(words.unsupportedAction())
        }

    /** Checks the skill's preconditions, returning the reason it cannot start. */
    private suspend fun preconditionFailure(skill: SemanticSkill, localOnly: Boolean): String? {
        if (skill.preconditions.isEmpty()) return null
        val snapshot = (perception.observe() as? PerceptionResult.Success)?.snapshot ?: return null
        for (condition in skill.preconditions) {
            val missing = condition.requiredTexts.filterNot { snapshot.containsText(it) }
            if (missing.isNotEmpty()) return "Cannot start: ${condition.description}"
        }
        return null
    }

    private fun partial(
        task: AgentTask,
        skill: SemanticSkill,
        results: List<StepResult>,
        cloudCalls: Int,
        deviceAiCalls: Int,
        message: String,
        status: OutcomeStatus,
    ) = TaskOutcome(
        taskId = task.id,
        status = status,
        goalValidated = false,
        completedSteps = results.count { it.success },
        totalSteps = skill.steps.size,
        cloudCalls = cloudCalls,
        deviceAiCalls = deviceAiCalls,
        message = message,
        stepResults = results,
    )

    private fun failedStep(
        step: SkillStep,
        index: Int,
        startedAt: Long,
        message: String,
        mode: ValidationMode,
    ) = StepResult(
        stepId = step.id,
        stepIndex = index,
        intent = step.intent,
        targetLabel = step.target.intentLabel,
        resolver = null,
        tier = RuntimeTier.DETERMINISTIC,
        success = false,
        validation = ValidationOutcome(mode, passed = false, reason = message),
        startedAt = startedAt,
        finishedAt = time.nowMillis(),
        message = message,
    )

    private fun event(
        taskId: String,
        type: ExecutionEventType,
        stepId: String? = null,
        stepIndex: Int? = null,
        message: String = "",
        success: Boolean? = null,
        tier: RuntimeTier? = null,
        resolver: ResolverKind? = null,
    ) = ExecutionEvent(
        id = Ids.event(),
        taskId = taskId,
        timestamp = time.nowMillis(),
        type = type,
        stepId = stepId,
        stepIndex = stepIndex,
        message = Logx.redact(message),
        tier = tier,
        resolver = resolver,
        success = success,
    )

    private companion object {
        const val VALUE_CONFIDENCE_THRESHOLD = 0.6f
        const val APP_LAUNCH_SETTLE_MS = 1_200L
        const val ROW_TOLERANCE_PX = 40
        const val COLUMN_TOLERANCE_PX = 160
        const val GOAL_STEP_ID = "goal"
        /** Enough to close a few dialogs and redo one step, not to wander. */
        const val STEP_RECOVERY_ACTION_BUDGET = 40
        const val MAX_ROUTE_LINES = 40
    }
}

private sealed interface StepGate {
    data class Proceed(val confirmed: Boolean) : StepGate
    data class Stop(val reason: String, val status: OutcomeStatus) : StepGate
}

/** Whether a learned step acts on the same element a recorded step was taught on. */
private fun SkillStep.actsOnSameTargetAs(other: SkillStep): Boolean {
    val ids = target.locators.filter { it.kind == com.autobile.core.model.LocatorKind.RESOURCE_ID }.map { it.value }
    val otherIds = other.target.locators.filter { it.kind == com.autobile.core.model.LocatorKind.RESOURCE_ID }.map { it.value }
    if (ids.any { it in otherIds }) return true
    val label = target.intentLabel.trim()
    return label.isNotEmpty() && label.equals(other.target.intentLabel.trim(), ignoreCase = true)
}

private data class StepOutcome(
    val result: StepResult,
    val cloudCalls: Int,
    val deviceAiCalls: Int,
    /** True when the step stalled because no runtime could make a required decision. */
    val awaitingReasoning: Boolean = false,
    /** True when Android explicitly blocked the perception needed for this step. */
    val blocked: Boolean = false,
    /** True when the user stopped the run while this step was in progress. */
    val cancelled: Boolean = false,
)

private data class ValueReading(
    val value: String?,
    val tier: RuntimeTier,
    val usedCloud: Boolean,
    val reason: String,
)

/** Actions that need no on-screen target, and so skip resolution entirely. */
private fun ActionSpec.asContextFreeAction(): ActionSpec? = when (this) {
    is ActionSpec.Back, is ActionSpec.Home, is ActionSpec.LaunchApp, is ActionSpec.Wait -> this
    else -> null
}

/** A short phrase describing a step to a person watching it run. */
fun SkillStep.describeForUser(): String = description.ifBlank {
    val target = target.description.ifBlank { target.intentLabel }
    when (intent) {
        StepIntent.LAUNCH_APP -> "Opening $target"
        StepIntent.NAVIGATE -> "Going to $target"
        StepIntent.SELECT_ITEM -> "Selecting $target"
        StepIntent.OPEN_TARGET -> "Opening $target"
        StepIntent.READ_VALUE -> "Reading $target"
        StepIntent.ENTER_TEXT -> "Entering $target"
        StepIntent.SET_OPTION -> "Setting $target"
        StepIntent.SCROLL_TO -> "Looking for $target"
        StepIntent.CONFIRM -> "Confirming $target"
        StepIntent.SEND -> "Sending $target"
        StepIntent.SHARE -> "Sharing $target"
        StepIntent.SAVE -> "Saving $target"
        StepIntent.DELETE -> "Deleting $target"
        StepIntent.GO_BACK -> "Going back"
        StepIntent.GO_HOME -> "Going to the home screen"
        StepIntent.WAIT -> "Waiting"
    }
}
