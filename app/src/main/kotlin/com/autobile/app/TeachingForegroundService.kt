package com.autobile.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat

/** Keeps a user-started teaching session visible while they demonstrate in other apps. */
class TeachingForegroundService : Service() {
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.teach_notification_channel), NotificationManager.IMPORTANCE_LOW),
        )
        val reopen = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        startForeground(
            NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(com.autobile.runtime.R.drawable.ic_autobile_agent)
                .setContentTitle(getString(R.string.teach_notification_title))
                .setContentText(getString(R.string.teach_notification_body))
                .setContentIntent(reopen)
                .setOngoing(true)
                .setSilent(true)
                .build(),
        )
    }

    /**
     * Never restarted by the system.
     *
     * The recording this notification stands for lives in the process that died. Letting
     * Android bring the service back would put "Autobile is learning" in the shade with
     * no recorder behind it, and the user would keep demonstrating into nothing.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "autobile_teaching"
        private const val NOTIFICATION_ID = 4202

        fun start(context: Context) {
            context.startForegroundService(Intent(context, TeachingForegroundService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TeachingForegroundService::class.java))
        }
    }
}
