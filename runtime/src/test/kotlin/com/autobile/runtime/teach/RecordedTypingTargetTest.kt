package com.autobile.runtime.teach

import android.view.accessibility.AccessibilityEvent
import com.autobile.core.model.ObservedAction
import com.autobile.runtime.FakeScreen
import com.autobile.runtime.accessibility.AccessibilityBridge
import com.autobile.runtime.accessibility.ObservedEvent
import com.autobile.runtime.node
import com.autobile.runtime.screen
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Which element a typed step is recorded against.
 *
 * A text-changed event carries the new contents, and more than one node reports them: a
 * note's scrolling body holds everything typed into it, so matching the event's text
 * finds the container as readily as the field. Recording the container produced steps
 * like "move to ScrollView", which nothing could resolve on a later run because a layout
 * has no identity to be found by.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class RecordedTypingTargetTest {

    private val typed = "12 September 09:12"

    private fun textChanged(packageName: String = "com.example.notes") =
        AccessibilityEvent.obtain().apply {
            eventType = AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
            this.packageName = packageName
            className = "android.widget.EditText"
            text.add(typed)
        }

    @Test
    fun `typing is recorded against the field, not the container reporting its text`() =
        runTest(UnconfinedTestDispatcher()) {
            // The container reports the same text as the field, and comes first in the
            // tree, so a label match reaches it first.
            val perception = FakeScreen(
                screen(
                    "com.example.notes",
                    "Note",
                    node("body_scroll", text = typed, clickable = false, className = "android.widget.ScrollView"),
                    node(
                        "body_field",
                        text = typed,
                        editable = true,
                        focused = true,
                        clickable = false,
                        className = "android.widget.EditText",
                    ),
                ),
            )
            val recorder = DemonstrationRecorder(perception, TestScope(testScheduler))

            recorder.start("Note")
            // Let the recorder subscribe before anything is published.
            testScheduler.advanceUntilIdle()
            AccessibilityBridge.publish(textChanged())
            testScheduler.advanceUntilIdle()
            val trace = recorder.stop()

            val event = trace?.events?.firstOrNull { it.action is ObservedAction.TextInput }
            assertThat(event).isNotNull()
            assertThat(event!!.targetNode?.nodeId).isEqualTo("body_field")
        }

    @Test
    fun `an unfocused field is found by the text it now holds`() = runTest(UnconfinedTestDispatcher()) {
        // Not every app reports focus. Among fields the one holding what was just typed
        // is unambiguous, which is not true among containers: a note body reports its
        // children's text as its own.
        val perception = FakeScreen(
            screen(
                "com.example.notes",
                "Note",
                node("body_scroll", text = typed, clickable = false, className = "android.widget.ScrollView"),
                node("title", text = "", editable = true, clickable = false),
                node("body_field", text = typed, editable = true, clickable = false),
            ),
        )
        val recorder = DemonstrationRecorder(perception, TestScope(testScheduler))

        recorder.start("Note")
        testScheduler.advanceUntilIdle()
        AccessibilityBridge.publish(textChanged())
        testScheduler.advanceUntilIdle()
        val trace = recorder.stop()

        val event = trace?.events?.firstOrNull { it.action is ObservedAction.TextInput }
        assertThat(event?.targetNode?.nodeId).isEqualTo("body_field")
    }

    @Test
    fun `a window that nobody navigated to is not recorded as opening an app`() =
        runTest(UnconfinedTestDispatcher()) {
            // The keyboard, the shade, a video in a corner, another service's overlay:
            // all of them raise a window-state change from some package. Taken at face
            // value each reads as the user opening that app, and the automation ends up
            // with a step that opens a keyboard.
            val perception = FakeScreen(screen("com.example.notes", "Note", node("n", text = "Note")))
            val recorder = DemonstrationRecorder(perception, TestScope(testScheduler))

            recorder.start("Note")
            testScheduler.advanceUntilIdle()
            AccessibilityBridge.publishForTest(
                ObservedEvent(
                    type = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                    packageName = "com.samsung.android.honeyboard",
                    className = "android.inputmethodservice.SoftInputWindow",
                    text = "",
                    contentDescription = null,
                    timestamp = 1,
                    fromDestinationWindow = false,
                ),
            )
            testScheduler.advanceUntilIdle()
            val trace = recorder.stop()

            assertThat(trace?.events.orEmpty()).isEmpty()
        }

    @Test
    fun `a real app window is still recorded as opening an app`() = runTest(UnconfinedTestDispatcher()) {
        // Starting somewhere else, so arriving at the notes app is a change of app.
        val perception = FakeScreen(screen("com.example.elsewhere", "Elsewhere", node("n", text = "Note")))
        val recorder = DemonstrationRecorder(perception, TestScope(testScheduler))

        recorder.start("Note")
        testScheduler.advanceUntilIdle()
        AccessibilityBridge.publishForTest(
            ObservedEvent(
                type = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                packageName = "com.example.notes",
                className = "com.example.notes.EditorActivity",
                text = "",
                contentDescription = null,
                timestamp = 1,
                fromDestinationWindow = true,
            ),
        )
        testScheduler.advanceUntilIdle()
        val trace = recorder.stop()

        assertThat(trace?.events?.map { it.action })
            .contains(ObservedAction.AppOpen("com.example.notes"))
    }

    @Test
    fun `the element the framework named wins over anything matching by text`() =
        runTest(UnconfinedTestDispatcher()) {
            // Matching by text cannot separate a note body from the layout scrolling it,
            // and predictive typing reports a half-finished word — which became a step
            // named after a single character. The event says which element it was.
            val perception = FakeScreen(
                screen(
                    "com.example.notes",
                    "Note",
                    node("body_scroll", text = "오", clickable = false, className = "android.widget.ScrollView"),
                ),
            )
            val recorder = DemonstrationRecorder(perception, TestScope(testScheduler))

            recorder.start("Note")
            testScheduler.advanceUntilIdle()
            AccessibilityBridge.publishForTest(
                ObservedEvent(
                    type = AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
                    packageName = "com.example.notes",
                    className = "android.widget.EditText",
                    text = "오",
                    contentDescription = null,
                    timestamp = 1,
                    sourceNode = node(
                        "the_field",
                        text = "오",
                        resourceId = "com.example.notes:id/body",
                        editable = true,
                        clickable = false,
                        className = "android.widget.EditText",
                    ),
                ),
            )
            testScheduler.advanceUntilIdle()
            val trace = recorder.stop()

            val event = trace?.events?.firstOrNull { it.action is ObservedAction.TextInput }
            assertThat(event?.targetNode?.resourceId).isEqualTo("com.example.notes:id/body")
        }

    @Test
    fun `a tap is still matched by its label`() = runTest(UnconfinedTestDispatcher()) {
        val perception = FakeScreen(
            screen(
                "com.example.notes",
                "Note",
                node("save", text = "Save"),
                node("body_field", text = "", editable = true, focused = true, clickable = false),
            ),
        )
        val recorder = DemonstrationRecorder(perception, TestScope(testScheduler))

        recorder.start("Note")
        testScheduler.advanceUntilIdle()
        AccessibilityBridge.publish(
            AccessibilityEvent.obtain().apply {
                eventType = AccessibilityEvent.TYPE_VIEW_CLICKED
                packageName = "com.example.notes"
                text.add("Save")
            },
        )
        testScheduler.advanceUntilIdle()
        val trace = recorder.stop()

        val event = trace?.events?.firstOrNull { it.action is ObservedAction.Click }
        assertThat(event?.targetNode?.nodeId).isEqualTo("save")
    }
}
