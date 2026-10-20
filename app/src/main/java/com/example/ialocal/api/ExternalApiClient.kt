package com.example.ialocal.api

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class RemoteApiModel(val id: String, val name: String)

data class RemoteChatTurn(val role: String, val content: String)

/**
 * OpenAI-compatible HTTP client: OpenRouter, OpenAI and configurable HTTPS providers.
 * Auth is sent only to the explicitly configured HTTPS host; redirects are never followed.
 */
class ExternalApiClient {
    suspend fun fetchModels(connection: RemoteApiConnection, apiKey: String): List<RemoteApiModel> =
        withContext(Dispatchers.IO) {
            require(apiKey.isNotBlank()) { "Configure sua chave de API primeiro." }
            val http = open(connection, apiKey, "/models", "GET")
            try {
                val status = http.responseCode
                if (status !in 200..299) throw IOException(remoteApiErrorMessage(status, readError(http)))
                val json = JSONObject(http.inputStream.bufferedReader().use { it.readText() })
                val list = json.optJSONArray("data") ?: throw IOException("A API não retornou uma lista de modelos.")
                buildList {
                    for (i in 0 until list.length()) {
                        val model = list.optJSONObject(i) ?: continue
                        val id = model.optString("id").trim()
                        if (id.isNotEmpty()) add(RemoteApiModel(id, model.optString("name").ifBlank { id }))
                    }
                }.sortedBy { it.name.lowercase() }
            } finally {
                http.disconnect()
            }
        }

    fun stream(
        connection: RemoteApiConnection,
        apiKey: String,
        turns: List<RemoteChatTurn>,
    ): Flow<String> = flow {
        require(apiKey.isNotBlank()) { "Configure sua chave de API primeiro." }
        require(connection.modelId.isNotBlank()) { "Escolha um modelo da API." }
        val http = open(connection, apiKey, "/chat/completions", "POST")
        val job = currentCoroutineContext()[Job]
        val cancellation = job?.invokeOnCompletion { http.disconnect() }
        try {
            http.setRequestProperty("Accept", "text/event-stream")
            val body = JSONObject().apply {
                put("model", connection.modelId.trim())
                put("stream", true)
                put("messages", JSONArray().apply {
                    turns.forEach { put(JSONObject().put("role", it.role).put("content", it.content)) }
                })
            }
            http.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = http.responseCode
            if (status !in 200..299) throw IOException(remoteApiErrorMessage(status, readError(http)))
            val contentType = http.contentType.orEmpty()
            if (contentType.contains("text/event-stream", ignoreCase = true)) {
                http.inputStream.bufferedReader().use { input ->
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val line = input.readLine() ?: break
                        if (line.trim() == "data: [DONE]") break
                        remoteApiEventContent(line)?.let { emit(it) }
                    }
                }
            } else {
                // Compatible providers may return JSON despite stream=true.
                val json = JSONObject(http.inputStream.bufferedReader().use { it.readText() })
                val content = json.optJSONArray("choices")?.optJSONObject(0)
                    ?.optJSONObject("message")?.optString("content").orEmpty()
                if (content.isEmpty()) throw IOException("O provedor não retornou texto.")
                emit(content)
            }
        } finally {
            cancellation?.dispose()
            http.disconnect()
        }
    }.flowOn(Dispatchers.IO)

    private fun open(config: RemoteApiConnection, key: String, path: String, method: String): HttpURLConnection {
        val root = remoteApiBaseUrl(config.baseUrl)
        return (URL(root + path).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false // never forward the credential to an unexpected host
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 120_000
            doInput = true
            doOutput = method == "POST"
            setRequestProperty("Authorization", "Bearer $key")
            setRequestProperty("Content-Type", "application/json")
            if (config.provider == RemoteApiProvider.OPENROUTER) {
                setRequestProperty("X-Title", "IA Offline")
            }
        }
    }

    private fun readError(connection: HttpURLConnection): String =
        runCatching { connection.errorStream?.bufferedReader()?.use { it.readText().take(4096) }.orEmpty() }
            .getOrDefault("")
}
