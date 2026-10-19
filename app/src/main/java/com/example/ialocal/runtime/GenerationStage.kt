package com.example.ialocal.runtime

import org.json.JSONObject

/**
 * What an answer is doing before its text shows up: waiting for its turn, loading the model,
 * reading the profile's instructions, the conversation or the message, using a tool. The chat
 * shows each step as it happens instead of a generic "preparing the answer".
 */
data class GenerationStage(
    val kind: Kind,
    /** The model being loaded or tested, the tool in use, or what the AI is busy with. */
    val detail: String? = null,
    /** Progress within the step: the turn of the conversation being read, the tokens written. */
    val done: Int = 0,
    /** What [done] counts up to; for [Kind.LOADING_MODEL], the size of the weights in MB. */
    val total: Int = 0,
) {
    enum class Kind {
        /** Other conversations are being answered; this one waits for its turn. */
        QUEUED,
        /** First use of a model: it is loaded and gives a short test answer. */
        VERIFYING,
        /** The AI process or its native engine is starting. */
        STARTING_AI,
        /** The AI is busy ([detail]: [BUSY_PREFILL], [BUSY_LOAD], [BUSY_ANSWER] or [BUSY_VERIFY]). */
        WAITING_FOR_AI,
        LOADING_MODEL,
        /** The profile's instructions, already processed for this model, come from the saved cache. */
        RESTORING_INSTRUCTIONS,
        READING_INSTRUCTIONS,
        /** The earlier turns of the conversation: [done] of [total]. */
        READING_HISTORY,
        /** The conversation is already in the model's memory ([detail]: [REUSED_SESSION] or [REUSED_PREFILL]). */
        CONTEXT_REUSED,
        CONSULTING_WIKIPEDIA,
        /** The new message ([detail] [MESSAGE_TOOL_RESULT] for a tool's result) before the first word. */
        READING_MESSAGE,
        /** Words are coming but held back, as the answer may still turn out to be a tool call. */
        WRITING,
        /** A tool the model asked for ([detail] is its name). */
        USING_TOOL,
        /** The model started reasoning; the chat shows the reasoning itself from here on. */
        REASONING,
    }

    fun encode(): String = JSONObject()
        .put("kind", kind.name)
        .put("detail", detail ?: JSONObject.NULL)
        .put("done", done)
        .put("total", total)
        .toString()

    companion object {
        const val BUSY_PREFILL = "prefill"
        const val BUSY_ANSWER = "answer"
        const val BUSY_VERIFY = "verify"
        const val BUSY_LOAD = "load"
        const val REUSED_SESSION = "session"
        const val REUSED_PREFILL = "prefill"
        const val MESSAGE_TOOL_RESULT = "tool"

        /** Null for anything that is not a stage, such as one from a newer version of the app. */
        fun decode(json: String): GenerationStage? = runCatching { fromJson(JSONObject(json)) }.getOrNull()

        fun fromJson(o: JSONObject): GenerationStage? {
            val kind = Kind.entries.firstOrNull { it.name == o.optString("kind") } ?: return null
            return GenerationStage(
                kind = kind,
                detail = if (o.isNull("detail")) null else o.optString("detail"),
                done = o.optInt("done"),
                total = o.optInt("total"),
            )
        }
    }
}
