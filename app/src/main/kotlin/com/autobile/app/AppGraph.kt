package com.autobile.app

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.view.inputmethod.InputMethodManager
import com.autobile.ai.cloud.CloudAiProvider
import com.autobile.ai.cloud.CloudConfig
import com.autobile.ai.cloud.CloudService
import com.autobile.ai.cloud.oauth.ChatGptSignIn
import com.autobile.ai.cloud.oauth.CloudSession
import com.autobile.ai.context.ContextMinimizer
import com.autobile.ai.local.LocalModelProvider
import com.autobile.ai.mlkit.MLKitGeminiNanoProvider
import com.autobile.ai.router.AiRuntimeRouter
import com.autobile.core.common.Ids
import com.autobile.core.data.AppPolicyStore
import com.autobile.core.data.AutobileDatabase
import com.autobile.core.data.HistoryStore
import com.autobile.core.data.Metric
import com.autobile.core.data.MetricsStore
import com.autobile.core.data.SettingsStore
import com.autobile.core.data.SkillPatchCandidate
import com.autobile.core.data.SkillStore
import com.autobile.core.data.TraceStore
import com.autobile.core.model.ActionSpec
import com.autobile.core.model.AutonomyLevel
import com.autobile.core.model.ExpectedState
import com.autobile.core.model.PatchAuthor
import com.autobile.core.model.ResolverKind
import com.autobile.core.model.RiskPolicy
import com.autobile.core.model.RuntimeRequirements
import com.autobile.core.model.SemanticSkill
import com.autobile.core.model.SkillConfidence
import com.autobile.core.model.SkillStep
import com.autobile.core.model.SkillVersionRecord
import com.autobile.core.model.StepIntent
import com.autobile.core.model.TargetSemantics
import com.autobile.core.model.TaskOrigin
import com.autobile.core.model.TriggerSpec
import com.autobile.core.model.ValidationMode
import com.autobile.core.model.ValidationSpec
import com.autobile.runtime.AutobileRuntime
import com.autobile.runtime.AutobileServices
import com.autobile.runtime.agent.AgentOrchestrator
import com.autobile.runtime.agent.ConfirmationMode
import com.autobile.runtime.background.ExecutabilityEvaluator
import com.autobile.runtime.capability.CapabilityDetector
import com.autobile.runtime.compiler.SkillCompiler
import com.autobile.runtime.control.ScreenController
import com.autobile.runtime.edit.SkillEditor
import com.autobile.runtime.executor.SkillExecutor
import com.autobile.runtime.overlay.AgentVisibilityCoordinator
import com.autobile.runtime.perception.PerceptionEngine
import com.autobile.runtime.recovery.SelfHealingEngine
import com.autobile.runtime.resolver.ExecutionResolver
import com.autobile.runtime.risk.RiskEngine
import com.autobile.runtime.teach.DemonstrationRecorder
import com.autobile.runtime.teach.TraceSegmenter
import com.autobile.runtime.trigger.TriggerScheduler
import com.autobile.runtime.validation.ValidationEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.Closeable

/** Process-wide dependency graph shared by UI, workers, and Android services. */
class AppGraph(val appContext: Context) : AutobileServices, Closeable {
    private val context = appContext.applicationContext
    private val graphJob: Job = SupervisorJob()
    private val scope = CoroutineScope(graphJob + Dispatchers.Default)
    private val database = AutobileDatabase(context)

    override val settings = SettingsStore(context)
    override val skillStore = SkillStore(database)
    override val historyStore = HistoryStore(database)
    val traceStore = TraceStore(database)
    val policyStore = AppPolicyStore(database)
    val metricsStore = MetricsStore(database)

    val deviceAi = MLKitGeminiNanoProvider()
    val localModel = LocalModelProvider(
        modelDirectory = context.getDir("models", Context.MODE_PRIVATE),
        enabled = { settings.localModelEnabled },
    )

    private fun cloudConfig(advanced: Boolean = false): CloudConfig {
        val privacy = settings.privacy()
        // The chosen service supplies the endpoint and the model names; anything the
        // user typed themselves wins over it, so a compatible deployment of their own
        // still works without needing a preset of its own.
        val service = CloudService.from(privacy.cloudServiceName)
        return CloudConfig(
            enabled = privacy.cloudEnabled,
            service = service,
            endpoint = privacy.cloudEndpoint.ifBlank { service.endpoint },
            apiKey = settings.cloudApiKey(),
            session = CloudSession.decode(settings.cloudSession()),
            lightModel = privacy.cloudModel.ifBlank { service.lightModel },
            advancedModel = privacy.cloudVisionModel.ifBlank { service.advancedModel },
            allowImages = privacy.allowScreenshotToCloud,
            maxInputTokens = if (advanced) 32_000 else 8_000,
        )
    }

