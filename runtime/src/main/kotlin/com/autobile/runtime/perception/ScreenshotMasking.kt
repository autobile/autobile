package com.autobile.runtime.perception

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import com.autobile.core.common.Logx
import com.autobile.core.model.ScreenSnapshot
import com.autobile.core.model.UiNode

/**
 * Paints out of a screenshot what should never be photographed.
 *
 * Masking already applies to every line of screen text the product logs or sends. A
 * screenshot was the hole in that: the same one-time code that is redacted out of the
 * text would leave the device intact as pixels, at the one moment the accessibility
 * tree could not answer and nobody is watching closely.
 *
 * The tree is what makes this possible — it says where each piece of text is, so the
 * rectangle holding a card number can be found without reading the image at all.
 */
object ScreenshotMasking {

    /**
     * Blacks out every region the redactor would refuse to log.
     *
     * Returns the original when there is nothing to hide, so the ordinary case pays
     * neither a copy nor an allocation.
     */
    fun mask(screenshot: Bitmap, snapshot: ScreenSnapshot): Bitmap {
        if (screenshot.width <= 0 || screenshot.height <= 0) return screenshot
        val regions = snapshot.nodes.filter(::isSensitive)
        if (regions.isEmpty()) return screenshot

        // Node bounds are in the window's coordinates and the capture is in pixels.
        // Normally the same, but masking the wrong rectangle is worse than masking
        // nothing: it would leave the sensitive part visible and black out whatever the
        // model actually needed to see.
        val scale = if (snapshot.screenWidth > 0) {
            screenshot.width.toFloat() / snapshot.screenWidth
        } else {
            1f
        }

        val masked = runCatching { screenshot.copy(Bitmap.Config.ARGB_8888, true) }.getOrNull()
            ?: return screenshot
        val canvas = Canvas(masked)
        val paint = Paint().apply { color = Color.BLACK }
        regions.forEach { node ->
            val bounds = node.bounds
            val rect = Rect(
                (bounds.left * scale).toInt(),
                (bounds.top * scale).toInt(),
                (bounds.right * scale).toInt(),
                (bounds.bottom * scale).toInt(),
            )
            if (rect.intersect(0, 0, masked.width, masked.height)) canvas.drawRect(rect, paint)
        }
        return masked
    }

    /**
     * Whether this element's rectangle must be hidden.
     *
     * Two kinds qualify: a field the platform itself marks as a password, and any
     * element whose visible text the redactor already refuses to log. The second is what
     * catches a one-time code or a card number sitting in plain view, where the tree has
     * the string but no flag saying it matters.
     */
    private fun isSensitive(node: UiNode): Boolean {
        if (node.bounds.isEmpty) return false
        if (node.editable && node.className?.contains("Password", ignoreCase = true) == true) return true
        val visible = listOfNotNull(node.text, node.contentDescription)
            .joinToString(" ")
            .trim()
        if (visible.isEmpty()) return false
        return Logx.redact(visible).contains(REDACTION_MARKER)
    }

    private const val REDACTION_MARKER = "[redacted]"
}
