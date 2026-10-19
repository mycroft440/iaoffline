package com.example.ialocal.runtime

import com.example.ialocal.ai.AiChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptContextBuilderTest {
    private val builder = PromptContextBuilder()

    @Test
    fun earlierTurnsBecomeChatTurnsInsteadOfSystemPromptText() {
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
        assertEquals("Sistema base", result.systemPrompt)
        assertEquals(listOf(AiChatMessage("user", "primeira"), AiChatMessage("assistant", "resposta")), result.history)
        assertEquals(result.history, result.conversation)
    }

    @Test
    fun aPrefillPicksTheHistoryTheNextMessageWillUse() {
        // Opening the chat prepares the conversation so far; sending then finds it already read.
        val conversation = listOf(
            AiChatMessage("assistant", "Olá! Como posso ajudar?"),
            AiChatMessage("user", "primeira"),
            AiChatMessage("assistant", "<think>x</think>resposta"),
        )
        val prefill = builder.prepareHistory("Sistema base", conversation, contextTokens = 8192, maxOutputTokens = 512)
        val request = builder.prepare(
            baseSystemPrompt = "Sistema base",
            messages = conversation + AiChatMessage("user", "segunda"),
            contextTokens = 8192,
            maxOutputTokens = 512,
        )

        assertEquals(request.systemPrompt, prefill.systemPrompt)
        assertEquals(request.conversation, prefill.conversation)
        assertEquals(request.history, prefill.history)
        assertEquals(listOf(AiChatMessage("user", "primeira"), AiChatMessage("assistant", "resposta")), prefill.history)
    }

    @Test
    fun normalizesTurnsTheWayChatTemplatesExpect() {
        val result = builder.prepare(
            baseSystemPrompt = "Sistema",
            messages = listOf(
                AiChatMessage("assistant", "Olá! Como posso ajudar?"),
                AiChatMessage("user", "A"),
                AiChatMessage("assistant", "<think>pensando</think>\n\nRespondo A\n\n<!--nexus_response_ms:1200-->"),
                AiChatMessage("user", "B sem resposta"),
                AiChatMessage("user", "C"),
            ),
            contextTokens = 8192,
            maxOutputTokens = 512,
        )

        // No leading answer, no reasoning or timings, and consecutive user turns merged.
        assertEquals(listOf(AiChatMessage("user", "A"), AiChatMessage("assistant", "Respondo A")), result.history)
        assertEquals("B sem resposta\n\nC", result.latestUser)
    }

    @Test
    fun truncatesOldHistoryWhenBudgetIsSmall() {
        val longText = "x".repeat(5000)
        val result = builder.prepare(
            baseSystemPrompt = "Sistema",
            messages = listOf(
                AiChatMessage("user", longText),
                AiChatMessage("assistant", longText),
                AiChatMessage("user", "curta"),
                AiChatMessage("assistant", "ok"),
                AiChatMessage("user", "agora"),
            ),
            contextTokens = 2048,
            maxOutputTokens = 512,
        )

        assertTrue(result.truncated)
        assertEquals("agora", result.latestUser)
        // The kept history still starts with a user turn.
        assertEquals(listOf(AiChatMessage("user", "curta"), AiChatMessage("assistant", "ok")), result.history)
        assertEquals(4, result.conversation.size)
    }

    @Test
    fun maximumAnswerBudgetStillLeavesRoomForHistory() {
        val history = "h".repeat(5000)
        val result = builder.prepare(
            baseSystemPrompt = "Sistema",
            messages = listOf(
                AiChatMessage("user", history),
                AiChatMessage("assistant", "ok"),
                AiChatMessage("user", "continue"),
            ),
            contextTokens = 8192,
            maxOutputTokens = RuntimeLimits.MAX_OUTPUT_TOKENS,
        )

        assertFalse(result.truncated)
        assertEquals(history, result.history.first().content)
    }
}
