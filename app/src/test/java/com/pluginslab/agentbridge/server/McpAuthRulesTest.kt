package com.pluginslab.agentbridge.server

import org.junit.Assert.*
import org.junit.Test

class McpAuthRulesTest {
    private val owner = "owner-secret"
    private val opencode = "termux-pairing-secret"

    @Test fun pairedKeyCanOnlyAccessScopedMcp() {
        assertTrue(McpAuthRules.allowed("/mcp/opencode", "Bearer $opencode", owner, opencode))
        assertTrue(McpAuthRules.allowed("/mcp/opencode/", "Bearer $opencode", owner, opencode))
        assertFalse(McpAuthRules.allowed("/mcp", "Bearer $opencode", owner, opencode))
        assertFalse(McpAuthRules.allowed("/mcp/other", "Bearer $opencode", owner, opencode))
    }

    @Test fun ownerKeyCannotBeUsedAsScopedIdentity() {
        assertTrue(McpAuthRules.allowed("/mcp", "Bearer $owner", owner, opencode))
        assertFalse(McpAuthRules.allowed("/mcp/opencode", "Bearer $owner", owner, opencode))
    }

    @Test fun revokedPairingKeyAlwaysFailsClosed() {
        assertFalse(McpAuthRules.allowed("/mcp/opencode", "Bearer $opencode", owner, ""))
        assertFalse(McpAuthRules.allowed("/mcp/opencode", null, owner, opencode))
        assertFalse(McpAuthRules.allowed("/mcp/opencode-evil", "Bearer $opencode", owner, opencode))
        assertFalse(McpAuthRules.allowed("/mcp/opencode/../mcp", "Bearer $owner", owner, opencode))
    }

    @Test fun authorizationIsNotSubstringMatching() {
        assertFalse(McpAuthRules.allowed("/mcp/opencode", "Bearer $opencode extra", owner, opencode))
        assertFalse(McpAuthRules.allowed("/mcp", "Bearer $owner extra", owner, opencode))
        assertFalse(McpAuthRules.allowed("/mcp", "Basic $owner", owner, opencode))
    }
}
