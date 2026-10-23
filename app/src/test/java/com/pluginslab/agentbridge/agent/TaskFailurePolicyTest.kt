package com.pluginslab.agentbridge.agent

import org.junit.Assert.*
import org.junit.Test

class TaskFailurePolicyTest {
    @Test fun exhaustedServiceFailureIsResumableWithoutAutomaticActionReplay() {
        for (code in listOf(500, 502, 503, 504)) {
            val result = TaskFailurePolicy.classify(ProviderHttpFailure(code, null, message = "unavailable"))
            assertEquals("PAUSED", result.taskStatus)
            assertEquals("provider_unavailable", result.category)
        }
    }

    @Test fun quotaAndCredentialFailureRemainDistinct() {
        assertEquals("WAIT_QUOTA", TaskFailurePolicy.classify(ProviderHttpFailure(429, null, true, "daily")).taskStatus)
        for (code in listOf(401, 403)) {
            val result = TaskFailurePolicy.classify(ProviderHttpFailure(code, null, message = "private response"))
            assertEquals("FAILED", result.taskStatus)
            assertEquals("provider_access", result.category)
        }
    }

    @Test fun disconnectedAccessibilityCanBeEnabledAndResumed() {
        val result = TaskFailurePolicy.classify(AccessibilityUnavailable())
        assertEquals("PAUSED", result.taskStatus)
        assertEquals("accessibility_disconnected", result.category)
    }

    @Test fun categoriesNeverContainArbitraryExceptionMessages() {
        val result = TaskFailurePolicy.classify(IllegalStateException("SECRET: private screen"))
        assertEquals("FAILED", result.taskStatus)
        assertEquals("task_state_error", result.category)
        assertFalse(result.toString().contains("SECRET"))
    }

    @Test fun networkFailuresAreResumableButInvalidInputIsNotRetried() {
        assertEquals("PAUSED", TaskFailurePolicy.classify(java.net.UnknownHostException("offline")).taskStatus)
        assertEquals("io_error", TaskFailurePolicy.classify(java.io.IOException("local disk")).category)
        assertEquals("FAILED", TaskFailurePolicy.classify(java.io.IOException("local disk")).taskStatus)
        assertEquals("network_timeout", TaskFailurePolicy.classify(java.net.SocketTimeoutException()).category)
        assertEquals("FAILED", TaskFailurePolicy.classify(IllegalArgumentException("bad model")).taskStatus)
    }
}
