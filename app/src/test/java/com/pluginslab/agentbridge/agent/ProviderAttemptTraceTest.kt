package com.pluginslab.agentbridge.agent

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class ProviderAttemptTraceTest {
    private val empty = buildJsonObject {}
    private fun trace(events: MutableList<ProviderAttemptEvent>, provider: String = "gemini") =
        ProviderAttemptTrace(provider, "task-attempt", 2) { events.add(it) }

    @Test fun countdownsAreNotErrorsAndEachRealRetryHasItsOwnIdentity() = runBlocking {
        val events = mutableListOf<ProviderAttemptEvent>()
        val progress = mutableListOf<String>()
        var requests = 0
        val payload = buildJsonObject { put("private_prompt", "SECRET") }
        val api = GeminiApi("SECRET", status = { progress.add(it) }, exchange = { _, data ->
            assertSame(payload, data)
            requests++
            GeminiApi.Reply(503, empty)
        }, pause = {}, now = { 0 })
        withContext(trace(events)) {
            try { api.request("/models/test:generateContent", payload); fail("expected failure") }
            catch (e: ProviderHttpFailure) { assertEquals(503, e.code) }
        }
        assertEquals(4, requests)
        assertTrue(progress.size > requests)
        assertEquals(4, events.count { it.kind == "api_request" })
        assertEquals(4, events.count { it.kind == "api_error" })
        assertEquals(4, events.map { it.requestId }.distinct().size)
        events.groupBy { it.requestId }.values.forEach { pair ->
            assertEquals(listOf("api_request", "api_error"), pair.map { it.kind })
            assertEquals("service_503", pair.last().status)
            assertTrue(pair.last().durationMs >= 0)
        }
        assertTrue(events.all { it.provider == "gemini" && it.taskAttemptId == "task-attempt" && it.step == 2 })
        assertFalse(events.toString().contains("SECRET"))
        assertFalse(events.toString().contains("generateContent"))
    }

    @Test fun successfulRetryKeepsHistoryAndClosesEachAttemptOnce() = runBlocking {
        val events = mutableListOf<ProviderAttemptEvent>()
        val payload = buildJsonObject { put("history", "already executed") }
        var requests = 0
        val api = GeminiApi("test", exchange = { _, data ->
            assertSame(payload, data)
            GeminiApi.Reply(if (++requests == 1) 429 else 200, empty, "0")
        }, pause = {})
        withContext(trace(events)) { api.request("/models/test:generateContent", payload) }
        assertEquals(listOf("api_request", "api_error", "api_request", "api_response"), events.map { it.kind })
        assertEquals("quota_429", events[1].status)
        assertNotEquals(events[0].requestId, events[2].requestId)
    }

    @Test fun cancellationDuringTransportIsNotAnApiFailure() = runBlocking {
        val events = mutableListOf<ProviderAttemptEvent>()
        val entered = CompletableDeferred<Unit>()
        val transport = ProviderRetry(exchange = { _, _, _ ->
            entered.complete(Unit)
            awaitCancellation()
        })
        val job = launch(trace(events, "openai")) { transport.post("https://example.com", emptyMap(), empty) }
        entered.await()
        job.cancelAndJoin()
        assertEquals(listOf("api_request", "api_cancelled"), events.map { it.kind })
        assertEquals(events[0].requestId, events[1].requestId)
        assertTrue(events.none { it.kind == "api_error" })
    }

    @Test fun cancellationDuringCountdownDoesNotFabricateAnotherAttempt() = runBlocking {
        val events = mutableListOf<ProviderAttemptEvent>()
        val api = GeminiApi("test", exchange = { _, _ -> GeminiApi.Reply(503, empty) },
            pause = { throw CancellationException("local stop") })
        withContext(trace(events)) {
            try { api.request("/models/test:generateContent"); fail("expected cancellation") }
            catch (_: CancellationException) { }
        }
        assertEquals(listOf("api_request", "api_error"), events.map { it.kind })
    }

    @Test fun quotaAdmissionWithoutTransportDoesNotRecordHttpFailure() = runBlocking {
        val events = mutableListOf<ProviderAttemptEvent>()
        val group = UUID.randomUUID().toString()
        ApiQueue.configure({ QuotaLedger(blockedUntil = System.currentTimeMillis() + 600_000) }, { _, _ -> })
        val api = GeminiApi("test", exchange = { _, _ -> fail("not admitted"); GeminiApi.Reply(200, empty) })
        withContext(QuotaContext(group, QuotaLimits(), true, {}) + trace(events)) {
            try { api.request("/models/test:generateContent"); fail("expected quota wait") }
            catch (_: QuotaWaitException) { }
        }
        assertTrue(events.isEmpty())
    }

    @Test fun nonGeminiRetryUsesSameTransportTraceAndPayload() = runBlocking {
        val events = mutableListOf<ProviderAttemptEvent>()
        var requests = 0
        val transport = ProviderRetry(exchange = { _, _, data ->
            assertSame(empty, data)
            if (++requests == 1) throw ProviderHttpFailure(502, "0", message = "private error")
            empty
        }, pause = {})
        withContext(trace(events, "anthropic")) { transport.post("https://example.com", emptyMap(), empty) }
        assertEquals(listOf("api_request", "api_error", "api_request", "api_response"), events.map { it.kind })
        assertEquals("http_502", events[1].status)
        assertFalse(events.toString().contains("private error"))
    }

    @Test fun networkExceptionHasNoPersistedMessage() = runBlocking {
        val events = mutableListOf<ProviderAttemptEvent>()
        val transport = ProviderRetry(exchange = { _, _, _ -> throw java.net.SocketTimeoutException("SECRET URL") })
        withContext(trace(events, "compatible")) {
            try { transport.post("https://example.com", emptyMap(), empty); fail("expected timeout") }
            catch (_: java.net.SocketTimeoutException) { }
        }
        assertEquals("network_timeout", events.last().status)
        assertFalse(events.toString().contains("SECRET"))
    }

    @Test fun failingJournalCannotReplayOrFailAnOtherwiseSuccessfulRequest() = runBlocking {
        var requests = 0
        withContext(ProviderAttemptTrace("gemini") { error("journal unavailable") }) {
            val result = ProviderAttemptTrace.exchange { requests++; empty }
            assertSame(empty, result)
        }
        assertEquals(1, requests)
    }
}
