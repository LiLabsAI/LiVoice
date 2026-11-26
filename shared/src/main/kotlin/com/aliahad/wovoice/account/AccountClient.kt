package com.aliahad.wovoice.account

import kotlinx.coroutines.CancellationException
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder

data class AccountUser(
    val id: String,
    val email: String,
    val vaultConfigured: Boolean,
    val vaultKeyVersion: Int?,
    val role: AccountRole = AccountRole.USER,
    val accountStatus: AccountStatus = AccountStatus(),
)

enum class AccountRole {
    USER,
    ADMIN;

    companion object {
        fun fromWire(value: String?): AccountRole = if (value.equals("admin", ignoreCase = true)) ADMIN else USER
    }
}

enum class AccountState {
    ACTIVE,
    SUSPENDED,
    BANNED;

    val restricted: Boolean get() = this != ACTIVE

    companion object {
        fun fromWire(value: String?): AccountState = when (value?.lowercase()) {
            "suspended" -> SUSPENDED
            "banned" -> BANNED
            else -> ACTIVE
        }
    }
}

data class AccountStatus(
    val state: AccountState = AccountState.ACTIVE,
    val suspendedUntilMs: Long? = null,
    val publicMessage: String? = null,
    val supportEmail: String = "support@aliahad.com",
)

data class AccountQuota(
    val limitAudioSeconds: Double,
    val usedAudioSeconds: Double,
    val reservedAudioSeconds: Double,
    val remainingAudioSeconds: Double,
    val resetAtMs: Long,
    val overrideExpiresAtMs: Long? = null,
)

data class AccountProfile(val user: AccountUser, val quota: AccountQuota?)

data class SessionTokens(
    val accessToken: String,
    val accessExpiresInSeconds: Long,
    val refreshToken: String,
    val refreshExpiresInSeconds: Long,
    val user: AccountUser,
)

data class DeviceSession(
    val id: String,
    val deviceName: String,
    val createdAtMs: Long,
    val lastSeenAtMs: Long,
    val current: Boolean,
)

sealed interface AccountResult<out T> {
    data class Success<T>(val value: T) : AccountResult<T>
    data class Error(
        val code: String,
        val message: String,
        val retryable: Boolean,
        val status: Int,
        val payload: String? = null,
    ) : AccountResult<Nothing>
}

interface AccountApi {
    fun exchangeAuthorizationCode(code: String, verifier: String, deviceName: String): AccountResult<SessionTokens>
    fun refresh(refreshToken: String): AccountResult<SessionTokens>
    fun profile(accessToken: String): AccountResult<AccountProfile>
    fun sessions(accessToken: String): AccountResult<List<DeviceSession>>
    fun revokeSession(accessToken: String, sessionId: String): AccountResult<Unit>
    fun logout(accessToken: String): AccountResult<Unit>
    fun deleteAccount(accessToken: String, reauthToken: String, codeVerifier: String): AccountResult<Unit>
}

class AccountClient(private val baseUrlProvider: () -> String) : AccountApi {
    override fun exchangeAuthorizationCode(code: String, verifier: String, deviceName: String): AccountResult<SessionTokens> =
        post(
            "/v1/auth/token",
            JSONObject()
                .put("grantType", "authorization_code")
                .put("code", code)
                .put("codeVerifier", verifier)
                .put("deviceName", deviceName),
        ) { parseTokens(it) }

    override fun refresh(refreshToken: String): AccountResult<SessionTokens> = post(
        "/v1/auth/refresh",
        JSONObject().put("refreshToken", refreshToken),
    ) { parseTokens(it) }

    override fun profile(accessToken: String): AccountResult<AccountProfile> = request(
        path = "/v1/me",
        method = "GET",
        accessToken = accessToken,
    ) { json ->
        AccountProfile(
            user = parseUser(json.getJSONObject("user")),
            quota = json.optJSONObject("quota")?.let(::parseQuota),
        )
    }

