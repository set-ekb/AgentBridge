package com.pluginslab.agentbridge.server

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*

/**
 * OpenCode's MCP identity is never accepted by the legacy unrestricted /mcp route.
 * Reuse the existing encrypted local pairing key instead of generating yet another secret.
 */
internal object McpAuthRules {
    fun isOpenCodePath(path: String): Boolean =
        path == "/mcp/opencode" || path.startsWith("/mcp/opencode/")

    fun allowed(path: String, authorization: String?, primary: String, scoped: String): Boolean {
        // Reject ambiguous path normalization before choosing an identity scope.
        // This applies to both token classes: no percent-encoding, dot segments
        // or backslashes may change which route Ktor ultimately resolves.
        if (!path.matches(Regex("/[A-Za-z0-9_/-]*")) || path.contains("//")) return false
        val key = if (isOpenCodePath(path)) scoped else primary
        if (key.isBlank() || authorization == null) return false
        val actual = authorization.toByteArray(Charsets.UTF_8)
        val expected = ("Bearer " + key).toByteArray(Charsets.UTF_8)
        return java.security.MessageDigest.isEqual(actual, expected)
    }
}

class BearerAuthPluginConfig {
    var token: String = ""
    var openCodeToken: () -> String = { "" }
}

val BearerAuthPlugin = createApplicationPlugin(
    name = "BearerAuth",
    createConfiguration = ::BearerAuthPluginConfig
) {
    val token = pluginConfig.token
    val openCodeToken = pluginConfig.openCodeToken

    onCall { call ->
        val path = call.request.local.uri.substringBefore('?')
        // Health is intentionally public; all MCP routes require their own Bearer credentials.
        if (path == "/health") return@onCall

        val scoped = if (McpAuthRules.isOpenCodePath(path))
            runCatching { openCodeToken() }.getOrDefault("") else ""
        if (!McpAuthRules.allowed(path, call.request.headers["Authorization"], token, scoped)) {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid or missing bearer token"))
            return@onCall
        }
    }
}
