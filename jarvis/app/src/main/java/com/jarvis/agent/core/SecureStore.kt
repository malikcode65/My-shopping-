package com.jarvis.agent.core

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores API keys encrypted with an AES-256-GCM key that never leaves the Android Keystore
 * (hardware-backed on most phones). Only the ciphertext is written to SharedPreferences.
 */
class SecureStore(context: Context) {

    enum class Secret(val prefKey: String) {
        ANTHROPIC_API_KEY("anthropic"),
        PROXY_TOKEN("proxy_token"),
        PICOVOICE_ACCESS_KEY("picovoice"),
        ELEVENLABS_API_KEY("elevenlabs"),
        OPENAI_API_KEY("openai_whisper"),
    }

    private val sp = context.getSharedPreferences("jarvis_secure", Context.MODE_PRIVATE)

    fun get(secret: Secret): String? {
        val stored = sp.getString(secret.prefKey, null) ?: return null
        return try {
            val bytes = Base64.decode(stored, Base64.NO_WRAP)
            val iv = bytes.copyOfRange(0, IV_LEN)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(bytes, IV_LEN, bytes.size - IV_LEN), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    fun put(secret: Secret, value: String?) {
        val v = value?.trim()
        if (v.isNullOrEmpty()) {
            sp.edit().remove(secret.prefKey).apply()
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val out = cipher.iv + cipher.doFinal(v.toByteArray(Charsets.UTF_8))
        sp.edit().putString(secret.prefKey, Base64.encodeToString(out, Base64.NO_WRAP)).apply()
    }

    fun has(secret: Secret): Boolean = sp.contains(secret.prefKey)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "jarvis_secrets_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LEN = 12
    }
}
