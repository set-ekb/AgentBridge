package com.pluginslab.agentbridge.agent

import org.junit.Assert.*
import org.junit.Test

class OpenCodeLocalLinkTest {
    @Test fun readsSanitizedStatus() {
        val result = OpenCodeLocalLink.decodeStatus(
            """{"gateway":"agentbridge-opencode","mode":"read_only","healthy":true,"version":"1.19.3","active_sessions":0}"""
        )
        assertTrue(result.healthy)
        assertEquals("1.19.3", result.version)
        assertEquals(0, result.activeSessions)
        assertTrue(result.readOnly)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnexpectedGateway() {
        OpenCodeLocalLink.decodeStatus(
            """{"gateway":"untrusted","mode":"read_only","healthy":true,"version":"x","active_sessions":0}"""
        )
    }

    @Test(expected = IllegalStateException::class)
    fun rejectsMissingFields() {
        OpenCodeLocalLink.decodeStatus(
            """{"gateway":"agentbridge-opencode","mode":"read_only","healthy":true}"""
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNegativeSessionCount() {
        OpenCodeLocalLink.decodeStatus(
            """{"gateway":"agentbridge-opencode","mode":"read_only","healthy":false,"version":"","active_sessions":-1}"""
        )
    }
}
