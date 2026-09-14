package com.autobile.runtime.perception

import android.content.Context
import android.graphics.Bitmap
import com.autobile.core.common.Logx
import com.autobile.core.model.PerceptionResult
import com.autobile.core.model.ScreenSnapshot
import com.autobile.runtime.accessibility.AccessibilityBridge
import com.autobile.runtime.accessibility.ScreenshotOutcome
import kotlinx.coroutines.delay

/**
 * Observes the screen for the rest of the runtime.
 *
 * The accessibility tree is always read first because it is structured, cheap and needs
 * no image processing. A screenshot is taken only when a caller explicitly needs pixels,
 * which keeps the expensive and privacy-sensitive path off the common case.
 */
class PerceptionEngine(
    private val context: Context,
    private val treeReader: UiTreeReader = UiTreeReader(),
) : ScreenObserver {

    override suspend fun observe(settleMs: Long): PerceptionResult {
        val service = AccessibilityBridge.require()
            ?: return PerceptionResult.Unavailable("Accessibility access is not granted")

        if (settleMs > 0) delay(settleMs)

        val root = service.activeRoot()
            ?: return PerceptionResult.Unavailable("No inspectable window is in the foreground")

        val metrics = context.resources.displayMetrics
        val snapshot = treeReader.read(
            root = root,
            screenWidth = metrics.widthPixels,
            screenHeight = metrics.heightPixels,
            windowTitle = runCatching { root.paneTitle?.toString() }.getOrNull().orEmpty(),
        )
        return PerceptionResult.Success(snapshot)
    }

    override suspend fun observeStable(timeoutMs: Long, settleMs: Long): PerceptionResult {
        val deadline = System.currentTimeMillis() + timeoutMs
        var previous: ScreenSnapshot? = null

        while (System.currentTimeMillis() < deadline) {
            when (val result = observe(settleMs)) {
                is PerceptionResult.Success -> {
                    val current = result.snapshot
                    if (previous != null && current.isEquivalentTo(previous)) return result
                    previous = current
                }

                else -> return result
            }
        }
        return previous?.let { PerceptionResult.Success(it) }
            ?: PerceptionResult.Unavailable("Screen did not settle")
    }

    override suspend fun captureScreenshot(): ScreenshotCapture {
        val service = AccessibilityBridge.require()
            ?: return ScreenshotCapture.Unavailable("Accessibility access is not granted")
        return captureWithBackoff(service::captureScreen, service::foregroundPackage)
    }
}

/**
 * The capture policy, separated from where the capture comes from.
 *
 * Each way a screenshot can fail wants a different answer, and getting one wrong is
 * invisible on a working device: a refusal retried forever stalls a run, and a rate
 * limit treated as a refusal makes a perfectly good step fail for a reason that would
 * have cleared in half a second. Kept apart from the accessibility service so those
 * answers can be checked without one.
 */
internal suspend fun captureWithBackoff(
    capture: suspend () -> ScreenshotOutcome,
    foregroundPackage: () -> String,
    attempts: Int = SCREENSHOT_ATTEMPTS,
    backoffMs: Long = THROTTLE_BACKOFF_MS,
): ScreenshotCapture {
    repeat(attempts) { attempt ->
        when (val outcome = capture()) {
            is ScreenshotOutcome.Captured -> return ScreenshotCapture.Success(outcome.bitmap)

            // The window holds protected content. A final answer, not a setback: retrying
            // is an attempt to get around a protection the user's other app asked for.
            ScreenshotOutcome.SecureWindowBlocked ->
                return ScreenshotCapture.SecureWindowBlocked(foregroundPackage())

            // The platform limits how often screens may be captured. Waiting longer each
            // time is the whole remedy.
            ScreenshotOutcome.Throttled -> delay(backoffMs * (attempt + 1))

            is ScreenshotOutcome.Failed -> {
                Logx.w("Screenshot failed: ${outcome.reason}")
                return ScreenshotCapture.Unavailable(outcome.reason)
            }
        }
    }
    return ScreenshotCapture.Unavailable("Screenshots are rate limited right now")
}

private const val SCREENSHOT_ATTEMPTS = 3
private const val THROTTLE_BACKOFF_MS = 400L

sealed interface ScreenshotCapture {
    data class Success(val bitmap: Bitmap) : ScreenshotCapture
    data class SecureWindowBlocked(val packageName: String) : ScreenshotCapture
    data class Unavailable(val reason: String) : ScreenshotCapture
}

/**
 * Compares two snapshots for practical equivalence.
 *
 * Bounds are ignored because a list can scroll by a pixel while showing the same thing;
 * what matters is whether the same elements are present in the same window.
 */
fun ScreenSnapshot.isEquivalentTo(other: ScreenSnapshot): Boolean {
    if (packageName != other.packageName) return false
    if (windowTitle != other.windowTitle) return false
    if (nodes.size != other.nodes.size) return false
    return allText() == other.allText()
}
