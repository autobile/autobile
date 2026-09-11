package com.autobile.runtime.background

import android.content.Context
import android.content.Intent
import com.autobile.core.common.Logx
import com.autobile.runtime.accessibility.AccessibilityBridge

/**
 * Brings Autobile back to the front after it has driven the user into another app.
 *
 * An automation works by opening somebody else's app, which means the moment it
 * finishes the user is left looking at a screen they did not choose to be on, with no
 * indication that anything ended. Walking back is the closing half of the run, not a
 * convenience: without it the first thing a new user experiences is being stranded in
 * Settings wondering whether the app crashed.
 */
object AppReturn {

    @Volatile
    private var interfaceVisible = false

    /**
     * Records whether Autobile's own interface is on screen.
     *
     * Reported by the activity rather than inferred from accessibility events, because
     * the two decisions that depend on it fail in opposite directions. Suppressing the
     * overlay wrongly hides the one surface that says the phone is being driven, and
     * that must never happen on a stale signal; the activity's own lifecycle is the only
     * account of its visibility that cannot lag behind the truth.
     */
    fun onInterfaceVisible(visible: Boolean) {
        interfaceVisible = visible
    }

    /** Whether Autobile's own interface is the thing the user is looking at. */
    fun isInForeground(context: Context): Boolean = interfaceVisible

    /**
     * Reopens Autobile on the screen the user left.
     *
     * Started from the accessibility service when one is connected. Android blocks most
     * activity launches from the background, and the service is the one component here
     * the platform has bound and the user has explicitly trusted, so it is the context
     * most likely to be allowed. The application context is tried afterwards rather than
     * instead, because a refusal is silent and there is nothing to fall back to later.
     */
    fun bringToFront(context: Context): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            ?: return false

        val launchers = listOfNotNull(AccessibilityBridge.require(), context)
        for (launcher in launchers) {
            val started = runCatching { launcher.startActivity(intent) }
                .onFailure { Logx.w("Could not reopen Autobile from ${launcher.javaClass.simpleName}", it) }
                .isSuccess
            if (started) return true
        }
        return false
    }
}
