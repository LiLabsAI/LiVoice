package com.aliahad.wovoice.account

import com.aliahad.wovoice.account.SecretNames.PENDING_AUTH_INTENT
import com.aliahad.wovoice.account.SecretNames.PENDING_PKCE_CREATED_AT
import com.aliahad.wovoice.account.SecretNames.PENDING_PKCE_STATE
import com.aliahad.wovoice.account.SecretNames.PENDING_PKCE_VERIFIER
import com.aliahad.wovoice.account.SecretNames.RECOVERY_SECRET
import com.aliahad.wovoice.account.SecretNames.REFRESH_TOKEN
import com.aliahad.wovoice.account.SecretNames.VAULT_KEY
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

class SessionManager internal constructor(
    private val secrets: SecretsVault,
    private val settings: AccountSettings,
    private val client: AccountApi,
    private val deviceName: () -> String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : SyncAccount {
    private val refreshMutex = Mutex()
    private val sessionStateLock = Any()
    private val sessionGeneration = AtomicLong(0L)

    @Volatile private var accessToken: String? = null
    @Volatile private var accessExpiresAtMs: Long = 0
    @Volatile private var quota: AccountQuota? = null
    @Volatile private var user: AccountUser? = storedUser()

    val signedIn: Boolean get() = settings.isSignedIn()
    override val accountId: String? get() = settings.accountId
    val email: String? get() = settings.accountEmail
    val currentQuota: AccountQuota? get() = quota
    val currentUser: AccountUser? get() = user
    val role: AccountRole get() = user?.role ?: AccountRole.fromWire(settings.accountRole)
    val accountStatus: AccountStatus get() = user?.accountStatus ?: storedStatus()
    override val cloudServicesAllowed: Boolean get() = signedIn && !accountStatus.state.restricted
    override val restrictionMessage: String get() = accountStatus.publicMessage
        ?: "This account cannot use cloud sync right now. Contact ${accountStatus.supportEmail} for help."

    override fun markRestricted(code: String, message: String, suspendedUntilMs: Long?) {
        synchronized(sessionStateLock) {
            markRestrictedLocked(code, message, suspendedUntilMs)
        }
    }

    fun prepareLogin(intent: String = AUTH_LOGIN): PkceRequest = Pkce.create().also { request ->
        require(intent == AUTH_LOGIN || intent == AUTH_DELETE)
        secrets.putString(PENDING_PKCE_VERIFIER, request.verifier)
        secrets.putString(PENDING_PKCE_STATE, request.state)
        secrets.putString(PENDING_AUTH_INTENT, intent)
        secrets.putString(PENDING_PKCE_CREATED_AT, clock().toString())
    }

    fun pendingLoginMatches(state: String): Boolean {
        if (isPendingLoginExpired()) {
            clearPendingLogin()
            return false
        }
        val expected = secrets.getString(PENDING_PKCE_STATE) ?: return false
        if (expected.length != state.length) return false
        var difference = 0
        expected.indices.forEach { index -> difference = difference or (expected[index].code xor state[index].code) }
        return difference == 0
    }

    suspend fun completeLogin(code: String, state: String): AccountResult<AccountUser> {
        if (!pendingLoginMatches(state) || secrets.getString(PENDING_AUTH_INTENT) != AUTH_LOGIN) {
            clearPendingLogin()
            return AccountResult.Error("AUTH_REQUIRED", "The sign-in response could not be verified.", false, 401)
        }
        val verifier = secrets.getString(PENDING_PKCE_VERIFIER)
        clearPendingLogin()
        if (verifier.isNullOrBlank()) {
            return AccountResult.Error("AUTH_REQUIRED", "The sign-in request expired. Please start again.", false, 401)
        }
        return when (val result = withContext(ioDispatcher) { client.exchangeAuthorizationCode(code, verifier, deviceName()) }) {
            is AccountResult.Success -> {
                synchronized(sessionStateLock) {
                    sessionGeneration.incrementAndGet()
                    quota = null
                    installLocked(result.value)
                }
                AccountResult.Success(result.value.user)
            }
            is AccountResult.Error -> result
        }
    }

    suspend fun completeAccountDeletion(reauthToken: String, state: String): AccountResult<Unit> {
        if (!pendingLoginMatches(state) || secrets.getString(PENDING_AUTH_INTENT) != AUTH_DELETE) {
            clearPendingLogin()
            return AccountResult.Error("AUTH_REQUIRED", "The deletion verification could not be verified.", false, 401)
        }
        val verifier = secrets.getString(PENDING_PKCE_VERIFIER)
        clearPendingLogin()
        if (verifier.isNullOrBlank()) {
            return AccountResult.Error("AUTH_REQUIRED", "The deletion verification expired. Please start again.", false, 401)
        }
        val generation = sessionGeneration.get()
        return when (val result = callWithRefresh(generation) { access -> client.deleteAccount(access, reauthToken, verifier) }) {
            is AccountResult.Success -> {
                clearLocalSession()
                result
            }
            is AccountResult.Error -> result
        }
    }

    override suspend fun validAccessToken(): AccountResult<String> {
        val current = accessToken
        if (!current.isNullOrBlank() && clock() < accessExpiresAtMs - EXPIRY_SKEW_MS) {
            return AccountResult.Success(current)
        }
        return refreshMutex.withLock {
            val checked = accessToken
            if (!checked.isNullOrBlank() && clock() < accessExpiresAtMs - EXPIRY_SKEW_MS) {
                return@withLock AccountResult.Success(checked)
            }
            refreshLocked()
        }
    }

    override suspend fun refreshAfterRejected(rejectedToken: String): AccountResult<String> = refreshMutex.withLock {
        val current = accessToken
        if (!current.isNullOrBlank() && current != rejectedToken && clock() < accessExpiresAtMs) {
            AccountResult.Success(current)
        } else refreshLocked()
    }

    suspend fun loadProfile(): AccountResult<AccountProfile> {
        val generation = sessionGeneration.get()
        return when (val result = callWithRefresh(generation) { token -> client.profile(token) }) {
            is AccountResult.Success -> {
                synchronized(sessionStateLock) {
                    if (sessionGeneration.get() != generation) {
                        return AccountResult.Error("AUTH_REQUIRED", "Your session ended.", false, 401)
                    }
                    installUserLocked(result.value.user)
                    quota = result.value.quota
                }
                result
            }
            is AccountResult.Error -> result
        }
    }

    suspend fun listSessions(): AccountResult<List<DeviceSession>> =
        callWithRefresh { token -> client.sessions(token) }

    suspend fun revokeSession(sessionId: String): AccountResult<Unit> =
        callWithRefresh { token -> client.revokeSession(token, sessionId) }

    /**
     * Revokes the session server-side when possible and always clears the
     * local session. Returns false when the server could not be reached — the
     * refresh token then remains valid there with no local copy to revoke it,
     * which callers should surface instead of swallowing.
     */
    suspend fun logout(): Boolean {
        return try {
            val result = callWithRefresh { token -> client.logout(token) }
            result is AccountResult.Success
        } finally {
            clearLocalSession()
        }
    }

    fun clearLocalSession() {
        synchronized(sessionStateLock) {
            clearLocalSessionLocked()
        }
    }

    private suspend fun <T> callWithRefresh(
        generation: Long = sessionGeneration.get(),
        request: (String) -> AccountResult<T>,
    ): AccountResult<T> {
        val token = when (val auth = validAccessToken()) {
            is AccountResult.Success -> auth.value
            is AccountResult.Error -> return auth
        }
        if (sessionGeneration.get() != generation) {
            return AccountResult.Error("AUTH_REQUIRED", "Your session ended.", false, 401)
        }
        val first = withContext(ioDispatcher) { request(token) }
        val finalResult = if (first is AccountResult.Error && first.code == "TOKEN_EXPIRED") {
            when (val refreshed = refreshAfterRejected(token)) {
                is AccountResult.Success -> {
                    if (sessionGeneration.get() != generation) {
                        return AccountResult.Error("AUTH_REQUIRED", "Your session ended.", false, 401)
                    }
                    withContext(ioDispatcher) { request(refreshed.value) }
                }
                is AccountResult.Error -> refreshed
            }
        } else first
        if (finalResult is AccountResult.Error &&
            (finalResult.code == "ACCOUNT_SUSPENDED" || finalResult.code == "ACCOUNT_BANNED")
        ) {
            synchronized(sessionStateLock) {
                if (sessionGeneration.get() == generation) {
                    markRestrictedLocked(finalResult.code, finalResult.message)
                }
            }
        }
        return finalResult
    }

    private suspend fun refreshLocked(): AccountResult<String> {
        val (generation, refreshToken) = synchronized(sessionStateLock) {
            val gen = sessionGeneration.get()
            val stored = secrets.getString(REFRESH_TOKEN)
            if (stored.isNullOrBlank()) {
                clearLocalSessionLocked()
            }
            gen to stored
        }
        if (refreshToken.isNullOrBlank()) {
            return AccountResult.Error("AUTH_REQUIRED", "Sign in to use voice input.", false, 401)
        }
        return when (val result = withContext(ioDispatcher) { client.refresh(refreshToken) }) {
            is AccountResult.Success -> synchronized(sessionStateLock) {
                if (sessionGeneration.get() != generation) {
                    AccountResult.Error("AUTH_REQUIRED", "Your session ended.", false, 401)
                } else {
                    installLocked(result.value)
                    AccountResult.Success(result.value.accessToken)
                }
            }
            is AccountResult.Error -> synchronized(sessionStateLock) {
                if (sessionGeneration.get() == generation) {
                    if (result.code == "AUTH_REQUIRED") clearLocalSessionLocked()
                    else if (result.code == "ACCOUNT_SUSPENDED" || result.code == "ACCOUNT_BANNED") {
                        markRestrictedLocked(result.code, result.message)
                    }
                }
                result
            }
        }
    }

    private fun clearLocalSessionLocked() {
        sessionGeneration.incrementAndGet()
        accessToken = null
        accessExpiresAtMs = 0
        quota = null
        user = null
        secrets.remove(REFRESH_TOKEN)
        clearPendingLogin()
        secrets.remove(VAULT_KEY)
        secrets.remove(RECOVERY_SECRET)
        settings.clearAccount()
    }

    private fun markRestrictedLocked(code: String, message: String, suspendedUntilMs: Long? = null) {
        val state = when (code) {
            "ACCOUNT_SUSPENDED" -> AccountState.SUSPENDED
            "ACCOUNT_BANNED" -> AccountState.BANNED
            else -> return
        }
        val current = user ?: storedUser() ?: return
        installUserLocked(
            current.copy(
                accountStatus = AccountStatus(
                    state = state,
                    suspendedUntilMs = if (state == AccountState.SUSPENDED) suspendedUntilMs else null,
                    publicMessage = message.trim().takeIf(String::isNotBlank),
                    supportEmail = current.accountStatus.supportEmail,
                ),
            ),
        )
    }

    private fun installLocked(tokens: SessionTokens) {
        accessToken = tokens.accessToken
        // Clamp the TTL: a hostile or buggy value must not overflow into a
        // negative expiry (perpetual refresh) or lock a token in for years.
        accessExpiresAtMs = clock() +
            tokens.accessExpiresInSeconds.coerceIn(0L, MAX_ACCESS_TTL_SECONDS) * 1_000L
        secrets.putString(REFRESH_TOKEN, tokens.refreshToken)
        settings.accountId = tokens.user.id
        installUserLocked(tokens.user)
    }

    private fun installUserLocked(value: AccountUser) {
        user = value
        settings.accountId = value.id
        settings.accountEmail = value.email
        settings.accountRole = value.role.name.lowercase()
        settings.accountState = value.accountStatus.state.name.lowercase()
        settings.accountSuspendedUntilMs = value.accountStatus.suspendedUntilMs
        settings.accountPublicMessage = value.accountStatus.publicMessage
        settings.accountSupportEmail = value.accountStatus.supportEmail
    }

    private fun storedUser(): AccountUser? {
        val id = settings.accountId ?: return null
        val email = settings.accountEmail ?: return null
        return AccountUser(
            id = id,
            email = email,
            vaultConfigured = false,
            vaultKeyVersion = null,
            role = AccountRole.fromWire(settings.accountRole),
            accountStatus = storedStatus(),
        )
    }

    private fun storedStatus(): AccountStatus = AccountStatus(
        state = AccountState.fromWire(settings.accountState),
        suspendedUntilMs = settings.accountSuspendedUntilMs,
        publicMessage = settings.accountPublicMessage,
        supportEmail = settings.accountSupportEmail,
    )

    private fun isPendingLoginExpired(): Boolean {
        val createdAt = secrets.getString(PENDING_PKCE_CREATED_AT)?.toLongOrNull() ?: return true
        val elapsed = clock() - createdAt
        return elapsed > PENDING_PKCE_TTL_MS || elapsed < -CLOCK_SKEW_MS
    }

    private fun clearPendingLogin() {
        secrets.remove(PENDING_PKCE_VERIFIER)
        secrets.remove(PENDING_PKCE_STATE)
        secrets.remove(PENDING_AUTH_INTENT)
        secrets.remove(PENDING_PKCE_CREATED_AT)
    }

    companion object {
        const val AUTH_LOGIN = "login"
        const val AUTH_DELETE = "delete"
        internal const val PENDING_PKCE_TTL_MS = 15 * 60_000L
        private const val CLOCK_SKEW_MS = 60_000L
        private const val EXPIRY_SKEW_MS = 30_000L

        // The server issues 15-minute access tokens; anything claiming longer
        // than a day is a hostile or broken response.
        private const val MAX_ACCESS_TTL_SECONDS = 24 * 60 * 60L

        @Volatile private var instance: SessionManager? = null

        /**
         * Idempotent singleton. Each platform supplies its own secure storage and
         * identity: Android wires Keystore-backed stores, macOS wires the Keychain
         * vault, and both pass their own worker-URL and device-name providers.
         */
        fun get(
            settings: AccountSettings,
            secrets: SecretsVault,
            baseUrlProvider: () -> String,
            deviceNameProvider: () -> String,
        ): SessionManager = instance ?: synchronized(this) {
            instance ?: SessionManager(
                secrets = secrets,
                settings = settings,
                client = AccountClient(baseUrlProvider),
                deviceName = deviceNameProvider,
            ).also { instance = it }
        }
    }
}
