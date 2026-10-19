package com.example.ialocal.chat

import com.example.ialocal.models.ModelProvider
import java.util.Locale

/**
 * A stored assistant message: optional reasoning, the answer, and what is appended after
 * generation as HTML comments: the timings (`<!--nexus_response_ms:N-->`,
 * `<!--nexus_reasoning_ms:N-->`), the speed the model wrote at (`<!--nexus_tokens_per_second:2.64-->`)
 * and the company of the model that answered (`<!--nexus_provider:DEEPSEEK-->`).
 */
data class AssistantContent(
    /** Null when the message has no reasoning block; may be blank while reasoning just started. */
    val reasoning: String?,
    val answer: String,
    /** True while the reasoning block is still open (the model is thinking). */
    val reasoningInProgress: Boolean,
    val reasoningMs: Long?,
    val responseMs: Long?,
    /** Null for replies saved before it was recorded, still being written or from an unknown model. */
    val provider: ModelProvider? = null,
    /** Tokens per second while writing; null when not measured (older replies, a cancelled answer). */
    val tokensPerSecond: Double? = null,
) {
    companion object {
        private val METADATA = Regex("(?is)<!--nexus_(reasoning|response)_ms:(\\d+)-->")
        private val PROVIDER = Regex("(?is)<!--nexus_provider:(\\w+)-->")
        private val SPEED = Regex("(?is)<!--nexus_tokens_per_second:(\\d+(?:\\.\\d+)?)-->")
        private val OPEN = Regex("(?is)<(?:think(?:ing)?|reasoning|analysis)>|\\[THINK]|<\\|channel>thought")
        private val CLOSE = Regex("(?is)</(?:think(?:ing)?|reasoning|analysis)>|\\[/THINK]|<channel\\|>")

        fun parse(raw: String): AssistantContent {
            var reasoningMs: Long? = null
            var responseMs: Long? = null
            METADATA.findAll(raw).forEach { match ->
                val value = match.groupValues[2].toLongOrNull()
                if (match.groupValues[1].equals("reasoning", true)) reasoningMs = value else responseMs = value
            }
            val providerName = PROVIDER.find(raw)?.groupValues?.get(1)
            val provider = ModelProvider.entries.firstOrNull { it.name == providerName }
            val tokensPerSecond = SPEED.find(raw)?.groupValues?.get(1)?.toDoubleOrNull()
            val clean = withoutMetadata(raw).trim()
            val open = OPEN.find(clean)
            val close = CLOSE.find(clean)
            val content = when {
                open != null && close != null && close.range.first > open.range.first -> AssistantContent(
                    reasoning = clean.substring(open.range.last + 1, close.range.first).trim(),
                    answer = (clean.substring(0, open.range.first) + clean.substring(close.range.last + 1)).trim(),
                    reasoningInProgress = false,
                    reasoningMs = reasoningMs,
                    responseMs = responseMs,
                )
                open != null -> AssistantContent(
                    reasoning = clean.substring(open.range.last + 1).trim(),
                    answer = clean.substring(0, open.range.first).trim(),
                    reasoningInProgress = true,
                    reasoningMs = reasoningMs,
                    responseMs = responseMs,
                )
                // Some templates open the block in the prompt, so only the closing tag is stored.
                close != null -> AssistantContent(
                    reasoning = clean.substring(0, close.range.first).trim(),
                    answer = clean.substring(close.range.last + 1).trim(),
                    reasoningInProgress = false,
                    reasoningMs = reasoningMs,
                    responseMs = responseMs,
                )
                else -> AssistantContent(null, clean, false, reasoningMs, responseMs)
            }
            return content.copy(provider = provider, tokensPerSecond = tokensPerSecond)
        }

        /**
         * Appends timings, the writing speed and the answering model's company to a finished
         * message, replacing earlier ones.
         */
        fun withTimings(
            content: String,
            responseMs: Long,
            reasoningMs: Long?,
            provider: ModelProvider? = null,
            tokensPerSecond: Double? = null,
        ): String = buildString {
            append(withoutMetadata(content).trimEnd())
            append("\n\n<!--nexus_response_ms:").append(responseMs.coerceAtLeast(0L)).append("-->")
            if (reasoningMs != null) append("<!--nexus_reasoning_ms:").append(reasoningMs.coerceAtLeast(0L)).append("-->")
            if (tokensPerSecond != null && tokensPerSecond > 0.0) {
                append("<!--nexus_tokens_per_second:").append(String.format(Locale.ROOT, "%.2f", tokensPerSecond)).append("-->")
            }
            if (provider != null) append("<!--nexus_provider:").append(provider.name).append("-->")
        }

        private fun withoutMetadata(content: String): String =
            SPEED.replace(PROVIDER.replace(METADATA.replace(content, ""), ""), "")

        /** The answer alone, as sent back to the model in later turns. */
        fun answerForHistory(raw: String): String = parse(raw).answer

        fun hasReasoningStarted(content: String): Boolean = OPEN.containsMatchIn(content)
        fun hasReasoningEnded(content: String): Boolean = CLOSE.containsMatchIn(content)
    }
}
