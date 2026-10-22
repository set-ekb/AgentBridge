package com.pluginslab.agentbridge.agent

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class AgentPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("native_agent", Context.MODE_PRIVATE)
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("agentbridge_gemini", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("agentbridge_gemini", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    var apiKey: String
        get() {
            val value = prefs.getString("api_key", null) ?: return ""
            val parts = value.split(":")
            require(parts.size == 2) { "Не удалось прочитать ключ; сохраните его заново" }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
            return String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
        }
        set(value) {
            if (value.isBlank()) { prefs.edit().remove("api_key").apply(); return }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE, key())
            val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            prefs.edit().putString("api_key", Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(encrypted, Base64.NO_WRAP)).apply()
        }
    var provider: String
        get() = prefs.getString("provider", "gemini") ?: "gemini"
        set(value) { require(value in setOf("gemini","openai","anthropic","compatible")); prefs.edit().putString("provider",value).apply() }
    /** Per-provider model and API key; keys still encrypted via existing Android Keystore. */
    fun modelFor(id: String): String = prefs.getString("model_$id", if(id=="gemini") model else "") ?: ""
    fun setModelFor(id: String, value: String) { prefs.edit().putString("model_$id",value).apply() }
    fun keyFor(id: String): String {
        if (id=="gemini") return apiKey
        val value = prefs.getString("api_key_$id",null) ?: return ""
        val parts = value.split(":"); require(parts.size==2) { "Не удалось расшифровать ключ" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,Base64.decode(parts[0],Base64.NO_WRAP)))
        return String(cipher.doFinal(Base64.decode(parts[1],Base64.NO_WRAP)),Charsets.UTF_8)
    }
    fun setKeyFor(id: String,value: String) {
        if(id=="gemini") { apiKey=value;return }
        if(value.isBlank()) { prefs.edit().remove("api_key_$id").apply();return }
        val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key())
        val encrypted=cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs.edit().putString("api_key_$id",Base64.encodeToString(cipher.iv,Base64.NO_WRAP)+":"+Base64.encodeToString(encrypted,Base64.NO_WRAP)).apply()
    }
    var compatibleEndpoint: String
        get()=prefs.getString("compatible_endpoint","") ?: ""
        set(value) { prefs.edit().putString("compatible_endpoint",value).apply() }
    var speakResults: Boolean
        get()=prefs.getBoolean("speak_results",true)
        set(value) { prefs.edit().putBoolean("speak_results",value).apply() }
    var speechPitch: Float
        get()=prefs.getFloat("speech_pitch",1.1f)
        set(value) { prefs.edit().putFloat("speech_pitch",value.coerceIn(0.5f,2f)).apply() }
    var speechRate: Float
        get()=prefs.getFloat("speech_rate",1f)
        set(value) { prefs.edit().putFloat("speech_rate",value.coerceIn(0.5f,2f)).apply() }
    private fun quotaId(id: String,model: String)=id+"_"+model.replace(Regex("[^A-Za-z0-9_-]"),"_")
    fun quotaGroup(id: String,model: String): String = id+":"+(prefs.getString("quota_account_$id","default-project") ?: "default-project")+":"+(prefs.getString("quota_group_${quotaId(id,model)}",model) ?: model)
    fun setQuotaGroup(id: String,model: String,account: String,group: String) { prefs.edit().putString("quota_account_$id",account).putString("quota_group_${quotaId(id,model)}",group).apply() }
    fun quotaAccount(id: String)=prefs.getString("quota_account_$id","default-project") ?: "default-project"
    fun quotaModelGroup(id: String,model: String)=prefs.getString("quota_group_${quotaId(id,model)}",model) ?: model
    fun limitsFor(id: String,model: String): QuotaLimits {
        val q=quotaId(id,model)
        val defaults=when {
            id=="gemini" && (model.contains("3.5-flash-lite") || model.contains("3.1-flash-lite")) -> QuotaLimits(15,250000,500)
            id=="gemini" && model.contains("3.8-flash") -> QuotaLimits(5,250000,20)
            else -> QuotaLimits()
        }
        return QuotaLimits(prefs.getInt("rpm_$q",defaults.rpm),prefs.getInt("tpm_$q",defaults.tpm),prefs.getInt("rpd_$q",defaults.rpd),prefs.getInt("reserve_$q",1))
    }
    fun setLimits(id: String,model: String,value: QuotaLimits) {
        require(value.rpm in 0..100000 && value.tpm in 0..100000000 && value.rpd in 0..1000000 && value.dialogueReserve in 0..100)
        val q=quotaId(id,model);prefs.edit().putInt("rpm_$q",value.rpm).putInt("tpm_$q",value.tpm).putInt("rpd_$q",value.rpd).putInt("reserve_$q",value.dialogueReserve).apply()
    }
    fun quotaContext(dialogue: Boolean,status: (String)->Unit): kotlin.coroutines.CoroutineContext {
        val id=provider;val m=modelFor(id)
        return QuotaContext(quotaGroup(id,m),limitsFor(id,m),dialogue,status) +
            ProviderAttemptTrace(id, sink = Diagnostics::recordAttempt)
    }
    var conversationEpoch: Int
        get()=prefs.getInt("conversation_epoch",0)
        set(value) { check(prefs.edit().putInt("conversation_epoch",value).commit()) }
    var widgetPrivate: Boolean
        get()=prefs.getBoolean("widget_private",false)
        set(value) { prefs.edit().putBoolean("widget_private",value).apply() }
    var speakEvents: Boolean
        get()=prefs.getBoolean("speak_events",false)
        set(value) { prefs.edit().putBoolean("speak_events",value).apply() }
    var mergeMillis: Int
        get()=prefs.getInt("merge_ms",350)
        set(value) { prefs.edit().putInt("merge_ms",value.coerceIn(0,1000)).apply() }
    var model: String
        get() = prefs.getString("model", "") ?: ""
        set(value) { prefs.edit().putString("model", value).apply() }
    var task: String
        get() {
            prefs.getString("task_encrypted",null)?.let { return AgentCrypto.decrypt(it) }
            val legacy=prefs.getString("task",null) ?: return "Прочитай название активного приложения. Ничего не нажимай."
            task=legacy
            return legacy
        }
        set(value) { check(prefs.edit().putString("task_encrypted",AgentCrypto.encrypt(value)).remove("task").commit()) { "Не удалось сохранить задание" } }
    var delaySeconds: Int
        get() = prefs.getInt("delay", 5)
        set(value) { prefs.edit().putInt("delay", value.coerceIn(0, 30)).apply() }
}