    override fun sessions(accessToken: String): AccountResult<List<DeviceSession>> = request(
        path = "/v1/me/sessions",
        method = "GET",
        accessToken = accessToken,
    ) { json ->
        val values = json.getJSONArray("sessions")
        buildList(values.length()) {
            repeat(values.length()) { index ->
                val value = values.getJSONObject(index)
                add(
                    DeviceSession(
                        id = value.getString("id"),
                        deviceName = value.optString("deviceName").takeIf(String::isNotBlank) ?: "LiVoice device",
                        createdAtMs = value.optLong("createdAt"),
                        lastSeenAtMs = value.optLong("lastSeenAt"),
                        current = value.optInt("current") == 1 || value.optBoolean("current"),
                    ),
                )
            }
        }
    }

    override fun revokeSession(accessToken: String, sessionId: String): AccountResult<Unit> = request(
        path = "/v1/me/sessions/${encodePathSegment(sessionId)}",
        method = "DELETE",
        accessToken = accessToken,
        acceptsEmpty = true,
    ) { Unit }

    override fun logout(accessToken: String): AccountResult<Unit> = request(
        path = "/v1/auth/logout",
        method = "POST",
        accessToken = accessToken,
        body = JSONObject(),
        acceptsEmpty = true,
    ) { Unit }

    override fun deleteAccount(accessToken: String, reauthToken: String, codeVerifier: String): AccountResult<Unit> = request(
        path = "/v1/me",
        method = "DELETE",
        accessToken = accessToken,
        body = JSONObject()
            .put("reauthToken", reauthToken)
            .put("codeVerifier", codeVerifier),
        acceptsEmpty = true,
    ) { Unit }

    private fun <T> post(path: String, body: JSONObject, parser: (JSONObject) -> T): AccountResult<T> =
        request(path, "POST", body = body, parser = parser)

    private fun <T> request(
        path: String,
        method: String,
        accessToken: String? = null,
        body: JSONObject? = null,
        acceptsEmpty: Boolean = false,
        parser: (JSONObject) -> T,
    ): AccountResult<T> {
        val endpoint = endpoint(path) ?: return AccountResult.Error(
            "INVALID_REQUEST",
            "LiVoice has an invalid service address.",
            false,
            0,
        )
        var connection: HttpURLConnection? = null
        var responseStatus = 0
        return try {
            connection = endpoint.openConnection() as HttpURLConnection
            connection.requestMethod = method
            connection.connectTimeout = 15_000
            connection.readTimeout = 25_000
            connection.useCaches = false
            connection.setRequestProperty("Accept", "application/json")
            if (!accessToken.isNullOrBlank()) connection.setRequestProperty("Authorization", "Bearer $accessToken")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            responseStatus = status
            val response = readResponse(connection, status)
            if (status in 200..299) {
                if (acceptsEmpty && response.isBlank()) AccountResult.Success(parser(JSONObject()))
                else AccountResult.Success(parser(JSONObject(response)))
            } else parseError(response, status)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: ResponseTooLargeException) {
            AccountResult.Error(
                "INVALID_RESPONSE",
                "LiVoice returned an unexpectedly large response.",
                false,
                responseStatus,
            )
        } catch (_: JSONException) {
            AccountResult.Error(
                "INVALID_RESPONSE",
                "LiVoice returned an unreadable response.",
                false,
                responseStatus,
            )
        } catch (_: IOException) {
            AccountResult.Error(
                "NETWORK_UNAVAILABLE",
                "Could not reach LiVoice. Check your connection and try again.",
                true,
                0,
            )
        } catch (_: Exception) {
            AccountResult.Error(
                "INTERNAL_ERROR",
                "LiVoice could not complete the request.",
                false,
                responseStatus,
            )
        } finally {
            connection?.disconnect()
        }
    }