    /**
     * Renews a signed-in session and writes it back.
     *
     * A failure leaves the stored session untouched. It may still work — a refresh can
     * fail for a dropped connection as easily as for a revoked grant — and discarding
     * it would sign the user out over a moment of bad signal.
     */
    private suspend fun renewCloudSession(session: CloudSession) {
        val renewed = ChatGptSignIn.refresh(session).getOrNull() ?: return
        settings.setCloudSession(CloudSession.encode(renewed), renewed.label)
    }

    override val aiRouter = AiRuntimeRouter(
        listOf(
            deviceAi,
            localModel,
            CloudAiProvider(
                com.autobile.core.model.RuntimeTier.CLOUD_LIGHT,
                { cloudConfig() },
                ::renewCloudSession,
            ),
            CloudAiProvider(
                com.autobile.core.model.RuntimeTier.CLOUD_ADVANCED,
                { cloudConfig(advanced = true) },
                ::renewCloudSession,
            ),
        ),
    )

    private val minimizer = ContextMinimizer()
    private val perception = PerceptionEngine(context)
    private val runtimeWords = ResourceRuntimeVocabulary(appContext)
    private val controller = ScreenController(context)
    private val resolver = ExecutionResolver(aiRouter, minimizer) { settings.privacy().maskSensitiveFields }
    private val riskEngine = RiskEngine(policyStore, settings, runtimeWords)
    private val validation = ValidationEngine(aiRouter, minimizer, runtimeWords)
    private val healing = SelfHealingEngine(aiRouter, riskEngine, minimizer)
    private val executor = SkillExecutor(
        perception = perception,
        controller = controller,
        resolver = resolver,
        validation = validation,
        healing = healing,
        riskEngine = riskEngine,
        router = aiRouter,
        skillStore = skillStore,
        minimizer = minimizer,
        words = runtimeWords,
        ownPackage = appContext.packageName,
    )
    val capabilityDetector = CapabilityDetector(
        context = context,
        deviceAi = deviceAi,
        localModel = localModel,
        cloudConfig = { cloudConfig() },
    )
    private val executability = ExecutabilityEvaluator(context, policyStore, settings)
    override val orchestrator = AgentOrchestrator(
        executor = executor,
        perception = perception,
        router = aiRouter,
        skillStore = skillStore,
        historyStore = historyStore,
        metrics = metricsStore,
        settings = settings,
        executability = executability,
        capabilityDetector = capabilityDetector,
        minimizer = minimizer,
        words = runtimeWords,
    )
    override val triggerScheduler = TriggerScheduler(context)
    private val segmenter = TraceSegmenter(aiRouter, transitPackages = transitPackages(context))
    val compiler = SkillCompiler(
        aiRouter,
        segmenter,
        riskEngine,
        vocabulary = ResourceCompilerVocabulary(appContext),
        canOpen = ::hasSomethingToOpen,
    )
    val recorder = DemonstrationRecorder(perception, scope)
    val skillEditor = SkillEditor(aiRouter, skillStore, triggerScheduler)
    private val visibility = AgentVisibilityCoordinator(context, orchestrator, settings)

    fun start() {
        AutobileRuntime.install(this)
        visibility.start(scope)
        scope.launch {
            seedProtectivePolicies()
            triggerScheduler.rescheduleAll(skillStore.listEnabledSkills())
            traceStore.prune(System.currentTimeMillis() - TRACE_RETENTION_MS)
            capabilityDetector.detect()
        }
    }

    /**
     * The packages that carry a person between apps, rather than apps they use.
     *
     * The home screen the device resolves to, the system UI that draws recents and the
     * shade, and every enabled keyboard. A keyboard is the one people forget: it opens a
     * window of its own the moment a field is focused, so a demonstration that types
     * anything records it as an app that was visited. Compiled as a step, the automation
     * then tries to launch a keyboard — which has no launcher activity, so it reports
     * the keyboard as not installed while the user is looking at it.
     *
     * Only the resolved home is listed, not every activity answering the home intent:
     * Settings registers a fallback home for devices with no launcher, and that reading
     * silently makes every task taught in Settings uncompilable.
     */
    /** Whether the phone can be asked to open this package at all. */
    private fun hasSomethingToOpen(packageName: String): Boolean = runCatching {
        context.packageManager.getLaunchIntentForPackage(packageName) != null
    }.getOrDefault(true)

