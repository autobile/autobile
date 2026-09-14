package com.autobile.runtime.perception

import android.view.accessibility.AccessibilityNodeInfo
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Regression coverage for rich editors that expose actions without isEditable. */
@RunWith(RobolectricTestRunner::class)
class TextInputCapabilityTest {

    @Test
    fun `set text action makes a non-editable node input capable`() {
        val node = AccessibilityNodeInfo.obtain().apply {
            className = "android.webkit.WebView"
            isEditable = false
            addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_TEXT)
        }

        val described = UiTreeReader().describe(node)

        assertThat(described.editable).isTrue()
    }

    @Test
    fun `paste action makes a non-editable node input capable`() {
        val node = AccessibilityNodeInfo.obtain().apply {
            className = "android.view.View"
            isEditable = false
            addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_PASTE)
        }

        val described = UiTreeReader().describe(node)

        assertThat(described.editable).isTrue()
    }
}
