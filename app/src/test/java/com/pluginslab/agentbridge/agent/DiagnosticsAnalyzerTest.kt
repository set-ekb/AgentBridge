package com.pluginslab.agentbridge.agent

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class DiagnosticsAnalyzerTest {
    private val now = 1_800_000_000_000L

    private fun item(
        event: String, status: String = "", detail: String = "",
        duration: Long? = null, source: String = "agent", at: Long = now - 1000L
    ): String = buildJsonObject {
        put("time", at)
        put("event", event)
        put("source", source)
        if (status.isNotBlank()) put("status", status)
        if (detail.isNotBlank()) put("detail", detail)
        duration?.let { put("duration_ms", it) }
        put("private_screen_text", "SECRET_123_should_never_be_returned")
        put("api_key", "SECRET_123_should_never_be_returned")
    }.toString()

    @Test fun summaryAndErrorGroupsAreBoundedAndDoNotLeakExtras() {
        val w = DiagnosticsAnalyzer.parse(listOf(
            item("task_start"),
            item("api_error", "quota_429", "gemini", source = "provider"),
            item("tool_finish", "error", "tap_node", 250),
            item("task_finish", "failed"),
            "{malformed"
        ), 24, now)
        val summary = DiagnosticsAnalyzer.summary(w)
        assertEquals(4, summary["coverage"]!!.jsonObject["event_count"]!!.jsonPrimitive.int)
        assertEquals(1, summary["coverage"]!!.jsonObject["bad_lines"]!!.jsonPrimitive.int)
        assertEquals(2, summary["errors_observed"]!!.jsonPrimitive.int)
        val errors = DiagnosticsAnalyzer.recentErrors(w, 2).toString()
        assertTrue(errors.contains("quota_429"))
        assertFalse(errors.contains("SECRET_123"))
        assertFalse(summary.toString().contains("private_screen_text"))
    }

    @Test fun apiFailuresDoNotInventAttributionOrNetworkStatus() {
        val w = DiagnosticsAnalyzer.parse(listOf(
            item("api_request", "start", "gemini", source = "provider"),
            item("api_error", "quota_429", "gemini", source = "provider"),
            item("api_error", "exception", "ProviderHttpFailure", source = "provider"),
            item("api_response", "ok", "gemini", duration = 125),
            item("api_response", "ok", "gemini", duration = 400)
        ), 24, now)
        val analysis = DiagnosticsAnalyzer.apiFailures(w)
        assertEquals(2, analysis["api_errors_observed"]!!.jsonPrimitive.int)
        assertEquals(1, analysis["errors_by_provider"]!!.jsonObject["gemini"]!!.jsonPrimitive.int)
        assertEquals(1, analysis["errors_by_provider"]!!.jsonObject["unattributed"]!!.jsonPrimitive.int)
        assertEquals(125L, analysis["latency_ms"]!!.jsonObject["p50"]!!.jsonPrimitive.long)
    }

    @Test fun toolLatencyAndSuccessAreNotResultVerification() {
        val w = DiagnosticsAnalyzer.parse(listOf(
            item("tool_finish", "ok", "tap_node", 10),
            item("tool_finish", "ok", "tap_node", 20),
            item("tool_finish", "rejected", "tap_node", 500)
        ), 24, now)
        val tool = DiagnosticsAnalyzer.toolPerformance(w)["tools"]!!.jsonArray.single().jsonObject
        assertEquals("tap_node", tool["tool"]!!.jsonPrimitive.content)
        assertEquals(1, tool["reported_error_or_rejected"]!!.jsonPrimitive.int)
        assertEquals(500L, tool["latency_ms"]!!.jsonObject["p95"]!!.jsonPrimitive.long)
        assertTrue(DiagnosticsAnalyzer.toolPerformance(w)["note"]!!.jsonPrimitive.content.contains("never verifies"))
    }

    @Test fun unknownIsNotFailureAndVectorHintsAreUntrusted() {
        val noSignal = DiagnosticsAnalyzer.parse(listOf(item("app_start")), 24, now)
        assertEquals("unknown_optional",
            DiagnosticsAnalyzer.health(noSignal,true,false)["vector"]!!.jsonPrimitive.content)
        assertEquals("not_observed",
            DiagnosticsAnalyzer.hookPipeline(noSignal)["vector_signal"]!!.jsonPrimitive.content)
        val hint = DiagnosticsAnalyzer.parse(listOf(item("hook_event", "resumed", source = "vector_hint")), 24, now)
        assertEquals("untrusted_hints_observed",
            DiagnosticsAnalyzer.hookPipeline(hint)["vector_signal"]!!.jsonPrimitive.content)
        assertEquals("unknown",
            DiagnosticsAnalyzer.hookPipeline(hint)["hooks_installed_in_vector"]!!.jsonPrimitive.content)
        assertEquals("degraded",
            DiagnosticsAnalyzer.health(hint,false,false)["overall"]!!.jsonPrimitive.content)
    }

    @Test fun oldFutureAndMalformedEntriesCannotProveLiveness() {
        val w = DiagnosticsAnalyzer.parse(listOf(
            item("app_start", at = now - 73 * 60L * 60L * 1000L),
            item("hook_event", at = now + 120_000),
            """{"time":$now,"event":"wrong","source":"agent"}"""
        ), 72, now)
        assertTrue(w.events.isEmpty())
        assertEquals("unknown",
            DiagnosticsAnalyzer.health(w,true,false)["overall"]!!.jsonPrimitive.content)
        assertEquals(1, w.badLines)
    }
}
