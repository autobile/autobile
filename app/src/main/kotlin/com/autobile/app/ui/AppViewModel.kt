package com.autobile.app.ui

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.autobile.ai.cloud.CloudService
import com.autobile.ai.cloud.oauth.ChatGptSignIn
import com.autobile.ai.cloud.oauth.CloudSession
import com.autobile.ai.cloud.oauth.LoopbackCallback
import com.autobile.ai.cloud.oauth.Pkce
import com.autobile.ai.cloud.oauth.SignInFailed
import com.autobile.ai.mlkit.DownloadState
import com.autobile.ai.mlkit.ModelDownloadProgress
import com.autobile.core.data.Metric
import com.autobile.core.data.PatchStatus
import com.autobile.core.data.SkillPatchCandidate
import com.autobile.core.model.AgentTask
import com.autobile.core.model.AppPolicy
import com.autobile.core.model.AppPolicyMode
import com.autobile.core.model.AutonomyLevel
import com.autobile.core.model.DeviceCapabilityProfile
import com.autobile.core.model.ExecutionEvent
import com.autobile.core.model.MetricsSnapshot
import com.autobile.core.model.PrivacySettings
import com.autobile.core.model.RuntimeTier
import com.autobile.core.model.SemanticSkill
import com.autobile.core.model.SkillVersionRecord
import com.autobile.core.model.TaskOrigin
import com.autobile.core.model.TaskOutcome
import com.autobile.core.model.TriggerSpec
import com.autobile.runtime.accessibility.AccessibilityBridge
import com.autobile.runtime.agent.AgentActivity
import com.autobile.runtime.agent.CommandResolution
import com.autobile.runtime.agent.ConfirmationMode
import com.autobile.runtime.agent.PendingConfirmation
import com.autobile.runtime.agent.RunResult
import com.autobile.runtime.compiler.CompilationResult
import com.autobile.runtime.edit.SkillEditApplyResult
import com.autobile.runtime.edit.SkillEditPreview
import com.autobile.runtime.teach.RecordingState
import com.autobile.app.AppGraph
import com.autobile.app.R
import com.autobile.app.TeachingForegroundService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class AppViewModel(private val graph: AppGraph) : ViewModel() {
    private val _state = MutableStateFlow(
        AppUiState(
            onboardingComplete = graph.settings.onboardingComplete,
            privacy = graph.settings.privacy(),
            killSwitchEngaged = graph.settings.killSwitch().engaged,
            touchIndicatorEnabled = graph.settings.showTouchIndicator,
            nanoDownloadConsented = graph.settings.deviceAiDownloadConsented,
        ),
    )
    val state: StateFlow<AppUiState> = _state.asStateFlow()

    /** The sign-in in progress, so a second tap does not open a second browser tab. */
    private var signIn: Job? = null

    /**
     * What the sign-in that is waiting needs in order to finish.
     *
     * Kept off the observable state: the verifier is the secret that proves this app
     * started the sign-in, and state that reaches the interface reaches logs with it.
     */
    private var pendingSignIn: PendingSignIn? = null

    init {
        viewModelScope.launch {
            graph.skillStore.observeSkills().collectLatest { skills ->
                val selectedId = _state.value.selectedSkillId
                _state.update {
                    it.copy(
                        skills = skills,
                        patches = graph.skillStore.pendingPatches(),
                        skillVersions = selectedId?.let { id -> graph.skillStore.versions(id) }.orEmpty(),
                    )
                }
            }
        }
        viewModelScope.launch {
            graph.historyStore.observeTasks().collectLatest { tasks ->
                val selectedId = _state.value.selectedSkillId
                _state.update {
                    it.copy(
                        tasks = tasks,
                        selectedSkillLastTask = tasks.firstOrNull { task -> task.skillId == selectedId },
                    )
                }
            }
        }
        viewModelScope.launch {
            graph.metricsStore.observe().collectLatest { metrics -> _state.update { it.copy(metrics = metrics) } }
        }
        viewModelScope.launch {
            graph.policyStore.observe().collectLatest { policies -> _state.update { it.copy(policies = policies) } }
        }
        viewModelScope.launch {
            graph.orchestrator.activity.collectLatest { activity -> _state.update { it.copy(activity = activity) } }
        }
        viewModelScope.launch {
            graph.orchestrator.pendingConfirmation.collectLatest { pending ->
                _state.update { it.copy(pendingConfirmation = pending) }
            }
        }
        viewModelScope.launch {
            graph.recorder.state.collectLatest { recording -> _state.update { it.copy(recording = recording) } }
        }
        // The screen-control capability is the one the user is watching on the first
        // screen, and it flips without any interaction of theirs: the service binds a
        // moment after launch, and the system can withdraw it at any time. Polling on
        // resume misses both, leaving "Not available" on screen while the service is in
        // fact connected — the app declaring itself broken when it is working.
        viewModelScope.launch {
            AccessibilityBridge.connected.collectLatest { refreshCapabilities() }
        }
        recoverInterruptedTeaching()
        refreshCapabilities()
    }

    /**
     * Accounts for a teaching session that the process did not outlive.
     *
     * A demonstration is performed with Autobile in the background, which is when
     * Android is most willing to reclaim it, and the recording only exists in memory.
     * Saying so is the whole point: the alternative is the user returning to an ordinary
     * home screen, with no sign that the five minutes they just spent were discarded.
     */
    private fun recoverInterruptedTeaching() {
        if (!graph.settings.teachingSessionOpen) return
        if (graph.recorder.state.value.recording) return
        graph.settings.teachingSessionOpen = false
        TeachingForegroundService.stop(graph.appContext)
        _state.update { it.copy(message = graph.appContext.getString(R.string.msg_teaching_interrupted)) }
    }

    fun navigate(screen: AppScreen) {
        _state.update { it.copy(screen = screen, message = null) }
    }

    fun dismissMessage() {
        _state.update { it.copy(message = null) }
    }

    fun refreshCapabilities() = launchAction {
        val profile = graph.capabilityDetector.detect()
        _state.update { it.copy(capability = profile, loading = false) }
    }

    fun nextOnboardingStep() {
        val next = OnboardingStep.entries.getOrElse(_state.value.onboardingStep.ordinal + 1) {
            OnboardingStep.COMPLETE
        }
        if (next == OnboardingStep.COMPLETE) finishOnboarding() else {
            _state.update { it.copy(onboardingStep = next) }
        }
    }

    fun runStarterTask() = launchAction {
        when (val result = graph.runStarterTask()) {
            is RunResult.Completed -> {
                _state.update {
                    it.copy(
                        // The stored message is a diagnostic written for the timeline.
                        // Handing it to someone on their first run spends their first
                        // impression on vocabulary that only means something in here.
                        message = graph.appContext.getString(
                            if (result.outcome.goalValidated) {
                                R.string.msg_first_run_complete
                            } else {
                                R.string.msg_first_run_unverified
                            },
                        ),
                        onboardingStep = OnboardingStep.TEACH,
                    )
                }
            }
            is RunResult.Deferred -> showMessageRes(result.state.labelRes())
            is RunResult.Rejected -> showMessage(result.reason)
        }
    }

    fun startTeaching(label: String? = null) = launchAction {
        if (!_state.value.capability.accessibilityConnected) {
            showMessageRes(R.string.msg_accessibility_required)
            return@launchAction
        }
        val name = label?.takeIf { it.isNotBlank() }
            ?: graph.appContext.getString(R.string.default_automation_name)
        graph.metricsStore.increment(Metric.TEACH_SESSIONS_STARTED)
        graph.recorder.start(name)
        graph.settings.teachingSessionOpen = true
        TeachingForegroundService.start(graph.appContext)
        // The instruction has to match what the user will actually find. Telling someone
        // to come back through a notification that the platform is not going to show is
        // how a session ends with them force-quitting the app.
        val instruction = if (_state.value.capability.canPostNotifications) {
            R.string.msg_recording_started
        } else {
            R.string.msg_recording_no_notification
        }
        _state.update {
            it.copy(screen = AppScreen.TEACH, message = graph.appContext.getString(instruction))
        }
    }

    fun cancelTeaching() {
        graph.recorder.cancel()
        graph.settings.teachingSessionOpen = false
        TeachingForegroundService.stop(graph.appContext)
        _state.update { it.copy(screen = AppScreen.HOME, compilation = null) }
    }

    fun finishTeaching() = launchAction {
        val trace = graph.recorder.stop()
        graph.settings.teachingSessionOpen = false
        TeachingForegroundService.stop(graph.appContext)
        if (trace == null || trace.events.isEmpty()) {
            showMessageRes(R.string.teach_nothing_recorded)
            return@launchAction
        }
        graph.traceStore.save(trace)
        _state.update { it.copy(loading = true, message = graph.appContext.getString(R.string.teach_understanding)) }
        when (val result = graph.compiler.compile(trace, localOnly = !graph.settings.privacy().cloudEnabled)) {
            is CompilationResult.Failed -> _state.update {
                it.copy(loading = false, message = result.reason, screen = AppScreen.TEACH)
            }
            is CompilationResult.Success -> _state.update {
                it.copy(
                    loading = false,
                    compilation = TeachDraft(
                        skill = result.skill,
                        summary = result.summary,
                        discardedSteps = result.discardedSteps,
                        usedCloud = result.usedCloud,
                        understoodBy = result.understoodBy,
                    ),
                    screen = AppScreen.TEACH_REVIEW,
                    message = null,
                )
            }
        }
    }

    fun updateTeachName(value: String) {
        _state.update { state ->
            state.copy(compilation = state.compilation?.copy(skill = state.compilation.skill.copy(name = value)))
        }
    }

    fun updateTeachGoal(value: String) {
        _state.update { state ->
            state.copy(compilation = state.compilation?.copy(skill = state.compilation.skill.copy(goal = value)))
        }
    }

    fun updateTeachAutonomy(value: AutonomyLevel) {
        _state.update { state ->
            state.copy(compilation = state.compilation?.copy(skill = state.compilation.skill.copy(autonomyLevel = value)))
        }
    }

    /**
     * Applies a plain-language correction to the understanding just presented.
     *
     * Editing the goal text alone would leave the compiled steps untouched, so a
     * correction such as "send net sales, not gross" has to run through the same editor
     * that changes a saved automation and rewrite the step semantics.
     */
    fun correctUnderstanding(request: String) = launchAction {
        val draft = _state.value.compilation ?: return@launchAction
        if (request.isBlank()) return@launchAction
        val preview = graph.skillEditor.preview(
            skill = draft.skill,
            request = request,
            localOnly = !graph.settings.privacy().cloudEnabled,
        )
        when (preview) {
            is SkillEditPreview.Rejected -> showMessage(preview.reason)
            is SkillEditPreview.Ready -> {
                graph.metricsStore.increment(Metric.USER_INTERVENTIONS)
                _state.update { state ->
                    state.copy(
                        compilation = state.compilation?.copy(
                            // The draft is still unsaved, so it stays at version 1
                            // rather than inheriting the editor's incremented version.
                            skill = preview.updated.copy(version = draft.skill.version),
                            summary = preview.summary,
                        ),
                        message = graph.appContext.getString(R.string.msg_correction_applied, preview.summary),
                    )
                }
            }
        }
    }

    fun updateTeachSchedule(hour: Int?, minute: Int?) {
        _state.update { state ->
            val draft = state.compilation ?: return@update state
            val trigger = if (hour == null || minute == null) TriggerSpec.Manual else TriggerSpec.Time(hour, minute)
            state.copy(compilation = draft.copy(skill = draft.skill.copy(trigger = trigger)))
        }
    }

    fun saveTeaching() = launchAction {
        val draft = _state.value.compilation ?: return@launchAction
        if (draft.skill.name.isBlank() || draft.skill.goal.isBlank()) {
            showMessageRes(R.string.msg_name_goal_required)
            return@launchAction
        }
        val timeTrigger = draft.skill.trigger as? TriggerSpec.Time
        if (timeTrigger != null && (timeTrigger.hour !in 0..23 || timeTrigger.minute !in 0..59)) {
            showMessageRes(R.string.msg_schedule_range)
            return@launchAction
        }
        val saved = graph.skillStore.save(draft.skill)
        graph.triggerScheduler.schedule(saved)
        graph.metricsStore.increment(Metric.TEACH_SESSIONS_COMPLETED)
        graph.metricsStore.increment(Metric.SKILLS_CREATED)
        _state.update {
            it.copy(
                compilation = null,
                selectedSkillId = saved.id,
                screen = if (it.onboardingComplete) AppScreen.SKILL_DETAIL else AppScreen.HOME,
                onboardingStep = if (it.onboardingComplete) it.onboardingStep else OnboardingStep.REPLAY,
                message = graph.appContext.getString(R.string.msg_automation_saved),
            )
        }
    }

    fun finishOnboarding() {
        graph.settings.onboardingComplete = true
        _state.update { it.copy(onboardingComplete = true, onboardingStep = OnboardingStep.COMPLETE, screen = AppScreen.HOME) }
    }

    fun selectSkill(id: String) = launchAction {
        val versions = graph.skillStore.versions(id)
        val lastTask = graph.historyStore.recentTasks().firstOrNull { it.skillId == id }
        _state.update {
            it.copy(
                selectedSkillId = id,
                skillVersions = versions,
                selectedSkillLastTask = lastTask,
                screen = AppScreen.SKILL_DETAIL,
            )
        }
    }

    fun setSkillEnabled(skill: SemanticSkill, enabled: Boolean) = launchAction {
        graph.skillStore.setEnabled(skill.id, enabled)
        if (enabled) graph.triggerScheduler.schedule(skill.copy(enabled = true)) else graph.triggerScheduler.cancel(skill.id)
    }

    fun setSkillAutonomy(skill: SemanticSkill, autonomy: AutonomyLevel) = launchAction {
        graph.skillStore.save(
            skill.copy(version = skill.version + 1, autonomyLevel = autonomy),
            SkillVersionRecord(
                version = skill.version + 1,
                createdAt = System.currentTimeMillis(),
                author = com.autobile.core.model.PatchAuthor.USER,
                reason = "Changed autonomy to ${autonomy.name}",
            ),
        )
    }

    fun rollbackSkill(skillId: String, version: Int) = launchAction {
        val restored = graph.skillStore.rollbackTo(skillId, version)
        if (restored == null) showMessageRes(R.string.msg_version_unavailable) else {
            graph.triggerScheduler.cancel(skillId)
            graph.triggerScheduler.schedule(restored)
            selectSkill(skillId)
            showMessage(graph.appContext.getString(R.string.msg_version_restored, restored.version))
        }
    }

    fun deleteSkill(skill: SemanticSkill) = launchAction {
        graph.triggerScheduler.cancel(skill.id)
        graph.skillStore.delete(skill.id)
        _state.update {
            it.copy(
                screen = AppScreen.HOME,
                selectedSkillId = null,
                message = graph.appContext.getString(R.string.msg_automation_deleted),
            )
        }
    }

    fun runSkill(skillId: String, replay: Boolean = false) = launchAction {
        when (val result = graph.orchestrator.runSkill(
            skillId = skillId,
            origin = if (replay) TaskOrigin.REPLAY else TaskOrigin.MANUAL,
            confirmation = ConfirmationMode.AskUser(),
        )) {
            is RunResult.Completed -> {
                _state.update {
                    it.copy(
                        message = result.outcome.message.ifBlank {
                            graph.appContext.getString(R.string.msg_automation_finished)
                        },
                    )
                }
                if (!itIsOnboarded()) {
                    _state.update { it.copy(onboardingStep = OnboardingStep.COMPLETE) }
                    finishOnboarding()
                }
            }
            is RunResult.Deferred -> showMessageRes(result.state.labelRes())
            is RunResult.Rejected -> showMessage(result.reason)
        }
    }

    fun submitCommand(command: String) = launchAction {
        if (command.isBlank()) return@launchAction
        _state.update { it.copy(loading = true) }
        when (val resolution = graph.orchestrator.interpretCommand(command)) {
            is CommandResolution.MatchedSkill -> {
                _state.update { it.copy(loading = false) }
                runSkill(resolution.skill.id)
            }
            is CommandResolution.NeedsTeaching -> _state.update {
                it.copy(
                    loading = false,
                    message = graph.appContext.getString(R.string.msg_show_me_how, resolution.goal),
                    teachLabel = resolution.goal,
                    screen = AppScreen.TEACH,
                )
            }
            is CommandResolution.NotUnderstood -> _state.update {
                it.copy(loading = false, message = resolution.reason)
            }
        }
    }

    fun previewEdit(skill: SemanticSkill, request: String) = launchAction {
        _state.update { it.copy(loading = true) }
        val preview = graph.skillEditor.preview(
            skill.id,
            request,
            localOnly = !graph.settings.privacy().cloudEnabled,
        )
        _state.update { it.copy(loading = false, editPreview = preview) }
    }

    fun dismissEditPreview() {
        _state.update { it.copy(editPreview = null) }
    }

    fun applyEdit(preview: SkillEditPreview.Ready) = launchAction {
        when (val result = graph.skillEditor.apply(preview)) {
            is SkillEditApplyResult.Applied -> {
                _state.update {
                    it.copy(editPreview = null, message = graph.appContext.getString(R.string.msg_change_applied))
                }
                selectSkill(result.skill.id)
            }
            is SkillEditApplyResult.Rejected -> _state.update {
                it.copy(editPreview = null, message = result.reason)
            }
        }
    }

    fun reviewPatch(candidate: SkillPatchCandidate, accept: Boolean) = launchAction {
        if (accept) {
            graph.acceptPatch(candidate).fold(
                onSuccess = { showMessage(graph.appContext.getString(R.string.msg_repair_applied, it.version)) },
                onFailure = { showMessage(it.message ?: graph.appContext.getString(R.string.msg_repair_failed)) },
            )
        } else {
            graph.rejectPatch(candidate)
            showMessageRes(R.string.msg_repair_dismissed)
        }
        _state.update { it.copy(patches = graph.skillStore.pendingPatches()) }
    }

    fun selectTask(taskId: String) = launchAction {
        val task = graph.historyStore.getTask(taskId) ?: return@launchAction
        val events = graph.historyStore.events(taskId)
        val outcome = graph.historyStore.outcome(taskId)
        _state.update {
            it.copy(selectedTask = task, selectedTaskEvents = events, selectedTaskOutcome = outcome, screen = AppScreen.HISTORY_DETAIL)
        }
    }

    fun approvePending(approved: Boolean) {
        graph.orchestrator.resolveConfirmation(approved)
    }

    fun stopAgent() {
        graph.orchestrator.cancelCurrentRun()
    }

    fun updateCloudEnabled(enabled: Boolean) = updatePrivacy { it.copy(cloudEnabled = enabled) }
    fun updateCloudScreenshots(enabled: Boolean) = updatePrivacy { it.copy(allowScreenshotToCloud = enabled) }
    fun updateMasking(enabled: Boolean) = updatePrivacy { it.copy(maskSensitiveFields = enabled) }
    fun updateCloudEndpoint(value: String) = updatePrivacy { it.copy(cloudEndpoint = value.trim()) }

    /**
     * Switches to a different cloud service.
     *
     * Any endpoint and model the user typed for the previous one are cleared, because
     * they name resources on a service that is no longer selected: keeping them would
     * point a Claude key at an OpenAI URL and fail with something unhelpful. A screenshot
     * consent given for a service that cannot accept pictures is dropped for the same
     * reason — it would promise something the service will refuse.
     */
    fun updateCloudService(name: String) = updatePrivacy { current ->
        val service = CloudService.from(name)
        current.copy(
            cloudServiceName = service.name,
            cloudEndpoint = "",
            cloudModel = "",
            cloudVisionModel = "",
            allowScreenshotToCloud = current.allowScreenshotToCloud && service.supportsImages,
        )
    }

    /**
     * Signs in to a subscription service in the system browser.
     *
     * The browser is used rather than an in-app view on purpose: a sign-in page shown
     * inside the app asking for a password is indistinguishable, to the person typing,
     * from one the app wrote itself. The browser shows the real address bar, the session
     * is exchanged on this device, and nothing is proxied through anything of ours.
     */
    fun signInToCloud() {
        if (signIn?.isActive == true) return
        signIn = viewModelScope.launch {
            val callback = LoopbackCallback()
            val port = callback.open().getOrElse { failed(it); return@launch }
            _state.update { it.copy(cloudSignInPending = true) }
            val pkce = Pkce.generate()
            val state = Pkce.state()
            val redirect = ChatGptSignIn.redirectUri(port)
            val opened = graph.appContext.openUrlExternally(ChatGptSignIn.authorizeUrl(redirect, pkce, state))
            if (!opened) {
                callback.close()
                failed(SignInFailed(graph.appContext.getString(R.string.msg_cloud_no_browser)))
                return@launch
            }
            pendingSignIn = PendingSignIn(state, pkce.verifier, redirect)
            val code = callback.awaitCode(state).getOrElse { failed(it); return@launch }
            completeSignIn(code)
        }
    }

    /**
     * Finishes a sign-in from a code the person pasted themselves.
     *
     * The loopback redirect is how this normally completes, and on most phones it does.
     * It cannot be relied on though: a browser may refuse to navigate to a local
     * address, or hand the redirect to a different app entirely. Without somewhere to
     * paste what is left in the address bar, those people simply cannot sign in, and
     * nothing on screen would tell them why.
     */
    fun completePastedSignIn(pasted: String) {
        val attempt = pendingSignIn ?: run {
            failed(SignInFailed(graph.appContext.getString(R.string.msg_cloud_sign_in_not_started)))
            return
        }
        val code = ChatGptSignIn.readPastedCode(pasted, attempt.state)
            .getOrElse { failed(it); return }
        signIn?.cancel()
        signIn = viewModelScope.launch { completeSignIn(code) }
    }

    private suspend fun completeSignIn(code: String) {
        val attempt = pendingSignIn ?: return
        val session = ChatGptSignIn.exchange(code, attempt.verifier, attempt.redirectUri)
            .getOrElse { failed(it); return }
        pendingSignIn = null
        graph.settings.setCloudSession(CloudSession.encode(session), session.label)
        _state.update {
            it.copy(
                privacy = graph.settings.privacy(),
                cloudSignInPending = false,
                message = graph.appContext.getString(R.string.msg_cloud_signed_in),
            )
        }
        refreshCapabilities()
    }

    fun cancelCloudSignIn() {
        signIn?.cancel()
        signIn = null
        pendingSignIn = null
        _state.update { it.copy(cloudSignInPending = false) }
    }

    fun signOutOfCloud() {
        graph.settings.setCloudSession("", "")
        _state.update {
            it.copy(
                privacy = graph.settings.privacy(),
                message = graph.appContext.getString(R.string.msg_cloud_signed_out),
            )
        }
        refreshCapabilities()
    }

    private fun failed(cause: Throwable) {
        _state.update {
            it.copy(
                cloudSignInPending = false,
                message = cause.message ?: graph.appContext.getString(R.string.msg_cloud_sign_in_failed),
            )
        }
    }

    fun setCloudApiKey(value: String) {
        graph.settings.setCloudApiKey(value.trim())
        _state.update {
            it.copy(privacy = graph.settings.privacy(), message = graph.appContext.getString(R.string.msg_cloud_credential_updated))
        }
        refreshCapabilities()
    }

    fun setKillSwitch(engaged: Boolean) {
        graph.settings.setKillSwitch(engaged, if (engaged) "Stopped from settings" else "")
        if (engaged) graph.orchestrator.cancelCurrentRun()
        _state.update { it.copy(killSwitchEngaged = engaged) }
    }

    fun setTouchIndicator(enabled: Boolean) {
        graph.settings.showTouchIndicator = enabled
        _state.update { it.copy(touchIndicatorEnabled = enabled) }
    }

    fun savePolicy(policy: AppPolicy, mode: AppPolicyMode) = launchAction {
        graph.policyStore.save(policy.copy(mode = mode, userSet = true))
    }

    fun consentAndDownloadDeviceModel() {
        graph.settings.deviceAiDownloadConsented = true
        _state.update { it.copy(nanoDownloadConsented = true) }
        viewModelScope.launch {
            graph.deviceAi.download().collectLatest { progress ->
                _state.update { it.copy(nanoDownload = progress) }
                if (progress.state == DownloadState.COMPLETED) refreshCapabilities()
            }
        }
    }

    private fun updatePrivacy(transform: (PrivacySettings) -> PrivacySettings) {
        val updated = transform(graph.settings.privacy())
        graph.settings.updatePrivacy(updated)
        _state.update { it.copy(privacy = graph.settings.privacy()) }
        refreshCapabilities()
    }

    /**
     * Shows a translated message.
     *
     * The view model resolves the string itself so that a reason produced deep in the
     * runtime still reaches the user in their own language, without the runtime needing
     * to know anything about resources.
     */
    private fun showMessageRes(@StringRes res: Int) {
        showMessage(graph.appContext.getString(res))
    }

    private fun showMessage(message: String) {
        _state.update { it.copy(message = message, loading = false) }
    }

    private fun launchAction(block: suspend () -> Unit) {
        viewModelScope.launch {
            runCatching { block() }
                .onFailure { showMessage(it.message ?: graph.appContext.getString(R.string.msg_generic_error)) }
        }
    }

    private fun itIsOnboarded(): Boolean = _state.value.onboardingComplete
}

