package com.aliahad.wovoice.background

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.aliahad.wovoice.bubble.BubbleAccessibilityService
import com.aliahad.wovoice.bubble.BubbleKeepAliveService
import com.aliahad.wovoice.settings.SettingsStore

/**
 * Restores the background keep-alive service and watchdog job after device reboot or app update.
 */
class BubbleBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val app = context.applicationContext
        val store = SettingsStore(app)
        BubbleHealthJobService.sync(app)
        if (!store.floatingBubbleEnabled) return
        if (store.keepAliveEnabled) {
            BubbleKeepAliveService.syncState(app)
        }
        if (!BubbleAccessibilityService.isEnabled(app)) {
            BubbleKeepAliveService.notifyDisconnectedIfNeeded(app)
        }
    }
}

/**
 * Lightweight zero-dependency periodic watchdog (15-minute JobScheduler interval, persisted across reboots).
 * Verifies the background keep-alive service is running and alerts if Accessibility was turned off.
 */
class BubbleHealthJobService : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean {
        val store = SettingsStore(this)
        if (!store.floatingBubbleEnabled) {
            cancel(this)
            return false
        }
        if (store.keepAliveEnabled && !BubbleKeepAliveService.isRunning()) {
            BubbleKeepAliveService.syncState(this)
        }
        if (!BubbleAccessibilityService.isEnabled(this) || !BubbleAccessibilityService.isConnected()) {
            if (!BubbleKeepAliveService.isRunning()) {
                BubbleKeepAliveService.notifyDisconnectedIfNeeded(this)
            } else {
                BubbleKeepAliveService.updateNotification(this)
            }
        }
        return false
    }

    override fun onStopJob(params: JobParameters?): Boolean = false

    companion object {
        private const val JOB_ID = 4401
        private const val INTERVAL_MS = 15L * 60L * 1000L

        fun sync(context: Context) {
            val app = context.applicationContext
            val store = SettingsStore(app)
            if (store.floatingBubbleEnabled && store.keepAliveEnabled) {
                schedule(app)
            } else {
                cancel(app)
            }
        }

        fun schedule(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            if (scheduler.getPendingJob(JOB_ID) != null) return
            val info = JobInfo.Builder(JOB_ID, ComponentName(context, BubbleHealthJobService::class.java))
                .setPeriodic(INTERVAL_MS)
                .setPersisted(true)
                .build()
            runCatching { scheduler.schedule(info) }
        }

        fun cancel(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            runCatching { scheduler.cancel(JOB_ID) }
        }
    }
}
