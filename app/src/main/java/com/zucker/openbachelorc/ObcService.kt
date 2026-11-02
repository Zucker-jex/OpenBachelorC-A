package com.zucker.openbachelorc

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder

/**
 * Keeps the client process alive while a Frida session is attached.
 *
 * This is not cosmetic: the Gadget is configured with
 * `"interaction": { "on_load": "wait" }` (see OpenBachelorG/main.py), so the
 * game stays blocked inside its loader until this client attaches. If the
 * client were killed by the background-process killer mid-session, the game
 * would appear frozen. A foreground service with a persistent notification
 * makes that far less likely.
 */
class ObcService : Service() {

    companion object {
        private const val CHANNEL_ID = "obc_session"
        private const val NOTIFICATION_ID = 1

        const val ACTION_STOP = "com.zucker.openbachelorc.STOP"

        fun start(context: Context) {
            val intent = Intent(context, ObcService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ObcService::class.java))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        createChannel()

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val stopSession = PendingIntent.getService(
            this,
            1,
            Intent(this, ObcService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(openApp)
            .addAction(
                Notification.Action.Builder(
                    null, getString(R.string.notification_stop), stopSession
                ).build()
            )
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)

        return START_STICKY
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }
}
