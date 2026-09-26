package com.prasbin.shadowlearn.data.listener

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.prasbin.shadowlearn.MainActivity
import com.prasbin.shadowlearn.R

/**
 * Phase 7 foreground holder for an active Listener Mode recording.
 *
 * This service owns NO audio itself — the [ListenerRepository] (driven by
 * the Listener screen's ViewModel) owns the recorder. The service exists so
 * that while a session is open the process holds a visible, user-stoppable
 * foreground notification (microphone type, as required for targetSdk 36)
 * and Android does not silently kill capture on navigation. It is started
 * when a recording starts and stopped when the session closes or fails —
 * there is never silent background recording.
 */
class ListenerService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                ensureChannel()
                startForeground(NOTIFICATION_ID, buildNotification(intent), micType())
            }
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        // Never restart a dead recording holder without an explicit start:
        // a resurrected service with no recorder would be a lie.
        return START_NOT_STICKY
    }

    private fun micType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else 0

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Lecture recording",
                    NotificationManager.IMPORTANCE_LOW
                ).apply { description = "Shows while SHADOW LEARN records a lecture." }
            )
        }
    }

    private fun buildNotification(intent: Intent): Notification {
        val sessionId = intent.getLongExtra(EXTRA_SESSION_ID, 0)
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, ListenerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Recording lecture…")
            .setContentText("Session #$sessionId · tap to open SHADOW LEARN")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(openApp)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stop)
            .build()
    }

    companion object {
        const val ACTION_START = "com.prasbin.shadowlearn.listener.START"
        const val ACTION_STOP = "com.prasbin.shadowlearn.listener.STOP"
        const val EXTRA_SESSION_ID = "session_id"
        private const val CHANNEL_ID = "listener_recording"
        private const val NOTIFICATION_ID = 41

        fun start(context: Context, sessionId: Long) {
            val intent = Intent(context, ListenerService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_SESSION_ID, sessionId)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(
                Intent(context, ListenerService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
