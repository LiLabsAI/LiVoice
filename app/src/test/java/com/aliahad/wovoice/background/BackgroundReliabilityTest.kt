package com.aliahad.wovoice.background

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundReliabilityTest {
    private val now = 1_800_000_000_000L

    @Test
    fun xiaomiChecklistIncludesAutostartAndRecentsLockAndFlagsMissingCriticalItems() {
        val snapshot = BackgroundHealthSnapshot(
            isXiaomi = true,
            floatingBubbleEnabled = true,
            keepAliveEnabled = true,
            accessibilityEnabled = false,
            accessibilityConnected = false,
            ignoringBattery = false,
            autoStartAllowed = false,
            notificationsEnabled = true,
            keepAliveRunning = false,
            recentsLockConfirmed = false,
            exitDiagnosis = ExitDiagnosis(0, 0, null),
        )

        val checks = BackgroundReliability.evaluateChecks(snapshot)
        assertEquals(
            listOf(
                BackgroundCheckKey.ACCESSIBILITY,
                BackgroundCheckKey.BATTERY,
                BackgroundCheckKey.AUTOSTART,
                BackgroundCheckKey.NOTIFICATIONS,
                BackgroundCheckKey.KEEP_ALIVE,
                BackgroundCheckKey.RECENTS_LOCK,
            ),
            checks.map { it.key },
        )

        val missing = BackgroundReliability.missingCritical(snapshot)
        assertEquals(
            listOf(
                BackgroundCheckKey.ACCESSIBILITY,
                BackgroundCheckKey.BATTERY,
                BackgroundCheckKey.AUTOSTART,
                BackgroundCheckKey.KEEP_ALIVE,
            ),
            missing.map { it.key },
        )
        assertNull(checks.first { it.key == BackgroundCheckKey.RECENTS_LOCK }.ok)
        assertTrue(BackgroundReliability.summaryLine(snapshot).contains("4 critical settings needed"))
    }

    @Test
    fun nonXiaomiOmitsHyperOsItemsAndPassesWhenReady() {
        val snapshot = BackgroundHealthSnapshot(
            isXiaomi = false,
            floatingBubbleEnabled = true,
            keepAliveEnabled = true,
            accessibilityEnabled = true,
            accessibilityConnected = true,
            ignoringBattery = true,
            autoStartAllowed = null,
            notificationsEnabled = true,
            keepAliveRunning = true,
            recentsLockConfirmed = false,
            exitDiagnosis = ExitDiagnosis(0, 0, null),
        )

        val checks = BackgroundReliability.evaluateChecks(snapshot)
        assertFalse(checks.any { it.key == BackgroundCheckKey.AUTOSTART || it.key == BackgroundCheckKey.RECENTS_LOCK })
        assertTrue(BackgroundReliability.missingCritical(snapshot).isEmpty())
        assertTrue(BackgroundReliability.summaryLine(snapshot).startsWith("✓ Background protection ready"))
    }

    @Test
    fun diagnosesSwipeUpCleanForceStopsWithinLast24Hours() {
        val exits = listOf(
            ExitRecord(now - 60_000L, BackgroundReliability.REASON_USER_REQUESTED, "stop com.aliahad.wovoice due to SwipeUpClean"),
            ExitRecord(now - 3_600_000L, BackgroundReliability.REASON_USER_REQUESTED, "stop com.aliahad.wovoice due to SwipeUpClean"),
            ExitRecord(now - 2 * 3_600_000L, BackgroundReliability.REASON_OTHER, "SwipeUpClean"),
            // Older than 24h — must be ignored
            ExitRecord(now - 25L * 3_600_000L, BackgroundReliability.REASON_USER_REQUESTED, "stop com.aliahad.wovoice due to SwipeUpClean"),
        )

        val diagnosis = BackgroundReliability.diagnoseExits(exits, now)
        assertEquals(2, diagnosis.swipeUpForceStops24h)
        assertEquals(1, diagnosis.systemKills24h)
        assertEquals(now - 60_000L, diagnosis.lastKillTimestampMs)
        assertTrue(diagnosis.hadRecentInterruption)

        val snapshot = BackgroundHealthSnapshot(
            isXiaomi = true,
            floatingBubbleEnabled = true,
            keepAliveEnabled = false,
            accessibilityEnabled = true,
            accessibilityConnected = true,
            ignoringBattery = true,
            autoStartAllowed = true,
            notificationsEnabled = true,
            keepAliveRunning = false,
            recentsLockConfirmed = true,
            exitDiagnosis = diagnosis,
        )
        assertTrue(BackgroundReliability.watchdogMessage(snapshot).contains("force-stopped 2×"))
        assertTrue(BackgroundReliability.summaryLine(snapshot).contains("Stopped 2× on Recents swipe"))
    }
}
