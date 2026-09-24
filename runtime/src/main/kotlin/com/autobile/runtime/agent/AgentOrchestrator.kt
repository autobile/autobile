package com.autobile.runtime.agent

import com.autobile.ai.context.ContextMinimizer
import com.autobile.ai.router.AiRuntimeRouter
import com.autobile.ai.router.RoutingAttempt
import com.autobile.ai.router.RoutingListener
import com.autobile.ai.task.AiTasks
import com.autobile.core.common.Ids
import com.autobile.core.common.Logx
import com.autobile.core.common.TimeSource
import com.autobile.core.data.HistoryStore
import com.autobile.core.data.Metric
import com.autobile.core.data.MetricsStore
import com.autobile.core.data.SettingsStore
import com.autobile.core.data.SkillStore
import com.autobile.core.model.AgentTask
import com.autobile.core.model.AutonomyLevel
import com.autobile.core.model.Condition
import com.autobile.core.model.EscalationReason
import com.autobile.core.model.ExecutionEvent
import com.autobile.core.model.ExecutionEventType
import com.autobile.core.model.ExecutionStrategy
import com.autobile.core.model.InferenceRequirements
import com.autobile.core.model.OutcomeStatus
import com.autobile.core.model.PerceptionResult
import com.autobile.core.model.RiskDecision
import com.autobile.core.model.RiskPolicy
import com.autobile.core.model.RuntimeRequirements
import com.autobile.core.model.RuntimeTier
import com.autobile.core.model.SemanticSkill
import com.autobile.core.model.SkillConfidence
import com.autobile.core.model.SkillStep
import com.autobile.core.model.TriggerSpec
import com.autobile.core.model.TaskOrigin
import com.autobile.core.model.TaskOutcome
import com.autobile.core.model.TaskState
import com.autobile.core.model.UiNode
import com.autobile.runtime.EnglishRuntimeVocabulary
import com.autobile.runtime.RuntimeVocabulary
import com.autobile.runtime.background.ExecutabilityEvaluator
import com.autobile.runtime.capability.CapabilityDetector
import com.autobile.runtime.executor.GoalAgent
import com.autobile.runtime.executor.SkillExecutor
import com.autobile.runtime.executor.ExecutionObserver
import com.autobile.runtime.executor.describeForUser
import com.autobile.runtime.perception.ScreenObserver
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.Collections

/**
 * Coordinates a single run from request to recorded outcome.
 *
 * Everything that starts an automation — a button, a schedule, a notification, a typed
 * command — goes through here, so task creation, permission checks, history and metrics
 * all behave identically regardless of what triggered the run.
 *
 * Runs are serialised. Two automations driving the same screen at once would interfere
 * with each other in ways neither could detect, so a second request while one is running
 * is rejected rather than queued behind it.
 */
