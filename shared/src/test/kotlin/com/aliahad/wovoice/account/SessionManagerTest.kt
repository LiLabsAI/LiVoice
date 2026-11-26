package com.aliahad.wovoice.account

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionManagerTest {

    @Test
    fun signOutDuringInFlightRefreshDoesNotResurrectSession() = runBlocking {
        var now = 1_000_000L
        val secrets = MemorySecrets().apply { putString(SecretNames.REFRESH_TOKEN, "refresh-old") }
        val settings = FakeSettings()
        val refreshStarted = CompletableDeferred<Unit>()
        val allowRefreshReturn = CompletableDeferred<Unit>()

        val api = object : StubAccountApi() {
            override fun refresh(refreshToken: String): AccountResult<SessionTokens> {
                refreshStarted.complete(Unit)
                runBlocking { allowRefreshReturn.await() }
                return AccountResult.Success(tokens("access-new", "refresh-new"))
            }
        }
        val manager = SessionManager(secrets, settings, api, { "Pixel" }, clock = { now }, ioDispatcher = Dispatchers.IO)

        val refreshCall = async(Dispatchers.Default) { manager.validAccessToken() }
        refreshStarted.await()
        manager.clearLocalSession()
        allowRefreshReturn.complete(Unit)

        val result = refreshCall.await()
        assertTrue(result is AccountResult.Error)
        assertEquals("AUTH_REQUIRED", (result as AccountResult.Error).code)
        assertFalse(manager.signedIn)
        assertNull(secrets.getString(SecretNames.REFRESH_TOKEN))
        assertNull(settings.accountId)
    }

    @Test
    fun signOutDuringInFlightProfileDoesNotResurrectUser() = runBlocking {
        var now = 1_000_000L
        val secrets = MemorySecrets()
        val settings = FakeSettings()
        val profileStarted = CompletableDeferred<Unit>()
        val allowProfileReturn = CompletableDeferred<Unit>()

        val api = object : StubAccountApi() {
            override fun exchangeAuthorizationCode(code: String, verifier: String, deviceName: String) =
                AccountResult.Success(tokens("access-1", "refresh-1"))

            override fun profile(accessToken: String): AccountResult<AccountProfile> {
                profileStarted.complete(Unit)
                runBlocking { allowProfileReturn.await() }
                return AccountResult.Success(AccountProfile(testUser(), quota = null))
            }
        }
        val manager = SessionManager(secrets, settings, api, { "Pixel" }, clock = { now }, ioDispatcher = Dispatchers.IO)
        val pkce = manager.prepareLogin()
        assertTrue(manager.completeLogin("code-1", pkce.state) is AccountResult.Success)

        val profileCall = async(Dispatchers.Default) { manager.loadProfile() }
        profileStarted.await()
        manager.clearLocalSession()
        allowProfileReturn.complete(Unit)

        val result = profileCall.await()
        assertTrue(result is AccountResult.Error)
        assertEquals("AUTH_REQUIRED", (result as AccountResult.Error).code)
        assertFalse(manager.signedIn)
        assertNull(manager.currentUser)
        assertNull(settings.accountEmail)
    }

    @Test
    fun listSessionsRevokeAndDeleteRetryAfterTokenExpired() = runBlocking {
        var now = 1_000_000L
        val secrets = MemorySecrets()
        val settings = FakeSettings()
        var refreshCount = 0
        var deletedVerifier: String? = null
        var deletedReauth: String? = null

        val api = object : StubAccountApi() {
            override fun exchangeAuthorizationCode(code: String, verifier: String, deviceName: String) =
                AccountResult.Success(tokens("expired-1", "refresh-1"))

            override fun refresh(refreshToken: String): AccountResult<SessionTokens> {
                refreshCount++
                return AccountResult.Success(tokens("fresh-$refreshCount", "refresh-${refreshCount + 1}"))
            }

            override fun sessions(accessToken: String): AccountResult<List<DeviceSession>> =
                if (accessToken == "expired-1") tokenExpired()
                else AccountResult.Success(listOf(DeviceSession("sess/1", "Pixel", 1L, 2L, true)))

            override fun revokeSession(accessToken: String, sessionId: String): AccountResult<Unit> =
                if (accessToken == "fresh-1") tokenExpired() else AccountResult.Success(Unit)

            override fun deleteAccount(accessToken: String, reauthToken: String, codeVerifier: String): AccountResult<Unit> {
                if (accessToken == "fresh-2") return tokenExpired()
                deletedReauth = reauthToken
                deletedVerifier = codeVerifier
                return AccountResult.Success(Unit)
            }
        }

        val manager = SessionManager(secrets, settings, api, { "Pixel" }, clock = { now }, ioDispatcher = Dispatchers.Unconfined)
        val loginPkce = manager.prepareLogin(SessionManager.AUTH_LOGIN)
        assertTrue(manager.completeLogin("code-1", loginPkce.state) is AccountResult.Success)

        val sessions = manager.listSessions()
        assertTrue(sessions is AccountResult.Success)
        assertEquals(1, (sessions as AccountResult.Success).value.size)

        val revoked = manager.revokeSession("sess/1")
        assertTrue(revoked is AccountResult.Success)

        val deletePkce = manager.prepareLogin(SessionManager.AUTH_DELETE)
        val deleted = manager.completeAccountDeletion("reauth-xyz", deletePkce.state)
        assertTrue(deleted is AccountResult.Success)
        assertEquals("reauth-xyz", deletedReauth)
        assertEquals(deletePkce.verifier, deletedVerifier)
        assertEquals(3, refreshCount)
        assertFalse(manager.signedIn)
        assertNull(secrets.getString(SecretNames.REFRESH_TOKEN))
    }

    @Test
    fun pendingPkceStateExpiresAfterFifteenMinutes() = runBlocking {
        var now = 10_000_000L
        val secrets = MemorySecrets()
        val settings = FakeSettings()
        val manager = SessionManager(secrets, settings, StubAccountApi(), { "Pixel" }, clock = { now }, ioDispatcher = Dispatchers.Unconfined)

        val pkce = manager.prepareLogin(SessionManager.AUTH_LOGIN)
        assertTrue(manager.pendingLoginMatches(pkce.state))

        now += SessionManager.PENDING_PKCE_TTL_MS + 1L
        assertFalse(manager.pendingLoginMatches(pkce.state))
        assertNull(secrets.getString(SecretNames.PENDING_PKCE_VERIFIER))
        assertNull(secrets.getString(SecretNames.PENDING_PKCE_STATE))
        assertNull(secrets.getString(SecretNames.PENDING_PKCE_CREATED_AT))
    }

    @Test
    fun encodePathSegmentEncodesSlashesAndSpaces() {
        assertEquals("abc%2Fdef%20123", AccountClient.encodePathSegment("abc/def 123"))
    }

    private fun testUser() = AccountUser(
        id = "user-1",
        email = "user@example.com",
        vaultConfigured = false,
        vaultKeyVersion = null,
    )

    private fun tokens(access: String, refresh: String) = SessionTokens(
        accessToken = access,
        accessExpiresInSeconds = 900L,
        refreshToken = refresh,
        refreshExpiresInSeconds = 2_592_000L,
        user = testUser(),
    )

    private fun tokenExpired() = AccountResult.Error("TOKEN_EXPIRED", "Expired", false, 401)

    private open class StubAccountApi : AccountApi {
        override fun exchangeAuthorizationCode(code: String, verifier: String, deviceName: String): AccountResult<SessionTokens> =
            AccountResult.Error("NOT_IMPLEMENTED", "stub", false, 500)
        override fun refresh(refreshToken: String): AccountResult<SessionTokens> =
            AccountResult.Error("NOT_IMPLEMENTED", "stub", false, 500)
        override fun profile(accessToken: String): AccountResult<AccountProfile> =
            AccountResult.Error("NOT_IMPLEMENTED", "stub", false, 500)
        override fun sessions(accessToken: String): AccountResult<List<DeviceSession>> =
            AccountResult.Error("NOT_IMPLEMENTED", "stub", false, 500)
        override fun revokeSession(accessToken: String, sessionId: String): AccountResult<Unit> =
            AccountResult.Error("NOT_IMPLEMENTED", "stub", false, 500)
        override fun logout(accessToken: String): AccountResult<Unit> = AccountResult.Success(Unit)
        override fun deleteAccount(accessToken: String, reauthToken: String, codeVerifier: String): AccountResult<Unit> =
            AccountResult.Success(Unit)
    }

    private class MemorySecrets : SecretsVault {
        private val map = mutableMapOf<String, String>()
        override fun putString(name: String, value: String?) {
            if (value == null) map.remove(name) else map[name] = value
        }
        override fun getString(name: String): String? = map[name]
        override fun remove(name: String) { map.remove(name) }
        override fun contains(name: String): Boolean = !map[name].isNullOrBlank()
    }

    private class FakeSettings : AccountSettings {
        override var accountId: String? = "user-1"
        override val lastAccountId: String? = "user-1"
        override var accountEmail: String? = "user@example.com"
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
        override fun isSignedIn(): Boolean = !accountId.isNullOrBlank()
        override fun clearAccount() {
            accountId = null
            accountEmail = null
        }
    }
}
