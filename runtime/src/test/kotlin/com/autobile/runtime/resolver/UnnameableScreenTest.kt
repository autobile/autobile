package com.autobile.runtime.resolver

import android.graphics.Bitmap
import com.autobile.ai.task.PointMatch
import com.autobile.core.model.RuntimeTier
import com.autobile.core.model.ScreenSnapshot
import com.autobile.core.model.TargetSemantics
import com.autobile.runtime.ScriptedProvider
import com.autobile.runtime.routerWith
import com.autobile.runtime.perception.ScreenshotCapture
import com.autobile.runtime.screen
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Operating an app that names nothing.
 *
 * A game, a canvas-drawn interface, a video player: the accessibility tree is empty or
 * holds nothing that can do the job. Choosing from a list of elements is not an option
 * there, and an agent that can only do that cannot operate those apps at all — it
 * stopped at "the screen has no readable elements" before looking at anything.
 */
@RunWith(RobolectricTestRunner::class)
class UnnameableScreenTest {

    private val target = TargetSemantics(
        intentLabel = "daily reward",
        description = "the daily reward claim button",
    )

    private fun bitmap(): Bitmap = Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888)

    private fun resolverSeeing(point: PointMatch) =
        ExecutionResolver(routerWith(ScriptedProvider().answerWith("point-match", point)))

    @Test
    fun `a screen with no elements is located by looking`() = runTest {
        val resolver = resolverSeeing(PointMatch(true, 0.5f, 0.82f, 0.8f, "claim button near the bottom"))

        val resolution = resolver.resolve(
            target,
            ScreenSnapshot(),
            screenshot = { ScreenshotCapture.Success(bitmap()) },
        )

        assertThat(resolution).isInstanceOf(Resolution.FoundPoint::class.java)
        val point = resolution as Resolution.FoundPoint
        assertThat(point.xRatio).isEqualTo(0.5f)
        assertThat(point.yRatio).isEqualTo(0.82f)
    }

    @Test
    fun `looking is refused when the screen genuinely offers nothing`() = runTest {
        val resolver = resolverSeeing(PointMatch(false, -1f, -1f, 0f, "no such control"))

        val resolution = resolver.resolve(
            target,
            ScreenSnapshot(),
            screenshot = { ScreenshotCapture.Success(bitmap()) },
        )

        assertThat(resolution).isInstanceOf(Resolution.NotFound::class.java)
    }

    @Test
    fun `visual grounding cannot select the system navigation edge`() = runTest {
        val resolver = resolverSeeing(PointMatch(true, 0.5f, 0.98f, 0.99f, "system Home button"))

        val resolution = resolver.resolve(
            target,
            ScreenSnapshot(),
            screenshot = { ScreenshotCapture.Success(bitmap()) },
        )

        assertThat(resolution).isInstanceOf(Resolution.NotFound::class.java)
        assertThat((resolution as Resolution.NotFound).reason).contains("system navigation")
    }

    @Test
    fun `missing vision runtime is deferred instead of reported as a missing control`() = runTest {
        val resolver = ExecutionResolver(routerWith(ScriptedProvider(available = false)))

        val resolution = resolver.resolve(
            target,
            ScreenSnapshot(),
            screenshot = { ScreenshotCapture.Success(bitmap()) },
        )

        assertThat(resolution).isInstanceOf(Resolution.NeedsReasoning::class.java)
    }

    @Test
    fun `screenshot failure is preserved instead of becoming a false not found`() = runTest {
        val resolver = resolverSeeing(PointMatch(true, 0.5f, 0.5f, 0.9f, "there"))

        val resolution = resolver.resolve(
            target,
            ScreenSnapshot(),
            screenshot = { ScreenshotCapture.Unavailable("no screenshot") },
        )

        assertThat(resolution).isEqualTo(Resolution.VisionBlocked("no screenshot", secureWindow = false))
    }

    @Test
    fun `secure window refusal remains an explicit blocked result`() = runTest {
        val resolver = resolverSeeing(PointMatch(true, 0.5f, 0.5f, 0.9f, "there"))

        val resolution = resolver.resolve(
            target,
            ScreenSnapshot(),
            screenshot = { ScreenshotCapture.SecureWindowBlocked("com.example.bank") },
        )

        assertThat(resolution).isEqualTo(
            Resolution.VisionBlocked(
                "Screen capture is blocked for com.example.bank",
                secureWindow = true,
            ),
        )
    }

    @Test
    fun `a nameable screen never reaches the expensive path`() = runTest {
        // Looking costs a screenshot, a model call and a guess at coordinates. It is the
        // last resort, not an alternative to following what the demonstration showed.
        val resolver = resolverSeeing(PointMatch(true, 0.1f, 0.1f, 0.99f, "should not be used"))
        val snapshot = screen(
            "com.example.app",
            "Home",
            com.autobile.runtime.node("claim", text = "daily reward", clickable = true),
        )

        val resolution = resolver.resolve(
            target,
            snapshot,
            screenshot = { ScreenshotCapture.Success(bitmap()) },
        )

        assertThat(resolution).isInstanceOf(Resolution.Found::class.java)
        assertThat((resolution as Resolution.Found).tier).isEqualTo(RuntimeTier.DETERMINISTIC)
    }
}