class AgentOrchestrator(
    private val executor: SkillExecutor,
    private val perception: ScreenObserver,
    private val router: AiRuntimeRouter,
    private val skillStore: SkillStore,
    private val historyStore: HistoryStore,
    private val metrics: MetricsStore,
    private val settings: SettingsStore,
    private val executability: ExecutabilityEvaluator,
    private val capabilityDetector: CapabilityDetector,
    private val minimizer: ContextMinimizer = ContextMinimizer(),
    private val time: TimeSource = TimeSource.System,
    private val words: RuntimeVocabulary = EnglishRuntimeVocabulary,
    private val resolveAppPackage: (String) -> String? = { null },
    private val ownPackage: String = "",
) {

    private val runLock = Mutex()
    private val cancelled = AtomicBoolean(false)

    private val _activity = MutableStateFlow<AgentActivity>(AgentActivity.Idle)
    val activity: StateFlow<AgentActivity> = _activity.asStateFlow()

    private val _pendingConfirmation = MutableStateFlow<PendingConfirmation?>(null)
    val pendingConfirmation: StateFlow<PendingConfirmation?> = _pendingConfirmation.asStateFlow()

    /**
     * Runs a saved skill.
     *
     * @param confirmation how risky steps should be approved. A run with no way to reach
     *   the user declines rather than assuming consent, so an unattended run never
     *   performs a step the user would have been asked about.
     */
    suspend fun runSkill(
        skillId: String,
        origin: TaskOrigin,
        triggerPayload: Map<String, String> = emptyMap(),
        userInputs: Map<String, String> = emptyMap(),
        confirmation: ConfirmationMode = ConfirmationMode.AskUser(),
        runInstruction: String = "",
    ): RunResult {
        if (settings.killSwitch().engaged) {
            return RunResult.Rejected("Automation is stopped")
        }
        if (runLock.isLocked) {
            return RunResult.Rejected("Another automation is already running")
        }

        return runLock.withLock {
            val skill = skillStore.get(skillId) ?: return@withLock RunResult.Rejected("Automation not found")
            if (!skill.enabled) return@withLock RunResult.Rejected("${skill.name} is paused")

            val profile = capabilityDetector.detect()
            val state = executability.evaluate(skill, profile)
            if (!state.isRunnable) {
                val task = newTask(skill, origin, triggerPayload).copy(
                    state = TaskState.DEFERRED,
                    executability = state,
                    deferredUntil = time.nowMillis() + executability.retryDelayMillis(state),
                )
                historyStore.saveTask(task)
                historyStore.appendEvent(task.lifecycleEvent(ExecutionEventType.TASK_CREATED, words.taskCreated(), time.nowMillis()))
                historyStore.appendEvent(
                    ExecutionEvent(
                        id = Ids.event(),
                        taskId = task.id,
                        timestamp = time.nowMillis(),
                        type = ExecutionEventType.TASK_DEFERRED,
                        message = state.name,
                    ),
                )
                return@withLock RunResult.Deferred(task, state)
            }

            execute(skill, origin, triggerPayload, userInputs, confirmation, runInstruction)
        }
    }

    /**
     * Runs a skill compiled from the user's current command, which is never saved.
     *
     * A one-off command has no recorded route, so the goal agent drives it from the first
     * action, with the same kill switch, app policy, confirmation and history as a saved
     * automation.
     */
    suspend fun runAdHoc(
        skill: SemanticSkill,
        confirmation: ConfirmationMode = ConfirmationMode.AskUser(),
    ): RunResult {
        if (settings.killSwitch().engaged) return RunResult.Rejected("Automation is stopped")
        if (runLock.isLocked) return RunResult.Rejected("Another automation is already running")
        return runLock.withLock {
            val profile = capabilityDetector.detect()
            val state = executability.evaluate(skill, profile)
            if (!state.isRunnable) return@withLock RunResult.Rejected("This task cannot run now: ${state.name}")
            execute(skill, TaskOrigin.MANUAL, emptyMap(), emptyMap(), confirmation)
        }
    }

    private suspend fun execute(
        skill: SemanticSkill,
        origin: TaskOrigin,
        triggerPayload: Map<String, String>,
        userInputs: Map<String, String>,
        confirmation: ConfirmationMode,
        runInstruction: String = "",
    ): RunResult {
        cancelled.set(false)
        val startedAt = time.nowMillis()
        var task = newTask(skill, origin, triggerPayload).copy(state = TaskState.RUNNING, startedAt = startedAt)
        historyStore.saveTask(task)
        historyStore.appendEvent(task.lifecycleEvent(ExecutionEventType.TASK_CREATED, words.taskCreated(), time.nowMillis()))
        metrics.increment(Metric.TASKS_STARTED)
        metrics.increment(Metric.SKILLS_REPEATED)

        val attended = origin == TaskOrigin.MANUAL || origin == TaskOrigin.REPLAY
        _activity.value = AgentActivity.Running(
            taskId = task.id,
            skillName = skill.name,
            // Left blank rather than worded. The orchestrator has no Context by design,
            // and every surface that shows this has one; a literal here would be the one
            // untranslated string in an otherwise translated run.
            stepDescription = "",
            stepIndex = 0,
            totalSteps = skill.steps.size,
            attended = attended,
        )

        val observer = RecordingObserver(task.id, skill, confirmation)
        val routingEvents = Collections.synchronizedList(mutableListOf<ExecutionEvent>())
        // Only ever narrows or clears the current run's state; a stale callback arriving
        // after the run ended finds Idle and leaves it alone.
        val deliberating: (RuntimeTier?) -> Unit = { tier ->
            _activity.update { current ->
                if (current is AgentActivity.Running) current.copy(deliberating = tier) else current
            }
        }
        val resolvedCloudCalls = AtomicInteger(0)
        val registration = router.addListener(object : RoutingListener {
            override fun onTierSelected(
                label: String,
                tier: RuntimeTier,
                escalatedFrom: RuntimeTier?,
                reason: EscalationReason?,
            ) {
                if (label !in EXECUTION_ROUTING_LABELS) return
                deliberating(tier)
                val message = buildString {
                    append(label.replace('-', ' ')).append(" via ").append(tier.diagnosticName)
                    reason?.let { append(", ").append(it.diagnosticName) }
                }
                routingEvents += ExecutionEvent(
                    id = Ids.event(),
                    taskId = task.id,
                    timestamp = time.nowMillis(),
                    type = ExecutionEventType.AI_RUNTIME_SELECTED,
                    message = Logx.redact(message),
                    tier = tier,
                )
            }

            override fun onResolved(label: String, tier: RuntimeTier, confidence: Float, escalated: Boolean) {
                if (label !in EXECUTION_ROUTING_LABELS) return
                deliberating(null)
                if (tier.isCloud) resolvedCloudCalls.incrementAndGet()
            }

            /**
             * Records why no runtime could answer.
             *
             * Every attempt already carries the tier it tried, whether it was skipped
             * and why, the confidence it came back with and what went wrong. Discarding
             * that left a timeline showing which runtime was chosen and then nothing at
             * all — enough to see that reasoning was attempted, never enough to see why
             * it did not work, which is exactly what someone reading a failed run needs.
             */
            override fun onExhausted(label: String, attempts: List<RoutingAttempt>) {
                if (label !in EXECUTION_ROUTING_LABELS) return
                deliberating(null)
                routingEvents += ExecutionEvent(
                    id = Ids.event(),
                    taskId = task.id,
                    timestamp = time.nowMillis(),
                    type = ExecutionEventType.AI_RUNTIME_SELECTED,
                    message = Logx.redact(describeExhaustion(label, attempts)),
                    success = false,
                )
            }
        })
        val outcome = try {
            executor.execute(
                skill = skill,
                task = task,
                observer = observer,
                localOnly = !settings.privacy().cloudEnabled,
                userInputs = userInputs,
                runInstruction = runInstruction,
            )
        } catch (e: Throwable) {
            Logx.e("Run failed for ${skill.name}", e)
            TaskOutcome(
                taskId = task.id,
                status = OutcomeStatus.FAILED,
                goalValidated = false,
                completedSteps = 0,
                totalSteps = skill.steps.size,
                cloudCalls = 0,
                deviceAiCalls = 0,
                message = e.message ?: "The automation stopped unexpectedly",
            )
        } finally {
            registration.close()
        }

        val capturedRoutingEvents = synchronized(routingEvents) { routingEvents.toList() }
        capturedRoutingEvents.sortedBy { it.timestamp }.forEach { event ->
            historyStore.appendEvent(event)
            metrics.increment(Metric.AI_DECISIONS_TOTAL)
            if (event.tier?.isLocal == true) metrics.increment(Metric.AI_DECISIONS_ON_DEVICE)
            if (event.tier?.isCloud == true) metrics.increment(Metric.CLOUD_ESCALATIONS)
        }
        if (resolvedCloudCalls.get() > 0) {
            metrics.increment(Metric.CLOUD_ESCALATIONS_RESOLVED, resolvedCloudCalls.get().toLong())
        }

        val finishedAt = time.nowMillis()
        // A run that stalled for want of a runtime is reported as waiting rather than
        // deferred, so the user is told the phone is missing the ability to decide
        // rather than that the automation was merely postponed.
        val deferredState = if (router.hasRuntimeFor(InferenceRequirements())) {
            TaskState.DEFERRED
        } else {
            TaskState.WAITING_FOR_REASONING
        }
        task = task.copy(
            state = outcome.status.toTaskState(deferredState),
            finishedAt = finishedAt,
            cloudCallCount = outcome.cloudCalls,
            deviceAiCallCount = outcome.deviceAiCalls,
            recoveryCount = outcome.stepResults.count { it.recovered },
            failureReason = outcome.message.takeIf { outcome.status != OutcomeStatus.SUCCESS },
        )
        historyStore.saveTask(task)
        historyStore.saveOutcome(outcome)
        historyStore.appendEvent(
            task.lifecycleEvent(
                type = when (outcome.status) {
                    OutcomeStatus.SUCCESS -> ExecutionEventType.TASK_COMPLETED
                    OutcomeStatus.CANCELLED -> ExecutionEventType.TASK_CANCELLED
                    OutcomeStatus.DEFERRED -> ExecutionEventType.TASK_DEFERRED
                    else -> ExecutionEventType.TASK_FAILED
                },
                message = outcome.message.ifBlank { outcome.status.name.lowercase().replace('_', ' ') },
                timestamp = finishedAt,
                success = outcome.status == OutcomeStatus.SUCCESS,
            ),
        )
        recordMetrics(task, outcome, origin, startedAt, finishedAt)
        updateConfidence(skill, outcome)

        _activity.value = AgentActivity.Idle
        _pendingConfirmation.value = null
        return RunResult.Completed(task, outcome)
    }

    /**
     * Interprets a typed or spoken instruction.
     *
     * Resolves against the saved skills first: most instructions are asking for
     * something the user has already taught, and matching there avoids both an inference
     * call and the risk of improvising a different interpretation of a familiar request.
     */
    suspend fun interpretCommand(command: String): CommandResolution {
        val skills = skillStore.listEnabledSkills()
        matchExistingSkill(command, skills)?.let { (skill, instruction) ->
            return CommandResolution.MatchedSkill(skill, command, instruction)
        }

        val screen = (perception.observe() as? PerceptionResult.Success)?.snapshot
        val description = screen?.let { minimizer.describeScreen(it) }

        val routed = router.infer(
            label = "command-intent",
            schema = AiTasks.commandIntent,
            prompt = AiTasks.commandIntentPrompt(command, description),
            systemInstruction = AiTasks.SYSTEM_INSTRUCTION,
            requirements = InferenceRequirements(
                minConfidence = COMMAND_CONFIDENCE_THRESHOLD,
                localOnly = !settings.privacy().cloudEnabled,
            ),
        )

        val intent = routed.value ?: return CommandResolution.NotUnderstood(
            "I could not work out what to do with that",
        )

        val candidate = skills.firstOrNull { it.goal.similarityTo(intent.goal) >= GOAL_SIMILARITY_THRESHOLD }
        if (candidate != null) {
            // A matched automation replays what it was taught. Anything the request adds
            // on top, such as a different option, rides along as this run's instruction.
            val instruction = command.trim().takeIf { intent.parameters.isNotEmpty() }.orEmpty()
            return CommandResolution.MatchedSkill(candidate, intent.goal, instruction)
        }

        val currentPackage = screen?.packageName.orEmpty().takeIf { it.isNotBlank() && it != ownPackage }
        val targetPackage = when {
            intent.referencesCurrentScreen -> currentPackage ?: resolveAppPackage(intent.appHint)
            intent.appHint.isNotBlank() -> resolveAppPackage(intent.appHint) ?: currentPackage
            else -> currentPackage
        }
        // Nothing needs to have been taught. When no app could be pinned down here the
        // agent starts from the screen in front and opens the app the request names.
        return CommandResolution.ReadyToRun(
            buildAdHocAgentSkill(intent, targetPackage, command.trim(), time.nowMillis()),
        )
    }

    /** Approves or rejects the step currently waiting on the user. */
    fun resolveConfirmation(approved: Boolean) {
        _pendingConfirmation.value?.respond(approved)
        _pendingConfirmation.value = null
    }

    /** Stops the current run at the next step boundary. */
    fun cancelCurrentRun() {
        cancelled.set(true)
        _pendingConfirmation.value?.respond(false)
        _pendingConfirmation.value = null
    }

    /**
     * Updates a skill's learned confidence from how its run went.
     *
     * Recovering from a change is a success, but a less reliable one than running
     * untouched: the skill worked, and it also told us its recorded path is drifting.
     */
    private suspend fun updateConfidence(skill: SemanticSkill, outcome: TaskOutcome) {
        // One-off commands deliberately are not persisted as learned automations.
        // The stored copy is read again rather than the one the run started with: a run
        // can save a new version of its own automation, such as a route it learned, and
        // writing the old copy back would silently undo that.
        val stored = skillStore.get(skill.id) ?: return
        val current = stored.confidence
        val succeeded = outcome.status == OutcomeStatus.SUCCESS
        val recovered = outcome.stepResults.any { it.recovered }

        val delta = when {
            succeeded && !recovered -> CONFIDENCE_GAIN
            succeeded -> CONFIDENCE_GAIN_AFTER_RECOVERY
            outcome.status == OutcomeStatus.CANCELLED || outcome.status == OutcomeStatus.DEFERRED -> 0f
            else -> CONFIDENCE_LOSS
        }

        val updated = current.copy(
            score = (current.score + delta).coerceIn(0f, 1f),
            executionCount = current.executionCount + 1,
            successCount = current.successCount + if (succeeded) 1 else 0,
            validationFailures = current.validationFailures + if (outcome.goalValidated) 0 else 1,
            recoveryCount = current.recoveryCount + if (recovered) 1 else 0,
            uiDriftEvents = current.uiDriftEvents + if (recovered) 1 else 0,
            lastUiMatchScore = outcome.stepResults.map { it.validation.confidence }.average().toFloat()
                .takeIf { !it.isNaN() } ?: current.lastUiMatchScore,
        )
        skillStore.save(stored.copy(confidence = updated))
    }

    private suspend fun recordMetrics(
        task: AgentTask,
        outcome: TaskOutcome,
        origin: TaskOrigin,
        startedAt: Long,
        finishedAt: Long,
    ) {
        when (outcome.status) {
            OutcomeStatus.SUCCESS -> {
                metrics.increment(Metric.TASKS_COMPLETED)
                metrics.increment(Metric.TOTAL_TASK_DURATION_MS, finishedAt - startedAt)
                if (task.isZeroCloud) metrics.increment(Metric.ZERO_CLOUD_RUNS)
                if (origin == TaskOrigin.TIME_TRIGGER || origin == TaskOrigin.NOTIFICATION_TRIGGER) {
                    metrics.increment(Metric.AUTONOMOUS_TASKS_COMPLETED)
                    metrics.recordAutonomousCompletion(task.id, finishedAt)
                }
            }

            OutcomeStatus.PARTIAL, OutcomeStatus.FAILED, OutcomeStatus.BLOCKED ->
                metrics.increment(Metric.TASKS_FAILED)

            OutcomeStatus.CANCELLED -> metrics.increment(Metric.USER_INTERVENTIONS)
            OutcomeStatus.DEFERRED -> Unit
        }
        if (outcome.stepResults.any { it.recovered }) {
            metrics.increment(Metric.RECOVERIES_ATTEMPTED)
            if (outcome.status == OutcomeStatus.SUCCESS) metrics.increment(Metric.RECOVERIES_SUCCEEDED)
        }
    }

    /**
     * One line saying what each runtime did with the question.
     *
     * Kept short because it sits in a list a person scrolls, and ordered as the router
     * tried them, so the escalation path reads left to right.
     */
    private fun describeExhaustion(label: String, attempts: List<RoutingAttempt>): String =
        describeRoutingExhaustion(label, attempts)


    private fun newTask(skill: SemanticSkill, origin: TaskOrigin, payload: Map<String, String>) = AgentTask(
        id = Ids.task(),
        goal = skill.goal,
        origin = origin,
        skillId = skill.id,
        skillVersion = skill.version,
        createdAt = time.nowMillis(),
        triggerPayload = payload,
    )

    /**
     * Matches a command against saved skills by name and goal.
     *
     * Deliberately literal. Fuzzy matching here would occasionally run the wrong
     * automation, and the cost of that is far higher than the cost of falling through to
     * interpretation.
     */
    private fun matchExistingSkill(command: String, skills: List<SemanticSkill>): Pair<SemanticSkill, String>? {
        val normalised = command.trim().lowercase()
        if (normalised.isEmpty()) return null
        skills.firstOrNull { it.name.lowercase() == normalised }?.let { return it to "" }
        val containing = skills.filter { normalised.contains(it.name.lowercase()) && it.name.length >= MIN_NAME_MATCH }
        val skill = containing.singleOrNull() ?: return null
        return skill to instructionBeyondName(command, skill.name)
    }

    /** Progress reporter that persists events and drives the on-screen indicator. */
    private inner class RecordingObserver(
        override val taskId: String,
        private val skill: SemanticSkill,
        private val confirmation: ConfirmationMode,
    ) : ExecutionObserver {

        // Routing events are recorded by the router listener, which also owns the
        // routing metrics, so this only has to persist what the executor reports.
        override suspend fun onEvent(event: ExecutionEvent) {
            historyStore.appendEvent(event)
        }

        override suspend fun onStepStarted(index: Int, step: SkillStep) {
            _activity.value = AgentActivity.Running(
                taskId = taskId,
                skillName = skill.name,
                stepDescription = step.describeForUser(),
                stepIndex = index,
                totalSteps = skill.steps.size,
                attended = (_activity.value as? AgentActivity.Running)?.attended ?: false,
            )
        }

        override suspend fun onTargetResolved(index: Int, node: UiNode) {
            val current = _activity.value
            if (current is AgentActivity.Running) {
                _activity.value = current.copy(touchTarget = node.bounds)
            }
        }

        override suspend fun requestConfirmation(step: SkillStep, decision: RiskDecision): Boolean {
            val mode = confirmation as? ConfirmationMode.AskUser ?: return false
            metrics.increment(Metric.USER_INTERVENTIONS)
            val pending = PendingConfirmation(step, decision)
            _pendingConfirmation.value = pending
            // A prompt nobody answers is treated as a refusal: an automation must never
            // proceed with a risky step because the user happened not to be looking.
            val approved = withTimeoutOrNull(mode.timeoutMs) { pending.await() } ?: false
            _pendingConfirmation.value = null
            return approved
        }

        override suspend fun onAgentProgress(index: Int, note: String) {
            _activity.update { current ->
                if (current is AgentActivity.Running && current.taskId == taskId) {
                    current.copy(stepDescription = note.take(MAX_PROGRESS_NOTE_LENGTH))
                } else {
                    current
                }
            }
        }

        override suspend fun onPatchProposed(patchId: String, summary: String, needsConfirmation: Boolean) {
            _activity.value = (_activity.value as? AgentActivity.Running)?.copy(
                repairNote = summary,
            ) ?: _activity.value
        }

        override fun isCancelled(): Boolean = cancelled.get() || settings.killSwitch().engaged
    }

    private companion object {
        const val CONFIDENCE_GAIN = 0.08f
        const val CONFIDENCE_GAIN_AFTER_RECOVERY = 0.02f
        const val CONFIDENCE_LOSS = -0.15f
        const val COMMAND_CONFIDENCE_THRESHOLD = 0.5f
        const val GOAL_SIMILARITY_THRESHOLD = 0.55f
        const val MIN_NAME_MATCH = 4

        const val MAX_PROGRESS_NOTE_LENGTH = 80
        val EXECUTION_ROUTING_LABELS = setOf(
            GoalAgent.LABEL,
            "element-match",
            "element-match-visual",
            "value-extraction",
            "outcome-check",
            "recovery-proposal",
        )
    }
}

