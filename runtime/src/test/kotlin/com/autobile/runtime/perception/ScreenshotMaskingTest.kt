package com.autobile.runtime.perception

import android.graphics.Bitmap
import android.graphics.Color
import com.autobile.core.model.Bounds
import com.autobile.core.model.ScreenSnapshot
import com.autobile.core.model.UiNode
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Keeping out of a picture what is already kept out of the text.
 *
 * Every line of screen text the product sends is redacted first. A screenshot was the
 * way around that: the same one-time code, refused in text, would leave the device
 * intact as pixels — and it would do so on exactly the rung that fires when the
 * accessibility tree could not answer, which is when nobody is looking.
 */
@RunWith(RobolectricTestRunner::class)
class ScreenshotMaskingTest {

    private val width = 200
    private val height = 400

    @Test
    fun `a visible one-time code is painted out`() {
        val snapshot = snapshotWith(
            node("Your code is 481920", Bounds(20, 40, 180, 80)),
            node("Continue", Bounds(20, 200, 180, 240)),
        )

        val masked = ScreenshotMasking.mask(white(), snapshot)

        assertThat(masked.getPixel(100, 60)).isEqualTo(Color.BLACK)
        // The rest of the screen is untouched, or the model is handed a black rectangle
        // and cannot answer the question that made this rung necessary.
        assertThat(masked.getPixel(100, 220)).isEqualTo(Color.WHITE)
    }

    @Test
    fun `a card number is painted out`() {
        val snapshot = snapshotWith(node("4111 1111 1111 1111", Bounds(0, 100, 200, 140)))
        val masked = ScreenshotMasking.mask(white(), snapshot)
        assertThat(masked.getPixel(100, 120)).isEqualTo(Color.BLACK)
    }

    @Test
    fun `a password field is painted out even with nothing readable in it`() {
        val snapshot = snapshotWith(
            UiNode(
                nodeId = "pw",
                className = "android.widget.EditText\$Password",
                editable = true,
                bounds = Bounds(0, 300, 200, 340),
            ),
        )
        val masked = ScreenshotMasking.mask(white(), snapshot)
        assertThat(masked.getPixel(100, 320)).isEqualTo(Color.BLACK)
    }

    @Test
    fun `an ordinary screen is handed straight back`() {
        val snapshot = snapshotWith(node("Send", Bounds(20, 40, 180, 80)))
        val original = white()
        // Same instance, not merely an identical one: the common case must not pay for
        // a full-screen copy on every look.
        assertThat(ScreenshotMasking.mask(original, snapshot)).isSameInstanceAs(original)
    }

    @Test
    fun `bounds are mapped onto the picture when the capture is a different size`() {
        // The tree describes a 400-wide window; the capture came back at 200.
        val snapshot = ScreenSnapshot(
            screenWidth = 400,
            screenHeight = 800,
            nodes = listOf(node("otp: 123456", Bounds(0, 200, 400, 280))),
        )

        val masked = ScreenshotMasking.mask(white(), snapshot)

        // Halving applies, so the band lands at y 100..140 rather than off the bottom.
        assertThat(masked.getPixel(100, 120)).isEqualTo(Color.BLACK)
        assertThat(masked.getPixel(100, 250)).isEqualTo(Color.WHITE)
    }

    @Test
    fun `an element with no rectangle is ignored rather than masking the corner`() {
        val snapshot = snapshotWith(node("otp: 999999", Bounds(0, 0, 0, 0)))
        val original = white()
        assertThat(ScreenshotMasking.mask(original, snapshot)).isSameInstanceAs(original)
    }

    private fun node(text: String, bounds: Bounds) =
        UiNode(nodeId = text, text = text, bounds = bounds)

    private fun snapshotWith(vararg nodes: UiNode) =
        ScreenSnapshot(screenWidth = width, screenHeight = height, nodes = nodes.toList())

    private fun white(): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
}
