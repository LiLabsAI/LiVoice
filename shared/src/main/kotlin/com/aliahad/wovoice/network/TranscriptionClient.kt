package com.aliahad.wovoice.network

import com.aliahad.wovoice.context.DictationContext
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import java.util.Collections
import java.util.IdentityHashMap
import java.util.Locale
import java.util.UUID

class TranscriptionClient internal constructor(
    private val allowInsecureHttpForTests: Boolean,
) {
    constructor() : this(allowInsecureHttpForTests = false)

    private val connectionLock = Any()
    private val activeConnections: MutableSet<HttpURLConnection> =
        Collections.newSetFromMap(IdentityHashMap())
    private val cancelledConnections: MutableSet<HttpURLConnection> =
        Collections.newSetFromMap(IdentityHashMap())

    sealed interface Result {
        data class Success(
            val text: String,
            val rawText: String,
            val polished: Boolean,
            val model: String,
            val requestId: String = "",
            val timings: Timings = Timings(),
            val usage: Usage? = null,
        ) : Result

        data class Error(
            val message: String,
            val retryable: Boolean,
            val code: String = "UNKNOWN",
            val status: Int = 0,
        ) : Result
    }

    data class Timings(
        val asr: Long = 0,
        val polish: Long = 0,
        val total: Long = 0,
    )

    data class Usage(
        val pricingVersion: String,
        val audioSeconds: Double,
        val inputTokens: Long,
        val outputTokens: Long,
        val asrNeurons: Double,
        val polishNeurons: Double,
        val totalNeurons: Double,
        val estimatedCostUsd: Double,
    )

    fun testConnection(baseUrl: String, token: String): Result {
        val url = endpoint(baseUrl, "/v1/health") ?: return invalidUrl()
        val connection = open(url, token, "GET") ?: return invalidUrl()
        return execute(connection) { responseCode, body ->
            if (responseCode in 200..299 && JSONObject(body).optBoolean("ok")) {
                Result.Success("Connected", "", false, "")
            } else parseError(body, responseCode)
        }
    }

    fun transcribe(
        baseUrl: String,
        token: String,
        audioFile: File,
        sentenceStart: Boolean,
        glossary: List<String>,
        context: DictationContext? = null,
        locale: String = DEFAULT_LOCALE,
        polish: String = DEFAULT_POLISH,
    ): Result {
        val url = endpoint(baseUrl, "/v1/transcriptions") ?: return invalidUrl()
        val boundary = "LiVoice-${UUID.randomUUID()}"
        val connection = open(url, token, "POST") ?: return invalidUrl()
        connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        connection.doOutput = true
        connection.setChunkedStreamingMode(32 * 1024)

        var responseCode = 0
        register(connection)
        return try {
            BufferedOutputStream(connection.outputStream).use { output ->
                writeTextPart(
                    output,
                    boundary,
                    "options",
                    buildOptionsJson(
                        sentenceStart = sentenceStart,
                        glossary = glossary,
                        context = context,
                        locale = locale,
                        polish = polish,
                    ).toString(),
                )
                output.write("--$boundary\r\n".toByteArray())
                output.write("Content-Disposition: form-data; name=\"audio\"; filename=\"voice.wav\"\r\n".toByteArray())
                output.write("Content-Type: audio/wav\r\n\r\n".toByteArray())
                audioFile.inputStream().buffered().use { it.copyTo(output) }
                output.write("\r\n--$boundary--\r\n".toByteArray())
            }
            val code = connection.responseCode
            responseCode = code
            val body = readResponse(connection, code)
            if (code in 200..299) {
                val json = JSONObject(body)
                val text = json.optString("text").trim()
                if (text.isEmpty()) Result.Error("The transcription was empty. Please try again.", true, "EMPTY_RESULT", code)
                else Result.Success(
                    text = text,
                    rawText = json.optString("rawText"),
                    polished = json.optBoolean("polished"),
                    model = json.optString("asrModel"),
                    requestId = json.optString("requestId"),
                    timings = json.optJSONObject("timingsMs")?.let {
                        Timings(
                            asr = it.optLong("asr"),
                            polish = it.optLong("polish"),
                            total = it.optLong("total"),
                        )
                    } ?: Timings(),
                    usage = parseUsage(json.optJSONObject("usage")),
                )
            } else parseError(body, code)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: ResponseTooLargeException) {
            Result.Error("LiVoice returned an unexpectedly large response.", false, "INVALID_RESPONSE", responseCode)
        } catch (_: JSONException) {
            Result.Error("LiVoice returned an unreadable response.", false, "INVALID_RESPONSE", responseCode)
        } catch (_: SocketTimeoutException) {
            if (isCancelled(connection)) cancelledError()
            else Result.Error("LiVoice took too long to respond. Please try again.", true, "REQUEST_TIMEOUT", 0)
        } catch (_: IOException) {
            if (isCancelled(connection)) cancelledError()
            else Result.Error("Could not reach LiVoice. Check your connection and try again.", true, "NETWORK_UNAVAILABLE", 0)
        } catch (_: Exception) {
            if (isCancelled(connection)) cancelledError()
            else Result.Error("LiVoice could not complete the transcription.", false, "INTERNAL_ERROR", responseCode)
        } finally {
            unregister(connection)
            runCatching { connection.disconnect() }
        }
    }

    fun cancel() {
        val toDisconnect = synchronized(connectionLock) {
            val snapshot = activeConnections.toList()
            cancelledConnections.addAll(snapshot)
            activeConnections.clear()
            snapshot
        }
        toDisconnect.forEach { runCatching { it.disconnect() } }
    }

    private fun register(connection: HttpURLConnection) {
        synchronized(connectionLock) {
            activeConnections.add(connection)
        }
    }

    private fun unregister(connection: HttpURLConnection) {
        synchronized(connectionLock) {
            activeConnections.remove(connection)
            cancelledConnections.remove(connection)
        }
    }

    private fun isCancelled(connection: HttpURLConnection): Boolean = synchronized(connectionLock) {
        connection in cancelledConnections
    }

    private fun cancelledError() = Result.Error("Transcription was cancelled.", false, "CANCELLED", 0)

    private fun open(url: URL, token: String, method: String): HttpURLConnection? = runCatching {
        (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 35_000
            useCaches = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer $token")
        }
    }.getOrNull()

    private fun <T : Result> execute(
        connection: HttpURLConnection,
        block: (Int, String) -> T,
    ): Result {
        var responseCode = 0
        register(connection)
        return try {
            val code = connection.responseCode
            responseCode = code
            block(code, readResponse(connection, code))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: ResponseTooLargeException) {
            Result.Error("LiVoice returned an unexpectedly large response.", false, "INVALID_RESPONSE", responseCode)
        } catch (_: JSONException) {
            Result.Error("LiVoice returned an unreadable response.", false, "INVALID_RESPONSE", responseCode)
        } catch (_: SocketTimeoutException) {
            if (isCancelled(connection)) cancelledError()
            else Result.Error("LiVoice took too long to respond. Please try again.", true, "REQUEST_TIMEOUT", 0)
        } catch (_: IOException) {
            if (isCancelled(connection)) cancelledError()
            else Result.Error("Could not reach LiVoice. Check your connection and try again.", true, "NETWORK_UNAVAILABLE", 0)
        } catch (_: Exception) {
            if (isCancelled(connection)) cancelledError()
            else Result.Error("LiVoice could not complete the request.", false, "INTERNAL_ERROR", responseCode)
        } finally {
            unregister(connection)
            runCatching { connection.disconnect() }
        }
    }

    private fun readResponse(connection: HttpURLConnection, code: Int): String {
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        if (stream == null) return ""
        return BufferedInputStream(stream).reader(Charsets.UTF_8).use { reader ->
            val buffer = CharArray(8_192)
            val text = StringBuilder()
            while (true) {
                val count = reader.read(buffer, 0, buffer.size)
                if (count < 0) break
                if (text.length + count > MAX_RESPONSE_CHARS) {
                    throw ResponseTooLargeException("Response exceeds $MAX_RESPONSE_CHARS characters")
                }
                text.append(buffer, 0, count)
            }
            text.toString()
        }
    }

    private fun parseError(body: String, code: Int): Result.Error = runCatching {
        val error = JSONObject(body).optJSONObject("error")
        Result.Error(
            error?.optString("message")?.takeIf(String::isNotBlank) ?: "Transcription failed ($code).",
            error?.optBoolean("retryable", code >= 500) ?: (code >= 500),
            error?.optString("code")?.takeIf(String::isNotBlank) ?: "HTTP_$code",
            code,
        )
    }.getOrElse { Result.Error("Transcription failed ($code).", code >= 500, "HTTP_$code", code) }

    private fun parseUsage(value: JSONObject?): Usage? {
        value ?: return null
        if (!value.optBoolean("estimated") || value.optString("currency") != "USD") return null
        return runCatching {
            Usage(
                pricingVersion = value.getString("pricingVersion"),
                audioSeconds = value.getDouble("audioSeconds"),
                inputTokens = value.getLong("inputTokens"),
                outputTokens = value.getLong("outputTokens"),
                asrNeurons = value.getDouble("asrNeurons"),
                polishNeurons = value.getDouble("polishNeurons"),
                totalNeurons = value.getDouble("totalNeurons"),
                estimatedCostUsd = value.getDouble("estimatedCostUsd"),
            )
        }.getOrNull()
    }

    private fun writeTextPart(
        output: BufferedOutputStream,
        boundary: String,
        name: String,
        value: String,
    ) {
        output.write("--$boundary\r\n".toByteArray())
        output.write("Content-Disposition: form-data; name=\"$name\"\r\n".toByteArray())
        output.write("Content-Type: application/json; charset=utf-8\r\n\r\n".toByteArray())
        output.write(value.toByteArray(Charsets.UTF_8))
        output.write("\r\n".toByteArray())
    }

    private fun endpoint(baseUrl: String, path: String): URL? = runCatching {
        val base = URI(baseUrl.trim().trimEnd('/'))
        val validScheme = base.scheme.equals("https", ignoreCase = true) ||
            (allowInsecureHttpForTests && base.scheme.equals("http", ignoreCase = true))
        require(validScheme && !base.host.isNullOrBlank())
        URI("${base.toASCIIString()}$path").toURL()
    }.getOrNull()

    private fun invalidUrl() = Result.Error("LiVoice has an invalid service address.", false, "INVALID_URL")

    private class ResponseTooLargeException(message: String) : IOException(message)

    companion object {
        const val DEFAULT_LOCALE = "en-IN"
        const val DEFAULT_POLISH = "light"
        const val MAX_GLOSSARY_ITEMS = 100
        const val MAX_GLOSSARY_TERM_LENGTH = 80
        const val MAX_GLOSSARY_CHARS = 4_000
        private const val MAX_RESPONSE_CHARS = 1_000_000

        private val LOCALE_PATTERN = Regex("^[a-z]{2}(-[A-Z]{2})?$")
        private val ALLOWED_POLISH = setOf("off", "light", "strong")

        fun sanitizeLocale(locale: String?): String {
            val trimmed = locale?.trim().orEmpty()
            return if (trimmed == "auto" || LOCALE_PATTERN.matches(trimmed)) trimmed else DEFAULT_LOCALE
        }

        fun sanitizePolish(polish: String?): String {
            val trimmed = polish?.trim()?.lowercase(Locale.ROOT).orEmpty()
            return if (trimmed in ALLOWED_POLISH) trimmed else DEFAULT_POLISH
        }

        /**
         * Bounds glossary terms to match the Worker contract: at most 100 entries,
         * each 1..80 trimmed characters, case-insensitively deduplicated, and joined
         * with newlines to at most 4,000 characters.
         */
        fun boundGlossary(glossary: List<String>): List<String> {
            val result = ArrayList<String>(minOf(glossary.size, MAX_GLOSSARY_ITEMS))
            val seen = HashSet<String>(minOf(glossary.size, MAX_GLOSSARY_ITEMS))
            var joinedChars = 0
            for (raw in glossary) {
                if (result.size >= MAX_GLOSSARY_ITEMS) break
                val term = raw.trim()
                if (term.isEmpty() || term.length > MAX_GLOSSARY_TERM_LENGTH) continue
                val key = term.lowercase(Locale.ROOT)
                if (key in seen) continue
                val added = if (result.isEmpty()) term.length else term.length + 1
                if (joinedChars + added > MAX_GLOSSARY_CHARS) {
                    if (MAX_GLOSSARY_CHARS - joinedChars < 2) break
                    continue
                }
                seen.add(key)
                result.add(term)
                joinedChars += added
            }
            return result
        }

        internal fun buildOptionsJson(
            sentenceStart: Boolean,
            glossary: List<String>,
            context: DictationContext? = null,
            locale: String = DEFAULT_LOCALE,
            polish: String = DEFAULT_POLISH,
        ): JSONObject = JSONObject()
            .put("locale", sanitizeLocale(locale))
            .put("polish", sanitizePolish(polish))
            .put("sentenceStart", sentenceStart)
            .put("commands", JSONArray(listOf("new_line", "new_paragraph")))
            .put("glossary", JSONArray(boundGlossary(glossary)))
            .apply {
                context?.let { value ->
                    put(
                        "context",
                        JSONObject()
                            .put("version", 1)
                            .put("level", value.level)
                            .put("purpose", value.purpose)
                            .put("audience", value.audience)
                            .put("source", value.source)
                            .put("style", value.style)
                            .apply {
                                value.before?.let { put("before", it) }
                                value.after?.let { put("after", it) }
                            },
                    )
                }
            }
    }
}
