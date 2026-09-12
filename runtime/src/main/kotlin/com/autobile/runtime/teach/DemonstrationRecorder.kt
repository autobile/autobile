package com.autobile.runtime.teach

import com.autobile.core.common.Ids
import com.autobile.core.common.TimeSource
import com.autobile.core.model.DemonstrationTrace
import com.autobile.core.model.Direction
import com.autobile.core.model.ObservedAction
import com.autobile.core.model.PerceptionResult
import com.autobile.core.model.Point
import com.autobile.core.model.ScreenSnapshot
import com.autobile.core.model.StateTransition
import com.autobile.core.model.TraceEvent
import com.autobile.core.model.UiNode
import androidx.annotation.StringRes
import com.autobile.runtime.R
import com.autobile.runtime.accessibility.AccessibilityBridge
import com.autobile.runtime.accessibility.ObservedEvent
import com.autobile.runtime.perception.ScreenObserver
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Records what the user does during a teaching session.
 *
 * The recorder captures the screen before and after each interaction, plus the element
 * that was touched, so the compiler later has enough evidence to work out *why* a step
 * happened rather than only that it did.
 *
 * It records generously and judges nothing: mis-taps and dead ends are kept, because
 * deciding what was incidental is a separate job done afterwards with the whole session
 * visible, not a guess made in the moment.
 */
class DemonstrationRecorder(
    private val perception: ScreenObserver,
    private val scope: CoroutineScope,
    private val time: TimeSource = TimeSource.System,
) {

    /**
     * Autobile's own package, so its interface is excluded from the recording.
     *
     * Compared exactly rather than by prefix: an unrelated app whose name merely starts
     * the same way is a task the user is entitled to automate.
     */
    private val ownPackage: String = OWN_PACKAGE

    private val _state = MutableStateFlow(RecordingState())
    val state: StateFlow<RecordingState> = _state.asStateFlow()

    private val mutex = Mutex()
    private val events = mutableListOf<TraceEvent>()
    private var collector: Job? = null
    private var lastSnapshot: ScreenSnapshot? = null
    private var traceId: String? = null
    private var startedAt: Long = 0

    fun start(label: String) {
        if (_state.value.recording) return
        traceId = Ids.trace()
        startedAt = time.nowMillis()
        events.clear()
        lastSnapshot = null
        _state.value = RecordingState(recording = true, label = label, eventCount = 0)

        collector = scope.launch {
            // Subscribing comes first. Capturing the starting screen takes long enough
            // for a quick user to have already tapped something, and an event that
            // arrives before anyone is listening is gone: the demonstration would be
            // missing its opening step with nothing to indicate it.
            val subscribed = CompletableDeferred<Unit>()
            launch {
                AccessibilityBridge.events
                    .onSubscription { subscribed.complete(Unit) }
                    .collect { event -> record(event) }
            }
            subscribed.await()
            // Capture the starting screen so the first interaction has a "before".
            val opening = (perception.observe() as? PerceptionResult.Success)?.snapshot
            mutex.withLock { if (lastSnapshot == null) lastSnapshot = opening }
        }
    }

    suspend fun stop(): DemonstrationTrace? = mutex.withLock {
        if (!_state.value.recording) return null
        collector?.cancel()
        collector = null
        val trace = DemonstrationTrace(
            id = traceId ?: Ids.trace(),
            label = _state.value.label,
            startedAt = startedAt,
            endedAt = time.nowMillis(),
            events = events.toList(),
        )
        _state.value = RecordingState()
        trace
    }

    fun cancel() {
        collector?.cancel()
        collector = null
        events.clear()
        _state.value = RecordingState()
    }

    private suspend fun record(event: ObservedEvent): Unit = mutex.withLock {
        if (!_state.value.recording) return@withLock
        // Events from Autobile's own interface are not part of what is being taught.
        if (event.packageName == ownPackage) return@withLock

        val action = event.toObservedAction() ?: return@withLock
        val before = lastSnapshot
        val after = (perception.observe(SETTLE_MS) as? PerceptionResult.Success)?.snapshot
        val target = after?.let { findLikelyTarget(it, event) } ?: before?.let { findLikelyTarget(it, event) }

        events += TraceEvent(
            id = Ids.event(),
            timestamp = event.timestamp,
            packageName = event.packageName,
            windowContext = event.className,
            before = before,
            after = after,
            action = action,
            targetNode = target,
            coordinates = target?.let { Point(it.bounds.centerX, it.bounds.centerY) },
            inputValue = (action as? ObservedAction.TextInput)?.value,
            stateTransition = StateTransition(
                fromPackage = before?.packageName.orEmpty(),
                toPackage = after?.packageName.orEmpty(),
                fromWindow = before?.windowTitle.orEmpty(),
                toWindow = after?.windowTitle.orEmpty(),
            ),
        )
        lastSnapshot = after ?: before
        _state.value = _state.value.copy(
            eventCount = events.size,
            lastAction = action.describe(),
            currentApp = event.packageName,
        )
    }

    /**
     * Locates the element the event refers to.
     *
     * Accessibility events carry the element's text but not a handle to it, so the node
     * is matched back by label. Focused and selected nodes are preferred, since those
     * are what the framework itself considers current.
     */
    private fun findLikelyTarget(snapshot: ScreenSnapshot, event: ObservedEvent): UiNode? {
        val source = event.sourceNode?.takeIf { it.className != null || it.resourceId != null }

        // Typing has to land on something that accepts typing. Some editors report the
        // change from the view that scrolls the text rather than the one holding it, and
        // a step recorded against that is a step the run cannot carry out: it dispatches
        // text at a layout and is told the target does not accept text input.
        if (event.type == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
            source?.takeIf { it.editable }?.let { return it }
        } else {
            // The framework naming the element settles every other kind of event.
            source?.let { return it }
        }

        val text = event.text.trim()
        val description = event.contentDescription?.trim()

        // Typing is matched to the field being typed into, before any label is
        // considered. A text-changed event carries the new contents, and a container
        // that reports the same contents — a note's scrolling body, a list that includes
        // the field — matches that text just as well as the field does. Picking the
        // container records the step as "type into the ScrollView", which nothing can
        // resolve later because a layout has no identity to find it by.
        if (event.type == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
            val fields = snapshot.nodes.filter { it.editable }
            fields.firstOrNull { it.focused }?.let { return it }
            // Focus is not always reported. The field that now holds what was just typed
            // is the one it was typed into, and among fields that is unambiguous in a
            // way it is not among containers, which report their children's text too.
            fields.firstOrNull { text.isNotEmpty() && it.text?.contains(text) == true }?.let { return it }
            fields.singleOrNull()?.let { return it }
            // Nothing on screen claims to accept text. The source is still the closest
            // account of what happened, and a later run can look for a field near it.
            source?.let { return it }
        }

        if (text.isNotEmpty() || !description.isNullOrEmpty()) {
            snapshot.nodes.firstOrNull { node ->
                val label = node.label()
                label.isNotBlank() && (label.equals(text, true) || label.equals(description, true))
            }?.let { return it }
        }
        return snapshot.nodes.firstOrNull { it.focused && it.isActionable() }
            ?: snapshot.nodes.firstOrNull { it.selected && it.isActionable() }
    }

    private fun ObservedEvent.toObservedAction(): ObservedAction? = when (type) {
        AccessibilityEvent.TYPE_VIEW_CLICKED -> ObservedAction.Click
        AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> ObservedAction.LongClick
        AccessibilityEvent.TYPE_VIEW_SELECTED -> ObservedAction.Select
        AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> ObservedAction.TextInput(text)
        AccessibilityEvent.TYPE_VIEW_SCROLLED -> ObservedAction.Scroll(Direction.DOWN)
        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> when {
            // A keyboard rising, a shade coming down, a video shrinking into a corner:
            // windows that appear without anyone navigating anywhere. Recorded as app
            // visits they become steps that open a keyboard, which cannot be opened.
            !fromDestinationWindow -> null
            packageName != lastSnapshot?.packageName -> ObservedAction.AppOpen(packageName)
            else -> ObservedAction.WindowChange(className)
        }
        // Content changes and focus moves are ambient noise, not user intent.
        else -> null
    }

    private companion object {
        const val SETTLE_MS = 200L
        const val OWN_PACKAGE = "com.autobile"
    }
}

