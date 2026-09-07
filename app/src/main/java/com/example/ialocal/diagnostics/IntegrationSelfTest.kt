package com.example.ialocal.diagnostics

import com.example.ialocal.api.ApiServerStatus
import com.example.ialocal.api.ApiSettingsRepository
import com.example.ialocal.api.LocalApiServer
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

enum class IntegrationCheckStatus { PENDING, RUNNING, PASSED, FAILED, SKIPPED }

data class IntegrationCheck(
    val id: String,
    val title: String,
    val status: IntegrationCheckStatus = IntegrationCheckStatus.PENDING,
    val detail: String? = null,
)

data class IntegrationTestState(
    val running: Boolean = false,
    val checks: List<IntegrationCheck> = defaultIntegrationChecks(),
    val summary: String? = null,
    val lastModelOutput: String? = null,
)

fun defaultIntegrationChecks(): List<IntegrationCheck> = listOf(
    IntegrationCheck("server", "Servidor localhost"),
    IntegrationCheck("health", "GET /v1/health"),
    IntegrationCheck("auth", "Autenticação Bearer"),
    IntegrationCheck("models", "GET /v1/models + modelo VERIFIED"),
    IntegrationCheck("chat", "POST /v1/chat/completions"),
    IntegrationCheck("sse", "Streaming SSE"),
)

/**
 * End-to-end proof that the exact path used by the app works:
 * localhost server -> auth -> model registry -> native inference -> HTTP response -> SSE.
 */
