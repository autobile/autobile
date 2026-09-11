package com.autobile.runtime.background

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.autobile.core.common.Logx
import com.autobile.runtime.AutobileRuntime
import com.autobile.runtime.R
import com.autobile.runtime.agent.AgentActivity
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Keeps a run alive while the user is in another app, and makes it visible.
 *
 * The notification is not only a platform requirement. An automation driving the phone
 * on its own must always be visible, stoppable, and — because it will have navigated the
 * user into somebody else's app — a way back to Autobile from wherever they end up.
 */
class AgentForegroundService : LifecycleService() {

    override fun onCreate() {
        super.onCreate()
        createChannels()
        startForeground(NOTIFICATION_ID, buildRunNotification(getString(R.string.agent_app_name), null))

        lifecycleScope.launch {
            val orchestrator = AutobileRuntime.services?.orchestrator
            if (orchestrator == null) {
                // Nothing will ever move this service off its placeholder notification,
                // and an ongoing notification the user cannot dismiss is worse than no
                // notification at all.
                Logx.w("Agent notification started without a runtime; stopping")
                stopSelf()
                return@launch
            }
            orchestrator.activity.collectLatest { activity ->
                when (activity) {
                    is AgentActivity.Running -> notify(activity)
                    AgentActivity.Idle -> stopSelf()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            AutobileRuntime.services?.orchestrator?.cancelCurrentRun()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    private fun notify(activity: AgentActivity.Running) {
        val manager = getSystemService(NotificationManager::class.java)
        val step = activity.stepDescription.ifBlank { getString(R.string.agent_notification_starting) }
        manager?.notify(
            NOTIFICATION_ID,
            buildRunNotification(
                title = activity.skillName,
                text = getString(
                    R.string.agent_notification_step,
                    step,
                    activity.stepIndex + 1,
                    activity.totalSteps,
                ),
            ),
        )
    }

    private fun buildRunNotification(title: String, text: String?): Notification {
        val stopIntent = PendingIntent.getService(
            this,
            REQUEST_STOP,
            Intent(this, AgentForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_autobile_agent)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openAppIntent(this, REQUEST_OPEN))
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .addAction(R.drawable.ic_autobile_stop, getString(R.string.agent_notification_stop), stopIntent)
            .build()
    }

    private fun createChannels() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.agent_channel_name),
                // Low importance: the notification must be present and reachable, but a
                // scheduled automation should not interrupt whatever the user is doing.
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.agent_channel_description)
                setShowBadge(false)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                FINISHED_CHANNEL_ID,
                getString(R.string.agent_finished_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { setShowBadge(false) },
        )
    }

    companion object {
        private const val CHANNEL_ID = "autobile_agent"
        private const val FINISHED_CHANNEL_ID = "autobile_agent_finished"
        private const val NOTIFICATION_ID = 4201
        private const val FINISHED_NOTIFICATION_ID = 4203
        private const val REQUEST_STOP = 1
        private const val REQUEST_OPEN = 2
        const val ACTION_STOP = "com.autobile.runtime.STOP_AGENT"

        private fun openAppIntent(context: Context, requestCode: Int): PendingIntent? {
            val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                ?: return null
            return PendingIntent.getActivity(
                context,
                requestCode,
                launch,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }

        /**
         * Starts the service if the platform allows it right now.
         *
         * Android restricts foreground service starts from the background, and the
         * restriction is not something the app can detect in advance. A rejection here
         * means the run continues without the notification, which is better than
         * crashing a scheduled automation over its own progress indicator.
         */
        fun start(context: Context) {
            val intent = Intent(context, AgentForegroundService::class.java)
            runCatching { context.startForegroundService(intent) }
                .onFailure { Logx.w("Could not start the agent notification", it) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, AgentForegroundService::class.java)) }
        }

        /**
         * Leaves a way back after a run the user was not watching.
         *
         * A scheduled automation must not pull anyone out of what they are doing, so it
         * reports by waiting in the shade instead. This one is dismissible: the run is
         * over and there is nothing left to stop.
         */
        fun notifyFinished(context: Context, skillName: String) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            val notification = NotificationCompat.Builder(context, FINISHED_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_autobile_agent)
                .setContentTitle(context.getString(R.string.agent_finished_title, skillName))
                .setContentText(context.getString(R.string.agent_finished_body))
                .setContentIntent(openAppIntent(context, REQUEST_OPEN))
                .setAutoCancel(true)
                .setSilent(true)
                .build()
            runCatching { manager.notify(FINISHED_NOTIFICATION_ID, notification) }
        }
    }
}
