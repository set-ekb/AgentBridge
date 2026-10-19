package com.pluginslab.agentbridge.agent

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object AgentCrypto {
    @Synchronized private fun key(): SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("agentbridge_gemini",null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("agentbridge_gemini",KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun encrypt(text: String): String {
        val c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,key())
        return Base64.encodeToString(c.iv,Base64.NO_WRAP)+":"+Base64.encodeToString(c.doFinal(text.toByteArray(Charsets.UTF_8)),Base64.NO_WRAP)
    }
    fun decrypt(text: String): String {
        val parts=text.split(":");require(parts.size==2) { "Зашифрованные данные повреждены" }
        val c=Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,Base64.decode(parts[0],Base64.NO_WRAP)))
        return String(c.doFinal(Base64.decode(parts[1],Base64.NO_WRAP)),Charsets.UTF_8)
    }
}
