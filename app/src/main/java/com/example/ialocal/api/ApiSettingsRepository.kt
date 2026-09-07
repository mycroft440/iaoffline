package com.example.ialocal.api

import android.content.Context
import android.util.Base64
import java.security.SecureRandom

class ApiSettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences("local_api", Context.MODE_PRIVATE)

    val port: Int
        get() = prefs.getInt(KEY_PORT, DEFAULT_PORT)

    val apiKey: String
        get() = prefs.getString(KEY_API_KEY, null) ?: generateAndPersistKey()

    val baseUrl: String
        get() = "http://127.0.0.1:$port"

    fun regenerateApiKey(): String {
        val key = newKey()
        prefs.edit().putString(KEY_API_KEY, key).apply()
        return key
    }

    private fun generateAndPersistKey(): String {
        val key = newKey()
        prefs.edit().putString(KEY_API_KEY, key).apply()
        return key
    }

    private fun newKey(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return "local_" + Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    companion object {
        const val DEFAULT_PORT = 11435
        private const val KEY_PORT = "port"
        private const val KEY_API_KEY = "api_key"
    }
}
