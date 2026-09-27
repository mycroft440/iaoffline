package com.example.ialocal.runtime

import com.example.ialocal.ai.AiChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NativeConversationTest {
    private val firstRequest = listOf(
        AiChatMessage("user", "Qual é a capital da Austrália?"),
    )
    private val held = NativeConversation(
        modelId = "qwen",
        systemPrompt = "Sistema",
        messages = firstRequest,
        usedTokens = 400,
        contextTokens = 32_768,
    )

    private fun nextRequest(answer: String = "Canberra.", question: String = "E a do Canadá?") =
        firstRequest + AiChatMessage("assistant", answer) + AiChatMessage("user", question)

    @Test
    fun sendsOnlyTheNewMessageWhenTheConversationContinues() {
        assertEquals("E a do Canadá?", held.continuation("qwen", "Sistema", nextRequest(), 8192))
    }

    @Test
    fun ignoresHowTheAppStoredTheAnswer() {
        // The context holds the answer as generated, reasoning included; the history copy is trimmed.
        assertEquals("E a do Canadá?", held.continuation("qwen", "Sistema", nextRequest(answer = "Canberra"), 8192))
    }

    @Test
    fun matchesTheAnsweredMessageWithoutItsDeepThinkSwitch() {
        val sent = held.copy(messages = listOf(AiChatMessage("user", "Qual é a capital da Austrália? /no_think")))
        assertEquals("E a do Canadá?", sent.continuation("qwen", "Sistema", nextRequest(), 8192))
    }

    @Test
    fun continuesAToolRound() {
        val round = firstRequest + AiChatMessage("assistant", "{\"tool\":\"current_time\",\"arguments\":{}}") +
            AiChatMessage("user", "[RESULTADO DA FERRAMENTA current_time]\n10:00")
        assertEquals("[RESULTADO DA FERRAMENTA current_time]\n10:00", held.continuation("qwen", "Sistema", round, 8192))
    }

    @Test
    fun rebuildsForAnotherModelOrSystemPrompt() {
        assertNull(held.continuation("gemma", "Sistema", nextRequest(), 8192))
        assertNull(held.continuation("qwen", "Sistema\n/no_think", nextRequest(), 8192))
    }

    @Test
    fun rebuildsWhenTheHistoryChanged() {
        val edited = listOf(AiChatMessage("user", "Qual é a capital da Nova Zelândia?")) +
            AiChatMessage("assistant", "Wellington.") + AiChatMessage("user", "E a do Canadá?")
        assertNull(held.continuation("qwen", "Sistema", edited, 8192))
        // A regenerated answer sends the same history again without a new message.
        assertNull(held.continuation("qwen", "Sistema", firstRequest, 8192))
        assertNull(held.continuation("qwen", "Sistema", nextRequest() + AiChatMessage("assistant", "Ottawa."), 8192))
    }

    @Test
    fun rebuildsWhenTheAnswerWouldNotFit() {
        val almostFull = held.copy(usedTokens = 30_000)
        assertNull(almostFull.continuation("qwen", "Sistema", nextRequest(), 8192))
        // The reserve is at most half the context, as when the prompt is rebuilt.
        val small = held.copy(usedTokens = 400, contextTokens = 2048)
        assertEquals("E a do Canadá?", small.continuation("qwen", "Sistema", nextRequest(), 8192))
        assertNull(small.continuation("qwen", "Sistema", nextRequest(question = "x".repeat(1200)), 8192))
    }

    @Test
    fun rebuildsForAnEmptyMessage() {
        assertNull(held.continuation("qwen", "Sistema", nextRequest(question = "  "), 8192))
    }
}
