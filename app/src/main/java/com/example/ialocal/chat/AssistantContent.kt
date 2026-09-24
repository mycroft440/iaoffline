package com.example.ialocal.chat

/**
 * A stored assistant message: optional reasoning, the answer, and the timings appended after
 * generation as HTML comments (`<!--nexus_response_ms:N-->`, `<!--nexus_reasoning_ms:N-->`).
 */
data class AssistantContent(
    /** Null when the message has no reasoning block; may be blank while reasoning just started. */
    val reasoning: String?,
    val answer: String,
    /** True while the reasoning block is still open (the model is thinking). */
    val reasoningInProgress: Boolean,
    val reasoningMs: Long?,
    val responseMs: Long?,
) {
    companion object {
        private val METADATA = Regex("(?is)<!--nexus_(reasoning|response)_ms:(\\d+)-->")
        private val OPEN = Regex("(?is)<(?:think(?:ing)?|reasoning|analysis)>|\\[THINK]|<\\|channel>thought")
        private val CLOSE = Regex("(?is)</(?:think(?:ing)?|reasoning|analysis)>|\\[/THINK]|<channel\\|>")

        fun parse(raw: String): AssistantContent {
            var reasoningMs: Long? = null
            var responseMs: Long? = null
            METADATA.findAll(raw).forEach { match ->
                val value = match.groupValues[2].toLongOrNull()
                if (match.groupValues[1].equals("reasoning", true)) reasoningMs = value else responseMs = value
            }
            val clean = METADATA.replace(raw, "").trim()
            val open = OPEN.find(clean)
            val close = CLOSE.find(clean)
            return when {
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
        }

        /** Appends timings to a finished message, replacing any earlier ones. */
        fun withTimings(content: String, responseMs: Long, reasoningMs: Long?): String = buildString {
            append(METADATA.replace(content, "").trimEnd())
            append("\n\n<!--nexus_response_ms:").append(responseMs.coerceAtLeast(0L)).append("-->")
            if (reasoningMs != null) append("<!--nexus_reasoning_ms:").append(reasoningMs.coerceAtLeast(0L)).append("-->")
        }

        /** The answer alone, as sent back to the model in later turns. */
        fun answerForHistory(raw: String): String = parse(raw).answer

        fun hasReasoningStarted(content: String): Boolean = OPEN.containsMatchIn(content)
        fun hasReasoningEnded(content: String): Boolean = CLOSE.containsMatchIn(content)
    }
}