    private fun parseTokens(json: JSONObject): SessionTokens = SessionTokens(
        accessToken = json.getString("accessToken"),
        accessExpiresInSeconds = json.getLong("accessExpiresIn"),
        refreshToken = json.getString("refreshToken"),
        refreshExpiresInSeconds = json.getLong("refreshExpiresIn"),
        user = parseUser(json.getJSONObject("user")),
    )

    private fun parseUser(json: JSONObject) = AccountUser(
        id = json.getString("id"),
        email = json.getString("email"),
        vaultConfigured = json.optBoolean("vaultConfigured"),
        vaultKeyVersion = if (json.isNull("vaultKeyVersion")) null else json.optInt("vaultKeyVersion"),
        role = AccountRole.fromWire(json.optString("role")),
        accountStatus = json.optJSONObject("accountStatus")?.let { status ->
            AccountStatus(
                state = AccountState.fromWire(status.optString("state")),
                suspendedUntilMs = status.optNullableLong("suspendedUntil"),
                publicMessage = status.optNullableString("publicMessage"),
                supportEmail = status.optString("supportEmail")
                    .takeIf(String::isNotBlank)
                    ?: "support@aliahad.com",
            )
        } ?: AccountStatus(),
    )

    private fun parseQuota(json: JSONObject) = AccountQuota(
        // Defaulted reads: an absent key must not become NaN and poison every
        // downstream quota display.
        limitAudioSeconds = json.optDouble("limitAudioSeconds", 0.0),
        usedAudioSeconds = json.optDouble("usedAudioSeconds", 0.0),
        reservedAudioSeconds = json.optDouble("reservedAudioSeconds", 0.0),
        remainingAudioSeconds = json.optDouble("remainingAudioSeconds", 0.0),
        resetAtMs = json.optLong("resetAt"),
        overrideExpiresAtMs = json.optNullableLong("overrideExpiresAt"),
    )

    private fun parseError(body: String, status: Int): AccountResult.Error = runCatching {
        val error = JSONObject(body).optJSONObject("error")
        AccountResult.Error(
            code = error?.optString("code")?.takeIf(String::isNotBlank) ?: "HTTP_$status",
            message = error?.optString("message")?.takeIf(String::isNotBlank) ?: "LiVoice request failed ($status).",
            retryable = error?.optBoolean("retryable", status >= 500) ?: (status >= 500),
            status = status,
            payload = body,
        )
    }.getOrElse { AccountResult.Error("HTTP_$status", "LiVoice request failed ($status).", status >= 500, status) }

    private fun readResponse(connection: HttpURLConnection, status: Int): String {
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        stream ?: return ""
        return BufferedInputStream(stream).reader(Charsets.UTF_8).use { reader ->
            val output = StringBuilder()
            val buffer = CharArray(4_096)
            while (true) {
                val count = reader.read(buffer, 0, buffer.size)
                if (count < 0) break
                if (output.length + count > MAX_RESPONSE_CHARS) {
                    throw ResponseTooLargeException("Response exceeds $MAX_RESPONSE_CHARS characters")
                }
                output.append(buffer, 0, count)
            }
            output.toString()
        }
    }

    private fun endpoint(path: String): URL? = runCatching {
        val base = URI(baseUrlProvider().trim().trimEnd('/'))
        require(base.scheme.equals("https", true) && !base.host.isNullOrBlank() && path.startsWith('/'))
        URI("${base.toASCIIString()}$path").toURL()
    }.getOrNull()

    private class ResponseTooLargeException(message: String) : IOException(message)

    internal companion object {
        const val MAX_RESPONSE_CHARS = 256_000

        fun encodePathSegment(segment: String): String =
            URLEncoder.encode(segment, Charsets.UTF_8.name()).replace("+", "%20")
    }
}

private fun JSONObject.optNullableLong(key: String): Long? =
    if (!has(key) || isNull(key)) null else optLong(key).takeIf { it > 0L }

private fun JSONObject.optNullableString(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key).trim().takeIf(String::isNotBlank)
