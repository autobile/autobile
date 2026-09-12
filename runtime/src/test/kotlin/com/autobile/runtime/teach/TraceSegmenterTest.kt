package com.autobile.runtime.teach

import com.autobile.ai.task.SegmentedStep
import com.autobile.ai.task.StepRole
import com.autobile.ai.task.TraceSegmentation
import com.autobile.core.model.DemonstrationTrace
import com.autobile.core.model.EventClassification
import com.autobile.core.model.ObservedAction
import com.autobile.core.model.ScreenSnapshot
import com.autobile.core.model.StateTransition
import com.autobile.core.model.TraceEvent
import com.autobile.runtime.ScriptedProvider
import com.autobile.runtime.node
import com.autobile.runtime.routerWith
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Segmentation decides what becomes an automation. Discarding a step the user needed is
 * far worse than keeping a redundant one, and the default-keep behaviour is asserted.
 */
class TraceSegmenterTest {

    private fun event(
        index: Int,
        action: ObservedAction,
        label: String = "Item $index",
        beforeWindow: String = "Home",
        afterWindow: String = "Home",
    ) = TraceEvent(
        id = "e$index",
        timestamp = index.toLong(),
        packageName = "com.example.business",
        windowContext = afterWindow,
        before = ScreenSnapshot(packageName = "com.example.business", windowTitle = beforeWindow),
        after = ScreenSnapshot(packageName = "com.example.business", windowTitle = afterWindow),
        action = action,
        targetNode = node("n$index", text = label),
        // Derived from the windows either side, the way the recorder fills it in. Left
        // null, every rule that asks whether the screen changed silently reads "no".
        stateTransition = StateTransition(
            fromPackage = "com.example.business",
            toPackage = "com.example.business",
            fromWindow = beforeWindow,
            toWindow = afterWindow,
        ),
    )

    private fun trace(vararg events: TraceEvent) = DemonstrationTrace(
        id = "t",
        startedAt = 0,
        endedAt = 100,
        events = events.toList(),
    )

    /** A tap on a bare layout: no id, no text, no description. */
    private fun unnamedEvent(
        index: Int,
        action: ObservedAction,
        beforeWindow: String = "Home",
        afterWindow: String = "Home",
    ) = event(index, action, beforeWindow = beforeWindow, afterWindow = afterWindow)
        .copy(targetNode = node("n$index", text = null, clickable = false))

    private fun transitEvent(index: Int, packageName: String) = TraceEvent(
        id = "t$index",
        timestamp = index.toLong(),
        packageName = packageName,
        windowContext = "Home",
        before = ScreenSnapshot(packageName = packageName, windowTitle = "Home"),
        after = ScreenSnapshot(packageName = packageName, windowTitle = "Home"),
        action = ObservedAction.AppOpen(packageName),
        targetNode = node("t$index", text = "Home"),
    )

    @Test
    fun `passing through the home screen is not part of the task`() = runTest {
        // Leaving Autobile to demonstrate means going through the launcher. Recorded as
        // a step, an automation taught this way opens the home screen first — and one
        // that got no further becomes an automation for opening the home screen.
        val segmenter = TraceSegmenter(
            routerWith(ScriptedProvider()),
            transitPackages = setOf("com.example.launcher"),
        )

        val result = segmenter.segment(
            trace(
                transitEvent(0, "com.example.launcher"),
                event(1, ObservedAction.AppOpen("com.example.business")),
                event(2, ObservedAction.TextInput("42")),
            ),
        )

        assertThat(result.events.first().classification).isEqualTo(EventClassification.NOISE)
        assertThat(result.compilable().map { it.packageName }).containsNoneIn(listOf("com.example.launcher"))
        assertThat(result.compilable()).hasSize(2)
    }

    @Test
    fun `the keyboard is not an app the demonstration visited`() = runTest {
        // A keyboard opens a window of its own the moment a field is focused, so any
        // demonstration that types records it as a visited app. Compiled as a step, the
        // automation tries to launch it — and a keyboard has no launcher activity, so it
        // reports the keyboard as not installed while the user is looking at it.
        val segmenter = TraceSegmenter(
            routerWith(ScriptedProvider()),
            transitPackages = setOf("com.example.launcher", "com.samsung.android.honeyboard"),
        )

        val result = segmenter.segment(
            trace(
                event(0, ObservedAction.AppOpen("com.example.notes")),
                transitEvent(1, "com.samsung.android.honeyboard"),
                event(2, ObservedAction.TextInput("12 September")),
            ),
        )

        assertThat(result.compilable().map { it.packageName })
            .doesNotContain("com.samsung.android.honeyboard")
        assertThat(result.compilable()).hasSize(2)
    }

