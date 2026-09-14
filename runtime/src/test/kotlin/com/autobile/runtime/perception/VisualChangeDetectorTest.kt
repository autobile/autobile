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
}
