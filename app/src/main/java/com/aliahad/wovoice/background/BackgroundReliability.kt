package com.aliahad.wovoice.background

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import com.aliahad.wovoice.bubble.BubbleAccessibilityService
import com.aliahad.wovoice.bubble.BubbleKeepAliveService
import com.aliahad.wovoice.settings.SettingsStore

enum class BackgroundCheckKey {
    ACCESSIBILITY,
    BATTERY,
    AUTOSTART,
    NOTIFICATIONS,
    KEEP_ALIVE,
    RECENTS_LOCK,
}

data class BackgroundCheckItem(
    val key: BackgroundCheckKey,
    val title: String,
    val summary: String,
    val ok: Boolean?,
    val critical: Boolean,
    val confirmable: Boolean = false,
)

data class ExitRecord(
    val timestampMs: Long,
    val reason: Int,
    val description: String,
)

data class ExitDiagnosis(
    val swipeUpForceStops24h: Int,
    val systemKills24h: Int,
    val lastKillTimestampMs: Long?,
) {
    val hadRecentInterruption: Boolean get() = swipeUpForceStops24h > 0 || systemKills24h > 0
}

data class BackgroundHealthSnapshot(
    val isXiaomi: Boolean,
    val floatingBubbleEnabled: Boolean,
    val keepAliveEnabled: Boolean,
    val accessibilityEnabled: Boolean,
    val accessibilityConnected: Boolean,
    val ignoringBattery: Boolean,
    val autoStartAllowed: Boolean?,
    val notificationsEnabled: Boolean,
    val keepAliveRunning: Boolean,
    val recentsLockConfirmed: Boolean,
    val exitDiagnosis: ExitDiagnosis,
)

/**
 * Pure JVM-testable evaluation + Android collector for background survival and Accessibility health.
 */
object BackgroundReliability {
    private const val WINDOW_24H_MS = 24L * 60L * 60L * 1000L
    const val REASON_USER_REQUESTED = 10
    const val REASON_LOW_MEMORY = 3
    const val REASON_OTHER = 13

    fun collectSnapshot(context: Context, store: SettingsStore, nowMs: Long = System.currentTimeMillis()): BackgroundHealthSnapshot {
        val exits = readRecentExits(context)
        return BackgroundHealthSnapshot(
            isXiaomi = HyperOS.isXiaomi,
            floatingBubbleEnabled = store.floatingBubbleEnabled,
            keepAliveEnabled = store.keepAliveEnabled,
            accessibilityEnabled = BubbleAccessibilityService.isEnabled(context),
            accessibilityConnected = BubbleAccessibilityService.isConnected(),
            ignoringBattery = HyperOS.ignoringBatteryOptimizations(context),
            autoStartAllowed = if (HyperOS.isXiaomi) HyperOS.opAllowed(context, HyperOS.OP_AUTO_START) else null,
            notificationsEnabled = HyperOS.notificationsEnabled(context),
            keepAliveRunning = BubbleKeepAliveService.isRunning(),
            recentsLockConfirmed = store.recentsLockConfirmed,
            exitDiagnosis = diagnoseExits(exits, nowMs),
        )
    }

