package com.autobile.runtime.teach

import android.view.accessibility.AccessibilityEvent
import com.autobile.core.model.ObservedAction
import com.autobile.runtime.FakeScreen
import com.autobile.runtime.accessibility.AccessibilityBridge
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
