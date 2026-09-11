package com.autobile.runtime.accessibility

import android.view.accessibility.AccessibilityEvent
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What reaches a listener, and what is dropped before it gets there.
 *
 * The events published here share one bounded buffer, and the recorder spends a moment
 * on each one observing the screen. Ambient events arrive faster than that — an
 * animating screen produces them continuously — so letting them through filled the
 * buffer and pushed out the taps a demonstration is made of. The session then came back
 * shorter than what the user actually did, with nothing to say so.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AccessibilityBridgeTest {

    private fun event(type: Int, packageName: String = "com.example.app"): AccessibilityEvent =
        AccessibilityEvent.obtain().apply {
            eventType = type
            this.packageName = packageName
        }

    @Test
    fun `actions a person took are published`() = runTest(UnconfinedTestDispatcher()) {
        val seen = mutableListOf<ObservedEvent>()
        val job = launch { AccessibilityBridge.events.toList(seen) }

        AccessibilityBridge.publish(event(AccessibilityEvent.TYPE_VIEW_CLICKED))
        AccessibilityBridge.publish(event(AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED))
        AccessibilityBridge.publish(event(AccessibilityEvent.TYPE_VIEW_LONG_CLICKED))
        AccessibilityBridge.publish(event(AccessibilityEvent.TYPE_VIEW_SELECTED))
        AccessibilityBridge.publish(event(AccessibilityEvent.TYPE_VIEW_SCROLLED))
        AccessibilityBridge.publish(event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED))

        assertThat(seen.map { it.type }).containsExactly(
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED,
            AccessibilityEvent.TYPE_VIEW_SELECTED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
        ).inOrder()
        job.cancel()
    }

    @Test
    fun `ambient screen chatter never reaches the buffer`() = runTest(UnconfinedTestDispatcher()) {
        val seen = mutableListOf<ObservedEvent>()
        val job = launch { AccessibilityBridge.events.toList(seen) }

        repeat(500) {
            AccessibilityBridge.publish(event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED))
            AccessibilityBridge.publish(event(AccessibilityEvent.TYPE_VIEW_FOCUSED))
        }

        assertThat(seen).isEmpty()
        job.cancel()
    }

    @Test
    fun `the foreground app is tracked from window changes`() = runTest(UnconfinedTestDispatcher()) {
        AccessibilityBridge.publish(
            event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, packageName = "com.example.bank"),
        )

        assertThat(AccessibilityBridge.foregroundPackage.value).isEqualTo("com.example.bank")

        AccessibilityBridge.publish(
            event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, packageName = "com.autobile"),
        )

        assertThat(AccessibilityBridge.foregroundPackage.value).isEqualTo("com.autobile")
    }

    @Test
    fun `a tap does not change which app is considered foreground`() = runTest(UnconfinedTestDispatcher()) {
        AccessibilityBridge.publish(
            event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, packageName = "com.example.bank"),
        )
        AccessibilityBridge.publish(
            event(AccessibilityEvent.TYPE_VIEW_CLICKED, packageName = "com.example.keyboard"),
        )

        assertThat(AccessibilityBridge.foregroundPackage.value).isEqualTo("com.example.bank")
    }

    @Test
    fun `a tap survives a flood of chatter around it`() = runTest(UnconfinedTestDispatcher()) {
        val seen = mutableListOf<ObservedEvent>()
        val job = launch { AccessibilityBridge.events.toList(seen) }

        repeat(1_000) { AccessibilityBridge.publish(event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)) }
        AccessibilityBridge.publish(event(AccessibilityEvent.TYPE_VIEW_CLICKED))
        repeat(1_000) { AccessibilityBridge.publish(event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)) }

        assertThat(seen.map { it.type }).containsExactly(AccessibilityEvent.TYPE_VIEW_CLICKED)
        job.cancel()
    }
}
