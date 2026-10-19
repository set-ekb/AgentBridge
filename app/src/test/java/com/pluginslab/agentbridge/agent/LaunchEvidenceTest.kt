package com.pluginslab.agentbridge.agent

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class LaunchEvidenceTest {
    @Test fun commandAcceptedButBridgeRemainedActiveIsFailure() {
        val result=LaunchEvidence.result("com.google.android.gm","com.pluginslab.agentbridge","foreground_activity")
        assertTrue(result["requested"]!!.jsonPrimitive.boolean)
        assertFalse(result["success"]!!.jsonPrimitive.boolean)
        assertFalse(result["verified"]!!.jsonPrimitive.boolean)
        assertTrue(result.containsKey("error"))
    }
    @Test fun actualGmailForegroundConfirmsLaunch() {
        val result=LaunchEvidence.result("com.google.android.gm","com.google.android.gm","foreground_activity")
        assertTrue(result["success"]!!.jsonPrimitive.boolean)
        assertTrue(result["verified"]!!.jsonPrimitive.boolean)
        assertFalse(result.containsKey("error"))
    }
    @Test fun noAccessibleWindowCannotConfirmLaunch() {
        val result=LaunchEvidence.result("com.google.android.gm",null,"accessibility_service")
        assertFalse(result["success"]!!.jsonPrimitive.boolean)
        assertEquals("unknown",result["current_package"]!!.jsonPrimitive.content)
    }
}
