package com.aliahad.wovoice.data

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.aliahad.wovoice.account.AccountSettings
import com.aliahad.wovoice.network.TranscriptionClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WoVoiceDaoTest {
    private lateinit var database: WoVoiceDatabase
    private lateinit var dao: WoVoiceDao

    @Before
    fun open() {
        database = Room.inMemoryDatabaseBuilder<WoVoiceDatabase>()
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
        dao = database.dao()
    }

    @After
    fun close() = database.close()

    @Test
    fun pushSettlementKeepsAnEditMadeWhileItWasInFlight() = runBlocking {
        dao.insertDictionary(term("t1", "Kubernetes", syncState = SYNC_QUEUED))
        // The user renames the term while its upload is in flight.
        dao.updateDictionary(dao.dictionaryBySyncId(ACCOUNT, "t1")!!.copy(term = "Kubernetes Engine", syncState = SYNC_LOCAL))
        dao.markDictionaryPushed(ACCOUNT, "t1", 1)
        val entry = dao.dictionaryBySyncId(ACCOUNT, "t1")!!
        assertEquals(SYNC_LOCAL, entry.syncState)
        assertEquals(1, entry.syncVersion)

        dao.insertDictionary(term("t2", "Cloudflare", syncState = SYNC_QUEUED))
        dao.markDictionaryPushed(ACCOUNT, "t2", 1)
        assertEquals(SYNCED, dao.dictionaryBySyncId(ACCOUNT, "t2")!!.syncState)
    }

    @Test
    fun removingASyncedEventTakesItOutOfTheDaysTotals() = runBlocking {
        val first = event("e1", processingMs = 900, corrected = true)
        val second = event("e2", processingMs = 400)
        val key = analyticsDateKey(ACCOUNT, first.createdAtMs, first.zoneId)
        dao.mergeRemoteAnalytics(first, seed(key, first))
        dao.mergeRemoteAnalytics(second, seed(key, second))
        assertEquals(2L, dao.dailyUsage(key, ACCOUNT)!!.dictationCount)

        dao.removeAnalyticsEvent(dao.analyticsBySyncId(ACCOUNT, "e1")!!, key)
        val day = dao.dailyUsage(key, ACCOUNT)!!
        assertEquals(1L, day.dictationCount)
        assertEquals(second.audioDurationMs, day.audioDurationMs)
        assertEquals(second.wordCount.toLong(), day.wordCount)
        assertEquals(0L, day.correctionCount)
        assertEquals("400", day.processingSamplesMs)
        assertNull(dao.analyticsBySyncId(ACCOUNT, "e1"))

        dao.removeAnalyticsEvent(dao.analyticsBySyncId(ACCOUNT, "e2")!!, key)
        assertNull(dao.dailyUsage(key, ACCOUNT))
    }

    @Test
    fun correctionFlagMovesTheDaysCorrectionCount() = runBlocking {
        val value = event("e1")
        val key = analyticsDateKey(ACCOUNT, value.createdAtMs, value.zoneId)
        dao.mergeRemoteAnalytics(value, seed(key, value))
        dao.setEventCorrected(dao.analyticsBySyncId(ACCOUNT, "e1")!!, corrected = true, dateKey = key, syncState = SYNC_LOCAL)
        assertEquals(1L, dao.dailyUsage(key, ACCOUNT)!!.correctionCount)
        val flagged = dao.analyticsBySyncId(ACCOUNT, "e1")!!
        assertTrue(flagged.corrected)
        assertEquals(SYNC_LOCAL, flagged.syncState)
        // Re-applying the same flag (an echo) must not count twice.
        dao.setEventCorrected(flagged, corrected = true, dateKey = key, syncState = SYNCED)
        assertEquals(1L, dao.dailyUsage(key, ACCOUNT)!!.correctionCount)
    }

    @Test
    fun resetSyncStateMarksEverythingForReupload() = runBlocking {
        dao.insertRecord(record("r1").copy(syncVersion = 4, syncState = SYNCED))
        dao.insertDictionary(term("t1", "Kubernetes", syncState = SYNCED, syncVersion = 2))
        dao.insertAnalyticsEvent(event("e1").copy(syncVersion = 3, syncState = SYNCED))
        dao.upsertOutbox(outbox("r2", deleted = true))
        dao.resetSyncState(ACCOUNT)
        assertEquals(listOf("r1"), dao.unsyncedHistory(ACCOUNT, 10).map(DictationRecord::syncId))
        assertEquals(0, dao.historyBySyncId(ACCOUNT, "r1")!!.syncVersion)
        assertEquals(listOf("t1"), dao.unsyncedDictionary(ACCOUNT, 10).map(DictionaryEntry::syncId))
        assertEquals(listOf("e1"), dao.unsyncedAnalytics(ACCOUNT, 10).map(AnalyticsSyncEvent::syncId))
        assertTrue(dao.outbox(ACCOUNT, 10).isEmpty())
    }

    @Test
    fun correctionReachesTheDictationsSyncedEvent() = runBlocking {
        val repository = WoVoiceRepository(dao, FakeSettings())
        repository.recordSuccessfulDictation(
            result = TranscriptionClient.Result.Success(
                text = "Meet Rahim tomorrow.",
                rawText = "meet rahim tomorrow",
                polished = true,
                model = "whisper-large-v3-turbo",
                requestId = "req-1",
            ),
            committedText = "Meet Rahim tomorrow.",
            audioDurationMs = 2_000,
            keepHistory = true,
            analyticsSyncId = "event-1",
        )
        dao.updateAnalyticsSync(ACCOUNT, "event-1", SYNCED, 1)
        repository.noteCorrection("event-1")
        val event = dao.analyticsBySyncId(ACCOUNT, "event-1")!!
        assertTrue(event.corrected)
        assertEquals(SYNC_LOCAL, event.syncState)
        val key = analyticsDateKey(ACCOUNT, event.createdAtMs, event.zoneId)
        assertEquals(1L, dao.dailyUsage(key, ACCOUNT)!!.correctionCount)
        assertFalse(dao.unsyncedAnalytics(ACCOUNT, 10).isEmpty())
    }

    @Test
    fun assignUnassignedHandlesDictionaryConflictsAndMigratesDailyUsageKeys() = runBlocking {
        // Account already has "kubernetes" and an existing prefixed daily_usage row.
        dao.insertDictionary(term("owned-1", "Kubernetes", syncState = SYNCED, syncVersion = 2))
        val existingKey = "$ACCOUNT|2026-08-04|UTC"
        dao.upsertUsage(
            DailyUsageAggregate(
                dateKey = existingKey,
                localDate = "2026-08-04",
                zoneId = "UTC",
                firstEventAtMs = 2_000,
                lastEventAtMs = 3_000,
                dictationCount = 2,
                audioDurationMs = 4_000,
                wordCount = 10,
                processingTotalMs = 900,
                processingSamplesMs = "400,500",
                polishedCount = 1,
                correctionCount = 1,
                asrNeurons = 2.0,
                polishNeurons = 1.0,
                totalNeurons = 3.0,
                estimatedCostUsd = 0.002,
                ownerAccountId = ACCOUNT,
            ),
        )

        // Legacy unassigned data: colliding "Kubernetes", duplicate unassigned "Cloudflare" (suggested vs confirmed),
        // one unique term "Antigravity", one overlapping daily_usage row, and one non-overlapping legacy day.
        dao.insertDictionary(term("", "Kubernetes", syncState = SYNC_LOCAL).copy(ownerAccountId = null))
        dao.insertDictionary(
            term("", "Cloudflare", syncState = SYNC_LOCAL)
                .copy(ownerAccountId = null, status = DictionaryEntry.STATUS_SUGGESTED, useCount = 1),
        )
        dao.insertDictionary(
            term("", "Cloudflare", syncState = SYNC_LOCAL)
                .copy(ownerAccountId = null, status = DictionaryEntry.STATUS_CONFIRMED, useCount = 5),
        )
        dao.insertDictionary(term("", "Antigravity", syncState = SYNC_LOCAL).copy(ownerAccountId = null))
        dao.upsertUsage(
            DailyUsageAggregate(
                dateKey = "2026-08-04|UTC",
                localDate = "2026-08-04",
                zoneId = "UTC",
                firstEventAtMs = 1_000,
                lastEventAtMs = 4_000,
                dictationCount = 3,
                audioDurationMs = 6_000,
                wordCount = 15,
                processingTotalMs = 1_100,
                processingSamplesMs = "300,800",
                polishedCount = 2,
                correctionCount = 0,
                asrNeurons = 1.5,
                polishNeurons = 0.5,
                totalNeurons = 2.0,
                estimatedCostUsd = 0.001,
                ownerAccountId = null,
            ),
        )
        dao.upsertUsage(
            DailyUsageAggregate(
                dateKey = "2026-08-03|UTC",
                localDate = "2026-08-03",
                zoneId = "UTC",
                firstEventAtMs = 500,
                lastEventAtMs = 500,
                dictationCount = 1,
                audioDurationMs = 1_000,
                wordCount = 4,
                processingTotalMs = 250,
                processingSamplesMs = "250",
                polishedCount = 1,
                correctionCount = 0,
                asrNeurons = 0.5,
                polishNeurons = 0.2,
                totalNeurons = 0.7,
                estimatedCostUsd = 0.0005,
                ownerAccountId = null,
            ),
        )

        dao.assignUnassigned(ACCOUNT)

        assertEquals(0, dao.unassignedCount())
        val confirmed = dao.dictionary(ACCOUNT, DictionaryEntry.STATUS_CONFIRMED, "").map { it.term }.toSet()
        assertEquals(setOf("Kubernetes", "Cloudflare", "Antigravity"), confirmed)
        // Original synced "Kubernetes" was preserved intact.
        assertEquals("owned-1", dao.dictionaryByNormalized(ACCOUNT, "kubernetes")!!.syncId)
        assertTrue(dao.dictionaryByNormalized(ACCOUNT, "cloudflare")!!.isConfirmed)
        assertTrue(dao.dictionaryByNormalized(ACCOUNT, "antigravity")!!.syncId.isNotBlank())

        // Legacy daily_usage rows were re-keyed and merged cleanly.
        assertNull(dao.dailyUsage("2026-08-04|UTC", null))
        val mergedDay = dao.dailyUsage(existingKey, ACCOUNT)!!
        assertEquals(5L, mergedDay.dictationCount)
        assertEquals(10_000L, mergedDay.audioDurationMs)
        assertEquals(25L, mergedDay.wordCount)
        assertEquals(1_000L, mergedDay.firstEventAtMs)
        assertEquals(4_000L, mergedDay.lastEventAtMs)
        assertEquals(3L, mergedDay.polishedCount)
        assertEquals(1L, mergedDay.correctionCount)
        val migratedDay = dao.dailyUsage("$ACCOUNT|2026-08-03|UTC", ACCOUNT)!!
        assertEquals(1L, migratedDay.dictationCount)
    }

    @Test
    fun deletingOrClearingSuggestedDictionaryEntriesNeverQueuesCloudTombstones() = runBlocking {
        val settings = FakeSettings()
        val repository = WoVoiceRepository(dao, settings)
        assertTrue(repository.addManualTerm("ConfirmedOne"))
        assertTrue(repository.addSuggestion("SuggestedOne"))
        assertTrue(repository.addSuggestion("SuggestedTwo"))

        val suggestedOne = repository.dictionary(confirmed = false).first { it.term == "SuggestedOne" }
        repository.deleteDictionary(suggestedOne)
        assertTrue(dao.outbox(ACCOUNT, 10).isEmpty())

        repository.clearDictionary()
        val tombstones = dao.outbox(ACCOUNT, 10)
        assertEquals(1, tombstones.size)
        assertEquals("dictionary", tombstones.single().recordType)
        assertTrue(repository.dictionary(confirmed = true).isEmpty())
        assertTrue(repository.dictionary(confirmed = false).isEmpty())
    }

    @Test
    fun offlineHistoryReadAppliesCachedRetention() = runBlocking {
        val now = 2_000_000_000_000L
        val settings = FakeSettings().apply { historyRetentionDays = 7 }
        val repository = WoVoiceRepository(dao, settings, clock = { now })
        dao.insertRecord(record("old").copy(createdAtMs = now - 10 * 86_400_000L, finalText = "Expired"))
        dao.insertRecord(record("fresh").copy(createdAtMs = now - 2 * 86_400_000L, finalText = "Kept"))

        val history = repository.history()
        assertEquals(listOf("Kept"), history.map(DictationRecord::finalText))
    }

    private fun record(syncId: String) = DictationRecord(
        requestId = syncId,
        finalText = "Hello.",
        createdAtMs = 1_700_000_000_000,
        zoneId = "UTC",
        offsetSeconds = 0,
        wordCount = 1,
        audioDurationMs = 1_000,
        asrModel = "whisper-large-v3-turbo",
        polished = true,
        asrMs = 1,
        polishMs = 1,
        totalMs = 2,
        pricingVersion = null,
        inputTokens = null,
        outputTokens = null,
        asrNeurons = null,
        polishNeurons = null,
        totalNeurons = null,
        estimatedCostUsd = null,
        ownerAccountId = ACCOUNT,
        syncId = syncId,
    )

    private fun term(syncId: String, value: String, syncState: String, syncVersion: Int = 0) = DictionaryEntry(
        term = value,
        normalizedTerm = value.lowercase(),
        status = DictionaryEntry.STATUS_CONFIRMED,
        source = DictionaryEntry.SOURCE_MANUAL,
        createdAtMs = 1,
        lastUsedAtMs = 1,
        useCount = 0,
        ownerAccountId = ACCOUNT,
        syncId = syncId,
        syncVersion = syncVersion,
        syncState = syncState,
    )

    private fun event(syncId: String, processingMs: Long = 500, corrected: Boolean = false) = AnalyticsSyncEvent(
        syncId = syncId,
        ownerAccountId = ACCOUNT,
        createdAtMs = 1_700_000_000_000,
        zoneId = "Asia/Kolkata",
        audioDurationMs = if (corrected) 3_000 else 2_000,
        wordCount = if (corrected) 6 else 4,
        processingMs = processingMs,
        polished = true,
        corrected = corrected,
        asrNeurons = 1.0,
        polishNeurons = 0.5,
        estimatedCostUsd = 0.001,
        syncState = SYNCED,
    )

    private fun seed(key: String, event: AnalyticsSyncEvent) = DailyUsageAggregate(
        dateKey = key,
        localDate = key.split('|')[1],
        zoneId = event.zoneId,
        firstEventAtMs = event.createdAtMs,
        lastEventAtMs = event.createdAtMs,
        dictationCount = 1,
        audioDurationMs = event.audioDurationMs,
        wordCount = event.wordCount.toLong(),
        processingTotalMs = event.processingMs,
        processingSamplesMs = event.processingMs.toString(),
        polishedCount = 1,
        correctionCount = if (event.corrected) 1 else 0,
        asrNeurons = event.asrNeurons,
        polishNeurons = event.polishNeurons,
        totalNeurons = event.asrNeurons + event.polishNeurons,
        estimatedCostUsd = event.estimatedCostUsd,
        ownerAccountId = ACCOUNT,
    )

    private fun outbox(recordId: String, deleted: Boolean) = EncryptedSyncOutboxItem(
        ownerAccountId = ACCOUNT,
        recordType = "history",
        recordId = recordId,
        baseVersion = 1,
        keyVersion = 1,
        nonce = if (deleted) "" else "bm9uY2U",
        ciphertext = if (deleted) "" else "Y2lwaGVy",
        deleted = deleted,
        createdAtMs = 1,
    )

    private class FakeSettings : AccountSettings {
        override var accountId: String? = ACCOUNT
        override val lastAccountId: String? = ACCOUNT
        override var accountEmail: String? = "person@example.com"
        override var accountRole: String = "user"
        override var accountState: String = "active"
        override var accountSuspendedUntilMs: Long? = null
        override var accountPublicMessage: String? = null
        override var accountSupportEmail: String = "support@example.com"
        override var syncCursor: Long = 0
        override var cloudSyncMigratedAccount: String? = ACCOUNT
        override var historySyncEnabled: Boolean = true
        override var historyRetentionDays: Int? = null
        override var lastSyncAtMs: Long = 0
        override fun isSignedIn(): Boolean = true
        override fun clearAccount() = Unit
    }

    private companion object {
        const val ACCOUNT = "account-1"
    }
}
