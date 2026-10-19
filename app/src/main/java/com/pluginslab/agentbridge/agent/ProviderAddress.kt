package com.pluginslab.agentbridge.agent

object ProviderAddress {
    fun validate(value: String) {
        val uri=java.net.URI(value)
        val loopback=uri.host in setOf("localhost","127.0.0.1","[::1]","::1")
        require(!uri.host.isNullOrBlank() && (uri.scheme=="https" || (uri.scheme=="http" && loopback))) { "API: HTTPS или HTTP только localhost" }
        require(uri.userInfo==null && uri.fragment==null && uri.query==null) { "URL API не должен содержать пароль, query или фрагмент" }
    }
}
