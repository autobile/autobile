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
import com.autobile.core.model.TraceEvent
import com.autobile.core.model.UiNode
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
 * What a step checks for once it has acted.
 *
 * A step anchors on something the next step will need, to confirm it reached the right
 * screen. The anchor has to be text a person could actually see: an element with no text
 * and no description is named after its id or its class, and requiring "ScrollView" to
 * appear is a check no screen can pass. The step then arrives exactly where it was
 * taught and is failed for it.
 */
@RunWith(RobolectricTestRunner::class)
class ValidationAnchorTest {

    private lateinit var riskEngine: RiskEngine

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

    private fun trace(second: UiNode) = DemonstrationTrace(
        id = "t",
        label = "Note",
        startedAt = 0,
        endedAt = 10,
        events = listOf(
            TraceEvent(
                id = "e0",
                timestamp = 0,
                packageName = "com.example.notes",
                windowContext = "List",
                before = ScreenSnapshot(packageName = "com.example.notes", windowTitle = "List"),
                after = ScreenSnapshot(packageName = "com.example.notes", windowTitle = "Editor"),
                action = ObservedAction.Click,
                targetNode = node("new", text = "New note", resourceId = "com.example.notes:id/new"),
                stateTransition = StateTransition("com.example.notes", "com.example.notes", "List", "Editor"),
            ),
            TraceEvent(
                id = "e1",
                timestamp = 1,
                packageName = "com.example.notes",
                windowContext = "Editor",
                before = ScreenSnapshot(packageName = "com.example.notes", windowTitle = "Editor"),
                after = ScreenSnapshot(packageName = "com.example.notes", windowTitle = "Editor"),
                action = ObservedAction.Click,
                targetNode = second,
                stateTransition = StateTransition("com.example.notes", "com.example.notes", "Editor", "Editor"),
            ),
        ),
    )

    @Test
    fun `a class name is never required to appear on screen`() = runTest {
        val unnamed = node("wrap", text = null, clickable = false, className = "android.widget.ScrollView")

        val result = compiler().compile(trace(unnamed), localOnly = true)

        val first = (result as CompilationResult.Success).skill.steps.first()
        assertThat(first.expectedState.requiredTexts).isEmpty()
    }

    @Test
    fun `a field's own contents are never required to appear`() = runTest {
        // Requiring them would demand the next run find the last run's answer in place.
        val field = node("body", text = "12 September 09:12", editable = true, clickable = false)

        val result = compiler().compile(trace(field), localOnly = true)

        val first = (result as CompilationResult.Success).skill.steps.first()
        assertThat(first.expectedState.requiredTexts).doesNotContain("12 September 09:12")
    }

    @Test
    fun `visible text is still anchored on`() = runTest {
        val labelled = node("save", text = "Save", clickable = true)

        val result = compiler().compile(trace(labelled), localOnly = true)

        val first = (result as CompilationResult.Success).skill.steps.first()
        assertThat(first.expectedState.requiredTexts).contains("Save")
    }
}
