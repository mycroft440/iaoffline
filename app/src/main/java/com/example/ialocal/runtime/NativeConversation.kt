package com.example.ialocal.runtime

import com.example.ialocal.ai.AiChatMessage

/**
 * The conversation held by the native context after an answer that ended normally. When the next
 * request is this conversation plus that answer and one new user message, only the new message is
 * sent: the model keeps what it already read instead of processing the system prompt and the whole
 * history again, which with a 7-9B model on a weak phone takes minutes per message.
 */
data class NativeConversation(
    val modelId: String,
    val systemPrompt: String,
    /** Messages of the request that produced the answer, ending with the user message answered. */
    val messages: List<AiChatMessage>,
    /** Context positions in use after the answer. */
    val usedTokens: Int,
    val contextTokens: Int,
) {
    /** The new user message when [messages] continues this conversation, or null to rebuild it. */
    fun continuation(
        modelId: String,
        systemPrompt: String,
        messages: List<AiChatMessage>,
        maxOutputTokens: Int,
    ): String? {
        if (modelId != this.modelId || systemPrompt != this.systemPrompt) return null
        if (messages.size != this.messages.size + 2) return null
        if (this.messages.indices.any { !sameMessage(this.messages[it], messages[it]) }) return null
        // The answer itself is already in the context as generated; the app's copy may be trimmed.
        if (!messages[this.messages.size].role.equals("assistant", ignoreCase = true)) return null
        val next = messages.last()
        if (!next.role.equals("user", ignoreCase = true)) return null
        val text = next.content.trim()
        if (text.isEmpty()) return null
        // Room for the message and for the answer, reserved as PromptContextBuilder does.
        val needed = usedTokens + text.length / MIN_CHARS_PER_TOKEN + TURN_OVERHEAD_TOKENS +
            minOf(maxOutputTokens, contextTokens / 2)
        return text.takeIf { needed <= contextTokens }
    }

    private fun sameMessage(sent: AiChatMessage, now: AiChatMessage): Boolean =
        sent.role.equals(now.role, ignoreCase = true) && comparable(sent.content) == comparable(now.content)

    companion object {
        /** The user message that was answered may carry a DeepThink switch the history copy lacks. */
        internal fun comparable(content: String): String =
            content.trim().removeSuffix("/no_think").removeSuffix("/think").trimEnd()

        /** Pessimistic, so that a long message rebuilds the conversation instead of overflowing it. */
        private const val MIN_CHARS_PER_TOKEN = 2
        private const val TURN_OVERHEAD_TOKENS = 64
    }
}
