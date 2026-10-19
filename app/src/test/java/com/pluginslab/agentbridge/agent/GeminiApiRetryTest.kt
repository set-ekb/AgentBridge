package com.pluginslab.agentbridge.agent

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class GeminiApiRetryTest {
    private val minute=Json.parseToJsonElement("""{"error":{"details":[{"violations":[{"quotaId":"GenerateRequestsPerMinutePerProjectPerModel-FreeTier"}]}]}}""").jsonObject
    @Test fun rateLimitedGenerationResendsTheSameHistoryAndReturnsOneReply() = runBlocking {
        val payload=Json.parseToJsonElement("""{"contents":[{"role":"model","parts":[{"thoughtSignature":"preserve-me","functionCall":{"name":"tap_node"}}]},{"role":"user","parts":[{"functionResponse":{"name":"tap_node","response":{"result":"already executed"}}}]}]}""").jsonObject
        var requests=0;var waited=0L
        val success=buildJsonObject { put("done",true) }
        val api=GeminiApi("test-key",exchange={ path,body ->
            assertEquals("/models/test:generateContent",path);assertSame(payload,body)
            requests++
            if(requests==1) GeminiApi.Reply(429,minute) else GeminiApi.Reply(200,success)
        },pause={ waited+=it },now={0})
        assertEquals(success,api.request("/models/test:generateContent",payload))
        assertEquals(2,requests);assertEquals(66000L,waited)
    }
    @Test fun stopDuringQuotaWaitDoesNotIssueAnotherRequest() = runBlocking {
        var requests=0
        val api=GeminiApi("test-key",exchange={ _,_ -> requests++;GeminiApi.Reply(429,minute) },pause={ throw CancellationException("owner stopped") })
        try { api.request("/models/test:generateContent",buildJsonObject {});fail("Expected cancellation") }
        catch (_: CancellationException) { }
        assertEquals(1,requests)
    }
    @Test fun dailyQuotaDoesNotTriggerAnyRetry() = runBlocking {
        var requests=0
        val daily=Json.parseToJsonElement("""{"error":{"details":[{"violations":[{"quotaId":"GenerateRequestsPerDayPerProjectPerModel-FreeTier"}]}]}}""").jsonObject
        val api=GeminiApi("test-key",exchange={ _,_ -> requests++;GeminiApi.Reply(429,daily) },pause={ fail("No retry permitted") })
        try { api.request("/models/test:generateContent");fail("Expected quota error") }
        catch(e: IllegalStateException) { assertTrue(e.message.orEmpty().contains("PerDay")) }
        assertEquals(1,requests)
    }
}
