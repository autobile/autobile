package com.autobile.runtime.perception

import android.graphics.Bitmap
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class VisualChangeDetectorTest {
    @Test
    fun `a materially changed frame is detected`() {
        val before = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val after = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply {
            eraseColor(0xffffffff.toInt())
        }

        assertThat(VisualChangeDetector.changed(before, after)).isTrue()
    }

    @Test
    fun `identical frames do not prove a visual action succeeded`() {
        val frame = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)

        assertThat(VisualChangeDetector.changed(frame, frame.copy(Bitmap.Config.ARGB_8888, false))).isFalse()
    }

    @Test
    fun `animation away from a grounded control does not prove the control worked`() {
        val before = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val after = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply {
            for (y in 0 until 15) for (x in 0 until 15) setPixel(x, y, 0xffffffff.toInt())
        }

        assertThat(VisualChangeDetector.changed(before, after)).isTrue()
        assertThat(VisualChangeDetector.changedAround(before, after, 0.8f, 0.8f)).isFalse()
    }

    @Test
    fun `feedback around a grounded control proves local visual change`() {
        val before = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val after = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply {
            for (y in 70 until 91) for (x in 70 until 91) setPixel(x, y, 0xffffffff.toInt())
        }

        assertThat(VisualChangeDetector.changedAround(before, after, 0.8f, 0.8f)).isTrue()
    }
}
