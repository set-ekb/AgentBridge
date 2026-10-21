package com.pluginslab.agentbridge.agent

import org.junit.Assert.*
import org.junit.Test

class LaunchFailurePolicyTest {
    @Test fun validPackageNamesAreStrictlyChecked() {
        assertTrue(LaunchFailurePolicy.validPackage("com.android.settings"))
        assertTrue(LaunchFailurePolicy.validPackage("org.telegram.messenger"))
        assertFalse(LaunchFailurePolicy.validPackage(""))
        assertFalse(LaunchFailurePolicy.validPackage("not_a_package"))
        assertFalse(LaunchFailurePolicy.validPackage("com.android.settings; echo secret"))
        assertFalse(LaunchFailurePolicy.validPackage("x".repeat(181)))
    }

    @Test fun foregroundReasonsMustNotInventCause() {
        assertEquals("ACCESSIBILITY_LOST", LaunchFailurePolicy.foregroundCode("com.android.settings", false))
        assertEquals("FOREGROUND_UNKNOWN", LaunchFailurePolicy.foregroundCode(null, true))
        assertEquals("FOREGROUND_MISMATCH", LaunchFailurePolicy.foregroundCode("com.android.launcher3", true))
    }

    @Test fun failureCodesContainNoRawExceptionDetails() {
        assertEquals("DISPATCH_DENIED", LaunchFailurePolicy.exceptionCode(SecurityException("secret")))
        assertEquals("DISPATCH_FAILED", LaunchFailurePolicy.exceptionCode(IllegalStateException("secret")))
        assertFalse(LaunchFailurePolicy.guidance("DISPATCH_DENIED").contains("secret"))
        assertTrue(LaunchFailurePolicy.guidance("FOREGROUND_MISMATCH").contains("get_active_window_info"))
    }
}
