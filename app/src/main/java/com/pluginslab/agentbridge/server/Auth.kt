package com.pluginslab.agentbridge.server

import java.security.MessageDigest

/**
 * Credentials for OpenCode's scoped Android MCP MUST NOT authenticate to /mcp.
 * Route-level Ktor Authentication enforces this; this helper checks token equality.
 */
internal object McpAuthRules {
    fun isOpenCodePath(path: String): Boolean =
        path == "/mcp/opencode" || path.startsWith("/mcp/opencode/")

    fun allowed(path: String, authorization: String?, primary: String, scoped: String): Boolean {
        // All callers choose a fixed scope; reject ambiguous paths in direct tests and usage.
        if (!path.matches(Regex("/[A-Za-z0-9_/-]*")) || path.contains("//")) return false
        val key = if (isOpenCodePath(path)) scoped else primary
        if (key.isBlank() || authorization == null) return false
        val actual = authorization.toByteArray(Charsets.UTF_8)
        val expected = ("Bearer " + key).toByteArray(Charsets.UTF_8)
        return MessageDigest.isEqual(actual, expected)
    }
}
