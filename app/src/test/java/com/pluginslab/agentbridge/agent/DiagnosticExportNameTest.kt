package com.pluginslab.agentbridge.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date
import java.util.TimeZone

class DiagnosticExportNameTest {
    @Test fun includesLocalTimestampAndJsonlExtension() {
        val previous = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            assertEquals("AgentBridge-log-2026-10-21_00-15-30.jsonl",
                Diagnostics.exportFileName(Date(1792541730000L)))
        } finally {
            TimeZone.setDefault(previous)
        }
    }

    @Test fun filenameIsSafeForAndroidDocuments() {
        val name = Diagnostics.exportFileName()
        assertTrue(name.matches(Regex("AgentBridge-log-\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2}\\.jsonl")))
        assertTrue(!name.contains("/") && !name.contains(":"))
    }
}
