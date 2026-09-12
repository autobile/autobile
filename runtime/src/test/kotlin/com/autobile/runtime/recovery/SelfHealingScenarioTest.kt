package com.autobile.runtime.recovery

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.autobile.ai.task.RecoveryAction
import com.autobile.ai.task.RecoveryProposal
import com.autobile.core.data.AppPolicyStore
import com.autobile.core.data.AutobileDatabase
import com.autobile.core.data.SettingsStore
import com.autobile.core.data.SkillStore
import com.autobile.core.model.ActionSpec
import com.autobile.core.model.AgentTask
import com.autobile.core.model.AutonomyLevel
import com.autobile.core.model.ExecutionEvent
import com.autobile.core.model.FallbackPolicy
import com.autobile.core.model.Locator
import com.autobile.core.model.LocatorKind
import com.autobile.core.model.OutcomeStatus
import com.autobile.core.model.ResolverKind
import com.autobile.core.model.RiskDecision
import com.autobile.core.model.SemanticSkill
import com.autobile.core.model.SkillStep
import com.autobile.core.model.StepIntent
import com.autobile.core.model.TargetSemantics
import com.autobile.core.model.TaskOrigin
import com.autobile.core.model.ValidationMode
import com.autobile.core.model.ValidationSpec
import com.autobile.runtime.FakeScreen
import com.autobile.runtime.ScriptedProvider
import com.autobile.runtime.executor.ExecutionObserver
import com.autobile.runtime.executor.SkillExecutor
import com.autobile.runtime.node
import com.autobile.runtime.resolver.ExecutionResolver
import com.autobile.runtime.risk.RiskEngine
import com.autobile.runtime.routerWith
import com.autobile.runtime.screen
import com.autobile.runtime.validation.ValidationEngine
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A real repair, end to end.
 *
 * The target is not on the screen the step arrived at. Rather than failing, the run
 * proposes a move, makes it, finds what it was looking for and finishes — and the repair
 * that worked is offered as a new version rather than discarded, so the next run does
 * not have to rediscover it.
 */
@RunWith(RobolectricTestRunner::class)
class SelfHealingScenarioTest {

    private lateinit var skillStore: SkillStore
    private lateinit var riskEngine: RiskEngine

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val database = AutobileDatabase(context)
        skillStore = SkillStore(database)
        val settings = SettingsStore(context)
        settings.setKillSwitch(false)
        riskEngine = RiskEngine(AppPolicyStore(database), settings)
    }

    private class Recorder(override val taskId: String = "task") : ExecutionObserver {
        val patches = mutableListOf<String>()
        override suspend fun onEvent(event: ExecutionEvent) = Unit
        override suspend fun onStepStarted(index: Int, step: SkillStep) = Unit
        override suspend fun requestConfirmation(step: SkillStep, decision: RiskDecision) = true
        override suspend fun onPatchProposed(patchId: String, summary: String, needsConfirmation: Boolean) {
            patches += summary
        }
        override fun isCancelled() = false
    }

    @Test
    fun `a target moved behind a menu is reached, and the repair is kept`() = runTest {
        // The app moved the row behind a "More" menu: the first screen does not hold it,
        // the screen after tapping "More" does.
        val before = screen("com.example.business", "Reports", node("more", text = "More", clickable = true))
        val after = screen(
            "com.example.business",
            "Reports",
            node("more", text = "More", clickable = true),
            node("daily", text = "Daily totals", resourceId = "com.example:id/row_daily", clickable = true),
        )
        val device = FakeScreen(current = before, nextScreen = after)

        val provider = ScriptedProvider().answerWith(
            "recovery-proposal",
            RecoveryProposal(
                action = RecoveryAction.TAP,
                index = 0,
                direction = "",
                reason = "the row is behind the More menu",
                confidence = 0.85f,
            ),
        )

        val router = routerWith(provider)
        val executor = SkillExecutor(
            perception = device,
            controller = device,
            resolver = ExecutionResolver(router),
            validation = ValidationEngine(router),
            healing = SelfHealingEngine(router, riskEngine),
            riskEngine = riskEngine,
            router = router,
            skillStore = skillStore,
        )

        val skill = SemanticSkill(
            id = "skill",
            version = 1,
            name = "Open the daily totals",
            goal = "open the daily totals",
            steps = listOf(
                SkillStep(
                    id = "s1",
                    intent = StepIntent.SELECT_ITEM,
                    target = TargetSemantics(
                        intentLabel = "Daily totals",
                        description = "Daily totals",
                        locators = listOf(Locator(LocatorKind.TEXT, "Daily totals", strength = 0.7f)),
                    ),
                    preferredResolver = ResolverKind.ACCESSIBILITY_NODE,
                    action = ActionSpec.Click,
                    validation = ValidationSpec(mode = ValidationMode.NONE),
                    fallback = FallbackPolicy(maxRetries = 2),
                ),
            ),
            autonomyLevel = AutonomyLevel.L4_EXPLICITLY_TRUSTED,
        )
        skillStore.save(skill)
        val recorder = Recorder()

        val outcome = executor.execute(
            skill,
            AgentTask(id = "task", goal = skill.goal, origin = TaskOrigin.MANUAL, skillId = skill.id),
            recorder,
        )

        assertThat(outcome.status).isEqualTo(OutcomeStatus.SUCCESS)
        assertThat(device.clicked).contains("daily")
        assertThat(outcome.stepResults.single().recovered).isTrue()
        // PP-07: a repair that worked becomes a version candidate rather than being lost.
        assertThat(recorder.patches).isNotEmpty()
    }
}