    @Test
    fun `an app that merely answers the home intent is still a real task`() = runTest {
        // Settings registers a fallback home activity for devices with no launcher
        // installed. Treating every answer to the home intent as transit made tasks
        // taught in Settings compile to nothing at all.
        val segmenter = TraceSegmenter(
            routerWith(ScriptedProvider()),
            transitPackages = setOf("com.example.launcher"),
        )

        val result = segmenter.segment(
            trace(
                transitEvent(0, "com.example.launcher"),
                transitEvent(1, "com.android.settings"),
                event(2, ObservedAction.TextInput("42")),
            ),
        )

        assertThat(result.compilable().map { it.packageName }).contains("com.android.settings")
    }

    @Test
    fun `a tap that only put the cursor in a field is not a step`() = runTest {
        // Tapping a note body before typing records a tap on the unnamed layout that
        // wraps the field. Kept as a step it becomes "select ScrollView", which nothing
        // can resolve on a later run, and the automation fails before it types anything.
        val segmenter = TraceSegmenter(routerWith(ScriptedProvider()))

        val result = segmenter.segment(
            trace(
                event(0, ObservedAction.Click, label = "ScrollView"),
                event(1, ObservedAction.TextInput("12 September")),
            ),
        )

        assertThat(result.events.first().classification).isEqualTo(EventClassification.NOISE)
        assertThat(result.compilable()).hasSize(1)
    }

    @Test
    fun `an unnamed tap before typing is dropped even when the screen changed`() = runTest {
        // Focusing a note body changes the window — the keyboard comes up and the editor
        // takes over — so "the screen changed" does not mean the tap navigated anywhere.
        // What settles it is that the thing tapped has nothing to find it by.
        val segmenter = TraceSegmenter(routerWith(ScriptedProvider()))

        val result = segmenter.segment(
            trace(
                unnamedEvent(0, ObservedAction.Click, beforeWindow = "List", afterWindow = "Editor"),
                event(1, ObservedAction.TextInput("12 September"), beforeWindow = "Editor", afterWindow = "Editor"),
            ),
        )

        assertThat(result.events.first().classification).isEqualTo(EventClassification.NOISE)
    }

    @Test
    fun `a tap that opened a new screen before typing is kept`() = runTest {
        // Not cursor placement: this one navigated somewhere, and the typing happened
        // there. Dropping it would lose the step that got the user to the field.
        val segmenter = TraceSegmenter(routerWith(ScriptedProvider()))

        val result = segmenter.segment(
            trace(
                event(0, ObservedAction.Click, label = "New note", beforeWindow = "List", afterWindow = "Editor"),
                event(1, ObservedAction.TextInput("12 September"), beforeWindow = "Editor", afterWindow = "Editor"),
            ),
        )

        assertThat(result.events.first().classification).isNotEqualTo(EventClassification.NOISE)
    }

    @Test
    fun `only the last of a run of typing into one field is kept`() = runTest {
        // Predictive keyboards report every keystroke and every correction, each event
        // carrying the contents so far. Kept as steps, the automation retypes the word
        // one syllable at a time, and each half-finished fragment becomes its own step
        // named after a single character.
        val segmenter = TraceSegmenter(routerWith(ScriptedProvider()))
        val field = node("field", text = null, resourceId = "com.example:id/body", clickable = false)

        val result = segmenter.segment(
            trace(
                event(0, ObservedAction.TextInput("1")).copy(targetNode = field),
                event(1, ObservedAction.TextInput("12")).copy(targetNode = field),
                event(2, ObservedAction.TextInput("12 September")).copy(targetNode = field),
            ),
        )

        assertThat(result.compilable()).hasSize(1)
        assertThat((result.compilable().single().action as ObservedAction.TextInput).value)
            .isEqualTo("12 September")
    }

    @Test
    fun `typing into two different fields stays two steps`() = runTest {
        val segmenter = TraceSegmenter(routerWith(ScriptedProvider()))
        val title = node("t", text = null, resourceId = "com.example:id/title", clickable = false)
        val body = node("b", text = null, resourceId = "com.example:id/body", clickable = false)

        val result = segmenter.segment(
            trace(
                event(0, ObservedAction.TextInput("Report")).copy(targetNode = title),
                event(1, ObservedAction.TextInput("12 September")).copy(targetNode = body),
            ),
        )

        assertThat(result.compilable()).hasSize(2)
    }