class IntegrationSelfTest(
    private val server: LocalApiServer,
    private val settings: ApiSettingsRepository,
    private val logger: AiEventLogger? = null,
) {
    suspend fun run(onProgress: (IntegrationTestState) -> Unit = {}): IntegrationTestState = withContext(Dispatchers.IO) {
        var state = IntegrationTestState(running = true)
        fun publish() = onProgress(state)
        fun update(id: String, status: IntegrationCheckStatus, detail: String? = null) {
            state = state.copy(
                checks = state.checks.map { if (it.id == id) it.copy(status = status, detail = detail) else it },
            )
            publish()
        }
        fun fail(id: String, message: String): IntegrationTestState {
            update(id, IntegrationCheckStatus.FAILED, message)
            state = state.copy(
                running = false,
                checks = state.checks.map {
                    if (it.status == IntegrationCheckStatus.PENDING) it.copy(status = IntegrationCheckStatus.SKIPPED, detail = "Não executado após falha anterior.") else it
                },
                summary = "Falha em: ${state.checks.firstOrNull { it.id == id }?.title ?: id}",
            )
            logger?.error("INTEGRATION_TEST", state.summary ?: message, IllegalStateException(message))
            publish()
            return state
        }

        publish()
        logger?.info("INTEGRATION_TEST", "Iniciando teste completo da IA local")

        update("server", IntegrationCheckStatus.RUNNING, "Iniciando 127.0.0.1:${settings.port}…")
        server.ensureStarted()
        repeat(100) {
            if (server.state.value.status != ApiServerStatus.STARTING) return@repeat
            delay(50)
        }
        val current = server.state.value
        when (current.status) {
            ApiServerStatus.RUNNING -> update("server", IntegrationCheckStatus.PASSED, settings.baseUrl)
            ApiServerStatus.ERROR -> return@withContext fail("server", current.error ?: "A API local entrou em estado de erro.")
            else -> return@withContext fail("server", "A API local não iniciou.")
        }

        update("health", IntegrationCheckStatus.RUNNING)
        val health = request("GET", "/v1/health", authorized = false)
        if (health.code != 200) return@withContext fail("health", "HTTP ${health.code}: ${health.body.take(180)}")
        val healthJson = runCatching { JSONObject(health.body) }.getOrElse {
            return@withContext fail("health", "Resposta JSON inválida: ${it.message}")
        }
        if (healthJson.optString("status") != "ok") return@withContext fail("health", "Status inesperado: ${health.body.take(180)}")
        update("health", IntegrationCheckStatus.PASSED, "runtime=${healthJson.optString("runtime_status", "?")}")

        update("auth", IntegrationCheckStatus.RUNNING, "Confirmando rejeição sem chave…")
        val unauthorized = request("GET", "/v1/models", authorized = false)
        if (unauthorized.code != 401) return@withContext fail("auth", "Esperado HTTP 401 sem chave, recebido ${unauthorized.code}.")
        update("auth", IntegrationCheckStatus.PASSED, "Sem chave = 401; chave local será usada nas próximas etapas.")

        update("models", IntegrationCheckStatus.RUNNING)
        val models = request("GET", "/v1/models", authorized = true)
        if (models.code != 200) return@withContext fail("models", "HTTP ${models.code}: ${models.body.take(180)}")
        val modelsJson = runCatching { JSONObject(models.body) }.getOrElse {
            return@withContext fail("models", "Resposta JSON inválida: ${it.message}")
        }
        val data = modelsJson.optJSONArray("data") ?: JSONArray()
        var activeModelId: String? = null
        for (i in 0 until data.length()) {
            val item = data.optJSONObject(i) ?: continue
            if (item.optBoolean("active") && item.optString("verification_status") == "VERIFIED") {
                activeModelId = item.optString("id").takeIf { it.isNotBlank() }
                break
            }
        }
        if (activeModelId == null) return@withContext fail("models", "Nenhum modelo ativo com status VERIFIED. Importe e valide um GGUF primeiro.")
        update("models", IntegrationCheckStatus.PASSED, "Modelo ativo: $activeModelId")

        update("chat", IntegrationCheckStatus.RUNNING, "Executando inferência real via HTTP…")
        val chatPayload = JSONObject()
            .put("model", activeModelId)
            .put("stream", false)
            .put("max_tokens", 32)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "Responda somente: FUNCIONOU")))
        val chat = request("POST", "/v1/chat/completions", authorized = true, body = chatPayload.toString())
        if (chat.code != 200) return@withContext fail("chat", "HTTP ${chat.code}: ${chat.body.take(240)}")
        val chatJson = runCatching { JSONObject(chat.body) }.getOrElse {
            return@withContext fail("chat", "Resposta JSON inválida: ${it.message}")
        }
        val output = chatJson.optJSONArray("choices")
            ?.optJSONObject(0)?.optJSONObject("message")?.optString("content")?.trim().orEmpty()
        if (output.isBlank()) return@withContext fail("chat", "A API respondeu 200, mas o modelo não gerou texto.")
        state = state.copy(lastModelOutput = output)
        update("chat", IntegrationCheckStatus.PASSED, "Resposta: ${output.take(120)}")

        update("sse", IntegrationCheckStatus.RUNNING, "Confirmando tokens via text/event-stream…")
        val ssePayload = JSONObject()
            .put("model", activeModelId)
            .put("stream", true)
            .put("max_tokens", 24)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "Responda somente: SSE_OK")))
        val sse = requestSse("/v1/chat/completions", ssePayload.toString())
        if (!sse.done) return@withContext fail("sse", sse.error ?: "Streaming terminou sem [DONE].")
        if (sse.content.isBlank()) return@withContext fail("sse", "Streaming terminou sem conteúdo.")
        update("sse", IntegrationCheckStatus.PASSED, "${sse.events} eventos; ${sse.content.length} caracteres.")

        state = state.copy(
            running = false,
            summary = "Integração completa aprovada: GGUF → llama.cpp → API local → chat → SSE.",
        )
        logger?.info("INTEGRATION_TEST", state.summary!!)
        publish()
        state
    }

    private fun request(method: String, path: String, authorized: Boolean, body: String? = null): HttpResult {
        val connection = (URL(settings.baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 5_000
            readTimeout = 15 * 60 * 1000
            setRequestProperty("Accept", "application/json")
            if (authorized) setRequestProperty("Authorization", "Bearer ${settings.apiKey}")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        return try {
            if (body != null) connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            HttpResult(code, stream?.bufferedReader()?.use { it.readText() }.orEmpty())
        } finally {
            connection.disconnect()
        }
    }

    private fun requestSse(path: String, body: String): SseResult {
        val connection = (URL(settings.baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 5_000
            readTimeout = 15 * 60 * 1000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "text/event-stream")
            setRequestProperty("Authorization", "Bearer ${settings.apiKey}")
        }
        return try {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            if (connection.responseCode !in 200..299) {
                val error = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                return SseResult(false, "", 0, "HTTP ${connection.responseCode}: ${error.take(220)}")
            }
            val content = StringBuilder()
            var events = 0
            var done = false
            connection.inputStream.bufferedReader().use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data == "[DONE]") { done = true; break }
                    if (data.isBlank()) continue
                    events++
                    val json = runCatching { JSONObject(data) }.getOrNull() ?: continue
                    json.optJSONObject("error")?.let {
                        return SseResult(false, content.toString(), events, it.optString("message", "Erro no SSE."))
                    }
                    val token = json.optJSONArray("choices")?.optJSONObject(0)
                        ?.optJSONObject("delta")?.optString("content").orEmpty()
                    content.append(token)
                }
            }
            SseResult(done, content.toString(), events, null)
        } finally {
            connection.disconnect()
        }
    }

    private data class HttpResult(val code: Int, val body: String)
    private data class SseResult(val done: Boolean, val content: String, val events: Int, val error: String?)
}
