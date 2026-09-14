package com.autobile.runtime.resolver

import android.graphics.Bitmap
import com.autobile.ai.task.ElementMatch
import com.autobile.ai.task.PointMatch
import com.autobile.core.model.RuntimeTier
import com.autobile.core.model.TargetSemantics
import com.autobile.runtime.ScriptedProvider
import com.autobile.runtime.node
import com.autobile.runtime.routerWith
import com.autobile.runtime.screen
import com.autobile.runtime.perception.ScreenshotCapture
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The resolver ladder the specification asks for: accessibility, then looking.
 *
 * A screen can be full of elements and still offer nothing that names the target —
 * scrolling layouts, wrappers, containers reporting their children's text. Choosing the
 * best of a bad set is how a step ends up typing into a ScrollView. Vision used to be
 * reachable only when the tree was *empty*, which is the one case those screens are not.
 */
@RunWith(RobolectricTestRunner::class)
class ScreenshotLadderTest {

    private val target = TargetSemantics(intentLabel = "daily reward", description = "the daily reward button")

    private fun bitmap(): Bitmap = Bitmap.createBitmap(60, 120, Bitmap.Config.ARGB_8888)

    /** A screen with elements on it, none of which are the target. */
    private fun unhelpfulScreen() = screen(
        "com.example.game",
        "Game",
        node("wrap", text = "ScrollView", clickable = false, className = "android.widget.ScrollView"),
        node("frame", text = null, clickable = false, className = "android.widget.FrameLayout"),
    )

    @Test
    fun `a screen full of unhelpful elements still reaches the screenshot`() = runTest {
        val provider = ScriptedProvider()
            .answerWith("element-match", ElementMatch(index = -1, confidence = 0.1f, reason = "none match"))
            .answerWith("element-match-visual", ElementMatch(index = -1, confidence = 0.1f, reason = "none match"))
            .answerWith("point-match", PointMatch(true, 0.5f, 0.8f, 0.86f, "the button near the bottom"))

        val resolution = ExecutionResolver(routerWith(provider))
            .resolve(target, unhelpfulScreen(), screenshot = { ScreenshotCapture.Success(bitmap()) })

        assertThat(resolution).isInstanceOf(Resolution.FoundPoint::class.java)
        assertThat((resolution as Resolution.FoundPoint).yRatio).isEqualTo(0.8f)
    }

    @Test
    fun `a nameable screen is answered from the tree and never looked at`() = runTest {
        var looked = false
        val snapshot = screen("com.example.app", "Home", node("claim", text = "daily reward", clickable = true))

        val resolution = ExecutionResolver(routerWith(ScriptedProvider()))
            .resolve(
                target,
                snapshot,
                screenshot = { looked = true; ScreenshotCapture.Success(bitmap()) },
            )

        assertThat((resolution as Resolution.Found).tier).isEqualTo(RuntimeTier.DETERMINISTIC)
        // The screenshot is lazy: the fast path must not pay for one.
        assertThat(looked).isFalse()
    }

    @Test
    fun `looking is refused when the screen genuinely offers nothing`() = runTest {
        val provider = ScriptedProvider()
            .answerWith("element-match", ElementMatch(index = -1, confidence = 0.1f, reason = "none"))
            .answerWith("element-match-visual", ElementMatch(index = -1, confidence = 0.1f, reason = "none"))
            .answerWith("point-match", PointMatch(false, -1f, -1f, 0f, "no such control"))

        val resolution = ExecutionResolver(routerWith(provider))
            .resolve(target, unhelpfulScreen(), screenshot = { ScreenshotCapture.Success(bitmap()) })

        assertThat(resolution).isInstanceOf(Resolution.NotFound::class.java)
    }

    @Test
    fun `a step that forbids looking is never sent to a screenshot`() = runTest {
        var looked = false
        val provider = ScriptedProvider()
            .answerWith("element-match", ElementMatch(index = -1, confidence = 0.1f, reason = "none"))

        val resolution = ExecutionResolver(routerWith(provider)).resolve(
            target,
            unhelpfulScreen(),
            screenshot = { looked = true; ScreenshotCapture.Success(bitmap()) },
            allowVision = false,
        )

        assertThat(resolution).isNotInstanceOf(Resolution.FoundPoint::class.java)
        assertThat(looked).isFalse()
    }
}