private fun AgentTask.lifecycleEvent(
    type: ExecutionEventType,
    message: String,
    timestamp: Long,
    success: Boolean? = null,
) = ExecutionEvent(
    id = Ids.event(),
    taskId = id,
    timestamp = timestamp,
    type = type,
    message = Logx.redact(message),
    success = success,
)

/** What the agent is doing, for the status banner and the touch indicator. */
sealed interface AgentActivity {
    data object Idle : AgentActivity

    data class Running(
        val taskId: String,
        val skillName: String,
        val stepDescription: String,
        val stepIndex: Int,
        val totalSteps: Int,
        val touchTarget: com.autobile.core.model.Bounds? = null,
        val repairNote: String? = null,
        /**
         * Which runtime is being consulted right now, while it is being consulted.
         *
         * A reasoning call takes seconds — longer over a network — and during them the
         * step description does not change. Without this the run looks stopped at
         * exactly the moment it is doing the most work, which is when someone reaches
         * for the stop button.
         */
        val deliberating: RuntimeTier? = null,
        /**
         * Whether the user started this run and is waiting on it.
         *
         * An attended run ends by returning to Autobile, because the user asked for it
         * and is owed the result. A scheduled or notification-triggered run must not:
         * pulling someone out of what they were doing to announce a background task is
         * the behaviour that gets an automation app uninstalled.
         */
        val attended: Boolean = false,
    ) : AgentActivity
}

