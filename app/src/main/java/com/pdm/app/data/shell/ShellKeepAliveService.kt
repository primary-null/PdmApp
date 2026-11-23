package com.pdm.app.data.shell

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.pdm.app.MainActivity
import com.pdm.app.R

/**
 * Keeps the process alive while a node shell is open. Without it, switching apps mid
 * `apt full-upgrade` lets Android freeze or kill the app, which drops the websocket and the node's
 * shell with it, interrupting apt. Holds nothing itself: the session lives in the view model.
 * Started when a shell opens, stopped when it closes.
 */
class ShellKeepAliveService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: "node"
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification(title),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
        // A killed process takes the shell with it; there is nothing to come back to.
        return START_NOT_STICKY
    }

    private fun notification(title: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Open terminals", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Keeps your terminal connected while you use another app."
                    setShowBadge(false)
                },
            )
        }
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_shell)
            .setContentTitle("Terminal open on $title")
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val CHANNEL = "shell"
        private const val NOTIFICATION_ID = 7031
        private const val EXTRA_TITLE = "title"

        /** Called with the app in front (the user just opened the shell), where starting one is allowed. */
        fun start(context: Context, title: String) {
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, ShellKeepAliveService::class.java).putExtra(EXTRA_TITLE, title),
                )
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, ShellKeepAliveService::class.java)) }
        }
    }
}
