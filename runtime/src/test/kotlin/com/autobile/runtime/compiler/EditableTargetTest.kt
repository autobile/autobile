package com.autobile.runtime.compiler

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.autobile.core.data.AppPolicyStore
import com.autobile.core.data.AutobileDatabase
import com.autobile.core.data.SettingsStore
import com.autobile.core.model.DemonstrationTrace
import com.autobile.core.model.LocatorKind
import com.autobile.core.model.ObservedAction
import com.autobile.core.model.ScreenSnapshot
import com.autobile.core.model.StateTransition
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
 * What a step remembers about the field it types into.
 *
 * A field's contents are the value, not the field. Recording them as the target's name
 * and as a text locator means the next run looks for the text the last run typed, which
 * is never there — and names the step after a date the user entered once.
 */
@RunWith(RobolectricTestRunner::class)
class EditableTargetTest {

    private lateinit var riskEngine: RiskEngine

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val settings = SettingsStore(context)
        settings.setKillSwitch(false)
        riskEngine = RiskEngine(AppPolicyStore(AutobileDatabase(context)), settings)
    }

    private fun compile(trace: DemonstrationTrace) = SkillCompiler(
        router = routerWith(ScriptedProvider()),
        segmenter = TraceSegmenter(routerWith(ScriptedProvider())),
        riskEngine = riskEngine,
    )

    private fun traceTypingInto(field: com.autobile.core.model.UiNode) = DemonstrationTrace(
        id = "t",
        label = "Note",
        startedAt = 0,
        endedAt = 10,
        events = listOf(
            TraceEvent(
                id = "e0",
                timestamp = 0,
                packageName = "com.example.notes",
                windowContext = "Note",
                before = ScreenSnapshot(packageName = "com.example.notes", windowTitle = "Note"),
                after = ScreenSnapshot(packageName = "com.example.notes", windowTitle = "Note"),
                action = ObservedAction.AppOpen("com.example.notes"),
                targetNode = node("root", text = "Notes"),
                stateTransition = StateTransition("com.example.notes", "com.example.notes", "Note", "Note"),
            ),
            TraceEvent(
                id = "e1",
                timestamp = 1,
                packageName = "com.example.notes",
                windowContext = "Note",
                before = ScreenSnapshot(packageName = "com.example.notes", windowTitle = "Note"),
                after = ScreenSnapshot(packageName = "com.example.notes", windowTitle = "Note"),
                action = ObservedAction.TextInput("12 September 09:12"),
                targetNode = field,
                inputValue = "12 September 09:12",
                stateTransition = StateTransition("com.example.notes", "com.example.notes", "Note", "Note"),
            ),
        ),
    )

    @Test
    fun `a field is named by its placeholder, not by what was typed into it`() = runTest {
        val field = node(
            "body",
            text = "12 September 09:12",
            editable = true,
            clickable = false,
            hint = "Enter note",
        )

        val result = compile(traceTypingInto(field)).compile(traceTypingInto(field), localOnly = true)

        val step = (result as CompilationResult.Success).skill.steps.last()
        assertThat(step.target.intentLabel).isEqualTo("Enter note")
        assertThat(step.target.intentLabel).doesNotContain("September")
    }

    @Test
    fun `what was typed never becomes a locator`() = runTest {
        val field = node(
            "body",
            text = "12 September 09:12",
            editable = true,
            clickable = false,
            hint = "Enter note",
            resourceId = "com.example.notes:id/body",
        )

        val result = compile(traceTypingInto(field)).compile(traceTypingInto(field), localOnly = true)

        val step = (result as CompilationResult.Success).skill.steps.last()
        val texts = step.target.locators.filter { it.kind == LocatorKind.TEXT }.map { it.value }
        assertThat(texts).isEmpty()
        assertThat(step.target.locators.map { it.kind }).contains(LocatorKind.RESOURCE_ID)
    }
}
