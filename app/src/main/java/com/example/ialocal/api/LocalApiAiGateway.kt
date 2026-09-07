package com.example.ialocal.api

import com.example.ialocal.ai.AiChatRequest
import com.example.ialocal.ai.AiGateway
import com.example.ialocal.diagnostics.AiEventLogger
import com.example.ialocal.files.AttachmentContextBuilder
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.json.JSONArray
import org.json.JSONObject

/** The app deliberately talks to its own localhost API instead of bypassing it. */
class LocalApiAiGateway(
    private val server: LocalApiServer,
    private val settings: ApiSettingsRepository,
    private val attachmentContextBuilder: AttachmentContextBuilder,
    private val logger: AiEventLogger? = null,
) : AiGateway {
    override fun streamChat(request: AiChatRequest): Flow<String> = flow {
        server.ensureStarted()
        var attempts = 0
        while (server.state.value.status == ApiServerStatus.STARTING && attempts < 120) {
            delay(25); attempts++
        }
        val state = server.state.value
        when (state.status) {
            ApiServerStatus.RUNNING -> Unit
            ApiServerStatus.ERROR -> throw IllegalStateException(state.error ?: "Falha ao iniciar a API local.")
            else -> throw IllegalStateException("A API local não está disponível.")
        }

        val attachmentContext = attachmentContextBuilder.build(request.attachments)
        val lastUserIndex = request.messages.indexOfLast { it.role.equals("user", ignoreCase = true) }
        val payload = JSONObject().apply {
            request.agentId?.let { put("agent_id", it) }
            put("stream", true)
            put("messages", JSONArray().apply {
                request.messages.forEachIndexed { index, message ->
                    val content = if (index == lastUserIndex && attachmentContext.isNotBlank()) {
                        message.content + "\n\n[Contexto dos anexos]\n" + attachmentContext
                    } else message.content
                    put(JSONObject().put("role", message.role).put("content", content))
                }
            })
        }

        logger?.info("API_REQUEST", "Chat interno -> POST /v1/chat/completions stream=true")
        val connection = (URL("${settings.baseUrl}/v1/chat/completions").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 5_000
            readTimeout = 15 * 60 * 1000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "text/event-stream")
            setRequestProperty("Authorization", "Bearer ${settings.apiKey}")
        }
        val cancelHandle = currentCoroutineContext()[Job]?.invokeOnCompletion { connection.disconnect() }
        try {
            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            if (status !in 200..299) {
                val text = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                val message = runCatching { JSONObject(text).getJSONObject("error").getString("message") }.getOrNull()
                throw IllegalStateException(message ?: "A API local respondeu com HTTP $status.")
            }
            connection.inputStream.bufferedReader().use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data.isBlank()) continue
                    if (data == "[DONE]") break
                    val json = JSONObject(data)
                    json.optJSONObject("error")?.let { error ->
                        throw IllegalStateException(error.optString("message", "Falha durante streaming local."))
                    }
                    val content = json.optJSONArray("choices")
                        ?.optJSONObject(0)?.optJSONObject("delta")?.optString("content").orEmpty()
                    if (content.isNotEmpty()) emit(content)
                }
            }
        } finally {
            cancelHandle?.dispose()
            connection.disconnect()
        }
    }.flowOn(Dispatchers.IO)
}