data class RecordingState(
    val recording: Boolean = false,
    val label: String = "",
    val eventCount: Int = 0,
    val lastAction: ActionLabel? = null,
    val currentApp: String = "",
)

/**
 * What the user just did, named but not yet worded.
 *
 * The recorder runs far from any Context and reports live into the interface, so it
 * carries the resource and its argument instead of a finished sentence. Formatting the
 * text here would pin the recording panel to whichever language the process started in.
 */
data class ActionLabel(@StringRes val res: Int, val argument: String? = null)

/**
 * A stable English name for an action, for prompts and logs.
 *
 * Deliberately not translated. This text goes into model input and diagnostic output,
 * where the wording is part of the interface to something else: a prompt that changed
 * language with the user's phone would make the same demonstration compile differently
 * in Seoul and London, and a log would stop being greppable.
 */
val ObservedAction.diagnosticName: String
    get() = when (this) {
        is ObservedAction.Click -> "tapped"
        is ObservedAction.LongClick -> "long-pressed"
        is ObservedAction.Select -> "selected"
        is ObservedAction.TextInput -> "typed"
        is ObservedAction.Scroll -> "scrolled"
        is ObservedAction.Back -> "went back"
        is ObservedAction.Home -> "home"
        is ObservedAction.AppOpen -> "opened $packageName"
        is ObservedAction.WindowChange -> "changed screen"
    }

fun ObservedAction.describe(): ActionLabel = when (this) {
    is ObservedAction.Click -> ActionLabel(R.string.observed_click)
    is ObservedAction.LongClick -> ActionLabel(R.string.observed_long_click)
    is ObservedAction.Select -> ActionLabel(R.string.observed_select)
    is ObservedAction.TextInput -> ActionLabel(R.string.observed_text_input)
    is ObservedAction.Scroll -> ActionLabel(R.string.observed_scroll)
    is ObservedAction.Back -> ActionLabel(R.string.observed_back)
    is ObservedAction.Home -> ActionLabel(R.string.observed_home)
    is ObservedAction.AppOpen -> ActionLabel(R.string.observed_app_open, packageName.substringAfterLast('.'))
    is ObservedAction.WindowChange -> ActionLabel(R.string.observed_window_change)
}
