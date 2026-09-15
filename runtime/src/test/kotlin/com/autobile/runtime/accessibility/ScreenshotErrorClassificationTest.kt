package com.autobile.runtime.accessibility

import android.accessibilityservice.AccessibilityService
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ScreenshotErrorClassificationTest {
    @Test
    fun `rate limiting remains retryable`() {
        assertThat(
            classifyScreenshotError(AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT, 30),
        ).isEqualTo(ScreenshotOutcome.Throttled)
    }

    @Test
    fun `an Android 11 internal failure is not mislabeled as a secure window`() {
        assertThat(
            classifyScreenshotError(AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR, 30),
        ).isInstanceOf(ScreenshotOutcome.Failed::class.java)
    }

    @Test
    fun `a secure-window error remains terminal on platforms that expose it`() {
        assertThat(
            classifyScreenshotError(AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW, 34),
        ).isEqualTo(ScreenshotOutcome.SecureWindowBlocked)
    }
}
