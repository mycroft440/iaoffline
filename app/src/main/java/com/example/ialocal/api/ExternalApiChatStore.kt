package com.example.ialocal.api

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Remote chat has its OWN file; none of these messages enter the offline Room chat database. */
data class SavedRemoteMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: String,
    val content: String,
    val createdAt: Long = System.currentTimeMillis(),
    /** Text/file references displayed to the user, not uploaded to another chat on replay. */
    val attachmentNames: List<String> = emptyList(),
)

data class SavedRemoteConversation(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val modelId: String,
    val provider: String,
    val baseUrl: String,
    val profile: String = "Padrão",
    val updatedAt: Long,
    val messages: List<SavedRemoteMessage>,
)

/**
 * Device-private, separately backed-up chat file for the online chat. The app never copies these
 * messages into the local GGUF chat history. A separate file also avoids schema migrations.
 */
class ExternalApiChatStore(context: Context) {
    private val file = AtomicFile(File(context.applicationContext.filesDir, "remote-api-conversations.json"))

    @Synchronized
    fun load(): List<SavedRemoteConversation> = runCatching {
        if (!file.baseFile.isFile) return@runCatching emptyList()
        val array = JSONArray(file.openRead().bufferedReader().use { it.readText() })
        buildList {
            for (i in 0 until array.length()) {
                val chat = array.optJSONObject(i) ?: continue
                val messages = chat.optJSONArray("messages") ?: JSONArray()
                val parsed = buildList {
                    for (j in 0 until messages.length()) {
                        val message = messages.optJSONObject(j) ?: continue
                        val names = message.optJSONArray("attachments") ?: JSONArray()
                        add(
                            SavedRemoteMessage(
                                id = message.optString("id").ifBlank { UUID.randomUUID().toString() },
                                role = message.optString("role"),
                                content = message.optString("content"),
                                createdAt = message.optLong("createdAt", chat.optLong("updatedAt")),
                                attachmentNames = List(names.length()) { n -> names.optString(n) },
                            )
                        )
                    }
                }
                add(
                    SavedRemoteConversation(
                        id = chat.optString("id"),
                        title = chat.optString("title"),
                        modelId = chat.optString("modelId"),
                        provider = chat.optString("provider"),
                        baseUrl = chat.optString("baseUrl"),
                        profile = chat.optString("profile", "Padrão"),
                        updatedAt = chat.optLong("updatedAt"),
                        messages = parsed,
                    )
                )
            }
        }.sortedByDescending { it.updatedAt }
    }.getOrDefault(emptyList())

    @Synchronized
    fun save(conversations: List<SavedRemoteConversation>) {
        val array = JSONArray()
        conversations.forEach { chat ->
            array.put(JSONObject().apply {
                put("id", chat.id)
                put("title", chat.title)
                put("modelId", chat.modelId)
                put("provider", chat.provider)
                put("baseUrl", chat.baseUrl)
                put("profile", chat.profile)
                put("updatedAt", chat.updatedAt)
                put("messages", JSONArray().apply {
                    chat.messages.forEach { message ->
                        put(JSONObject().apply {
                            put("id", message.id)
                            put("role", message.role)
                            put("content", message.content)
                            put("createdAt", message.createdAt)
                            put("attachments", JSONArray(message.attachmentNames))
                        })
                    }
                })
            })
        }
        val stream = file.startWrite()
        try {
            stream.write(array.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }
}
