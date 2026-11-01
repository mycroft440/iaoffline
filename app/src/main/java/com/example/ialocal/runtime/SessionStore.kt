package com.example.ialocal.runtime

import android.content.Context
import com.example.ialocal.ai.AiChatMessage
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * The conversations the AI process answered last, kept on storage with what the model holds of each
 * (its native context, see step 18 of scripts/prepare_llama_android.sh). When the model was freed,
 * Android stopped the AI process or the user went to another conversation, going back to one of
 * them restores it in about a second instead of reading it all again, which takes minutes for a long
 * conversation with a large model on a phone's CPU. Each conversation has a folder of its own; the
 * least recently used ones are deleted beyond [capacity] conversations or [maxBytes] together.
 * They live in the cache directory: if Android clears it, a conversation is simply read again.
 */
class SessionStore(
    private val dir: File,
    private val capacity: () -> Int = { 1 },
    private val maxBytes: () -> Long = { Long.MAX_VALUE },
) {
    /**
     * A conversation on storage: its turns ([session]) and the native context next to them. [used]
     * orders the conversations by their last use.
     */
    class Saved internal constructor(
        val session: ChatSession,
        val stateFile: File,
        internal val folder: File,
        internal val used: Long,
    )

    /** The folder [beginSave] chose, until [finishSave]. */
    private var saving: File? = null

    /** The saved conversations whose native context is there, the most recently used first. */
    fun all(): List<Saved> = folders().mapNotNull(::read).sortedByDescending { it.used }

    /** The most recently used saved conversation that [accepts]. */
    fun find(accepts: (ChatSession) -> Boolean): Saved? = all().firstOrNull { accepts(it.session) }

    /** Marks [saved] as just used, so it is the last one to be deleted. */
    fun touch(saved: Saved) {
        write(saved.folder, saved.session)
    }

    /**
     * Starts saving [session] in the folder of its conversation (an earlier state of it is replaced)
     * and returns the file the native side writes the context to. Until [finishSave], that folder
     * holds nothing to restore.
     */
    fun beginSave(session: ChatSession): File {
        deleteSingleConversationFiles()
        val folder = File(dir, folderOf(session))
        folder.mkdirs()
        File(folder, SESSION).delete()
        saving = folder
        return File(folder, STATE)
    }

    fun finishSave(session: ChatSession) {
        val folder = saving ?: return
        saving = null
        write(folder, session)
        evict(keep = folder)
    }

    /** Writes the turns of [session] to [folder] as its most recently used conversation. */
    private fun write(folder: File, session: ChatSession) {
        val used = (all().maxOfOrNull { it.used } ?: 0L) + 1
        val partial = File(folder, "$SESSION.partial")
        partial.writeText(encode(session).put(USED, used).toString())
        if (!partial.renameTo(File(folder, SESSION))) partial.delete()
    }

    fun clear() {
        dir.deleteRecursively()
    }

    /** Deletes the saved conversations of [modelId], as when the model is deleted. */
    fun forget(modelId: String) {
        deleteSingleConversationFiles()
        all().filter { it.session.modelId == modelId }.forEach { it.folder.deleteRecursively() }
    }

    private fun folders(): List<File> = dir.listFiles { file -> file.isDirectory }?.toList().orEmpty()

    private fun read(folder: File): Saved? = runCatching {
        val state = File(folder, STATE)
        val session = File(folder, SESSION)
        if (!state.isFile || !session.isFile) return null
        val json = JSONObject(session.readText())
        Saved(decode(json), state, folder, json.optLong(USED))
    }.getOrNull()

    /**
     * Deletes the least recently used conversations beyond [capacity] or [maxBytes], and folders a
     * save left without its turns, except [keep], just saved.
     */
    private fun evict(keep: File) {
        val complete = all()
        folders().filter { folder -> folder != keep && complete.none { it.folder == folder } }.forEach { it.deleteRecursively() }
        val byUse = complete.map { it.folder }.sortedByDescending { it == keep }
        val limit = capacity().coerceAtLeast(1)
        var total = 0L
        byUse.forEachIndexed { index, folder ->
            val bytes = folder.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            if (folder != keep && (index >= limit || total + bytes > maxBytes())) {
                folder.deleteRecursively()
            } else {
                total += bytes
            }
        }
    }

    /** The files of the version that kept one conversation, directly in [dir]. */
    private fun deleteSingleConversationFiles() {
        dir.listFiles { file -> file.isFile }?.forEach { it.delete() }
    }

    companion object {
        private const val DIR = "session-cache"
        private const val STATE = "conversation.state"
        private const val SESSION = "conversation.json"
        private const val USED = "used"

        /**
         * Below this estimate of the time to read the conversation again, writing its context
         * (tens to hundreds of MB) after every answer is not worth it.
         */
        const val MIN_READ_AGAIN_MS = 10_000L
        /** Fewer tokens than this make the measured reading speed mostly overhead. */
        private const val MIN_MEASURED_TOKENS = 16
        /** Without a usable measurement: about a long system prompt and a few turns. */
        private const val UNMEASURED_MIN_TOKENS = 1_024
        /** Storage the saved conversations may take: at most this, and a tenth of the free space. */
        const val MAX_STORAGE_BYTES = 1024L * 1024 * 1024

        /** [capacity] is how many conversations are kept ready ([RuntimeTuning.readyConversations]). */
        fun of(context: Context, capacity: () -> Int = { RuntimeTuning.DEFAULT_READY_CONVERSATIONS }): SessionStore {
            val cacheDir = context.cacheDir
            return SessionStore(File(cacheDir, DIR), capacity) {
                minOf(MAX_STORAGE_BYTES, cacheDir.usableSpace / 10)
            }
        }

        /** One folder per conversation: its model, instructions and first message. */
        internal fun folderOf(session: ChatSession): String =
            "c-" + PromptCache.hashOf(session.modelId + "\u0000" + session.key + "\u0000" + session.origin())

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
            .put("skipped", session.skipped)
            .put("opening", session.opening)

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
                skipped = o.optInt("skipped"),
                opening = o.optString("opening"),
            )
        }
    }
}
