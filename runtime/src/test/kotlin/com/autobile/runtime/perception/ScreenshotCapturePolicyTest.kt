package com.autobile.runtime.perception

import android.graphics.Bitmap
import com.autobile.runtime.accessibility.ScreenshotOutcome
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Telling the ways a screenshot fails apart from each other.
 *
 * On a working device they are indistinguishable — every one of them produces no
 * picture — so the wrong answer to any of them goes unnoticed until it is someone's
 * automation stalling. A rate limit is a wait; a protected window is a final no;
 * a broken display is a failure to report. Each is pinned here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ScreenshotCapturePolicyTest {

    private val bitmap: Bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)

    @Test
    fun `a rate limit is waited out rather than reported as failure`() = runTest {
        var calls = 0
        val outcomes = listOf(
            ScreenshotOutcome.Throttled,
            ScreenshotOutcome.Throttled,
            ScreenshotOutcome.Captured(bitmap),
        )

        val result = captureWithBackoff(
            capture = { outcomes[calls++] },
            foregroundPackage = { "com.example.app" },
        )

        assertThat(result).isInstanceOf(ScreenshotCapture.Success::class.java)
        assertThat(calls).isEqualTo(3)
    }

    @Test
    fun `each wait is longer than the last`() = runTest {
        val clock = testScheduler
        val waitedAt = mutableListOf<Long>()
        var calls = 0

        captureWithBackoff(
            capture = { waitedAt += clock.currentTime; calls++; ScreenshotOutcome.Throttled },
            foregroundPackage = { "com.example.app" },
        )

        // Backing off by a constant would keep hitting the same limit that caused the
        // refusal; the gaps have to grow.
        val gaps = waitedAt.zipWithNext { earlier, later -> later - earlier }
        assertThat(gaps).isInOrder()
        assertThat(gaps.toSet()).hasSize(gaps.size)
    }

    @Test
    fun `a rate limit that never clears gives up and says why`() = runTest {
        var calls = 0

        val result = captureWithBackoff(
            capture = { calls++; ScreenshotOutcome.Throttled },
            foregroundPackage = { "com.example.app" },
        )

        assertThat(calls).isEqualTo(3)
        assertThat(result).isInstanceOf(ScreenshotCapture.Unavailable::class.java)
        assertThat((result as ScreenshotCapture.Unavailable).reason).contains("rate limited")
    }

    @Test
    fun `a protected window is a final answer and is never retried`() = runTest {
        var calls = 0

        val result = captureWithBackoff(
            capture = { calls++; ScreenshotOutcome.SecureWindowBlocked },
            foregroundPackage = { "com.bank.app" },
        )

        // Retrying would be an attempt to work around a protection another app asked
        // for, which this product does not do.
        assertThat(calls).isEqualTo(1)
        assertThat(result).isEqualTo(ScreenshotCapture.SecureWindowBlocked("com.bank.app"))
    }

    @Test
    fun `an outright failure is reported with its reason, not retried`() = runTest {
        var calls = 0

        val result = captureWithBackoff(
            capture = { calls++; ScreenshotOutcome.Failed("Display is not available") },
            foregroundPackage = { "com.example.app" },
        )

        assertThat(calls).isEqualTo(1)
        assertThat((result as ScreenshotCapture.Unavailable).reason).isEqualTo("Display is not available")
    }

    @Test
    fun `a picture on the first try costs no wait at all`() = runTest {
        val before = testScheduler.currentTime

        val result = captureWithBackoff(
            capture = { ScreenshotOutcome.Captured(bitmap) },
            foregroundPackage = { "com.example.app" },
        )

        assertThat(result).isInstanceOf(ScreenshotCapture.Success::class.java)
        assertThat(testScheduler.currentTime).isEqualTo(before)
    }
}
