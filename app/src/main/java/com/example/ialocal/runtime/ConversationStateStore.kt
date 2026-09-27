package com.example.ialocal.runtime

import com.example.ialocal.ai.AiChatMessage
import java.io.File
import java.security.MessageDigest

/**
 * Native conversation states saved on disk. Restoring one takes a fraction of a second, while
 * processing the same tokens again takes tens of seconds (a system prompt with the tool list) to
 * minutes (a long history) with a 9B model on a phone. Two kinds are kept:
 * - a profile's system prompt, shared by every new conversation with that model;
 * - a conversation after its last answer, for when it is opened again later, after another
 *   conversation used the model or after Android closed the app.
 *
 * A state is only valid for the exact model file, so the model's id, path and size are part of
 * the key. Files are dropped least recently used first, within [maxBytes] and [maxFiles].
 */
class ConversationStateStore(
    private val directory: File,
    private val maxBytes: Long = MAX_BYTES,
    private val maxFiles: Int = MAX_FILES,
    private val minFreeBytes: Long = MIN_FREE_BYTES,
) {
    data class Model(val id: String, val path: String, val sizeBytes: Long)

    fun systemPromptKey(model: Model, systemPrompt: String): String =
        key("system", model, systemPrompt, emptyList())

    /** Key of the conversation that [messages] (ending with the user message answered) produced. */
    fun conversationKey(model: Model, systemPrompt: String, messages: List<AiChatMessage>): String =
        key("conversation", model, systemPrompt, messages)

    /** The saved state for [key], marked as recently used, or null if there is none. */
    fun find(key: String): File? {
        val state = stateFile(key)
        val chat = chatFile(state)
        if (!state.isFile || !chat.isFile) return null
        val now = System.currentTimeMillis()
        state.setLastModified(now)
        chat.setLastModified(now)
        return state
    }

    /** Where to save [key], or null when the device is short of storage. */
    fun fileForSaving(key: String): File? {
        if (!directory.isDirectory && !directory.mkdirs()) return null
        if (directory.usableSpace < minFreeBytes) return null
        return stateFile(key)
    }

    fun remove(key: String) {
        val state = stateFile(key)
        chatFile(state).delete()
        state.delete()
    }

    /** Drops the least recently used states beyond the limits, never [keep]. */
    fun prune(keep: String? = null) {
        val kept = keep?.let(::stateFile)
        val states = directory.listFiles { file -> file.name.endsWith(STATE_SUFFIX) }.orEmpty()
            .sortedByDescending { it.lastModified() }
        var bytes = 0L
        states.forEachIndexed { index, state ->
            val size = state.length() + chatFile(state).length()
            bytes += size
            if (state != kept && (index >= maxFiles || bytes > maxBytes)) {
                chatFile(state).delete()
                state.delete()
                bytes -= size
            }
        }
        // A state whose companion file was lost cannot be restored.
        directory.listFiles { file -> file.name.endsWith(CHAT_SUFFIX) }.orEmpty()
            .filter { !File(directory, it.name.removeSuffix(".chat")).isFile }
            .forEach { it.delete() }
    }

    private fun stateFile(key: String) = File(directory, key + STATE_SUFFIX)

    /** Written next to the state by the native binding: chat history and positions. */
    private fun chatFile(state: File) = File(state.path + ".chat")

    private fun key(kind: String, model: Model, systemPrompt: String, messages: List<AiChatMessage>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun add(value: String) {
            digest.update(value.toByteArray(Charsets.UTF_8))
            digest.update(0)
        }
        add(FORMAT_VERSION)
        add(kind)
        add(model.id)
        add(model.path)
        add(model.sizeBytes.toString())
        add(systemPrompt)
        messages.forEach { message ->
            add(message.role.lowercase())
            add(NativeConversation.comparable(message.content))
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        /** Change when the native state format changes, so old files are simply not found. */
        private const val FORMAT_VERSION = "1"
        private const val STATE_SUFFIX = ".state"
        private const val CHAT_SUFFIX = ".state.chat"

        /** A 9B hybrid state is 55-150 MB, mostly its recurrent part. */
        const val MAX_BYTES = 768L * 1024 * 1024
        const val MAX_FILES = 8
        const val MIN_FREE_BYTES = 2L * 1024 * 1024 * 1024
    }
}
