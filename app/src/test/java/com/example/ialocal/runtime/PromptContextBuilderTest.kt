package com.example.ialocal.runtime

import com.example.ialocal.ai.AiChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptContextBuilderTest {
    private val builder = PromptContextBuilder()

    @Test
    fun keepsLatestUserOutsideFoldedHistory() {
        val result = builder.prepare(
            baseSystemPrompt = "Sistema base",
            messages = listOf(
                AiChatMessage("user", "primeira"),
                AiChatMessage("assistant", "resposta"),
                AiChatMessage("user", "última pergunta"),
            ),
            contextTokens = 8192,
            maxOutputTokens = 512,
        )

        assertEquals("última pergunta", result.latestUser)
        assertTrue(result.systemPrompt.contains("primeira"))
        assertTrue(result.systemPrompt.contains("resposta"))
        assertFalse(result.systemPrompt.contains("última pergunta"))
    }

    @Test
    fun truncatesOldHistoryWhenBudgetIsSmall() {
        val longText = "x".repeat(5000)
        val result = builder.prepare(
            baseSystemPrompt = "Sistema",
            messages = listOf(
                AiChatMessage("user", longText),
                AiChatMessage("assistant", longText),
                AiChatMessage("user", "agora"),
            ),
            contextTokens = 2048,
            maxOutputTokens = 512,
        )

        assertTrue(result.truncated)
        assertEquals("agora", result.latestUser)
    }
}
