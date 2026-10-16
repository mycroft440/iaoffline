package com.example.ialocal.runtime

import com.example.ialocal.ai.AiChatMessage
import com.example.ialocal.chat.AssistantContent

/**
 * Turns a request into what the native binding needs: a system prompt, the earlier turns as real
 * chat turns (formatted by the model's own template, as it was trained) and the latest user
 * message. The earlier turns are normalized the way chat templates expect them: only "user" and
 * "assistant" roles, alternating, starting with the user, and answers without their reasoning.
 */
class PromptContextBuilder {
    data class Prepared(
        val systemPrompt: String,
        /** Every earlier turn, normalized; what the conversation logically contains. */
        val conversation: List<AiChatMessage>,
        /** The most recent turns of [conversation] that fit the context budget. */
        val history: List<AiChatMessage>,
        val latestUser: String,
        val truncated: Boolean,
    )

    fun prepare(
        baseSystemPrompt: String,
        messages: List<AiChatMessage>,
        contextTokens: Int,
        maxOutputTokens: Int,
    ): Prepared {
        val turns = normalize(messages)
        require(turns.isNotEmpty() && turns.last().role == USER) { "A solicitação precisa conter uma mensagem do usuário." }
        val latestUser = turns.last().content
        require(latestUser.isNotBlank()) { "A última mensagem do usuário está vazia." }
        val conversation = turns.dropLast(1)

        // Reserve at most half the context for the answer so the history keeps room; a longer
        // answer continues through the native context shift.
        val reservedOutput = minOf(maxOutputTokens, contextTokens / 2)
        val usableInputTokens = (contextTokens - reservedOutput - TOKEN_HEADROOM).coerceAtLeast(1024)
        val charBudget = usableInputTokens * APPROX_CHARS_PER_TOKEN
        val system = baseSystemPrompt.trim().ifBlank { DEFAULT_FALLBACK_SYSTEM }
        val historyBudget = (charBudget - system.length - latestUser.length - TURN_OVERHEAD_CHARS).coerceAtLeast(0)

        val selected = ArrayDeque<AiChatMessage>()
        var used = 0
        for (message in conversation.asReversed()) {
            val cost = message.content.length + TURN_OVERHEAD_CHARS
            if (used + cost > historyBudget) break
            selected.addFirst(message)
            used += cost
        }
        // Templates expect the history to open with a user turn.
        while (selected.isNotEmpty() && selected.first().role != USER) selected.removeFirst()

        return Prepared(
            systemPrompt = system,
            conversation = conversation,
            history = selected.toList(),
            latestUser = latestUser,
            truncated = selected.size < conversation.size,
        )
    }

    companion object {
        const val USER = "user"
        const val ASSISTANT = "assistant"
        private const val APPROX_CHARS_PER_TOKEN = 3
        private const val TOKEN_HEADROOM = 512
        /** Role markers and separators that the template adds around each turn. */
        private const val TURN_OVERHEAD_CHARS = 32
        private const val DEFAULT_FALLBACK_SYSTEM = "Você é um assistente útil."

        /**
         * User and assistant turns only, consecutive turns of one role merged, no leading answer
         * and no reasoning or timing marks in answers. A request whose last turn is an answer gets
         * no latest user message, which [prepare] rejects.
         */
        fun normalize(messages: List<AiChatMessage>): List<AiChatMessage> {
            val turns = mutableListOf<AiChatMessage>()
            for (message in messages) {
                val role = if (message.role.equals(ASSISTANT, ignoreCase = true)) ASSISTANT else USER
                val raw = when {
                    role == ASSISTANT -> AssistantContent.answerForHistory(message.content)
                    message.role.equals("system", ignoreCase = true) -> "[Sistema] " + message.content.trim()
                    else -> message.content
                }
                val content = raw.trim()
                if (content.isEmpty()) continue
                if (turns.isEmpty() && role == ASSISTANT) continue
                val last = turns.lastOrNull()
                if (last != null && last.role == role) {
                    turns[turns.lastIndex] = last.copy(content = last.content + "\n\n" + content)
                } else {
                    turns += AiChatMessage(role, content)
                }
            }
            return turns
        }
    }
}
