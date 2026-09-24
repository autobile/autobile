package com.autobile.runtime.executor

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.autobile.ai.task.AgentAction
import com.autobile.ai.task.AgentActionType
import com.autobile.ai.task.AgentTurn
import com.autobile.ai.task.AgentTurnStatus
import com.autobile.core.data.AppPolicyStore
import com.autobile.core.data.AutobileDatabase
import com.autobile.core.data.SettingsStore
import com.autobile.core.model.ActionSpec
import com.autobile.core.model.AutonomyLevel
import com.autobile.core.model.ExecutionEvent
import com.autobile.core.model.InferenceErrorKind
import com.autobile.core.model.RiskDecision
import com.autobile.core.model.RiskPolicy
import com.autobile.core.model.SemanticSkill
import com.autobile.core.model.SkillConfidence
import com.autobile.core.model.SkillStep
import com.autobile.core.model.StepIntent
import com.autobile.core.model.TargetSemantics
import com.autobile.runtime.FakeScreen
import com.autobile.runtime.ScriptedProvider
import com.autobile.runtime.node
import com.autobile.runtime.perception.ScreenshotCapture
import com.autobile.runtime.risk.RiskEngine
import com.autobile.runtime.routerWith
import com.autobile.runtime.screen
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The goal agent is the part of a run that decides for itself, so what it refuses to do
 * matters as much as what it does. Each test drives it against a fake screen with a
 * scripted model and asserts on what was actually pressed.
 */
@RunWith(RobolectricTestRunner::class)
class GoalAgentTest {

