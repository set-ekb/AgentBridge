package com.pluginslab.agentbridge.agent

import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

class ProviderRetryTest {
    @Test fun retryUsesIdenticalPayloadAndKeepsTaskHistory() = runBlocking {
        val payload=buildJsonObject { put("history","tool output already accepted") }
        val seen=mutableListOf<JsonObject>();var waited=0L
        val transport=ProviderRetry(exchange={ _,_,request ->
            seen.add(request)
            if(seen.size==1) throw ProviderHttpFailure(429,"0",message="rate limited")
            buildJsonObject { put("ok",true) }
        },pause={ waited+=it })
        transport.post("https://example.com",emptyMap(),payload)
        assertEquals(2,seen.size);assertTrue(seen.all { it===payload });assertEquals(1000L,waited)
    }
    @Test fun permanentQuotaDoesNotWaitOrRepeat() = runBlocking {
        var attempts=0;var waited=false
        val transport=ProviderRetry(exchange={ _,_,_ -> attempts++;throw ProviderHttpFailure(429,null,true,"billing") },pause={ waited=true })
        try { transport.post("https://example.com",emptyMap(),buildJsonObject {}) } catch(_:ProviderHttpFailure) {}
        assertEquals(1,attempts);assertFalse(waited)
    }
    @Test fun stoppingDuringWaitPreventsNextRequest() = runBlocking {
        var attempts=0
        val transport=ProviderRetry(exchange={ _,_,_ -> attempts++;throw ProviderHttpFailure(429,null,message="limit") },pause={ throw CancellationException("stop") })
        var cancelled=false;try { transport.post("https://example.com",emptyMap(),buildJsonObject {}) } catch(_:CancellationException) { cancelled=true }
        assertTrue(cancelled);assertEquals(1,attempts)
    }
}