/**
 * A step waiting for the user's decision.
 *
 * Published as state so any surface — the app, the overlay, a notification — can present
 * it, and completed exactly once by whichever one the user actually responds on.
 */
class PendingConfirmation(
    val step: SkillStep,
    val decision: RiskDecision,
) {
    private val answer = CompletableDeferred<Boolean>()

    internal suspend fun await(): Boolean = answer.await()

    internal fun respond(approved: Boolean) {
        answer.complete(approved)
    }
}

/** How risky steps are approved during a run. */
sealed interface ConfirmationMode {
    /**
     * Prompt the user and wait.
     *
     * @param timeoutMs how long to wait before treating silence as a refusal.
     */
    data class AskUser(val timeoutMs: Long = DEFAULT_CONFIRMATION_TIMEOUT_MS) : ConfirmationMode

    /** Refuse every risky step without prompting, for runs with no user present. */
    data object AutoDecline : ConfirmationMode
}

const val DEFAULT_CONFIRMATION_TIMEOUT_MS: Long = 60_000L

sealed interface RunResult {
    data class Completed(val task: AgentTask, val outcome: TaskOutcome) : RunResult
    data class Deferred(val task: AgentTask, val state: com.autobile.core.model.ExecutabilityState) : RunResult
    data class Rejected(val reason: String) : RunResult
}

