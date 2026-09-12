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
import com.autobile.core.model.Condition
import com.autobile.core.model.ConditionKind
import com.autobile.core.model.ExecutionEvent
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
 * A goal nobody could check is not a goal that was missed.
 *
 * Most phones have no runtime that can answer a semantic question, which is the ordinary
 * case rather than a fault. An automation whose every step did what it was told, each
 * confirming its own screen, was reported as failed because no model was available to
 * say the goal had been reached.
 *
 * The distinction that matters: a check that could not run, versus a check that ran and
 * said no. Neither confirms anything, and only one means something went wrong.
 */
@RunWith(RobolectricTestRunner::class)
class UncheckedGoalTest {

    private lateinit var context: Context
    private lateinit var skillStore: SkillStore
    private lateinit var riskEngine: RiskEngine

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val database = AutobileDatabase(context)
        skillStore = SkillStore(database)
        val settings = SettingsStore(context)
        settings.setKillSwitch(false)
        riskEngine = RiskEngine(AppPolicyStore(database), settings)
    }

    private fun executor(screen: FakeScreen): SkillExecutor {
        // A router with no provider that can answer: the phone most people own.
        val router = routerWith(ScriptedProvider())
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
        override suspend fun onEvent(event: ExecutionEvent) = Unit
        override suspend fun onStepStarted(index: Int, step: SkillStep) = Unit
        override suspend fun requestConfirmation(step: SkillStep, decision: RiskDecision) = true
        override suspend fun onPatchProposed(patchId: String, summary: String, needsConfirmation: Boolean) = Unit
        override fun isCancelled() = false
    }

    private fun skill(postconditions: List<Condition>) = SemanticSkill(
        id = "skill",
        version = 1,
        name = "Write the date down",
        goal = "write the date into a note",
        steps = listOf(
            SkillStep(
                id = "s1",
                intent = StepIntent.SELECT_ITEM,
                target = TargetSemantics(intentLabel = "Daily totals", description = "Daily totals"),
                preferredResolver = ResolverKind.ACCESSIBILITY_NODE,
                action = ActionSpec.Click,
                validation = ValidationSpec(mode = ValidationMode.NONE),
            ),
        ),
        postconditions = postconditions,
        autonomyLevel = AutonomyLevel.L4_EXPLICITLY_TRUSTED,
    )

    private fun screenWithTarget() = FakeScreen(
        screen("com.example.notes", "Note", node("n1", text = "Daily totals", clickable = true)),
    )

    @Test
    fun `a run whose every step worked is not reported as failed for want of a model`() = runTest {
        val skill = skill(listOf(Condition(description = "the note was written", kind = ConditionKind.SEMANTIC)))
        val screen = screenWithTarget()

        val outcome = executor(screen).execute(
            skill,
            AgentTask(id = "task", goal = skill.goal, origin = TaskOrigin.MANUAL, skillId = skill.id),
            Silent(),
        )

        assertThat(screen.clicked).isNotEmpty()
        assertThat(outcome.status).isEqualTo(OutcomeStatus.SUCCESS)
        // And it never claims the goal was confirmed, because nothing confirmed it.
        assertThat(outcome.goalValidated).isFalse()
    }

    @Test
    fun `a goal that was checked and not met is still reported as partial`() = runTest {
        // The safety property this rests on: an answer of "no" is not the same as no
        // answer, and only the first should ever be rounded up.
        val skill = skill(
            listOf(
                Condition(
                    description = "the note was written",
                    kind = ConditionKind.STRUCTURAL,
                    requiredTexts = listOf("definitely not on this screen"),
                ),
            ),
        )

        val outcome = executor(screenWithTarget()).execute(
            skill,
            AgentTask(id = "task", goal = skill.goal, origin = TaskOrigin.MANUAL, skillId = skill.id),
            Silent(),
        )

        assertThat(outcome.status).isEqualTo(OutcomeStatus.PARTIAL)
        assertThat(outcome.goalValidated).isFalse()
    }
}
