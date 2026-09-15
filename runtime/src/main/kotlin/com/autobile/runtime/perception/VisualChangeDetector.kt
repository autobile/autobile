package com.autobile.runtime.perception

import android.graphics.Bitmap
import kotlin.math.abs

/**
 * Detects a material pixel change without uploading either frame.
 *
 * Visual-only screens have no tree transition to validate. Sampling keeps the check
 * bounded on high-resolution devices while the channel threshold ignores compression,
 * antialiasing and tiny animation noise.
 */
internal object VisualChangeDetector {
    fun changed(before: Bitmap, after: Bitmap): Boolean {
        if (before.width != after.width || before.height != after.height) return true
        if (before.width == 0 || before.height == 0) return false

        val stride = maxOf(1, kotlin.math.sqrt((before.width * before.height) / MAX_SAMPLES.toDouble()).toInt())
        var sampled = 0
        var changed = 0
        var y = 0
        while (y < before.height) {
            var x = 0
            while (x < before.width) {
                sampled++
                if (colorDistance(before.getPixel(x, y), after.getPixel(x, y)) >= CHANNEL_DISTANCE) changed++
                x += stride
            }
            y += stride
        }
        return sampled > 0 && changed.toFloat() / sampled >= CHANGED_SAMPLE_RATIO
    }

    /**
     * Detects feedback near a visually grounded control.
     *
     * Games commonly animate the rest of the screen continuously. Treating any global
     * animation as proof that a button worked creates false success, so point actions
     * only accept a material change in a bounded region around the chosen point.
     */
    fun changedAround(before: Bitmap, after: Bitmap, xRatio: Float, yRatio: Float): Boolean {
        if (before.width != after.width || before.height != after.height) return true
        if (before.width == 0 || before.height == 0) return false

        val centreX = (before.width * xRatio.coerceIn(0f, 1f)).toInt()
        val centreY = (before.height * yRatio.coerceIn(0f, 1f)).toInt()
        val radiusX = maxOf(MIN_REGION_PX, (before.width * REGION_RATIO).toInt())
        val radiusY = maxOf(MIN_REGION_PX, (before.height * REGION_RATIO).toInt())
        val left = (centreX - radiusX).coerceAtLeast(0)
        val top = (centreY - radiusY).coerceAtLeast(0)
        val right = (centreX + radiusX).coerceAtMost(before.width - 1)
        val bottom = (centreY + radiusY).coerceAtMost(before.height - 1)
        val area = maxOf(1, (right - left + 1) * (bottom - top + 1))
        val stride = maxOf(1, kotlin.math.sqrt(area / MAX_REGION_SAMPLES.toDouble()).toInt())

        var sampled = 0
        var changed = 0
        var y = top
        while (y <= bottom) {
            var x = left
            while (x <= right) {
                sampled++
                if (colorDistance(before.getPixel(x, y), after.getPixel(x, y)) >= CHANNEL_DISTANCE) changed++
                x += stride
            }
            y += stride
        }
        return sampled > 0 && changed.toFloat() / sampled >= CHANGED_SAMPLE_RATIO
    }

    private fun colorDistance(first: Int, second: Int): Int =
        abs((first shr 16 and 0xff) - (second shr 16 and 0xff)) +
            abs((first shr 8 and 0xff) - (second shr 8 and 0xff)) +
            abs((first and 0xff) - (second and 0xff))

    private const val MAX_SAMPLES = 4_096
    private const val MAX_REGION_SAMPLES = 1_024
    private const val CHANNEL_DISTANCE = 36
    private const val CHANGED_SAMPLE_RATIO = 0.01f
    private const val REGION_RATIO = 0.12f
    private const val MIN_REGION_PX = 12
}
