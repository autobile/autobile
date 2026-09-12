package com.autobile.runtime.executor

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.autobile.core.data.AppPolicyStore
import com.autobile.core.data.AutobileDatabase
import com.autobile.core.data.SettingsStore
import com.autobile.core.data.SkillStore
import com.autobile.core.model.ActionSpec
import com.autobile.core.model.AgentTask
import com.autobile.core.model.AutonomyLevel
import com.autobile.core.model.ExecutionEvent
import com.autobile.core.model.Locator
import com.autobile.core.model.LocatorKind
import com.autobile.core.model.OutcomeStatus
import com.autobile.core.model.PerceptionResult
import com.autobile.core.model.ResolverKind
import com.autobile.core.model.RiskDecision
import com.autobile.core.model.RuntimeTier
import com.autobile.core.model.SemanticSkill
import com.autobile.core.model.SkillStep
import com.autobile.core.model.StepIntent
import com.autobile.core.model.TargetSemantics
import com.autobile.core.model.TaskOrigin
import com.autobile.core.model.ValidationMode
import com.autobile.core.model.ValidationSpec
import com.autobile.runtime.FakeScreen
import com.autobile.runtime.ScriptedProvider
import com.autobile.runtime.node
import com.autobile.runtime.recovery.SelfHealingEngine
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
 * Replaying a skill against a screen that has changed since it was taught.
 *
 * This is the claim the product is built on: a demonstration is stored as what each
 * control *meant*, so an app that moves its buttons around does not break the
 * automation. It is also the claim that is easiest to believe without checking.
 */
@RunWith(RobolectricTestRunner::class)
class SemanticReplayTest {

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

    private fun executor(screen: FakeScreen, provider: ScriptedProvider = ScriptedProvider()): SkillExecutor {
        val router = routerWith(provider)
        return SkillExecutor(
            perception = screen,
            controller = screen,
            resolver = ExecutionResolver(router),
            validation = ValidationEngine(router),
            healing = SelfHealingEngine(router, riskEngine),
            riskEngine = riskEngine,
            router = router,
            skillStore = skillStore,
        )
    }

    private class Silent(override val taskId: String = "task") : ExecutionObserver {
        val events = mutableListOf<ExecutionEvent>()
        override suspend fun onEvent(event: ExecutionEvent) { events += event }
        override suspend fun onStepStarted(index: Int, step: SkillStep) = Unit
        override suspend fun requestConfirmation(step: SkillStep, decision: RiskDecision) = true
        override suspend fun onPatchProposed(patchId: String, summary: String, needsConfirmation: Boolean) = Unit
        override fun isCancelled() = false
    }

    /** Taught against an element with a specific id, at a specific place in the tree. */
    private fun taughtSkill() = SemanticSkill(
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
                    locators = listOf(
                        Locator(LocatorKind.RESOURCE_ID, "com.example:id/row_daily", strength = 1f),
                        Locator(LocatorKind.TEXT, "Daily totals", strength = 0.7f),
                        Locator(LocatorKind.HIERARCHY_PATH, "0/2/1", strength = 0.3f),
                    ),
                ),
                preferredResolver = ResolverKind.ACCESSIBILITY_NODE,
                action = ActionSpec.Click,
                validation = ValidationSpec(mode = ValidationMode.NONE),
            ),
        ),
        autonomyLevel = AutonomyLevel.L4_EXPLICITLY_TRUSTED,
    )

    private fun run(screen: FakeScreen, provider: ScriptedProvider = ScriptedProvider()): Pair<OutcomeStatus, Silent> {
        val skill = taughtSkill()
        val observer = Silent()
        val outcome = kotlinx.coroutines.runBlocking {
            executor(screen, provider).execute(
                skill,
                AgentTask(id = "task", goal = skill.goal, origin = TaskOrigin.MANUAL, skillId = skill.id),
                observer,
            )
        }
        return outcome.status to observer
    }

    @Test
    fun `the app redesigning its ids does not break the automation`() = runTest {
        // A release renames every id. The label is what the user meant, and it survives.
        val screen = FakeScreen(
            screen(
                "com.example.business",
                "Reports",
                node("moved", text = "Daily totals", resourceId = "com.example:id/v2_daily_row", clickable = true),
            ),
        )

        val (status, _) = run(screen)

        assertThat(screen.clicked).contains("moved")
        assertThat(status).isEqualTo(OutcomeStatus.SUCCESS)
    }

    @Test
    fun `the row moving to a different place in the list does not break it either`() = runTest {
        // Same id, different position. A recorded path would have pressed the wrong row.
        val screen = FakeScreen(
            screen(
                "com.example.business",
                "Reports",
                node("other", text = "Weekly totals", resourceId = "com.example:id/row_weekly", clickable = true),
                node(
                    "target",
                    text = "Daily totals",
                    resourceId = "com.example:id/row_daily",
                    clickable = true,
                    indexPath = listOf(9, 9, 9),
                ),
            ),
        )

        val (status, _) = run(screen)

        assertThat(screen.clicked).containsExactly("target")
        assertThat(status).isEqualTo(OutcomeStatus.SUCCESS)
    }

    @Test
    fun `a screen that is protected stops the run rather than being worked around`() = runTest {
        val blocked = FakeScreen(perception = PerceptionResult.BlockedSecureWindow("com.example.bank"))

        val (status, observer) = run(blocked)

        assertThat(status).isEqualTo(OutcomeStatus.BLOCKED)
        assertThat(blocked.clicked).isEmpty()
        assertThat(observer.events.any { (it.message ?: "").contains("protected") }).isTrue()
    }
}