    private lateinit var riskEngine: RiskEngine

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val database = AutobileDatabase(context)
        val settings = SettingsStore(context)
        settings.setKillSwitch(false)
        riskEngine = RiskEngine(AppPolicyStore(database), settings)
    }

    @Test
    fun `an unexpected reward pop-up is closed and the goal is still reached`() = runTest {
        val close = node("close", text = "Close")
        val screen = FakeScreen(
            current = screen("com.example.sudoku", "Daily reward", node("reward", text = "Day 3 reward"), close),
            nextScreen = screen("com.example.sudoku", "Board", node("cell", text = "Cell")),
        )
        val provider = ScriptedProvider().answerSequence(
            LABEL,
            act(AgentAction(AgentActionType.TAP, element = 1, label = "Close")),
            complete(),
            complete(),
        )

        val outcome = agent(screen, provider).pursue(mission(), skill(), step(), 0, Observer(), localOnly = false)

        assertThat(outcome.completed).isTrue()
        assertThat(screen.clicked).containsExactly("close")
        assertThat(provider.requestedPrompts.first()).contains("0. \"Day 3 reward\"")
        assertThat(provider.requestedPrompts.first()).contains("1. \"Close\" [clickable]")
    }

    @Test
    fun `a tap on the navigation bar is refused and the next proposal is performed`() = runTest {
        val screen = FakeScreen(current = screen("com.example.sudoku", "Board", node("cell", text = "Cell")))
        val provider = ScriptedProvider().answerSequence(
            LABEL,
            act(AgentAction(AgentActionType.TAP, x = 0.5f, y = 0.99f, label = "bottom button")),
            act(AgentAction(AgentActionType.TAP, x = 0.5f, y = 0.5f, label = "cell")),
            complete(),
            complete(),
        )

        val outcome = agent(screen, provider).pursue(mission(), skill(), step(), 0, Observer(), localOnly = false)

        assertThat(outcome.completed).isTrue()
        assertThat(screen.gestures).containsExactly("tap")
        assertThat(screen.homePresses).isEqualTo(0)
        assertThat(provider.requestedPrompts[1]).contains("system bar")
    }

    @Test
    fun `several actions chosen together are performed in order`() = runTest {
        val screen = FakeScreen(current = screen("com.example.sudoku", "Board", node("cell", text = "Cell")))
        val provider = ScriptedProvider().answerSequence(
            LABEL,
            act(
                AgentAction(AgentActionType.TAP, x = 0.31f, y = 0.42f, label = "row 3 column 2"),
                AgentAction(AgentActionType.TAP, x = 0.55f, y = 0.86f, label = "digit 7"),
            ),
            complete(),
            complete(),
        )

        val outcome = agent(screen, provider).pursue(mission(), skill(), step(), 0, Observer(), localOnly = false)

        assertThat(outcome.completed).isTrue()
        assertThat(outcome.actionsPerformed).isEqualTo(2)
        assertThat(screen.gestures).containsExactly("tap", "tap").inOrder()
    }

    @Test
    fun `a transient runtime failure is retried instead of ending the run`() = runTest {
        val screen = FakeScreen(current = screen("com.example.sudoku", "Board", node("cell", text = "Cell")))
        val provider = ScriptedProvider()
            .failOnceWith(LABEL, InferenceErrorKind.TIMEOUT)
            .answerSequence(LABEL, complete(), complete())

        val outcome = agent(screen, provider).pursue(mission(), skill(), step(), 0, Observer(), localOnly = false)

        assertThat(outcome.completed).isTrue()
        assertThat(provider.requestedLabels.count { it == LABEL }).isEqualTo(3)
    }

    @Test
    fun `completion is only accepted on two consecutive observations`() = runTest {
        val screen = FakeScreen(current = screen("com.example.sudoku", "Board", node("cell", text = "Cell")))
        val provider = ScriptedProvider().answerSequence(
            LABEL,
            complete(),
            act(AgentAction(AgentActionType.TAP, x = 0.5f, y = 0.5f)),
            complete(),
            complete(),
        )

        val outcome = agent(screen, provider).pursue(mission(), skill(), step(), 0, Observer(), localOnly = false)

        assertThat(outcome.completed).isTrue()
        assertThat(provider.requestedLabels.count { it == LABEL }).isEqualTo(4)
    }

    @Test
    fun `a purchase waits for the user and a refusal is not retried`() = runTest {
        val screen = FakeScreen(
            current = screen(
                "com.example.sudoku",
                "Shop",
                node("buy", text = "Buy 100 gems"),
                node("close", text = "Close"),
            ),
        )
        val provider = ScriptedProvider().answerSequence(
            LABEL,
            act(AgentAction(AgentActionType.TAP, element = 0, label = "Buy 100 gems", risk = "purchase")),
            act(AgentAction(AgentActionType.TAP, element = 1, label = "Close")),
            complete(),
            complete(),
        )
        val observer = Observer(approve = false)

        val outcome = agent(screen, provider).pursue(mission(), skill(), step(), 0, observer, localOnly = false)

        assertThat(outcome.completed).isTrue()
        assertThat(observer.confirmations).isEqualTo(1)
        assertThat(screen.clicked).containsExactly("close")
        assertThat(provider.requestedPrompts[1]).contains("The user declined")
    }

    @Test
    fun `a purchase is recognised from the control's words even when the model does not declare it`() = runTest {
        val screen = FakeScreen(current = screen("com.example.sudoku", "Shop", node("buy", text = "Buy now")))
        val provider = ScriptedProvider().answerSequence(
            LABEL,
            act(AgentAction(AgentActionType.TAP, element = 0)),
            blocked(),
            blocked(),
        )
        val observer = Observer(approve = false)

        agent(screen, provider).pursue(mission(), skill(), step(), 0, observer, localOnly = false)

        assertThat(observer.confirmations).isEqualTo(1)
        assertThat(screen.clicked).isEmpty()
    }

    @Test
    fun `closing a dialog with cancel does not ask the user`() = runTest {
        val screen = FakeScreen(
            current = screen("com.example.sudoku", "Rate us", node("cancel", text = "Cancel")),
            nextScreen = screen("com.example.sudoku", "Board", node("cell", text = "Cell")),
        )
        val provider = ScriptedProvider().answerSequence(
            LABEL,
            act(AgentAction(AgentActionType.TAP, element = 0, label = "Cancel")),
            complete(),
            complete(),
        )
        val observer = Observer(approve = false)

        val outcome = agent(screen, provider).pursue(mission(), skill(), step(), 0, observer, localOnly = false)

        assertThat(outcome.completed).isTrue()
        assertThat(observer.confirmations).isEqualTo(0)
        assertThat(screen.clicked).containsExactly("cancel")
    }

    @Test
    fun `a request that names no open app opens it by name`() = runTest {
        val screen = FakeScreen(
            current = screen("com.autobile", "Autobile", node("run", text = "Run")),
            nextScreen = screen("com.example.sudoku", "Board", node("cell", text = "Cell")),
        )
        val provider = ScriptedProvider().answerSequence(
            LABEL,
            act(AgentAction(AgentActionType.TAP, element = 0, label = "Run")),
            act(AgentAction(AgentActionType.OPEN_APP, app = "Sudoku")),
            complete(),
            complete(),
        )

        val outcome = agent(screen, provider, resolveApp = { if (it == "Sudoku") "com.example.sudoku" else null })
            .pursue(mission(taskApps = emptyList()), skill(), step(), 0, Observer(), localOnly = false)

        assertThat(outcome.completed).isTrue()
        assertThat(screen.clicked).isEmpty()
        assertThat(screen.launched).containsExactly("com.example.sudoku")
        assertThat(provider.requestedPrompts.first()).contains("this is the assistant itself")
    }

    @Test
    fun `a run that starts in another app opens the task app before asking anything`() = runTest {
        val screen = FakeScreen(
            current = screen("com.android.launcher", "Home", node("icon", text = "Sudoku")),
            nextScreen = screen("com.example.sudoku", "Board", node("cell", text = "Cell")),
        )
        val provider = ScriptedProvider().answerSequence(LABEL, complete(), complete())

        val outcome = agent(screen, provider).pursue(mission(), skill(), step(), 0, Observer(), localOnly = false)

        assertThat(outcome.completed).isTrue()
        assertThat(screen.launched).containsExactly("com.example.sudoku")
        assertThat(provider.requestedLabels.count { it == LABEL }).isEqualTo(2)
    }

    @Test
    fun `being stuck twice ends the pursuit with the model's reason`() = runTest {
        val screen = FakeScreen(current = screen("com.example.sudoku", "Login", node("password", text = "Password")))
        val provider = ScriptedProvider().answerSequence(LABEL, blocked(), blocked())

        val outcome = agent(screen, provider).pursue(mission(), skill(), step(), 0, Observer(), localOnly = false)

        assertThat(outcome.completed).isFalse()
        assertThat(outcome.decided).isTrue()
        assertThat(outcome.message).contains("password")
        assertThat(provider.requestedPrompts[1]).contains("You reported blocked")
    }

    @Test
    fun `no runtime means waiting rather than failing`() = runTest {
        val screen = FakeScreen(current = screen("com.example.sudoku", "Board", node("cell", text = "Cell")))

        val outcome = agent(screen, ScriptedProvider(available = false))
            .pursue(mission(), skill(), step(), 0, Observer(), localOnly = false)

        assertThat(outcome.awaitingReasoning).isTrue()
        assertThat(outcome.decided).isFalse()
    }

    @Test
    fun `a protected screen stops the pursuit`() = runTest {
        val screen = FakeScreen(
            current = screen("com.example.sudoku", "Board"),
            screenshot = ScreenshotCapture.SecureWindowBlocked("com.example.sudoku"),
        )

        val outcome = agent(screen, ScriptedProvider()).pursue(mission(), skill(), step(), 0, Observer(), localOnly = false)

        assertThat(outcome.blocked).isTrue()
        assertThat(screen.gestures).isEmpty()
    }

    @Test
    fun `the budget bounds the number of actions`() = runTest {
        val screen = FakeScreen(
            current = screen("com.example.sudoku", "Board", node("cell", text = "Cell")),
            screenshot = ScreenshotCapture.Success(Bitmap.createBitmap(60, 120, Bitmap.Config.ARGB_8888)),
        )
        val provider = ScriptedProvider().answerWith(LABEL, act(AgentAction(AgentActionType.WAIT, durationMs = 100)))

        val outcome = agent(screen, provider).pursue(mission(maxActions = 3), skill(), step(), 0, Observer(), localOnly = false)

        assertThat(outcome.completed).isFalse()
        assertThat(outcome.actionsPerformed).isEqualTo(3)
    }

    @Test
    fun `stopping the run stops the pursuit`() = runTest {
        val screen = FakeScreen(current = screen("com.example.sudoku", "Board", node("cell", text = "Cell")))

        val outcome = agent(screen, ScriptedProvider())
            .pursue(mission(), skill(), step(), 0, Observer(cancelled = true), localOnly = false)

        assertThat(outcome.cancelled).isTrue()
    }

    private fun agent(
        screen: FakeScreen,
        provider: ScriptedProvider,
        resolveApp: (String) -> String? = { null },
    ) = GoalAgent(
        perception = screen,
        controller = screen,
        router = routerWith(provider),
        riskEngine = riskEngine,
        ownPackage = "com.autobile",
        resolveApp = resolveApp,
    )

    private fun mission(
        taskApps: List<String> = listOf("com.example.sudoku"),
        maxActions: Int = 40,
    ) = AgentMission(
        objective = "Solve today's sudoku",
        completionCriteria = "A solved board or a success message is visible",
        taskApps = taskApps,
        maxActions = maxActions,
    )

    private fun skill() = SemanticSkill(
        id = "skill",
        version = 1,
        name = "Sudoku",
        goal = "Solve today's sudoku",
        autonomyLevel = AutonomyLevel.L3_AUTONOMOUS_LOW_RISK,
        confidence = SkillConfidence(score = 0.9f),
        riskPolicy = RiskPolicy(requireConfirmation = false),
    )

    private fun step() = SkillStep(
        id = "goal",
        intent = StepIntent.NAVIGATE,
        target = TargetSemantics("Solve today's sudoku"),
        action = ActionSpec.VisualTask("Solve today's sudoku", "Solved board visible"),
    )

    private fun act(vararg actions: AgentAction) = AgentTurn(
        status = AgentTurnStatus.ACT,
        actions = actions.toList(),
        observation = "",
        memory = "",
        progress = "working",
        confidence = 0.8f,
        reason = "advances the task",
    )

    private fun complete() = AgentTurn(
        status = AgentTurnStatus.COMPLETE,
        actions = emptyList(),
        observation = "solved board",
        memory = "",
        progress = "checking",
        confidence = 0.9f,
        reason = "solved board visible",
    )

    private fun blocked() = AgentTurn(
        status = AgentTurnStatus.BLOCKED,
        actions = emptyList(),
        observation = "login screen",
        memory = "",
        progress = "",
        confidence = 0.9f,
        reason = "a password is required",
    )

    private class Observer(
        private val approve: Boolean = true,
        private val cancelled: Boolean = false,
    ) : ExecutionObserver {
        override val taskId: String = "task"
        val events = mutableListOf<ExecutionEvent>()
        var confirmations = 0
            private set

        override suspend fun onEvent(event: ExecutionEvent) {
            events += event
        }

        override suspend fun requestConfirmation(step: SkillStep, decision: RiskDecision): Boolean {
            confirmations++
            return approve
        }

        override fun isCancelled(): Boolean = cancelled
    }

    private companion object {
        const val LABEL = "agent-turn"
    }
}