    private fun readRecentExits(context: Context): List<ExitRecord> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
        return runCatching {
            val manager = context.getSystemService(ActivityManager::class.java) ?: return emptyList()
            manager.getHistoricalProcessExitReasons(context.packageName, 0, 12).map { info ->
                ExitRecord(
                    timestampMs = info.timestamp,
                    reason = info.reason,
                    description = info.description.orEmpty(),
                )
            }
        }.getOrDefault(emptyList())
    }

    fun diagnoseExits(exits: List<ExitRecord>, nowMs: Long): ExitDiagnosis {
        val cutoff = nowMs - WINDOW_24H_MS
        var swipeForceStops = 0
        var systemKills = 0
        var latest: Long? = null
        for (exit in exits) {
            if (exit.timestampMs < cutoff) continue
            val desc = exit.description.lowercase()
            val isSwipeForceStop = exit.reason == REASON_USER_REQUESTED &&
                (desc.contains("swipeupclean") || desc.contains("force stop") || desc.contains("remove task"))
            val isSystemKill = exit.reason == REASON_LOW_MEMORY || exit.reason == REASON_OTHER
            if (isSwipeForceStop) {
                swipeForceStops++
                latest = maxOf(latest ?: 0L, exit.timestampMs)
            } else if (isSystemKill) {
                systemKills++
                latest = maxOf(latest ?: 0L, exit.timestampMs)
            }
        }
        return ExitDiagnosis(
            swipeUpForceStops24h = swipeForceStops,
            systemKills24h = systemKills,
            lastKillTimestampMs = latest,
        )
    }

    fun evaluateChecks(snapshot: BackgroundHealthSnapshot): List<BackgroundCheckItem> {
        val items = mutableListOf<BackgroundCheckItem>()

        items += BackgroundCheckItem(
            key = BackgroundCheckKey.ACCESSIBILITY,
            title = "Accessibility service connected",
            summary = when {
                snapshot.accessibilityEnabled && snapshot.accessibilityConnected ->
                    "Connected — LiVoice can display the dictation bubble in any app."
                snapshot.accessibilityEnabled ->
                    "Enabled but disconnected — toggle LiVoice off and back on once in Accessibility."
                else ->
                    "Turn on “LiVoice dictation bubble” in Android Accessibility settings."
            },
            ok = snapshot.accessibilityEnabled && snapshot.accessibilityConnected,
            critical = true,
        )

        items += if (snapshot.isXiaomi) {
            BackgroundCheckItem(
                key = BackgroundCheckKey.BATTERY,
                title = "HyperOS: Battery saver → No restrictions",
                summary = "Without “No restrictions”, HyperOS freezes LiVoice in the background and drops the dictation bubble.",
                ok = snapshot.ignoringBattery,
                critical = true,
            )
        } else {
            BackgroundCheckItem(
                key = BackgroundCheckKey.BATTERY,
                title = "Unrestricted battery usage",
                summary = "Prevents Android Doze and vendor battery managers from closing the background bubble service.",
                ok = snapshot.ignoringBattery,
                critical = true,
            )
        }

        if (snapshot.isXiaomi) {
            items += BackgroundCheckItem(
                key = BackgroundCheckKey.AUTOSTART,
                title = "HyperOS: Autostart",
                summary = "Critical on Xiaomi/Redmi/POCO: when Autostart is off, swiping LiVoice away in Recents triggers Force Stop and turns Accessibility off.",
                ok = snapshot.autoStartAllowed,
                critical = true,
            )
        }

        items += BackgroundCheckItem(
            key = BackgroundCheckKey.NOTIFICATIONS,
            title = "Notifications allowed",
            summary = "Needed for the silent background status notification that keeps LiVoice protected at foreground-service priority.",
            ok = snapshot.notificationsEnabled,
            critical = snapshot.keepAliveEnabled,
        )

        if (snapshot.keepAliveEnabled && snapshot.floatingBubbleEnabled) {
            items += BackgroundCheckItem(
                key = BackgroundCheckKey.KEEP_ALIVE,
                title = "Background keep-alive service",
                summary = if (snapshot.keepAliveRunning) {
                    "Active — LiVoice is running a quiet foreground service so closing the app window keeps the bubble alive."
                } else {
                    "Not running yet — tap Fix to start the quiet background service."
                },
                ok = snapshot.keepAliveRunning,
                critical = true,
            )
        }

        if (snapshot.isXiaomi) {
            items += BackgroundCheckItem(
                key = BackgroundCheckKey.RECENTS_LOCK,
                title = "Lock LiVoice in Recents",
                summary = "Open Recents, long-press the LiVoice card, and tap the lock icon so tapping “Clear all” skips LiVoice.",
                ok = if (snapshot.recentsLockConfirmed) true else null,
                critical = false,
                confirmable = true,
            )
        }

        return items
    }

    fun missingCritical(snapshot: BackgroundHealthSnapshot): List<BackgroundCheckItem> =
        evaluateChecks(snapshot).filter { it.critical && it.ok != true }

    fun summaryLine(snapshot: BackgroundHealthSnapshot): String {
        val checks = evaluateChecks(snapshot)
        val missing = checks.count { it.critical && it.ok != true }
        val done = checks.count { it.ok == true }
        return when {
            missing > 0 -> "! $missing critical ${if (missing == 1) "setting" else "settings"} needed ($done/${checks.size} ready)"
            snapshot.exitDiagnosis.swipeUpForceStops24h > 0 ->
                "! Stopped ${snapshot.exitDiagnosis.swipeUpForceStops24h}× on Recents swipe in 24h — review setup"
            else -> "✓ Background protection ready ($done/${checks.size} checks)"
        }
    }

    fun watchdogMessage(snapshot: BackgroundHealthSnapshot): String {
        val diag = snapshot.exitDiagnosis
        return when {
            diag.swipeUpForceStops24h > 0 ->
                "LiVoice was force-stopped ${diag.swipeUpForceStops24h}× in the last 24h when swiped out of Recents, which also turns Accessibility off. Turn on Autostart and Battery → No restrictions below so closing LiVoice keeps the bubble alive."
            diag.systemKills24h > 0 ->
                "Your phone closed LiVoice in the background ${diag.systemKills24h}× in the last 24h. Keeping Battery on “No restrictions” and the background service enabled prevents interruptions."
            else ->
                "No unexpected background stops detected in the last 24h."
        }
    }
}
