package com.aliahad.wovoice.sync

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.aliahad.wovoice.account.AccountResult
import com.aliahad.wovoice.account.AccountSettings
import com.aliahad.wovoice.account.SecretNames
import com.aliahad.wovoice.account.SecretsVault
import com.aliahad.wovoice.account.SyncAccount
import com.aliahad.wovoice.data.AnalyticsPeriod
import com.aliahad.wovoice.data.DeletionHold
import com.aliahad.wovoice.data.SYNC_LOCAL
import com.aliahad.wovoice.data.SYNC_QUEUED
import com.aliahad.wovoice.data.WoVoiceDao
import com.aliahad.wovoice.data.WoVoiceDatabase
import com.aliahad.wovoice.data.WoVoiceRepository
import com.aliahad.wovoice.network.TranscriptionClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncCoordinatorTest {
    private val cloud = FakeCloud()
    private val databases = mutableListOf<WoVoiceDatabase>()

    @After
    fun close() = databases.forEach(WoVoiceDatabase::close)

    @Test
    fun dictationsTermsAndTotalsFollowTheAccountToAnotherDevice() = runBlocking {
        val phone = device()
        val mac = device()
        phone.dictate("req-1", "Meet Rahim at the clinic.")
        phone.repository.addManualTerm("Rahim")
        assertEquals(SyncResult.Success(uploaded = 3, downloaded = 0), phone.sync.syncNow())

        val pulled = mac.sync.syncNow() as SyncResult.Success
        assertEquals(3, pulled.downloaded)
        assertEquals(listOf("Meet Rahim at the clinic."), mac.repository.history().map { it.finalText })
        assertEquals(listOf("Rahim"), mac.repository.dictionary(confirmed = true).map { it.term })
        assertEquals(1L, mac.repository.dashboard(AnalyticsPeriod.ALL_TIME).aggregates.sumOf { it.dictationCount })
        // Nothing echoes back as new work on the next sync.
        assertEquals(SyncResult.Success(0, 0), phone.sync.syncNow())
    }

    @Test
    fun iosContractImportsIntoAndroidAndRemoteChangesUpdateHistoryVocabularyAndTotals() = runBlocking {
        val fixture = checkNotNull(javaClass.getResourceAsStream("/ios-sync-v2.json"))
            .bufferedReader().use { JSONObject(it.readText()) }.getJSONArray("items")
        val writes = (0 until fixture.length()).map { index ->
            val item = fixture.getJSONObject(index)
            CloudWrite(item.getString("id"), item.getString("type"), item.getInt("baseVersion"), item.getJSONObject("payload"))
        }
        assertTrue(cloud.push("fixture", writes) is AccountResult.Success)
        val phone = device()
        assertEquals(SyncResult.Success(0, 3), phone.sync.syncNow())
        val history = phone.repository.history().single()
        assertEquals("Meet Professor Rahman at 10:30 tomorrow.", history.finalText)
        assertEquals(6, history.wordCount)
        assertEquals(1250L, history.audioDurationMs)
        assertEquals("Professor Rahman", phone.repository.dictionary(confirmed = true).single().term)
        val totals = phone.repository.dashboard(AnalyticsPeriod.ALL_TIME).aggregates
        assertEquals(1L, totals.sumOf { it.dictationCount })
        assertEquals(6L, totals.sumOf { it.wordCount })
        assertEquals(0.0001, totals.sumOf { it.estimatedCostUsd }, 0.0000001)

        val word = writes.single { it.type == "dictionary" }
        val edited = JSONObject(checkNotNull(word.payload).toString())
            .put("term", "Doctor Rahman").put("normalizedTerm", "doctor rahman")
        val changes = listOf(CloudWrite(word.id, word.type, 1, edited)) + writes.filter { it.type != "dictionary" }
            .map { CloudWrite(it.id, it.type, 1, null) }
        assertTrue(cloud.push("fixture", changes) is AccountResult.Success)
        assertEquals(SyncResult.Success(0, 3), phone.sync.syncNow())
        assertTrue(phone.repository.history().isEmpty())
        assertEquals("Doctor Rahman", phone.repository.dictionary(confirmed = true).single().term)
        assertEquals(0L, phone.repository.dashboard(AnalyticsPeriod.ALL_TIME).aggregates.sumOf { it.dictationCount })
    }

    @Test
    fun anUploadWhoseResponseWasLostConverges() = runBlocking {
        val phone = device()
        phone.dictate("req-1", "Hello there.")
        cloud.dropNextPushResponse = true
        assertTrue(phone.sync.syncNow() is SyncResult.Error)
        assertTrue(phone.sync.syncNow() is SyncResult.Success)
        assertEquals(SyncResult.Success(0, 0), phone.sync.syncNow())
        assertEquals(2, cloud.records.size)
    }

    @Test
    fun deletesReachOtherDevicesAndUndoBeforeSyncKeepsTheRecord() = runBlocking {
        val phone = device()
        val mac = device()
        phone.dictate("req-1", "Keep me.")
        phone.dictate("req-2", "Delete me.")
        phone.sync.syncNow()
        mac.sync.syncNow()

        val records = phone.repository.history().associateBy { it.requestId }
        phone.repository.deleteHistory(records.getValue("req-1"))
        phone.repository.restoreHistory(records.getValue("req-1"))
        phone.repository.deleteHistory(records.getValue("req-2"))
        phone.sync.syncNow()
        mac.sync.syncNow()
        assertEquals(listOf("Keep me."), mac.repository.history().map { it.finalText })
    }

    @Test
    fun turningHistorySyncOffKeepsLocalCopiesAndStopsUploads() = runBlocking {
        val phone = device()
        val mac = device()
        phone.dictate("req-1", "Private note.")
        phone.sync.syncNow()
        mac.sync.syncNow()

        assertTrue(phone.sync.updateSettings(historySyncEnabled = false) is AccountResult.Success)
        assertTrue(cloud.records.keys.none { it.startsWith("history:") })
        mac.sync.syncNow()
        assertFalse(mac.settings.historySyncEnabled)
        mac.dictate("req-2", "Stays on the Mac.")
        mac.sync.syncNow()
        assertTrue(cloud.records.keys.none { it.startsWith("history:") })
        assertEquals(2, mac.repository.history().size)

        phone.sync.updateSettings(historySyncEnabled = true)
        mac.sync.syncNow()
        assertEquals(2, cloud.records.keys.count { it.startsWith("history:") })
    }

    @Test
    fun retentionRemovesOldLocalHistory() = runBlocking {
        val phone = device()
        phone.dictate("req-old", "Old note.", createdAtMs = System.currentTimeMillis() - 40 * 86_400_000L)
        phone.dictate("req-new", "New note.")
        phone.sync.updateSettings(historyRetentionDays = 30)
        assertEquals(listOf("New note."), phone.repository.history().map { it.finalText })
        assertEquals(30, phone.settings.historyRetentionDays)
    }

    @Test
    fun resetAnalyticsAndCorrectionsReachOtherDevices() = runBlocking {
        val phone = device()
        val mac = device()
        phone.dictate("req-1", "Meet Rahim.", analyticsId = "event-1")
        phone.sync.syncNow()
        mac.sync.syncNow()
        phone.repository.noteCorrection("event-1")
        phone.sync.syncNow()
        mac.sync.syncNow()
        assertEquals(1L, mac.repository.dashboard(AnalyticsPeriod.ALL_TIME).aggregates.sumOf { it.correctionCount })

        phone.repository.resetAnalytics()
        phone.sync.syncNow()
        mac.sync.syncNow()
        assertTrue(mac.repository.dashboard(AnalyticsPeriod.ALL_TIME).aggregates.isEmpty())
    }

    @Test
    fun aFullDictionaryDoesNotBlockHistory() = runBlocking {
        val phone = device()
        cloud.dictionaryLimit = 0
        phone.repository.addManualTerm("Rahim")
        phone.dictate("req-1", "Still syncs.")
        val result = phone.sync.syncNow() as SyncResult.Success
        assertEquals("Your dictionary is full.", result.warning)
        assertTrue("history:req-1" in cloud.records)
    }

    @Test
    fun theOldVaultIsImportedOnceAndRetired() = runBlocking {
        val vaultKey = VaultCrypto.newSecret()
        val recovery = VaultCrypto.newSecret()
        val sealed = VaultCrypto.encryptRecord(
            vaultKey,
            JSONObject()
                .put("schemaVersion", 1)
                .put("text", "Only in the old vault.")
                .put("createdAtMs", 1_790_000_000_000)
                .put("zoneId", "UTC")
                .put("offsetSeconds", 0)
                .put("wordCount", 5)
                .put("audioDurationMs", 2_000)
                .toString().toByteArray(),
            ACCOUNT,
            "history",
            "vault-only",
            1,
            1,
        )
        val legacy = FakeLegacy(
            vault = VaultCrypto.wrapVaultKey(vaultKey, recovery, ACCOUNT, 1),
            records = listOf(LegacyRecord("vault-only", "history", 1, sealed.nonce, sealed.ciphertext, deleted = false)),
        )
        val mac = device(legacy)
        mac.secrets.putString(SecretNames.VAULT_KEY, VaultCrypto.encodeSecret(vaultKey))
        mac.secrets.putString(SecretNames.RECOVERY_SECRET, VaultCrypto.encodeSecret(recovery))
        mac.dictate("req-1", "Already on this Mac.")

        assertTrue(mac.sync.syncNow() is SyncResult.Success)
        assertTrue(cloud.legacyMigrationCompleted)
        assertNull(mac.secrets.getString(SecretNames.VAULT_KEY))
        assertEquals(ACCOUNT, mac.settings.cloudSyncMigratedAccount)
        assertTrue("history:vault-only" in cloud.records)
        assertTrue("history:req-1" in cloud.records)
        assertEquals(2, mac.repository.history().size)
    }

    @Test
    fun cancelledPushAndCrashOrphanedQueuedRowsRecoverToLocal() = runBlocking {
        // 1) Pre-existing SYNC_QUEUED row from a previous crash recovers on syncLocked() entry.
        val crashed = device()
        crashed.dictate("req-crash", "Recovered after crash.")
        crashed.dao.updateHistorySync(ACCOUNT, "req-crash", SYNC_QUEUED, 0)
        val recovered = crashed.sync.syncNow() as SyncResult.Success
        assertEquals(2, recovered.uploaded)
        assertTrue("history:req-crash" in cloud.records)

        // 2) Cancelling coroutine mid-push restores staged rows back to SYNC_LOCAL via NonCancellable finally.
        val pushEntered = CompletableDeferred<Unit>()
        val releasePush = CompletableDeferred<Unit>()
        val stallingApi = object : CloudSyncApi by cloud {
            override fun push(token: String, writes: List<CloudWrite>): AccountResult<List<AppliedSyncItem>> {
                pushEntered.complete(Unit)
                runBlocking { releasePush.await() }
                return cloud.push(token, writes)
            }
        }
        val phone = device(apiOverride = stallingApi)
        phone.dictate("req-cancel", "Must not stay queued.")
        val job = launch(Dispatchers.Default) { phone.sync.syncNow() }
        pushEntered.await()
        job.cancel()
        releasePush.complete(Unit)
        job.join()

        val row = phone.dao.historyBySyncId(ACCOUNT, "req-cancel")!!
        assertEquals(SYNC_LOCAL, row.syncState)
    }

    @Test
    fun pullGuardsAgainstStalledCursorAndPageCap() = runBlocking {
        val stalledApi = object : CloudSyncApi by cloud {
            override fun pull(token: String, cursor: Long): AccountResult<CloudPage> =
                AccountResult.Success(CloudPage(nextCursor = cursor, hasMore = true, records = emptyList(), settings = null))
        }
        val stalledDevice = device(apiOverride = stalledApi)
        val stalledResult = stalledDevice.sync.syncNow()
        assertTrue(stalledResult is SyncResult.Error)
        assertTrue((stalledResult as SyncResult.Error).message.contains("did not advance"))

        var pulls = 0
        val infiniteApi = object : CloudSyncApi by cloud {
            override fun pull(token: String, cursor: Long): AccountResult<CloudPage> {
                pulls++
                return AccountResult.Success(CloudPage(nextCursor = cursor + 1, hasMore = true, records = emptyList(), settings = null))
            }
        }
        val cappedDevice = device(apiOverride = infiniteApi)
        val cappedResult = cappedDevice.sync.syncNow()
        assertTrue(cappedResult is SyncResult.Error)
        assertEquals(SyncCoordinator.MAX_PULL_PAGES, pulls)
    }

    @Test
    fun legacyVaultAcceptsKeyVersionGreaterThanOneAndPreservesKeysOnFailedUnwrap() = runBlocking {
        val vaultKey = VaultCrypto.newSecret()
        val recovery = VaultCrypto.newSecret()
        val wrongRecovery = VaultCrypto.newSecret()
        val sealedV2 = VaultCrypto.encryptRecord(
            vaultKey,
            JSONObject()
                .put("schemaVersion", 1)
                .put("text", "Encrypted with rotated key v2.")
                .put("createdAtMs", 1_790_000_000_000)
                .put("zoneId", "UTC")
                .put("offsetSeconds", 0)
                .put("wordCount", 5)
                .put("audioDurationMs", 2_000)
                .toString().toByteArray(),
            ACCOUNT,
            "history",
            "vault-v2",
            keyVersion = 2,
            schemaVersion = 1,
        )
        val legacy = FakeLegacy(
            vault = VaultCrypto.wrapVaultKey(vaultKey, recovery, ACCOUNT, keyVersion = 2),
            records = listOf(LegacyRecord("vault-v2", "history", keyVersion = 2, sealedV2.nonce, sealedV2.ciphertext, deleted = false)),
        )

        // Wrong recovery secret: must fail WITHOUT wiping secrets or marking migrated.
        val broken = device(legacy)
        broken.secrets.putString(SecretNames.VAULT_KEY, VaultCrypto.encodeSecret(vaultKey))
        broken.secrets.putString(SecretNames.RECOVERY_SECRET, VaultCrypto.encodeSecret(wrongRecovery))
        val failed = broken.sync.syncNow()
        assertTrue(failed is SyncResult.Error)
        assertTrue(broken.secrets.contains(SecretNames.VAULT_KEY))
        assertTrue(broken.secrets.contains(SecretNames.RECOVERY_SECRET))
        assertFalse(broken.settings.isCloudSyncMigrated(ACCOUNT))

        // Correct recovery secret: keyVersion = 2 record imports and retires the vault.
        val healthy = device(legacy)
        healthy.secrets.putString(SecretNames.VAULT_KEY, VaultCrypto.encodeSecret(vaultKey))
        healthy.secrets.putString(SecretNames.RECOVERY_SECRET, VaultCrypto.encodeSecret(recovery))
        assertTrue(healthy.sync.syncNow() is SyncResult.Success)
        assertEquals(listOf("Encrypted with rotated key v2."), healthy.repository.history().map { it.finalText })
        assertTrue(healthy.settings.isCloudSyncMigrated(ACCOUNT))
    }

    @Test
    fun syncPropagatesAccountRestrictionStatus() = runBlocking {
        val restrictedApi = object : CloudSyncApi by cloud {
            override fun pull(token: String, cursor: Long): AccountResult<CloudPage> =
                AccountResult.Error("ACCOUNT_SUSPENDED", "Suspended for abuse.", false, 403)
        }
        val fakeAccount = FakeAccount()
        val phone = device(apiOverride = restrictedApi, accountOverride = fakeAccount)
        val result = phone.sync.syncNow()
        assertTrue(result is SyncResult.Error)
        assertEquals("ACCOUNT_SUSPENDED", fakeAccount.restrictedCode)
        assertFalse(fakeAccount.cloudServicesAllowed)
    }

    @Test
    fun cachedRetentionAppliesAfterPullEvenWhenPageSettingsNull() = runBlocking {
        val now = 2_000_000_000_000L
        val oldPayload = JSONObject()
            .put("schemaVersion", 1)
            .put("text", "Old pulled dictation")
            .put("createdAtMs", now - 40 * 86_400_000L)
            .put("zoneId", "UTC")
            .put("offsetSeconds", 0)
            .put("wordCount", 3)
            .put("audioDurationMs", 1_000)
        val recentPayload = JSONObject()
            .put("schemaVersion", 1)
            .put("text", "Recent pulled dictation")
            .put("createdAtMs", now - 2 * 86_400_000L)
            .put("zoneId", "UTC")
            .put("offsetSeconds", 0)
            .put("wordCount", 3)
            .put("audioDurationMs", 1_000)

        val nullSettingsApi = object : CloudSyncApi by cloud {
            override fun pull(token: String, cursor: Long): AccountResult<CloudPage> =
                AccountResult.Success(
                    CloudPage(
                        nextCursor = 2L,
                        hasMore = false,
                        records = listOf(
                            CloudRecord("old-1", "history", 1, false, oldPayload),
                            CloudRecord("new-1", "history", 1, false, recentPayload),
                        ),
                        settings = null,
                    ),
                )
        }
        val phone = device(apiOverride = nullSettingsApi, clock = { now })
        phone.settings.historyRetentionDays = 30
        phone.sync.syncNow()
        assertEquals(listOf("Recent pulled dictation"), phone.repository.history().map { it.finalText })
    }

    @Test
    fun skipNewTermsDoesNotStarveEditsToAlreadySyncedTerms() = runBlocking {
        val phone = device()
        // Sync 1 existing term first so its syncVersion == 1.
        assertTrue(phone.repository.addManualTerm("ExistingTerm"))
        assertTrue(phone.sync.syncNow() is SyncResult.Success)
        val existingEntry = phone.repository.dictionary(confirmed = true).single { it.term == "ExistingTerm" }
        assertEquals(1, existingEntry.syncVersion)

        // Now cap cloud dictionary at 1, add 105 brand-new local terms (more than MAX_BATCH=100),
        // AND rename the existing synced term. Even with 105 unsynced new terms ahead of it,
        // the edit to the synced term must NOT be starved!
        cloud.dictionaryLimit = 1
        repeat(105) { idx -> assertTrue(phone.repository.addManualTerm("NewTerm$idx")) }
        assertTrue(phone.repository.renameTerm(existingEntry, "ExistingTermRenamed"))

        val result = phone.sync.syncNow() as SyncResult.Success
        assertEquals("Your dictionary is full.", result.warning)
        val remote = cloud.records["dictionary:${existingEntry.syncId}"]!!
        assertEquals("ExistingTermRenamed", remote.payload!!.getString("term"))
        assertEquals(2, remote.version)
    }

    @Test
    fun undoDeletionHoldDefersTombstoneUploadUntilReleasedOrExpired() = runBlocking {
        var now = 1_000_000L
        val hold = DeletionHold()
        val phone = device(clock = { now }, deletionHold = hold)
        phone.dictate("req-hold", "Undoable dictation.")
        phone.sync.syncNow()
        assertTrue("history:req-hold" in cloud.records)

        val stored = phone.repository.history().single()
        phone.repository.deleteHistory(stored, holdWindowMs = 8_000L)

        // Sync during the Undo window must NOT upload the tombstone.
        phone.sync.syncNow()
        assertFalse(cloud.records["history:req-hold"]!!.deleted)

        // After the hold expires, syncing uploads the tombstone.
        now += 9_000L
        phone.sync.syncNow()
        assertTrue(cloud.records["history:req-hold"]!!.deleted)
    }

    @Test
    fun hittingMaxPushRoundsReportsRemainingWorkWarning() = runBlocking {
        val phone = device(maxPushRounds = 1)
        // 105 local dictations (> 1 batch of 100) with maxPushRounds = 1 leaves work pending.
        repeat(105) { idx -> phone.dictate("req-$idx", "Item $idx") }
        val result = phone.sync.syncNow() as SyncResult.Success
        assertEquals(100, result.uploaded)
        assertTrue(result.warning?.contains("waiting to sync") == true)
    }

    // ---- Harness ----

    private inner class Device(
        val repository: WoVoiceRepository,
        val sync: SyncCoordinator,
        val settings: FakeSettings,
        val secrets: MemorySecrets,
        val dao: WoVoiceDao,
    ) {
        suspend fun dictate(
            requestId: String,
            text: String,
            createdAtMs: Long? = null,
            analyticsId: String = "analytics-$requestId",
        ) {
            repository.recordSuccessfulDictation(
                result = TranscriptionClient.Result.Success(text, text.lowercase(), true, "whisper-large-v3-turbo", requestId),
                committedText = text,
                audioDurationMs = 2_000,
                keepHistory = true,
                analyticsSyncId = analyticsId,
            )
            if (createdAtMs != null) {
                val stored = repository.history().first { it.requestId == requestId }
                dao.updateRecord(stored.copy(createdAtMs = createdAtMs))
            }
        }
    }

    private fun device(
        legacy: LegacyVaultApi = FakeLegacy(null, emptyList()),
        apiOverride: CloudSyncApi = cloud,
        accountOverride: FakeAccount = FakeAccount(),
        clock: () -> Long = System::currentTimeMillis,
        deletionHold: DeletionHold = DeletionHold(),
        maxPushRounds: Int = 20,
    ): Device {
        val database = Room.inMemoryDatabaseBuilder<WoVoiceDatabase>()
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
        databases += database
        val settings = FakeSettings()
        val secrets = MemorySecrets()
        val sync = SyncCoordinator(
            account = accountOverride,
            api = apiOverride,
            legacy = legacy,
            secrets = secrets,
            settings = settings,
            dao = database.dao(),
            clock = clock,
            ioDispatcher = Dispatchers.IO,
            deletionHold = deletionHold,
            maxPushRounds = maxPushRounds,
        )
        return Device(WoVoiceRepository(database.dao(), settings, clock = clock, deletionHold = deletionHold), sync, settings, secrets, database.dao())
    }

    private class FakeCloud : CloudSyncApi {
        val records = linkedMapOf<String, CloudRecord>()
        private val feed = linkedMapOf<String, Long>()
        private var seq = 0L
        private var historySyncEnabled = true
        private var retention: Int? = null
        private var lastConflicts: List<CloudRecord> = emptyList()
        var dropNextPushResponse = false
        var dictionaryLimit = 1_000
        var legacyMigrationCompleted = false

        private fun write(record: CloudRecord) {
            val key = "${record.type}:${record.id}"
            records[key] = record
            feed.remove(key)
            feed[key] = ++seq
        }

        private fun settingsValue() = CloudSettings(historySyncEnabled, retention, legacyVault = false)

        override fun pull(token: String, cursor: Long): AccountResult<CloudPage> {
            val page = feed.entries.filter { it.value > cursor }.sortedBy { it.value }.take(50)
            return AccountResult.Success(
                CloudPage(
                    nextCursor = page.maxOfOrNull { it.value } ?: cursor,
                    hasMore = page.size == 50,
                    records = page.map { records.getValue(it.key) },
                    settings = settingsValue(),
                ),
            )
        }

        override fun push(token: String, writes: List<CloudWrite>): AccountResult<List<AppliedSyncItem>> {
            val keys = writes.map { "${it.type}:${it.id}" }
            check(keys.toSet().size == keys.size) { "client pushed the same record twice" }
            if (!historySyncEnabled && writes.any { it.type == "history" && it.payload != null }) {
                return AccountResult.Error("HISTORY_SYNC_DISABLED", "History sync is off.", false, 409)
            }
            val conflicts = writes.filter { (records["${it.type}:${it.id}"]?.version ?: 0) != it.baseVersion }
            if (conflicts.isNotEmpty()) {
                lastConflicts = conflicts.map { records["${it.type}:${it.id}"] ?: CloudRecord(it.id, it.type, 0, false, null) }
                return AccountResult.Error("SYNC_CONFLICT", "Conflict.", false, 409)
            }
            val newTerms = writes.count { it.type == "dictionary" && it.payload != null && records["dictionary:${it.id}"] == null }
            if (newTerms > 0 && records.values.count { it.type == "dictionary" && !it.deleted } + newTerms > dictionaryLimit) {
                return AccountResult.Error("STORAGE_LIMIT_REACHED", "Your dictionary is full.", false, 413)
            }
            val applied = writes.map { value ->
                val record = CloudRecord(value.id, value.type, value.baseVersion + 1, value.payload == null, value.payload?.let { JSONObject(it.toString()) })
                write(record)
                AppliedSyncItem(record.id, record.type, record.version)
            }
            if (dropNextPushResponse) {
                dropNextPushResponse = false
                return AccountResult.Error("NETWORK_UNAVAILABLE", "Offline.", true, 0)
            }
            return AccountResult.Success(applied)
        }

        override fun conflicts(error: AccountResult.Error): List<CloudRecord> = lastConflicts

        override fun settings(token: String): AccountResult<CloudSettings> = AccountResult.Success(settingsValue())

        override fun updateSettings(
            token: String,
            historySyncEnabled: Boolean?,
            historyRetentionDays: Int?,
            clearRetention: Boolean,
        ): AccountResult<CloudSettings> {
            if (historySyncEnabled == false && this.historySyncEnabled) {
                records.keys.filter { it.startsWith("history:") }.forEach { key ->
                    records.remove(key)
                    feed.remove(key)
                }
            }
            historySyncEnabled?.let { this.historySyncEnabled = it }
            if (clearRetention) retention = null else historyRetentionDays?.let { retention = it }
            return AccountResult.Success(settingsValue())
        }

        override fun completeLegacyMigration(token: String): AccountResult<Unit> {
            legacyMigrationCompleted = true
            return AccountResult.Success(Unit)
        }
    }

    private class FakeLegacy(private val vault: WrappedVaultKey?, private val records: List<LegacyRecord>) : LegacyVaultApi {
        override fun getVault(token: String): AccountResult<WrappedVaultKey?> = AccountResult.Success(vault)
        override fun pullLegacy(token: String, cursor: Long): AccountResult<LegacyPage> =
            AccountResult.Success(LegacyPage(nextCursor = records.size.toLong(), hasMore = false, records = if (cursor == 0L) records else emptyList()))
    }

    private class FakeAccount : SyncAccount {
        override val accountId: String = ACCOUNT
        override var cloudServicesAllowed: Boolean = true
        override var restrictionMessage: String = "Restricted."
        var restrictedCode: String? = null
        override suspend fun validAccessToken(): AccountResult<String> = AccountResult.Success("token")
        override suspend fun refreshAfterRejected(rejectedToken: String): AccountResult<String> = AccountResult.Success("token")
        override fun markRestricted(code: String, message: String, suspendedUntilMs: Long?) {
            restrictedCode = code
            cloudServicesAllowed = false
            restrictionMessage = message
        }
    }

    private class MemorySecrets : SecretsVault {
        private val values = mutableMapOf<String, String>()
        override fun putString(name: String, value: String?) {
            if (value == null) values.remove(name) else values[name] = value
        }
        override fun getString(name: String): String? = values[name]
        override fun remove(name: String) {
            values.remove(name)
        }
        override fun contains(name: String): Boolean = name in values
    }

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
        override var cloudSyncMigratedAccount: String? = null
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
