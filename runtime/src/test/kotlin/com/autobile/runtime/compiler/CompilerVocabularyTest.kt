package com.autobile.runtime.compiler

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.autobile.core.data.AppPolicyStore
import com.autobile.core.data.AutobileDatabase
import com.autobile.core.data.SettingsStore
import com.autobile.core.model.DemonstrationTrace
import com.autobile.core.model.ObservedAction
import com.autobile.core.model.ScreenSnapshot
import com.autobile.core.model.StateTransition
import com.autobile.core.model.StepIntent
import com.autobile.core.model.TraceEvent
import com.autobile.runtime.ScriptedProvider
import com.autobile.runtime.node
import com.autobile.runtime.risk.RiskEngine
import com.autobile.runtime.routerWith
import com.autobile.runtime.teach.TraceSegmenter
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What the compiler writes when no model names the automation for it.
 *
 * This wording is not incidental: with cloud assistance off and no on-device AI — the
 * default on most phones — it becomes the name, the goal and every step description the
 * user reads for as long as the automation exists. It went out in English regardless of
 * the phone's language, so these tests pin it to the vocabulary rather than to literals.
 */
@RunWith(RobolectricTestRunner::class)
class CompilerVocabularyTest {

    private lateinit var riskEngine: RiskEngine

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val settings = SettingsStore(context)
        settings.setKillSwitch(false)
        riskEngine = RiskEngine(AppPolicyStore(AutobileDatabase(context)), settings)
    }

    /** A vocabulary that marks everything it produces, so its reach is visible. */
    private object ShoutingVocabulary : CompilerVocabulary {
        override fun newAutomation() = "VOCAB-NAME"
        override fun repeatTask(stepCount: Int, apps: List<String>) = "VOCAB-GOAL-$stepCount"
        override fun step(intent: StepIntent, target: String) = "VOCAB-STEP-${intent.name}"
        override fun theApp(name: String) = "VOCAB-APP-$name"
        override fun nothingRecorded() = "VOCAB-EMPTY"
        override fun noRepeatableSteps() = "VOCAB-NO-STEPS"
        override fun stepsNotUnderstood() = "VOCAB-UNREADABLE"
    }

    private fun compiler(vocabulary: CompilerVocabulary) = SkillCompiler(
        router = routerWith(ScriptedProvider()),
        segmenter = TraceSegmenter(routerWith(ScriptedProvider())),
        riskEngine = riskEngine,
        vocabulary = vocabulary,
    )

    private fun trace(label: String = "") = DemonstrationTrace(
        id = "t",
        label = label,
        startedAt = 0,
        endedAt = 1_000,
        events = listOf(
            TraceEvent(
                id = "e0",
                timestamp = 0,
                packageName = "com.example.reports",
                windowContext = "Reports",
                before = ScreenSnapshot(packageName = "com.example.reports", windowTitle = "Home"),
                after = ScreenSnapshot(packageName = "com.example.reports", windowTitle = "Reports"),
                action = ObservedAction.AppOpen("com.example.reports"),
                targetNode = node("n0", text = "Reports"),
                stateTransition = StateTransition(
                    "com.example.reports",
                    "com.example.reports",
                    "Home",
                    "Reports",
                ),
            ),
            TraceEvent(
                id = "e1",
                timestamp = 1,
                packageName = "com.example.reports",
                windowContext = "Reports",
                before = ScreenSnapshot(packageName = "com.example.reports", windowTitle = "Reports"),
                after = ScreenSnapshot(packageName = "com.example.reports", windowTitle = "Daily"),
                action = ObservedAction.Click,
                targetNode = node("n1", text = "Daily totals", resourceId = "com.example:id/daily"),
                stateTransition = StateTransition(
                    "com.example.reports",
                    "com.example.reports",
                    "Reports",
                    "Daily",
                ),
            ),
        ),
    )

    @Test
    fun `a goal invented by the compiler comes from the vocabulary`() = runTest {
        val result = compiler(ShoutingVocabulary).compile(trace(), localOnly = true)

        assertThat(result).isInstanceOf(CompilationResult.Success::class.java)
        val skill = (result as CompilationResult.Success).skill
        assertThat(skill.goal).startsWith("VOCAB-GOAL")
    }

    @Test
    fun `every step description the compiler writes comes from the vocabulary`() = runTest {
        val result = compiler(ShoutingVocabulary).compile(trace(), localOnly = true)

        val skill = (result as CompilationResult.Success).skill
        assertThat(skill.steps).isNotEmpty()
        skill.steps.forEach { assertThat(it.description).startsWith("VOCAB-STEP-") }
    }

    @Test
    fun `a refusal to compile is worded by the vocabulary`() = runTest {
        val empty = DemonstrationTrace(id = "t", label = "", startedAt = 0, endedAt = 1, events = emptyList())

        val result = compiler(ShoutingVocabulary).compile(empty, localOnly = true)

        assertThat((result as CompilationResult.Failed).reason).isEqualTo("VOCAB-EMPTY")
    }

    @Test
    fun `the name the user typed is kept ahead of any invented one`() = runTest {
        val result = compiler(ShoutingVocabulary).compile(trace(label = "Daily report"), localOnly = true)

        val skill = (result as CompilationResult.Success).skill
        assertThat(skill.name).isEqualTo("Daily report")
    }
}
