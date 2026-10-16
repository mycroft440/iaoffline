package com.example.ialocal.runtime

import com.example.ialocal.ai.AiChatMessage
import com.example.ialocal.chat.AssistantContent

/**
 * What the native context holds after an answer that ended normally. When the next request is the
 * same conversation plus one new message, the runtime continues this context: no model reload and
 * no re-reading of the history, so the answer starts after processing only the new message.
 */
data class ChatSession(
    val modelId: String,
    /** System prompt plus the switches that change how the conversation is formatted. */
    val key: String,
    /** Normalized turns in the context, ending with the user turn that was answered. */
    val conversation: List<AiChatMessage>,
    /** The answer as the runtime streamed it. */
    val reply: String,
    /** Tokens in use in the native context. */
    val tokens: Int,
) {
    /**
     * True when [requestConversation] (the request's turns before its latest user message) is
     * this session's conversation followed by the answer it produced.
     */
    fun continuesWith(modelId: String, key: String, requestConversation: List<AiChatMessage>): Boolean {
        if (modelId != this.modelId || key != this.key) return false
        if (requestConversation.size != conversation.size + 1) return false
        val answer = requestConversation.last()
        if (answer.role != PromptContextBuilder.ASSISTANT) return false
        for (i in conversation.indices) {
            val expected = conversation[i]
            val actual = requestConversation[i]
            if (expected.role != actual.role || collapse(expected.content) != collapse(actual.content)) return false
        }
        return sameAnswer(answer.content, reply)
    }

    /** Whether the new message and the answer still fit without the native context shift. */
    fun hasRoomFor(latestUserChars: Int, maxOutputTokens: Int, contextTokens: Int): Boolean {
        val reservedOutput = minOf(maxOutputTokens, contextTokens / 2)
        return tokens + latestUserChars / CHARS_PER_TOKEN + reservedOutput + HEADROOM_TOKENS <= contextTokens
    }

    companion object {
        /** Conservative: Portuguese and code average more characters per token than this. */
        private const val CHARS_PER_TOKEN = 3
        private const val HEADROOM_TOKENS = 256
        private const val COMPARED_PREFIX_CHARS = 300
        private val WHITESPACE = Regex("\\s+")

        private fun collapse(text: String): String = text.replace(WHITESPACE, " ").trim()

        /** The stored answer may lose its reasoning, timings or surrounding whitespace. */
        fun sameAnswer(stored: String, streamed: String): Boolean {
            val a = collapse(AssistantContent.answerForHistory(stored))
            val b = collapse(AssistantContent.answerForHistory(streamed))
            if (a == b) return true
            return minOf(a.length, b.length) >= COMPARED_PREFIX_CHARS &&
                a.take(COMPARED_PREFIX_CHARS) == b.take(COMPARED_PREFIX_CHARS)
        }
    }
}