sealed interface CommandResolution {
    /**
     * @property instruction what the command asked beyond naming the automation, passed
     *   to that run; empty when the command only named it.
     */
    data class MatchedSkill(
        val skill: SemanticSkill,
        val goal: String,
        val instruction: String = "",
    ) : CommandResolution
    data class ReadyToRun(val skill: SemanticSkill) : CommandResolution

    data class NotUnderstood(val reason: String) : CommandResolution
}

/**
 * Compiles a direct command into a one-off, goal-driven task.
 *
 * There are no steps: the goal agent works from the screen. The completion criteria the
 * command was interpreted with become the post-condition the agent has to see twice
 * before it may report success, and the task starts at ask-first autonomy, so the user
 * approves it before the first action and approves anything that commits separately.
 *
 * @param packageName the app to start in, when one could be identified. Without one the
 *   agent is told which app the request names and opens it itself.
 */
internal fun buildAdHocAgentSkill(
    intent: com.autobile.ai.task.CommandIntent,
    packageName: String?,
    command: String,
    now: Long,
): SemanticSkill {
    val completion = intent.completionCriteria.ifBlank { "The requested outcome is visibly complete" }
    val appNote = intent.appHint.takeIf { it.isNotBlank() && packageName == null }
        ?.let { "Use the $it app." }
        .orEmpty()
    return SemanticSkill(
        id = Ids.skill(),
        version = 1,
        name = intent.goal.take(48),
        goal = intent.goal,
        description = appNote,
        trigger = TriggerSpec.Manual,
        steps = emptyList(),
        postconditions = listOf(Condition(description = completion)),
        // The words the user typed are kept alongside the interpreted goal. An
        // interpretation loses detail, and the detail is usually what the user meant.
        guidance = listOf(command).filter { it.isNotBlank() && !it.equals(intent.goal, ignoreCase = true) },
        strategy = ExecutionStrategy.AGENT_FIRST,
        riskPolicy = RiskPolicy(requireConfirmation = true, maxAutonomy = AutonomyLevel.L2_ASK_BEFORE_ACTION),
        autonomyLevel = AutonomyLevel.L2_ASK_BEFORE_ACTION,
        confidence = SkillConfidence(score = 0.25f),
        runtimeRequirements = RuntimeRequirements(
            requiredPackages = listOfNotNull(packageName),
        ),
        createdAt = now,
        updatedAt = now,
    )
}

