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
    /**
     * Earlier turns of the conversation that are not in the context: left out because they did not
     * fit, or dropped from it to make room for the next message.
     */
    val skipped: Int = 0,
    /** The first user turn of the whole conversation, which tells conversations apart on storage. */
    val opening: String = "",
) {
    /**
     * True when [requestConversation] (the request's turns before its latest user message) is
     * this session's conversation followed by the answer it produced.
     */
    fun continuesWith(modelId: String, key: String, requestConversation: List<AiChatMessage>): Boolean {
        if (modelId != this.modelId || key != this.key) return false
        if (requestConversation.size < skipped) return false
        val request = requestConversation.drop(skipped)
        if (request.size != conversation.size + 1) return false
        val answer = request.last()
        if (answer.role != PromptContextBuilder.ASSISTANT) return false
        if (!sameTurns(conversation, request.dropLast(1))) return false
        return sameAnswer(answer.content, reply)
    }

    /**
     * True when the context already holds [conversation] (the turns before the next message): it
     * continues with this answer, or it is this conversation just before the answer, from a
     * prefill that arrives after the answer it was preparing.
     */
    fun holds(modelId: String, key: String, conversation: List<AiChatMessage>): Boolean {
        if (continuesWith(modelId, key, conversation)) return true
        if (modelId != this.modelId || key != this.key) return false
        if (conversation.size < skipped) return false
        val request = conversation.drop(skipped)
        if (request.size !in this.conversation.size - 1..this.conversation.size) return false
        return sameTurns(this.conversation.take(request.size), request)
    }

    /** Whether the new message and the answer still fit without the native context shift. */
    fun hasRoomFor(latestUserChars: Int, maxOutputTokens: Int, contextTokens: Int): Boolean =
        tokens + latestUserChars / CHARS_PER_TOKEN + continuationReserve(maxOutputTokens, contextTokens) + HEADROOM_TOKENS <=
            contextTokens

    /**
     * How many of the oldest turns in the context to drop so that the next message and its answer fit
     * with a quarter of the context left over, which lets the following messages continue too: whole
     * user and answer pairs, keeping at least the latest one. 0 when dropping cannot make room.
     */
    fun turnsToDrop(latestUserChars: Int, maxOutputTokens: Int, contextTokens: Int): Int {
        val target = contextTokens * 3 / 4 - latestUserChars / CHARS_PER_TOKEN -
            continuationReserve(maxOutputTokens, contextTokens) - HEADROOM_TOKENS
        var remaining = tokens
        var turns = 0
        // The context holds the conversation and then the answer; its last user turn stays.
        while (remaining > target && turns + 2 < conversation.size) {
            remaining -= estimatedTokens(conversation[turns]) + estimatedTokens(conversation[turns + 1])
            turns += 2
        }
        return if (remaining <= target) turns else 0
    }

    /** What tells this conversation apart: [opening], or its first turn when the whole conversation is in the context. */
    fun origin(): String =
        collapse(withoutAttachments(opening.ifEmpty { if (skipped == 0) conversation.firstOrNull()?.content.orEmpty() else "" }))

    /** This session after its [turns] oldest turns were dropped from a context that now holds [tokens]. */
    fun dropped(turns: Int, tokens: Int): ChatSession =
        copy(conversation = conversation.drop(turns), skipped = skipped + turns, tokens = tokens)

    companion object {
        /** Conservative: Portuguese and code average more characters per token than this. */
        private const val CHARS_PER_TOKEN = 3
        private const val HEADROOM_TOKENS = 256
        /** Role markers and separators the template adds around each turn, in tokens. */
        private const val TURN_OVERHEAD_TOKENS = 8
        private const val COMPARED_PREFIX_CHARS = 300
        /** Enough of the first message to tell conversations apart. */
        const val OPENING_CHARS = 2_000
        private val WHITESPACE = Regex("\\s+")
        /**
         * Where the text of a message's attachments starts. A turn is sent with all of it and
         * rebuilt from the database with excerpts ("[Contexto persistido dos anexos]"), so turns are
         * compared without it; the context keeps the fuller text.
         */
        private const val ATTACHMENT_CONTEXT_MARKER = "\n\n[Contexto "

        private fun collapse(text: String): String = text.replace(WHITESPACE, " ").trim()

        private fun withoutAttachments(text: String): String = text.substringBefore(ATTACHMENT_CONTEXT_MARKER)

        private fun estimatedTokens(turn: AiChatMessage): Int = turn.content.length / CHARS_PER_TOKEN + TURN_OVERHEAD_TOKENS

        /**
         * Room kept for the answer when a conversation continues in its context. A longer answer goes on
         * through the native context shift, so the reserve is a fraction of the context instead of
         * the whole answer budget, which made long chats read again from the start much sooner.
         */
        fun continuationReserve(maxOutputTokens: Int, contextTokens: Int): Int =
            minOf(maxOutputTokens, contextTokens / 2, (contextTokens / 8).coerceIn(2048, 4096))

        /** The same turns, ignoring whitespace and the text of attachments. */
        fun sameTurns(a: List<AiChatMessage>, b: List<AiChatMessage>): Boolean =
            a.size == b.size && a.indices.all { i ->
                a[i].role == b[i].role && collapse(withoutAttachments(a[i].content)) == collapse(withoutAttachments(b[i].content))
            }

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
