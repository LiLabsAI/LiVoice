package com.aliahad.wovoice.bubble

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.aliahad.wovoice.R
import com.aliahad.wovoice.background.BubbleHealthJobService
import com.aliahad.wovoice.settings.SettingsStore
import com.aliahad.wovoice.settings.SetupActivity

/**
 * Quiet START_STICKY foreground service that keeps LiVoice at foreground-service priority
 * while the user has enabled the floating dictation bubble.
 *
 * Paired with Battery "No restrictions" and HyperOS Autostart, this prevents the OS from
 * evicting the process when the user closes SetupActivity, and surfaces an immediate
 * 1-tap "Reconnect" notification if Accessibility ever gets turned off.
 */
class BubbleKeepAliveService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val store = SettingsStore(this)
        if (intent?.action == ACTION_PAUSE) {
            store.floatingBubbleEnabled = false
            running = false
            stopForegroundSafely()
            stopSelf()
            BubbleHealthJobService.sync(this)
            return START_NOT_STICKY
        }
        if (!store.floatingBubbleEnabled || !store.keepAliveEnabled) {
            running = false
            stopForegroundSafely()
            stopSelf()
            return START_NOT_STICKY
        }
        if (!promoteForeground()) {
            running = false
            stopSelf()
            return START_NOT_STICKY
        }
        running = true
        BubbleHealthJobService.sync(this)
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val store = SettingsStore(this)
        if (store.floatingBubbleEnabled && store.keepAliveEnabled) {
            // Keep the Accessibility bubble active after the dashboard task is swiped away.
            BubbleAccessibilityService.refreshConnection()
            BubbleHealthJobService.sync(this)
            updateNotification(this)
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        running = false
        stopForegroundSafely()
        super.onDestroy()
    }

    private fun promoteForeground(): Boolean {
        createChannel(this)
        val notification = buildStatusNotification(this)
        val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        return runCatching {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, serviceType)
            true
        }.getOrDefault(false)
    }

    private fun stopForegroundSafely() {
        runCatching {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        }
    }

    companion object {
        const val CHANNEL_ID = "livoice_bubble_keepalive"
        const val ALERT_CHANNEL_ID = "livoice_bubble_alerts"
        const val NOTIFICATION_ID = 44
        const val DISCONNECTED_ALERT_ID = 45
        private const val ACTION_REFRESH = "com.aliahad.wovoice.KEEPALIVE_REFRESH"
        private const val ACTION_PAUSE = "com.aliahad.wovoice.KEEPALIVE_PAUSE"

        @Volatile
        private var running = false

        fun isRunning(): Boolean = running

        fun createChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            val keepAlive = NotificationChannel(
                CHANNEL_ID,
                "Background dictation service",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Quiet status notification that keeps the floating dictation bubble active in the background."
                setShowBadge(false)
            }
            val alerts = NotificationChannel(
                ALERT_CHANNEL_ID,
                "Bubble connection alerts",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Alerts you if Android or HyperOS turns off LiVoice Accessibility access."
            }
            manager.createNotificationChannel(keepAlive)
            manager.createNotificationChannel(alerts)
        }

        fun syncState(context: Context) {
            val app = context.applicationContext
            val store = SettingsStore(app)
            if (store.floatingBubbleEnabled && store.keepAliveEnabled) {
                createChannel(app)
                if (running) {
                    updateNotification(app)
                } else {
                    runCatching {
                        ContextCompat.startForegroundService(
                            app,
                            Intent(app, BubbleKeepAliveService::class.java).setAction(ACTION_REFRESH),
                        )
                    }
                }
                if (BubbleAccessibilityService.isEnabled(app) && BubbleAccessibilityService.isConnected()) {
                    NotificationManagerCompat.from(app).cancel(DISCONNECTED_ALERT_ID)
                }
            } else {
                running = false
                runCatching { app.stopService(Intent(app, BubbleKeepAliveService::class.java)) }
                NotificationManagerCompat.from(app).cancel(DISCONNECTED_ALERT_ID)
            }
        }

        fun updateNotification(context: Context) {
            if (!running) return
            val app = context.applicationContext
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) return
            createChannel(app)
            try {
                NotificationManagerCompat.from(app).notify(NOTIFICATION_ID, buildStatusNotification(app))
            } catch (_: SecurityException) {
                // Notification permission revoked between check and notify.
            }
        }

        fun notifyDisconnectedIfNeeded(context: Context) {
            val app = context.applicationContext
            val store = SettingsStore(app)
            if (!store.floatingBubbleEnabled) return
            val connected = BubbleAccessibilityService.isEnabled(app) && BubbleAccessibilityService.isConnected()
            if (connected) {
                NotificationManagerCompat.from(app).cancel(DISCONNECTED_ALERT_ID)
                return
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) return
            createChannel(app)
            val reconnectIntent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val reconnectPi = PendingIntent.getActivity(
                app,
                10,
                reconnectIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val openAppPi = PendingIntent.getActivity(
                app,
                11,
                Intent(app, SetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val notification = NotificationCompat.Builder(app, ALERT_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_mic_small)
                .setContentTitle("LiVoice dictation bubble turned off")
                .setContentText("Your phone disabled Accessibility access. Tap to reconnect.")
                .setContentIntent(reconnectPi)
                .addAction(0, "Reconnect", reconnectPi)
                .addAction(0, "Fix setup", openAppPi)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .build()
            try {
                NotificationManagerCompat.from(app).notify(DISCONNECTED_ALERT_ID, notification)
            } catch (_: SecurityException) {
                // Notification permission revoked between check and notify.
            }
        }

        private fun buildStatusNotification(context: Context): Notification {
            val connected = BubbleAccessibilityService.isEnabled(context) && BubbleAccessibilityService.isConnected()
            val openApp = PendingIntent.getActivity(
                context,
                20,
                Intent(context, SetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val pause = PendingIntent.getService(
                context,
                21,
                Intent(context, BubbleKeepAliveService::class.java).setAction(ACTION_PAUSE),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_mic_small)
                .setOngoing(true)
                .setSilent(true)
                .setShowWhen(false)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

            if (connected) {
                builder
                    .setContentTitle("LiVoice bubble active")
                    .setContentText("Ready in the background — tap any text box to speak")
                    .setContentIntent(openApp)
                    .addAction(0, "Open", openApp)
                    .addAction(0, "Pause", pause)
            } else {
                val reconnect = PendingIntent.getActivity(
                    context,
                    22,
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                builder
                    .setContentTitle("LiVoice bubble disconnected")
                    .setContentText("Accessibility is off — tap to reconnect")
                    .setContentIntent(reconnect)
                    .addAction(0, "Reconnect", reconnect)
                    .addAction(0, "Pause", pause)
            }
            return builder.build()
        }
    }
}