/**
 * What a command asks of this run beyond naming the automation, or "" when nothing.
 *
 * "Sudoku on hard" names the automation and says something about this run; the words
 * around the name are the part the recorded steps do not already know. "Run Sudoku now"
 * says nothing more, and must keep replaying what was taught rather than hand the run to
 * the agent. The whole command is returned, because the words only make sense together.
 */
internal fun instructionBeyondName(command: String, name: String): String {
    val remainder = command.trim().lowercase()
        .replace(name.trim().lowercase(), " ")
        .split(WORD_SEPARATOR)
        .filter { it.isNotBlank() && it !in RUN_FILLER_WORDS }
        .joinToString("")
    return if (remainder.length >= MIN_INSTRUCTION_LENGTH) command.trim() else ""
}

private const val MIN_INSTRUCTION_LENGTH = 2
private val WORD_SEPARATOR = Regex("[^\\p{L}\\p{N}]+")

/** Words that only ask for a run, in the languages the app ships. */
private val RUN_FILLER_WORDS = setOf(
    "run", "start", "do", "it", "now", "please", "execute", "launch", "go", "the", "again",
    "실행", "실행해", "실행해줘", "실행해주세요", "실행하기", "시작", "시작해", "시작해줘",
    "돌려", "돌려줘", "해", "해줘", "해주세요", "줘", "지금", "좀", "다시", "부탁해",
)

