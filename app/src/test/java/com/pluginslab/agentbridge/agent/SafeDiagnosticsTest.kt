package com.pluginslab.agentbridge.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SafeDiagnosticsTest {
    @Test fun allowlistedTechnicalFields() {
        assertEquals("resumed", Diagnostics.safeLabel("resumed"))
        assertEquals("com.android.settings", Diagnostics.safePackage("com.android.settings"))
        assertEquals("com.android.settings.Settings", Diagnostics.safeActivity("com.android.settings.Settings"))
    }

    @Test fun rejectFreeTextAndSecrets() {
        assertEquals("", Diagnostics.safeLabel("Bearer secret-token with spaces"))
        assertEquals("", Diagnostics.safeLabel("https://server.example/path?token=SECRET"))
        assertEquals("", Diagnostics.safePackage("com.android.settings; rm -rf /"))
        assertEquals("", Diagnostics.safeActivity("Activity\nAuthorization: Bearer token"))
        assertTrue(Diagnostics.safeLabel("x".repeat(65)).isEmpty())
        assertTrue(Diagnostics.safePackage("x".repeat(181)).isEmpty())
    }
}
