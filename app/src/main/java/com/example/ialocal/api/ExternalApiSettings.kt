package com.example.ialocal.api

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

/** A remote API connection is independent of the existing localhost API server. */
data class RemoteApiConnection(
    val provider: RemoteApiProvider = RemoteApiProvider.OPENROUTER,
    val baseUrl: String = RemoteApiProvider.OPENROUTER.defaultUrl,
    val modelId: String = "",
    val hasKey: Boolean = false,
)

enum class RemoteApiProvider(val label: String, val defaultUrl: String) {
    OPENROUTER("OpenRouter", "https://openrouter.ai/api/v1"),
    OPENAI("OpenAI", "https://api.openai.com/v1"),
    CUSTOM("Outra API", "https://example.com/v1"),
}

/**
 * Stores provider and model preferences separately from the local API.
 * The user's credential is encrypted with an AndroidKeyStore AES-256-GCM key and is never logged.
 */
class ExternalApiSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("external_api", Context.MODE_PRIVATE)

    fun read(): RemoteApiConnection {
        val provider = runCatching { RemoteApiProvider.valueOf(prefs.getString("provider", "") ?: "") }
            .getOrDefault(RemoteApiProvider.OPENROUTER)
        return RemoteApiConnection(
            provider = provider,
            baseUrl = prefs.getString("base_url", null) ?: provider.defaultUrl,
            modelId = prefs.getString("model_id", "") ?: "",
            hasKey = prefs.contains("key_ciphertext"),
        )
    }

    fun save(provider: RemoteApiProvider, baseUrl: String, modelId: String, newKey: String?) {
        val safeUrl = remoteApiBaseUrl(baseUrl)
        val previous = read()
        // Never re-use a bearer token for a different API host/provider by accident.
        // Changing only the model is safe; switching the server requires a new key.
        require(
            !previous.hasKey ||
                (previous.provider == provider && remoteApiBaseUrl(previous.baseUrl) == safeUrl) ||
                !newKey.isNullOrBlank()
        ) {
            "Ao trocar de provedor ou URL, informe uma nova chave para proteger suas credenciais."
        }
        val changes = prefs.edit()
            .putString("provider", provider.name)
            .putString("base_url", safeUrl)
            .putString("model_id", modelId.trim())
        if (newKey != null) {
            if (newKey.isBlank()) {
                changes.remove("key_iv").remove("key_ciphertext")
            } else {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                    init(Cipher.ENCRYPT_MODE, secretKey())
                }
                val encrypted = cipher.doFinal(newKey.trim().toByteArray(Charsets.UTF_8))
                changes.putString("key_iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                changes.putString("key_ciphertext", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            }
        }
        check(changes.commit()) { "Não foi possível salvar as configurações da API." }
    }

    fun apiKey(): String {
        val iv = prefs.getString("key_iv", null) ?: return ""
        val ciphertext = prefs.getString("key_ciphertext", null) ?: return ""
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(
                Cipher.DECRYPT_MODE,
                secretKey(),
                GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)),
            )
        }
        return String(cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP)), Charsets.UTF_8)
    }

    fun removeKey() {
        prefs.edit().remove("key_iv").remove("key_ciphertext").apply()
    }

    private fun secretKey(): java.security.Key {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        store.getKey(KEY_ALIAS, null)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
        }.generateKey()
    }

    private companion object {
        const val KEY_ALIAS = "iaoffline.remote_api_credentials.v1"
    }
}
