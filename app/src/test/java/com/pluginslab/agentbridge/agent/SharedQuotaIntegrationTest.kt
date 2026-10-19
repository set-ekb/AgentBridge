package com.pluginslab.agentbridge.agent

import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.util.UUID

class SharedQuotaIntegrationTest {
    @Test fun longServerDeadlineBlocksGroupEvenWhenThisRequestCannotWait() = runBlocking {
        val group="test:"+UUID.randomUUID()
        var saved=QuotaLedger();var attempts=0
        ApiQueue.configure({ QuotaLedger() },{ _,value -> saved=value })
        val transport=ProviderRetry(exchange={ _,_,_ -> attempts++;throw ProviderHttpFailure(429,"900",message="limit") },pause={ fail("Must not wait for 15 minutes") })
        val before=System.currentTimeMillis()
        withContext(QuotaContext(group,QuotaLimits(),true,{})) {
            try { transport.post("https://example.com",emptyMap(),buildJsonObject {}) } catch(_:ProviderHttpFailure) {}
        }
        assertEquals(1,attempts);assertTrue(saved.blockedUntil>=before+900000)
        assertTrue(saved.reason.contains("срок сервера"))
    }
    @Test fun everyRealRetryReservesAnotherRequestInTheSameGroup() = runBlocking {
        val group="test:"+UUID.randomUUID()
        var saved=QuotaLedger();var attempts=0
        ApiQueue.configure({ QuotaLedger() },{ _,value -> saved=value })
        val transport=ProviderRetry(exchange={ _,_,_ ->
            attempts++
            if(attempts==1) throw ProviderHttpFailure(429,"0",message="limit")
            buildJsonObject { put("ok",true) }
        },pause={})
        withContext(QuotaContext(group,QuotaLimits(),true,{})) { transport.post("https://example.com",emptyMap(),buildJsonObject {}) }
        assertEquals(2,attempts);assertEquals(2,saved.uses.size)
        assertNotEquals(saved.uses[0].id,saved.uses[1].id)
    }
}