private fun OutcomeStatus.toTaskState(deferredState: TaskState): TaskState = when (this) {
    OutcomeStatus.SUCCESS -> TaskState.COMPLETED
    OutcomeStatus.PARTIAL, OutcomeStatus.FAILED -> TaskState.FAILED
    OutcomeStatus.DEFERRED -> deferredState
    OutcomeStatus.CANCELLED -> TaskState.CANCELLED
    OutcomeStatus.BLOCKED -> TaskState.BLOCKED
}

/**
 * Word-overlap similarity between two goal descriptions.
 *
 * Intentionally simple: the result only decides whether to offer an existing skill or to
 * suggest teaching a new one, and both outcomes are presented to the user rather than
 * acted on silently.
 */
private fun String.similarityTo(other: String): Float {
    val a = lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 2 }.toSet()
    val b = other.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 2 }.toSet()
    if (a.isEmpty() || b.isEmpty()) return 0f
    return a.intersect(b).size.toFloat() / maxOf(a.size, b.size)
}

/**
 * One line saying what each runtime did with the question.
 *
 * Kept short because it sits in a list a person scrolls, and ordered as the router tried
 * them, so the escalation path reads left to right.
 */
internal fun describeRoutingExhaustion(label: String, attempts: List<RoutingAttempt>): String = buildString {
        append(label.replace('-', ' ')).append(": ")
        if (attempts.isEmpty()) {
            append("no runtime was available to try")
            return@buildString
        }
        append(
            attempts.joinToString("; ") { attempt ->
                val tier = attempt.tier.diagnosticName
                val failure = attempt.errorKind
                when {
                    attempt.skipped -> "$tier skipped, ${attempt.reason?.diagnosticName ?: "not eligible"}"
                    failure != null -> "$tier ${failure.name.lowercase().replace('_', ' ')}"
                    else -> "$tier answered with confidence ${"%.2f".format(attempt.confidence)}"
                }
            },
        )
}
