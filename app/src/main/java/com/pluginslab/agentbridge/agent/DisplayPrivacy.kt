package com.pluginslab.agentbridge.agent

object DisplayPrivacy {
    private val secrets=Regex("""(?i)(?:bearer\s+[a-z0-9._-]{12,}|sk-[a-z0-9_-]{12,}|AIza[a-z0-9_-]{20,}|(?:api[_ -]?key|password|пароль|token|токен)\s*[:=]\s*\S+)""")
    fun safe(text: String)=secrets.replace(text,"[секрет скрыт]")
}
