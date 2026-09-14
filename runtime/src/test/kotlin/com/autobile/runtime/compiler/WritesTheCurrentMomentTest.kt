package com.autobile.runtime.compiler

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.autobile.core.data.AppPolicyStore
import com.autobile.core.data.AutobileDatabase
import com.autobile.core.data.SettingsStore
import com.autobile.core.model.ActionSpec
import com.autobile.core.model.DemonstrationTrace
import com.autobile.core.model.ObservedAction
import com.autobile.core.model.ScreenSnapshot
import com.autobile.core.model.StateTransition
import com.autobile.core.model.TraceEvent
import com.autobile.core.model.VariableBinding
import com.autobile.runtime.ScriptedProvider
import com.autobile.runtime.executor.ExecutionContext
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
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The difference between an automation and a recording.
 *
 * Someone opens a notes app and writes the date and time. Replayed literally, that
 * automation writes the afternoon it was taught, every time, for ever. What they meant
 * was "now", and they said so in the only way a person does: by writing today's date in
 * a recognisable format.
 *
 * This is the promise the whole project rests on, tested end to end from a demonstration
 * to the characters that would actually be typed.
 */
@RunWith(RobolectricTestRunner::class)
class WritesTheCurrentMomentTest {

    private lateinit var riskEngine: RiskEngine

    private val taughtAt = LocalDateTime.of(2026, 9, 12, 14, 56)
    private val ranAt = LocalDateTime.of(2026, 11, 3, 9, 5)

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val settings = SettingsStore(context)
        settings.setKillSwitch(false)
        riskEngine = RiskEngine(AppPolicyStore(AutobileDatabase(context)), settings)
    }

    private fun compiler() = SkillCompiler(
        router = routerWith(ScriptedProvider()),
        segmenter = TraceSegmenter(routerWith(ScriptedProvider())),
        riskEngine = riskEngine,
    )

    /** Open a notes app, then type a heading and the moment, as a person would. */
    private fun demonstration(typed: String) = DemonstrationTrace(
        id = "t",
        label = "Note",
        startedAt = taughtAt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
        endedAt = taughtAt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() + 1_000,
        events = listOf(
            TraceEvent(
                id = "e0",
                timestamp = 0,
                packageName = "com.example.notes",
                windowContext = "Editor",
                before = ScreenSnapshot(packageName = "com.example.notes", windowTitle = "List"),
                after = ScreenSnapshot(packageName = "com.example.notes", windowTitle = "Editor"),
                action = ObservedAction.AppOpen("com.example.notes"),
                targetNode = node("root", text = "Notes"),
                stateTransition = StateTransition("com.example.notes", "com.example.notes", "List", "Editor"),
            ),
            TraceEvent(
                id = "e1",
                timestamp = 1,
                packageName = "com.example.notes",
                windowContext = "Editor",
                before = ScreenSnapshot(packageName = "com.example.notes", windowTitle = "Editor"),
                after = ScreenSnapshot(packageName = "com.example.notes", windowTitle = "Editor"),
                action = ObservedAction.TextInput(typed),
                targetNode = node(
                    "body",
                    text = typed,
                    resourceId = "com.example.notes:id/body",
                    editable = true,
                    clickable = false,
                ),
                inputValue = typed,
                stateTransition = StateTransition("com.example.notes", "com.example.notes", "Editor", "Editor"),
            ),
        ),
    )

    private suspend fun whatWouldBeTyped(typed: String): String? {
        val result = compiler().compile(demonstration(typed), localOnly = true)
        val skill = (result as CompilationResult.Success).skill
        val step = skill.steps.last { it.action is ActionSpec.InputText }
        val context = ExecutionContext(skill, now = { ranAt })
        return context.resolve((step.action as ActionSpec.InputText).value)
    }

    @Test
    fun `a date and time taught in September is written as the day it runs`() = runTest {
        val written = whatWouldBeTyped("26.09.12 14:56")

        assertThat(written).isEqualTo("26.11.03 09:05")
    }

    @Test
    fun `a timestamp inside a note changes while surrounding text stays literal`() = runTest {
        val written = whatWouldBeTyped("현재 날짜 및 시간\n\n26.09.12 14:56")

        assertThat(written).isEqualTo("현재 날짜 및 시간\n\n26.11.03 09:05")
    }

    @Test
    fun `the moment is stored as a binding, not as the characters typed`() = runTest {
        val result = compiler().compile(demonstration("26.09.12 14:56"), localOnly = true)

        val variable = (result as CompilationResult.Success).skill.variables.single()
        assertThat(variable.binding).isInstanceOf(VariableBinding.RelativeDate::class.java)
        assertThat((variable.binding as VariableBinding.RelativeDate).offsetDays).isEqualTo(0)
    }

    @Test
    fun `text that is not a moment is written exactly as taught`() = runTest {
        val written = whatWouldBeTyped("Daily sales report")

        assertThat(written).isEqualTo("Daily sales report")
    }

    @Test
    fun `a step never fails for not knowing what to type`() = runTest {
        // Whatever else is unknown, the demonstration always showed one answer.
        assertThat(whatWouldBeTyped("26.09.12 14:56")).isNotEmpty()
        assertThat(whatWouldBeTyped("Daily sales report")).isNotEmpty()
    }
}