    @Test
    fun `an empty trace segments to nothing`() = runTest {
        val segmenter = TraceSegmenter(routerWith(ScriptedProvider()))
        val result = segmenter.segment(trace())
        assertThat(result.events).isEmpty()
        assertThat(result.usedInference).isFalse()
    }

    @Test
    fun `opening an app is navigation, not the point of the task`() = runTest {
        val segmenter = TraceSegmenter(routerWith(ScriptedProvider()))
        val result = segmenter.segment(trace(event(0, ObservedAction.AppOpen("com.example.business"))))
        assertThat(result.events.first().classification).isEqualTo(EventClassification.NAVIGATION)
    }

    @Test
    fun `a window change carries no intent of its own`() = runTest {
        val segmenter = TraceSegmenter(routerWith(ScriptedProvider()))
        val result = segmenter.segment(trace(event(0, ObservedAction.WindowChange("Reports"))))
        assertThat(result.events.first().classification).isEqualTo(EventClassification.NOISE)
    }

    @Test
    fun `typing is always essential`() = runTest {
        val segmenter = TraceSegmenter(routerWith(ScriptedProvider()))
        val result = segmenter.segment(trace(event(0, ObservedAction.TextInput("hello"))))
        assertThat(result.events.first().classification).isEqualTo(EventClassification.ESSENTIAL)
    }

    @Test
    fun `a tap undone by an immediate back is discarded`() = runTest {
        val segmenter = TraceSegmenter(routerWith(ScriptedProvider()))
        val result = segmenter.segment(
            trace(
                event(0, ObservedAction.Click, beforeWindow = "Home", afterWindow = "Settings"),
                event(1, ObservedAction.Back, beforeWindow = "Settings", afterWindow = "Home"),
            ),
        )
        assertThat(result.events.first().classification).isEqualTo(EventClassification.NOISE)
        assertThat(result.discardedCount).isAtLeast(1)
    }

    @Test
    fun `structural rules alone avoid an inference call`() = runTest {
        val provider = ScriptedProvider()
        val segmenter = TraceSegmenter(routerWith(provider))

        segmenter.segment(
            trace(
                event(0, ObservedAction.AppOpen("com.example.business")),
                event(1, ObservedAction.TextInput("hello")),
                event(2, ObservedAction.Scroll(com.autobile.core.model.Direction.DOWN)),
            ),
        )

        assertThat(provider.requestedLabels).isEmpty()
    }

    @Test
    fun `ambiguous taps are classified by inference`() = runTest {
        val provider = ScriptedProvider().answerWith(
            "trace-segmentation",
            TraceSegmentation(
                steps = listOf(SegmentedStep(index = 0, role = StepRole.NOISE, why = "browsed then left")),
                confidence = 0.8f,
            ),
        )
        val segmenter = TraceSegmenter(routerWith(provider))

        val result = segmenter.segment(trace(event(0, ObservedAction.Click)))

        assertThat(provider.requestedLabels).contains("trace-segmentation")
        assertThat(result.events.first().classification).isEqualTo(EventClassification.NOISE)
    }

    @Test
    fun `steps are kept when no runtime can classify them`() = runTest {
        val segmenter = TraceSegmenter(routerWith(ScriptedProvider(available = false)))
        val result = segmenter.segment(trace(event(0, ObservedAction.Click)))
        assertThat(result.events.first().classification).isEqualTo(EventClassification.ESSENTIAL)
        assertThat(result.compilable()).hasSize(1)
    }

    @Test
    fun `only noise is excluded from compilation`() = runTest {
        val provider = ScriptedProvider().answerWith(
            "trace-segmentation",
            TraceSegmentation(
                steps = listOf(SegmentedStep(index = 1, role = StepRole.ESSENTIAL, why = "taps send")),
                confidence = 0.9f,
            ),
        )
        val segmenter = TraceSegmenter(routerWith(provider))

        val result = segmenter.segment(
            trace(
                event(0, ObservedAction.AppOpen("com.example.business")),
                event(1, ObservedAction.Click, label = "Send"),
                event(2, ObservedAction.WindowChange("Sent")),
            ),
        )

        assertThat(result.compilable().map { it.id }).containsExactly("e0", "e1").inOrder()
    }
}
