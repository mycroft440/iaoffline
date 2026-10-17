package com.example.ialocal.runtime.remote

import com.example.ialocal.ai.AiChatMessage
import com.example.ialocal.data.AiModelEntity
import com.example.ialocal.runtime.TurnOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteProtocolTest {
    private val model = AiModelEntity(
        id = "m1",
        name = "Gemma 4 E4B QAT",
        apiModelId = "gemma4-e4b-it-qat-q4-0",
        format = "gguf",
        architecture = null,
        filePath = "/data/user/0/app/files/models/gemma.gguf",
        sizeBytes = 5_154_941_280L,
        importedAt = 1_700_000_000_000L,
        isActive = true,
        declaredContextLength = 131072,
        verificationStatus = "VERIFIED",
        lastVerifiedAt = null,
    )

    @Test
    fun modelsCrossTheProcessUnchanged() {
        assertEquals(model, RemoteProtocol.decodeModel(RemoteProtocol.encodeModel(model)))
        val full = model.copy(architecture = "gemma4", sizeLabel = "4B", lastError = "x", lastVerifiedAt = 5L)
        assertEquals(full, RemoteProtocol.decodeModel(RemoteProtocol.encodeModel(full)))
    }

    @Test
    fun requestsCrossTheProcessUnchanged() {
        val request = GenerateRequest(
            model = model,
            systemPrompt = "Sistema",
            messages = listOf(AiChatMessage("user", "Olá"), AiChatMessage("assistant", "Oi!"), AiChatMessage("user", "Tudo bem?")),
            temperature = 0.3f,
            maxTokens = 2048,
            turn = TurnOptions(userSuffix = " /no_think", assistantPrefix = "<think>\n\n</think>\n\n", templateThinking = false),
            safeLevel = 1,
        )
        assertEquals(request, RemoteProtocol.decodeRequest(RemoteProtocol.encodeRequest(request)))
    }

    @Test
    fun longConversationsKeepTheirMostRecentMessagesWithinTheBinderLimit() {
        val messages = (1..10).map { AiChatMessage(if (it % 2 == 1) "user" else "assistant", "m$it" + "x".repeat(98)) }
        val kept = RemoteProtocol.fitToLimit(messages, limit = 350)
        assertEquals(messages.takeLast(3), kept)
    }

    @Test
    fun aHugeLastMessageKeepsItsEnd() {
        val kept = RemoteProtocol.fitToLimit(listOf(AiChatMessage("user", "a".repeat(1000) + "pergunta?")), limit = 100)
        assertEquals(1, kept.size)
        assertEquals(100, kept.single().content.length)
        assertTrue(kept.single().content.endsWith("pergunta?"))
    }
}
