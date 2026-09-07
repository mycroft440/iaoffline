package com.example.ialocal.runtime

import com.example.ialocal.ai.AiChatMessage

/**
 * Builds a stateless prompt for the Android llama.cpp binding.
 * The binding accepts one system prompt after model load, so previous turns are folded into it.
 */
class PromptContextBuilder {
    data class Prepared(val systemPrompt: String, val latestUser: String, val truncated: Boolean)

    fun prepare(
        baseSystemPrompt: String,
        messages: List<AiChatMessage>,
        contextTokens: Int,
        maxOutputTokens: Int,
    ): Prepared {
        val lastUserIndex = messages.indexOfLast { it.role.equals("user", ignoreCase = true) }
        require(lastUserIndex >= 0) { "A solicitação precisa conter uma mensagem do usuário." }
        val latestUser = messages[lastUserIndex].content.trim()
        require(latestUser.isNotBlank()) { "A última mensagem do usuário está vazia." }

        // Conservative approximation. Native context is 8192 in the pinned Android binding.
        val usableInputTokens = (contextTokens - maxOutputTokens - TOKEN_HEADROOM).coerceAtLeast(1024)
        val charBudget = usableInputTokens * APPROX_CHARS_PER_TOKEN
        val base = baseSystemPrompt.trim().ifBlank { DEFAULT_FALLBACK_SYSTEM }
        val historyBudget = (charBudget - base.length - latestUser.length - 512).coerceAtLeast(0)

        val history = messages.take(lastUserIndex)
        val selected = ArrayDeque<String>()
        var used = 0
        var truncated = false
        for (message in history.asReversed()) {
            val role = when (message.role.lowercase()) {
                "assistant" -> "Assistente"
                "system" -> "Sistema"
                else -> "Usuário"
            }
            val block = "[$role] ${message.content.trim()}\n"
            if (used + block.length > historyBudget) {
                truncated = true
                break
            }
            selected.addFirst(block)
            used += block.length
        }

        val prompt = buildString {
            append(base)
            if (selected.isNotEmpty()) {
                append("\n\nHistórico anterior fornecido pela aplicação:\n")
                selected.forEach(::append)
                if (truncated) append("[Sistema] Parte mais antiga do histórico foi omitida por limite de contexto.\n")
                append("Continue a conversa respeitando o histórico acima.")
            }
        }
        return Prepared(prompt, latestUser, truncated)
    }

    companion object {
        private const val APPROX_CHARS_PER_TOKEN = 3
        private const val TOKEN_HEADROOM = 512
        private const val DEFAULT_FALLBACK_SYSTEM = "Você é um assistente útil."
    }
}
