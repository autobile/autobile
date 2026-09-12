package com.autobile.runtime.accessibility

import android.content.Context
import androidx.annotation.VisibleForTesting
import android.provider.Settings
import android.text.TextUtils
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import com.autobile.core.common.Logx
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Connection point between the accessibility service and the rest of the runtime.
 *
 * An accessibility service is created by the system, not by the application, so there is
 * no constructor to inject through. This object holds the live instance and exposes
 * connection state as a flow, which lets the UI react to the user revoking access
 * mid-session instead of discovering it at the next failed step.
 */
object AccessibilityBridge {

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    /**
     * Accessibility events that represent something a person did.
     *
     * Replay is zero because these only matter to whoever is listening at the time. The
     * buffer is generous because a listener may take a moment over each event, and a
     * dropped event during a demonstration is a step the user has to notice is missing.
     */
    private val _events = MutableSharedFlow<ObservedEvent>(replay = 0, extraBufferCapacity = 256)
    val events: SharedFlow<ObservedEvent> = _events.asSharedFlow()

    /**
     * The package of the window most recently brought to the front.
     *
     * Tracked from the event stream because the alternative — asking the service for
     * `rootInActiveWindow` — is a synchronous call into the window manager that can
     * fetch a whole window's state. Callers need this on the main thread and often
     * enough that paying that cost each time risks stalling the interface.
     */
    private val _foregroundPackage = MutableStateFlow("")
    val foregroundPackage: StateFlow<String> = _foregroundPackage.asStateFlow()

    @Volatile
    private var service: AutobileAccessibilityService? = null

    internal fun attach(instance: AutobileAccessibilityService) {
        service = instance
        _connected.value = true
    }

    internal fun detach() {
        service = null
        _connected.value = false
        _foregroundPackage.value = ""
        Logx.i("Accessibility service disconnected")
    }

    /**
     * Publishes an event if it carries user intent.
     *
     * Content changes and focus moves are emitted constantly by ordinary apps — a
     * scrolling list alone produces them faster than any listener can consume them. They
     * are filtered here rather than downstream because the buffer is shared: letting
     * them through would evict the taps and typing that a demonstration is actually made
     * of, and the recorder would report a shorter session than the user performed.
     */
    internal fun publish(event: AccessibilityEvent) {
        if (event.eventType !in MEANINGFUL_EVENT_TYPES) return
        val from = event.packageName?.toString().orEmpty()
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && from.isNotBlank()) {
            _foregroundPackage.value = from
        }
        val observed = ObservedEvent(
            type = event.eventType,
            packageName = from,
            className = event.className?.toString().orEmpty(),
            text = event.text.joinToString(" ") { it.toString() },
            contentDescription = event.contentDescription?.toString(),
            timestamp = System.currentTimeMillis(),
            fromDestinationWindow = isDestinationWindow(event),
        )
        _events.tryEmit(observed)
    }

    /**
     * Whether this event came from a window a person navigated to.
     *
     * Plenty of windows appear without anyone going anywhere: the keyboard rises when a
     * field takes focus, the shade comes down, a video shrinks into a corner, another
     * accessibility service draws over everything. Each is a window-state change from
     * some package, and taken at face value each reads as "the user opened that app".
     *
     * Judged by what kind of window it is rather than by which package it belongs to.
     * Naming the packages means learning them one incident at a time — a keyboard, then
     * picture-in-picture, then whatever is next — while the platform already says which
     * windows are application windows the user is actually looking at.
     */
    private fun isDestinationWindow(event: AccessibilityEvent): Boolean {
        val live = service ?: return true
        val windows = runCatching { live.windows }.getOrNull() ?: return true
        // No window information is not evidence against: better to record an event and
        // let later stages judge it than to silently drop a real step.
        val window = windows.firstOrNull { it.id == event.windowId } ?: return true
        if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) return false
        if (runCatching { window.isInPictureInPictureMode }.getOrDefault(false)) return false
        return window.isActive
    }

    /** Publishes an already-reduced event, for tests that have no framework event. */
    @VisibleForTesting
    fun publishForTest(event: ObservedEvent) {
        _events.tryEmit(event)
    }

    private val MEANINGFUL_EVENT_TYPES = setOf(
        AccessibilityEvent.TYPE_VIEW_CLICKED,
        AccessibilityEvent.TYPE_VIEW_LONG_CLICKED,
        AccessibilityEvent.TYPE_VIEW_SELECTED,
        AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
        AccessibilityEvent.TYPE_VIEW_SCROLLED,
        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
    )

    /** The connected service, or null when the user has not granted access. */
    fun require(): AutobileAccessibilityService? = service

    /**
     * Whether the user has enabled Autobile in accessibility settings.
     *
     * Read from settings rather than from [connected] because the service may be enabled
     * but not yet bound when this is called during startup.
     */
    fun isEnabledInSettings(context: Context): Boolean {
        val expected = "${context.packageName}/${AutobileAccessibilityService::class.java.name}"
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        if (enabled.isEmpty()) return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        return splitter.any { it.equals(expected, ignoreCase = true) }
    }
}

/** An accessibility event reduced to the fields the runtime uses. */
data class ObservedEvent(
    val type: Int,
    val packageName: String,
    val className: String,
    val text: String,
    val contentDescription: String?,
    val timestamp: Long,
    /**
     * Whether this came from an application window the user navigated to, as opposed to
     * a keyboard, a shade, a picture-in-picture corner or an overlay drawn on top.
     */
    val fromDestinationWindow: Boolean = true,
) {
    val isWindowChange: Boolean
        get() = type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED

    val isInteraction: Boolean
        get() = type == AccessibilityEvent.TYPE_VIEW_CLICKED ||
            type == AccessibilityEvent.TYPE_VIEW_LONG_CLICKED ||
            type == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED ||
            type == AccessibilityEvent.TYPE_VIEW_SELECTED
}
