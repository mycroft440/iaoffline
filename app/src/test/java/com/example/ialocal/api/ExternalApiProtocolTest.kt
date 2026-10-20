package com.example.ialocal.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalApiProtocolTest {
    @Test
    fun normalizesOpenRouterAndCustomHttpsEndpoints() {
        assertEquals("https://openrouter.ai/api/v1", remoteApiBaseUrl(" https://openrouter.ai/api/v1/ "))
        assertEquals("https://example.org/v1", remoteApiBaseUrl("https://example.org/v1"))
        assertEquals("https://api.openai.com/v1", RemoteApiProvider.OPENAI.defaultUrl)
    }

    @Test
    fun rejectsHttpSecretsInUrls() {
        listOf(
            "http://example.org/v1",
            "http://127.0.0.1:1234/v1",
            "https://username:password@example.org/v1",
            "https://example.org/v1?key=secret",
            "javascript:alert(1)",
        ).forEach { invalid ->
            assertTrue("URL proibida deveria falhar: $invalid",
                runCatching { remoteApiBaseUrl(invalid) }.isFailure)
        }
    }

    @Test
    fun extractsOpenAiCompatibleStreamingText() {
        assertEquals("Olá", remoteApiEventContent(
            """data: {"choices":[{"delta":{"content":"Olá"}}]}""",
        ))
        assertEquals(" mundo", remoteApiEventContent(
            """data: {"choices":[{"delta":{"content":" mundo","role":"assistant"}}]}""",
        ))
        assertNull(remoteApiEventContent("data: [DONE]"))
        assertNull(remoteApiEventContent(": heartbeat"))
        assertNull(remoteApiEventContent("""data: {"choices":[{"delta":{"role":"assistant"}}]}"""))
    }

    @Test
    fun displaysProviderErrorsWithoutExposingSecrets() {
        assertTrue(remoteApiErrorMessage(401, "").contains("Chave"))
        assertTrue(remoteApiErrorMessage(429, "").contains("Limite"))
        assertEquals("Saldo insuficiente", remoteApiErrorMessage(
            402, """{"error":{"message":"Saldo insuficiente"}}""",
        ))
    }

    @Test
    fun remoteProviderDefaultsAreOnlyHttps() {
        assertTrue(RemoteApiProvider.entries.all { it.defaultUrl.startsWith("https://") })
        assertFalse(RemoteApiProvider.entries.any { it.defaultUrl.startsWith("http://") })
    }
}
