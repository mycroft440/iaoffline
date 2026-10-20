package com.example.ialocal.runtime

import android.content.Context
import com.example.ialocal.ai.AiChatMessage
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * The conversation the AI process answered last, kept on storage with what the model holds of it
 * (its native context, see step 18 of scripts/prepare_llama_android.sh). When the model was freed
 * or Android stopped the AI process, going back to that conversation restores it in about a second
 * instead of reading it all again, which takes minutes for a long conversation with a large model
 * on a phone's CPU. One conversation is kept, so the files never take more than one context.
 * They live in the cache directory: if Android clears it, the conversation is simply read again.
 */
class SessionStore(private val dir: File) {
    /** The native context; the native side writes its turns and positions next to it (".meta"). */
    val stateFile: File get() = File(dir, STATE)
    private val sessionFile: File get() = File(dir, SESSION)

    /** The saved conversation, if its native context is there too. */
    fun load(): ChatSession? = runCatching {
        if (!stateFile.isFile) return null
        decode(JSONObject(sessionFile.readText()))
    }.getOrNull()

    /** Called before the native side writes [stateFile]: until [finishSave], nothing is saved. */
    fun beginSave(): File {
        dir.mkdirs()
        sessionFile.delete()
        return stateFile
    }

    fun finishSave(session: ChatSession) {
        val partial = File(dir, "$SESSION.partial")
        partial.writeText(encode(session).toString())
        if (!partial.renameTo(sessionFile)) partial.delete()
    }

    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }

    /** Deletes the saved conversation if [modelId] wrote it, as when the model is deleted. */
    fun forget(modelId: String) {
        if (load()?.modelId == modelId) clear()
    }

    companion object {
        private const val DIR = "session-cache"
        private const val STATE = "conversation.state"
        private const val SESSION = "conversation.json"

        /**
         * Below this estimate of the time to read the conversation again, writing its context
         * (tens to hundreds of MB) after every answer is not worth it.
         */
        const val MIN_READ_AGAIN_MS = 10_000L
        /** Fewer tokens than this make the measured reading speed mostly overhead. */
        private const val MIN_MEASURED_TOKENS = 16
        /** Without a usable measurement: about a long system prompt and a few turns. */
        private const val UNMEASURED_MIN_TOKENS = 1_024

        fun of(context: Context): SessionStore = SessionStore(File(context.cacheDir, DIR))

        /**
         * Whether to save a context of [tokens], from the speed at which the model read the
         * latest message ([speed]): when reading it all again would take [MIN_READ_AGAIN_MS].
         */
        fun worthSaving(tokens: Int, speed: AnswerSpeed?): Boolean {
            val measured = speed?.takeIf { it.promptTokens >= MIN_MEASURED_TOKENS && it.promptMillis > 0 }
                ?: return tokens >= UNMEASURED_MIN_TOKENS
            return tokens.toLong() * measured.promptMillis / measured.promptTokens >= MIN_READ_AGAIN_MS
        }

        internal fun encode(session: ChatSession): JSONObject = JSONObject()
            .put("modelId", session.modelId)
            .put("key", session.key)
            .put(
                "conversation",
                JSONArray().apply {
                    session.conversation.forEach { put(JSONObject().put("role", it.role).put("content", it.content)) }
                },
            )
            .put("reply", session.reply)
            .put("tokens", session.tokens)

        internal fun decode(o: JSONObject): ChatSession {
            val turns = o.getJSONArray("conversation")
            return ChatSession(
                modelId = o.getString("modelId"),
                key = o.getString("key"),
                conversation = List(turns.length()) { i ->
                    val turn = turns.getJSONObject(i)
                    AiChatMessage(turn.getString("role"), turn.getString("content"))
                },
                reply = o.getString("reply"),
                tokens = o.getInt("tokens"),
            )
        }
    }
}
