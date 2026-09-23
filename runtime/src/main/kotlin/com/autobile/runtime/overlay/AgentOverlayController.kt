package com.autobile.runtime.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.Gravity as ViewGravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.autobile.core.common.Logx
import com.autobile.core.model.Bounds
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Shows what the agent is doing, on top of whatever app it is operating.
 *
 * This is not decoration. A phone that starts navigating by itself is alarming unless
 * the user can see what it is doing and stop it, and during development the indicator is
 * frequently the only way to tell a mis-resolved target from a mis-timed one.
 *
 * The overlay is entirely optional: without the permission the agent runs normally and
 * only the visibility is lost, so the feature is never a prerequisite for automation.
 *
 * Every method here marshals onto the main thread. A run is driven from background
 * dispatchers, and attaching a window from one throws — which the callers turned into a
 * logged warning, so the banner silently never appeared while the permission, the
 * notification and the rest of the run all looked correct.
 */
class AgentOverlayController(private val context: Context) {

    private val windowManager: WindowManager? =
        context.getSystemService(WindowManager::class.java)

    private val main = Handler(Looper.getMainLooper())

    private var bannerView: TextView? = null
    private var indicatorView: TouchIndicatorView? = null
    private var desiredBanner: CharSequence? = null
    private var desiredIndicator: Bounds? = null
    private var hiddenForCapture = false

    /**
     * Runs UI work on the main thread, always by posting.
     *
     * Posting even when already on the main thread keeps these in the order they were
     * requested. Running some inline and queueing others would let a dismissal overtake
     * the attachment it is meant to undo and strand a banner on screen after the run.
     */
    private fun onMain(block: () -> Unit) {
        main.post(block)
    }

    val isPermitted: Boolean get() = Settings.canDrawOverlays(context)

    /**
     * Shows the status banner, creating it on first use and updating it afterwards.
     *
     * The view is owned here rather than handed in, because it may only be built and
     * touched on the main thread while every caller is driving a run from a background
     * dispatcher. Updating in place also matters: the text changes on every step, and
     * reattaching a window that often makes the banner visibly flicker over the app
     * being operated.
     *
     * Attached at the top of the screen and explicitly not focusable or touchable, so it
     * cannot intercept input intended for the app underneath — including input the agent
     * itself is about to deliver.
     */
    fun showBanner(text: CharSequence) = onMain {
        desiredBanner = text
        if (hiddenForCapture) return@onMain
        showBannerNow(text)
    }

    private fun showBannerNow(text: CharSequence) {
        if (!isPermitted) return
        bannerView?.let { existing ->
            existing.text = text
            existing.contentDescription = text
            return
        }
        val view = TextView(context).apply {
            styleAsBanner()
            this.text = text
            contentDescription = text
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        }
        runCatching { windowManager?.addView(view, params) }
            .onSuccess { bannerView = view }
            .onFailure { Logx.w("Could not show the agent banner", it) }
    }

    fun hideBanner() = onMain {
        desiredBanner = null
        hideBannerNow()
    }

    private fun hideBannerNow() {
        bannerView?.let { view ->
            runCatching { windowManager?.removeView(view) }
            bannerView = null
        }
    }

    /** Marks the element the agent is about to act on. */
    fun showTouchIndicator(bounds: Bounds) = onMain {
        desiredIndicator = bounds
        if (hiddenForCapture) return@onMain
        showTouchIndicatorNow(bounds)
    }

    private fun showTouchIndicatorNow(bounds: Bounds) {
        if (!isPermitted || bounds.isEmpty) return
        val view = indicatorView ?: TouchIndicatorView(context).also { created ->
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            )
            runCatching { windowManager?.addView(created, params) }
                .onSuccess { indicatorView = created }
                .onFailure { Logx.w("Could not show the touch indicator", it) }
        }
        view.highlight(bounds)
    }

    fun hideTouchIndicator() = onMain {
        desiredIndicator = null
        hideTouchIndicatorNow()
    }

    private fun hideTouchIndicatorNow() {
        indicatorView?.let { view ->
            view.clear()
            runCatching { windowManager?.removeView(view) }
            indicatorView = null
        }
    }

    fun dismissAll() = onMain {
        desiredBanner = null
        desiredIndicator = null
        hideTouchIndicatorNow()
        hideBannerNow()
    }

    /** Removes Autobile-owned windows before pixels are captured for reasoning. */
    suspend fun hideForCapture() {
        onMainAwait {
            hiddenForCapture = true
            hideTouchIndicatorNow()
            hideBannerNow()
        }
        // Window removal is asynchronous at the compositor boundary. One frame keeps
        // our own status UI out of the screenshot without adding user-visible latency.
        delay(CAPTURE_FRAME_DELAY_MS)
    }

    /** Restores the latest requested visibility after a screenshot completes. */
    suspend fun restoreAfterCapture() = onMainAwait {
        hiddenForCapture = false
        desiredBanner?.let(::showBannerNow)
        desiredIndicator?.let(::showTouchIndicatorNow)
    }

    private suspend fun onMainAwait(block: () -> Unit) = suspendCancellableCoroutine { continuation ->
        main.post {
            runCatching(block)
                .onFailure { Logx.w("Could not update the agent overlay", it) }
            if (continuation.isActive) continuation.resume(Unit)
        }
    }

    /**
     * The window type used for both overlays.
     *
     * This is the only type an ordinary application may use to draw above other apps,
     * and it is gated behind the display-over-apps permission checked in [isPermitted].
     */
    private fun overlayType(): Int = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    private fun TextView.styleAsBanner() {
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.argb(235, 28, 32, 38))
        setPadding(dp(20), dp(12), dp(20), dp(12))
        textSize = 14f
        setTypeface(typeface, Typeface.BOLD)
        gravity = ViewGravity.CENTER_VERTICAL
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    private companion object {
        const val CAPTURE_FRAME_DELAY_MS = 32L
    }
}
