package com.aliahad.wovoice.network

import com.sun.net.httpserver.HttpServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class TranscriptionClientTest {

    @Test
    fun boundGlossaryEnforces4000CharBudgetAnd100ItemCap() {
        // 80 terms of 70 chars each = 5,679 joined chars if unbounded -> must fit <= 4,000.
        val longTerms = (1..80).map { i -> "Term${i.toString().padStart(2, '0')}_" + "a".repeat(63) }
        val bounded = TranscriptionClient.boundGlossary(longTerms + listOf("Short"))
        assertTrue(bounded.size < 80)
        assertTrue(bounded.size <= TranscriptionClient.MAX_GLOSSARY_ITEMS)
        assertTrue(bounded.joinToString("\n").length <= TranscriptionClient.MAX_GLOSSARY_CHARS)
        // Greedy packing allows a shorter trailing term to fit into remaining space.
        assertTrue("Short" in bounded)

        // Over 100 short terms still caps at 100.
        val manyShort = (1..140).map { "w$it" }
        assertEquals(100, TranscriptionClient.boundGlossary(manyShort).size)

        // Trims, skips blanks / >80 chars, and deduplicates case-insensitively.
        val cleaned = TranscriptionClient.boundGlossary(
            listOf("  LiVoice  ", "livoice", "", "   ", "x".repeat(81), "Cloudflare"),
        )
        assertEquals(listOf("LiVoice", "Cloudflare"), cleaned)
    }

    @Test
    fun optionsJsonSendsConfiguredLocalePolishAndSanitizesInvalidValues() {
        val valid = TranscriptionClient.buildOptionsJson(
            sentenceStart = true,
            glossary = listOf("LiVoice"),
            locale = "auto",
            polish = "strong",
        )
        assertEquals("auto", valid.getString("locale"))
        assertEquals("strong", valid.getString("polish"))

        val hi = TranscriptionClient.buildOptionsJson(
            sentenceStart = false,
            glossary = emptyList(),
            locale = "hi-IN",
            polish = "off",
        )
        assertEquals("hi-IN", hi.getString("locale"))
        assertEquals("off", hi.getString("polish"))

        val invalid = TranscriptionClient.buildOptionsJson(
            sentenceStart = true,
            glossary = emptyList(),
            locale = "english-invalid",
            polish = "extreme",
        )
        assertEquals("en-IN", invalid.getString("locale"))
        assertEquals("light", invalid.getString("polish"))
    }

    @Test
    fun transcribeUsesLiVoiceBoundarySendsOptionsAndClassifiesNonNetworkErrors() {
        var capturedContentType = ""
        var capturedBody = ""
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/transcriptions") { exchange ->
            capturedContentType = exchange.requestHeaders.getFirst("Content-Type").orEmpty()
            capturedBody = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            val response = """{"text":"Hello world","rawText":"hello world","polished":true,"asrModel":"nova-3","requestId":"r1"}"""
                .toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.createContext("/v1/health") { exchange ->
            val badJson = "not-json".toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(200, badJson.size.toLong())
            exchange.responseBody.use { it.write(badJson) }
        }
        server.start()
        val tempAudio = File.createTempFile("livoice-test-", ".wav").apply {
            writeBytes(ByteArray(64) { 1 })
            deleteOnExit()
        }
        try {
            val baseUrl = "http://127.0.0.1:${server.address.port}"
            val client = TranscriptionClient(allowInsecureHttpForTests = true)
            val result = client.transcribe(
                baseUrl = baseUrl,
                token = "tok",
                audioFile = tempAudio,
                sentenceStart = true,
                glossary = listOf("LiVoice"),
                locale = "en-US",
                polish = "strong",
            )
            assertTrue(result is TranscriptionClient.Result.Success)
            assertTrue("Expected LiVoice boundary, got: $capturedContentType", capturedContentType.contains("boundary=LiVoice-"))
            assertFalse("Must not use legacy WoVoice boundary", capturedContentType.contains("WoVoice-"))
            assertTrue(capturedBody.contains("\"locale\":\"en-US\""))
            assertTrue(capturedBody.contains("\"polish\":\"strong\""))

            // Malformed JSON on 200 must be classified as non-retryable INVALID_RESPONSE, not NETWORK_UNAVAILABLE.
            val bad = client.testConnection(baseUrl, "tok")
            assertTrue(bad is TranscriptionClient.Result.Error)
            bad as TranscriptionClient.Result.Error
            assertEquals("INVALID_RESPONSE", bad.code)
            assertFalse(bad.retryable)
        } finally {
            tempAudio.delete()
            server.stop(0)
        }
    }

    @Test
    fun cancellingInFlightRequestReturnsNonRetryableCancelledError() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/health") { exchange ->
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
            runCatching {
                val ok = JSONObject().put("ok", true).toString().toByteArray(Charsets.UTF_8)
                exchange.sendResponseHeaders(200, ok.size.toLong())
                exchange.responseBody.use { it.write(ok) }
            }
        }
        server.start()
        try {
            val client = TranscriptionClient(allowInsecureHttpForTests = true)
            val baseUrl = "http://127.0.0.1:${server.address.port}"
            var result: TranscriptionClient.Result? = null
            val worker = Thread { result = client.testConnection(baseUrl, "tok") }
            worker.start()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            client.cancel()
            worker.join(5_000)
            release.countDown()

            assertTrue(result is TranscriptionClient.Result.Error)
            val err = result as TranscriptionClient.Result.Error
            assertEquals("CANCELLED", err.code)
            assertFalse(err.retryable)
        } finally {
            release.countDown()
            server.stop(0)
        }
    }
}
