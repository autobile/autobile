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

    private fun colorDistance(first: Int, second: Int): Int =
        abs((first shr 16 and 0xff) - (second shr 16 and 0xff)) +
            abs((first shr 8 and 0xff) - (second shr 8 and 0xff)) +
            abs((first and 0xff) - (second and 0xff))

    private const val MAX_SAMPLES = 4_096
    private const val CHANNEL_DISTANCE = 36
    private const val CHANGED_SAMPLE_RATIO = 0.01f
}