    private fun transitPackages(context: Context): Set<String> {
        val keyboards = runCatching {
            context.getSystemService(InputMethodManager::class.java)
                ?.enabledInputMethodList
                ?.map { it.packageName }
                .orEmpty()
        }.getOrDefault(emptyList())
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val packages = context.packageManager
        val launcher = runCatching {
            // The typed-flags overload only exists from API 33, and this app runs from
            // 30. Lint catches the difference; a device on 11 or 12 would not.
            val resolved = if (Build.VERSION.SDK_INT >= 33) {
                packages.resolveActivity(
                    home,
                    PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()),
                )
            } else {
                @Suppress("DEPRECATION")
                packages.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
            }
            resolved?.activityInfo?.packageName
        }.getOrNull()
        return buildSet {
            launcher?.takeIf { it != context.packageName }?.let(::add)
            addAll(keyboards.filter { it.isNotBlank() && it != context.packageName })
            add("com.android.systemui")
        }
    }

    suspend fun starterSkill(): SemanticSkill {
        skillStore.get(STARTER_SKILL_ID)?.let { return it }
        val now = System.currentTimeMillis()
        return skillStore.save(
            SemanticSkill(
                id = STARTER_SKILL_ID,
                version = 1,
                // Written in the user's language at the moment it is created. This is
                // the first automation anyone sees, sitting on the home screen under
                // their own list; generating it in English made the app look like it had
                // only been half translated.
                name = appContext.getString(R.string.starter_skill_name),
                goal = appContext.getString(R.string.starter_skill_goal),
                description = appContext.getString(R.string.starter_skill_description),
                trigger = TriggerSpec.Manual,
                steps = listOf(
                    SkillStep(
                        id = Ids.step(),
                        intent = StepIntent.LAUNCH_APP,
                        target = TargetSemantics(
                            appContext.getString(R.string.starter_skill_target),
                            appContext.getString(R.string.starter_skill_target_description),
                        ),
                        preferredResolver = ResolverKind.DIRECT_API,
                        action = ActionSpec.LaunchApp("com.android.settings"),
                        expectedState = ExpectedState(requiredPackage = "com.android.settings"),
                        validation = ValidationSpec(mode = ValidationMode.STRUCTURAL, goalCritical = true),
                        description = appContext.getString(R.string.starter_skill_goal),
                    ),
                ),
                riskPolicy = RiskPolicy(requireConfirmation = false),
                autonomyLevel = AutonomyLevel.L3_AUTONOMOUS_LOW_RISK,
                confidence = SkillConfidence(score = 0.7f),
                runtimeRequirements = RuntimeRequirements(requiredPackages = listOf("com.android.settings")),
                history = listOf(
                    SkillVersionRecord(1, now, PatchAuthor.COMPILER, "Created as a starter automation"),
                ),
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    suspend fun runStarterTask() = orchestrator.runSkill(
        starterSkill().id,
        TaskOrigin.MANUAL,
        confirmation = ConfirmationMode.AskUser(),
    )

    suspend fun acceptPatch(candidate: SkillPatchCandidate): Result<SemanticSkill> = runCatching {
        val current = skillStore.get(candidate.skillId) ?: error("Automation not found")
        check(current.version == candidate.baseVersion) { "Automation changed since this repair was proposed" }
        val version = current.version + 1
        val record = SkillVersionRecord(
            version = version,
            createdAt = System.currentTimeMillis(),
            author = PatchAuthor.SELF_HEAL,
            reason = candidate.summary,
            summary = candidate.summary,
            changedStepIds = candidate.patchedSkill.steps.map { it.id },
        )
        val saved = skillStore.save(
            candidate.patchedSkill.copy(version = version, history = current.history),
            record,
        )
        skillStore.updatePatchStatus(candidate.id, com.autobile.core.data.PatchStatus.ACCEPTED)
        triggerScheduler.cancel(saved.id)
        triggerScheduler.schedule(saved)
        saved
    }

    suspend fun rejectPatch(candidate: SkillPatchCandidate) {
        skillStore.updatePatchStatus(candidate.id, com.autobile.core.data.PatchStatus.REJECTED)
    }

    private suspend fun seedProtectivePolicies() {
        val packages = runCatching {
            context.packageManager.getInstalledApplications(0)
                .asSequence()
                .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 }
                .map { it.packageName to context.packageManager.getApplicationLabel(it).toString() }
                .toList()
        }.getOrDefault(emptyList())
        policyStore.seedDefaults(packages)
    }

    override fun close() {
        visibility.stop()
        recorder.cancel()
        runBlocking { graphJob.cancelAndJoin() }
        aiRouter.close()
        database.close()
    }

    private companion object {
        const val STARTER_SKILL_ID = "starter-open-settings"
        const val TRACE_RETENTION_MS = 7L * 24 * 60 * 60 * 1000
    }
}
