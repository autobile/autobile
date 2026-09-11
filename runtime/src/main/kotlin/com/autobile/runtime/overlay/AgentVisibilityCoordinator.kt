package com.autobile.runtime.overlay

import android.content.Context
import com.autobile.core.data.SettingsStore
import com.autobile.runtime.agent.AgentActivity
import com.autobile.runtime.agent.AgentOrchestrator
import com.autobile.runtime.R
import com.autobile.runtime.background.AgentForegroundService
import com.autobile.runtime.background.AppReturn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Keeps every user-visible execution surface in sync with the orchestrator.
 *
 * The notification keeps Android from reclaiming an active run and provides a stop
 * action. The overlay explains what is happening above the app being operated, while
 * the touch marker shows which control will be used next. All three are driven from
 * the same state so they cannot disagree about whether an automation is active.
 */
class AgentVisibilityCoordinator(
    private val context: Context,
    private val orchestrator: AgentOrchestrator,
    private val settings: SettingsStore,
    private val overlay: AgentOverlayController = AgentOverlayController(context),
) {
    private var collection: Job? = null

    private var runActive = false

    /**
     * What the run that is ending was, remembered while it is still running.
     *
     * The activity state is already [AgentActivity.Idle] by the time the run is over, so
     * whether the user was waiting on it — and what it was called — has to be captured
     * before that, or the closing step has nothing to act on.
     */
    private var finishing: FinishedRun? = null

    fun start(scope: CoroutineScope) {
        if (collection != null) return
        // On the main thread because this builds and updates a View that is attached to
        // a window. A run is driven from background dispatchers, and touching an
        // attached view from one throws rather than merely failing to draw.
        collection = scope.launch {
            orchestrator.activity.collectLatest { activity ->
                when (activity) {
                    AgentActivity.Idle -> {
                        val ended = finishing
                        finishing = null
                        runActive = false
                        overlay.dismissAll()
                        AgentForegroundService.stop(context)
                        if (ended != null) concludeRun(ended)
                    }

                    is AgentActivity.Running -> {
                        finishing = FinishedRun(activity.skillName, activity.attended)
                        if (!runActive) {
                            runActive = true
                            AgentForegroundService.start(context)
                        }
                        // The overlay exists to report the run over somebody else's app.
                        // While Autobile is the app on screen its own live band already
                        // says the same thing, and drawing both stacks two copies of one
                        // sentence over each other.
                        if (AppReturn.isInForeground(context)) {
                            overlay.hideBanner()
                            overlay.hideTouchIndicator()
                        } else {
                            overlay.showBanner(bannerText(activity))
                            if (settings.showTouchIndicator) {
                                activity.touchTarget?.let(overlay::showTouchIndicator)
                                    ?: overlay.hideTouchIndicator()
                            } else {
                                overlay.hideTouchIndicator()
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Hands the phone back once a run is over.
     *
     * A run the user started is returned to Autobile, because they asked for it and are
     * owed the result rather than being left on whichever screen the automation happened
     * to stop on. A run they did not start waits in the notification shade instead: a
     * scheduled automation that yanked someone out of their messaging app to report
     * success would be a worse bug than the one this fixes.
     */
    private fun concludeRun(run: FinishedRun) {
        if (AppReturn.isInForeground(context)) return
        val returned = run.attended && AppReturn.bringToFront(context)
        if (!returned) AgentForegroundService.notifyFinished(context, run.skillName)
    }

    private data class FinishedRun(val skillName: String, val attended: Boolean)

    fun stop() {
        collection?.cancel()
        collection = null
        finishing = null
        runActive = false
        overlay.dismissAll()
        AgentForegroundService.stop(context)
    }

    private fun bannerText(activity: AgentActivity.Running): String = with(activity) {
        val step = stepDescription.ifBlank { context.getString(R.string.agent_notification_starting) }
        buildString {
            append(context.getString(R.string.agent_overlay_working))
            append('\n')
            append(context.getString(R.string.agent_overlay_step, skillName, step, stepIndex + 1, totalSteps))
            repairNote?.let { append('\n').append(context.getString(R.string.agent_overlay_adapting, it)) }
        }
    }
}