enum class AppScreen { HOME, HISTORY, SETTINGS, SKILL_DETAIL, HISTORY_DETAIL, TEACH, TEACH_REVIEW }

enum class OnboardingStep { CAPABILITY, PERMISSIONS, INSTANT_TASK, TEACH, REPLAY, COMPLETE }

data class TeachDraft(
    val skill: SemanticSkill,
    val summary: String,
    val discardedSteps: Int,
    val usedCloud: Boolean,
    /** Which runtime worked out what this meant, or null when the rules did. */
    val understoodBy: RuntimeTier? = null,
)

/** The half of a sign-in that must survive the trip to the browser and back. */
private data class PendingSignIn(
    val state: String,
    val verifier: String,
    val redirectUri: String,
)

data class AppUiState(
    val onboardingComplete: Boolean = false,
    val onboardingStep: OnboardingStep = OnboardingStep.CAPABILITY,
    val screen: AppScreen = AppScreen.HOME,
    val capability: DeviceCapabilityProfile = DeviceCapabilityProfile(),
    val skills: List<SemanticSkill> = emptyList(),
    val tasks: List<AgentTask> = emptyList(),
    val metrics: MetricsSnapshot = MetricsSnapshot(),
    val policies: List<AppPolicy> = emptyList(),
    val patches: List<SkillPatchCandidate> = emptyList(),
    val activity: AgentActivity = AgentActivity.Idle,
    val pendingConfirmation: PendingConfirmation? = null,
    val recording: RecordingState = RecordingState(),
    val compilation: TeachDraft? = null,
    val selectedSkillId: String? = null,
    val skillVersions: List<SemanticSkill> = emptyList(),
    val selectedSkillLastTask: AgentTask? = null,
    val selectedTask: AgentTask? = null,
    val selectedTaskEvents: List<ExecutionEvent> = emptyList(),
    val selectedTaskOutcome: TaskOutcome? = null,
    val editPreview: SkillEditPreview? = null,
    val privacy: PrivacySettings = PrivacySettings(),
    val killSwitchEngaged: Boolean = false,
    val touchIndicatorEnabled: Boolean = true,
    val nanoDownloadConsented: Boolean = false,
    val nanoDownload: ModelDownloadProgress? = null,
    /**
     * Whether a browser sign-in is still waiting to be finished.
     *
     * Shown because the sign-in happens in another app: without it, someone who opened
     * the browser and came back sees the same button they already pressed and no sign
     * that anything is in progress.
     */
    val cloudSignInPending: Boolean = false,
    /**
     * The name offered when teaching starts, when something has suggested one.
     *
     * Null means no suggestion, and the screen fills in the translated default. A
     * literal here would have put an English name on every automation taught on a phone
     * set to any other language.
     */
    val teachLabel: String? = null,
    val loading: Boolean = true,
    val message: String? = null,
) {
    val selectedSkill: SemanticSkill? get() = skills.firstOrNull { it.id == selectedSkillId }
}

/**
 * Builds the view model with the process-wide graph.
 *
 * The graph is owned by the application rather than created per screen, so a run started
 * from the interface and one started by a scheduled trigger are driven by the same
 * orchestrator and cannot disagree about what is happening.
 */
class AppViewModelFactory(private val graph: AppGraph) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = AppViewModel(graph) as T
}
