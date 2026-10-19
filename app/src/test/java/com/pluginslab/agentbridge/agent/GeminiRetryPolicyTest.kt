package com.pluginslab.agentbridge.agent

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class GeminiRetryPolicyTest {
    private val minute=Json.parseToJsonElement("""{"error":{"details":[{"violations":[{"quotaId":"GenerateRequestsPerMinutePerProjectPerModel-FreeTier"}]}]}}""").jsonObject
    @Test fun minuteQuotaWaitsForAFullWindowInsteadOfThreeSeconds() {
        assertTrue(GeminiRetryPolicy.minuteLimited(minute))
        assertEquals(66000L,GeminiRetryPolicy.delayMs(429,minute,null,0,0,0))
        assertEquals(131000L,GeminiRetryPolicy.delayMs(429,minute,null,1,66000,0))
        assertNull(GeminiRetryPolicy.delayMs(429,minute,null,2,197000,0))
    }
    @Test fun retryInfoAndHeaderUseTheLongerDelayWithMargin() {
        val body=Json.parseToJsonElement("""{"error":{"details":[{"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"12.250s"}]}}""").jsonObject
        assertEquals(13250L,GeminiRetryPolicy.delayMs(429,body,"10",0,0,0))
        assertEquals(21000L,GeminiRetryPolicy.delayMs(429,body,"20",0,0,0))
        assertEquals(60000L,GeminiRetryPolicy.serverDelayMs(buildJsonObject {},"Thu, 1 Jan 1970 00:01:00 GMT",0))
    }
    @Test fun dailyQuotaAndZeroEntitlementStopWithoutWaiting() {
        for (violation in listOf("""{"quotaId":"GenerateRequestsPerDayPerProjectPerModel-FreeTier"}""","""{"quotaId":"MinuteQuota","quotaValue":"0"}""")) {
            val body=Json.parseToJsonElement("""{"error":{"details":[{"violations":[$violation]}]}}""").jsonObject
            assertNull(GeminiRetryPolicy.delayMs(429,body,"10",0,0,0))
        }
    }
    @Test fun retryBudgetCannotBeExtendedByMalformedOrLargeServerHints() {
        assertNull(GeminiRetryPolicy.delayMs(429,minute,"600",0,0,0))
        assertNull(GeminiRetryPolicy.delayMs(429,minute,null,4,0,0))
        assertEquals(66000L,GeminiRetryPolicy.delayMs(429,minute,"NaN",0,0,0))
        assertNull(GeminiRetryPolicy.delayMs(403,minute,null,0,0,0))
        assertEquals(4000L,GeminiRetryPolicy.delayMs(503,buildJsonObject {},null,0,0,0))
    }
}
