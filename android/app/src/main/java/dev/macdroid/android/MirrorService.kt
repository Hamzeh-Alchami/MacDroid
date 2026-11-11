package dev.macdroid.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder

/**
 * Foreground service that keeps MacDroid's connection alive while the screen is off.
 * A foreground service keeps its network access during Doze; a background app loses it
 * a few minutes after the screen turns off.
 */
class MirrorService : Service() {

    private val stateListener: (MirrorClient.State) -> Unit = { updateNotification(it) }
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        val notification = buildNotification(MirrorClient.state)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        MirrorClient.addListener(stateListener)

        val cm = getSystemService(ConnectivityManager::class.java)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = MirrorClient.onNetworkAvailable()
        }
        cm.registerDefaultNetworkCallback(cb)
        networkCallback = cb
        AppLog.i("Background service started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCONNECT) {
            MirrorClient.disconnect()
            stopSelf()
            return START_NOT_STICKY
        }
        MirrorClient.connectIfConfigured()
        return START_STICKY
    }

    override fun onDestroy() {
        MirrorClient.removeListener(stateListener)
        networkCallback?.let {
            runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) }
        }
        AppLog.i("Background service stopped")
        super.onDestroy()
    }

    private fun updateNotification(state: MirrorClient.State) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(state))
    }

    private fun buildNotification(state: MirrorClient.State): Notification {
        val text = when (state) {
            is MirrorClient.State.Connected -> "Mirroring to ${state.macName}"
            MirrorClient.State.Connecting -> "Connecting to your Mac…"
            is MirrorClient.State.Retrying -> "Reconnecting to your Mac…"
            is MirrorClient.State.Rejected -> "Pairing code rejected. Open MacDroid."
            MirrorClient.State.Disconnected -> "Not connected"
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val disconnect = PendingIntent.getService(
            this, 1, Intent(this, MirrorService::class.java).setAction(ACTION_DISCONNECT), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("MacDroid")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "Disconnect", disconnect).build())
            .build()
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Connection status", NotificationManager.IMPORTANCE_LOW).apply {
                    setShowBadge(false)
                }
            )
        }
    }

    companion object {
        private const val CHANNEL_ID = "connection"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_DISCONNECT = "dev.macdroid.android.DISCONNECT"

        /** Starts the service if the user has set up a connection. Safe to call from anywhere. */
        fun startIfEnabled(context: Context) {
            if (!Prefs.enabled || !Prefs.isConfigured) return
            try {
                context.startForegroundService(Intent(context, MirrorService::class.java))
            } catch (e: Exception) {
                // Android 12+ blocks starting it from the background unless battery
                // optimization is off for MacDroid; it starts next time the app is opened.
                AppLog.i("Couldn't start background service: ${e.javaClass.simpleName}. Allow background use.")
                MirrorClient.connectIfConfigured()
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MirrorService::class.java))
        }
    }
}
