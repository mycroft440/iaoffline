package com.example.ialocal.api

import com.example.ialocal.agent.AiOrchestrator
import com.example.ialocal.ai.AiChatMessage
import com.example.ialocal.diagnostics.AiEventLogger
import com.example.ialocal.models.ModelManager
import com.example.ialocal.models.ModelRepository
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

enum class ApiServerStatus { STOPPED, STARTING, RUNNING, ERROR }

data class ApiServerState(
    val status: ApiServerStatus = ApiServerStatus.STOPPED,
    val port: Int = ApiSettingsRepository.DEFAULT_PORT,
    val error: String? = null,
)

class LocalApiServer(
    private val settings: ApiSettingsRepository,
    private val modelRepository: ModelRepository,
    private val modelManager: ModelManager,
    private val orchestrator: AiOrchestrator,
    private val logger: AiEventLogger? = null,
) : Closeable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(ApiServerState(port = settings.port))
    val state: StateFlow<ApiServerState> = _state.asStateFlow()

    @Volatile private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private val activeClients = AtomicInteger(0)

    @Synchronized
    fun start() {
        if (acceptJob?.isActive == true || serverSocket != null || _state.value.status == ApiServerStatus.STARTING) return
        _state.value = ApiServerState(ApiServerStatus.STARTING, settings.port)
        acceptJob = scope.launch {
            var ownedSocket: ServerSocket? = null
            try {
                val socket = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), settings.port))
                }
                ownedSocket = socket
                synchronized(this@LocalApiServer) {
                    serverSocket = socket
                }
                _state.value = ApiServerState(ApiServerStatus.RUNNING, settings.port)
                logger?.info("API_RESPONSE", "API local ativa em ${settings.baseUrl}")

                while (!socket.isClosed) {
                    val client = try {
                        socket.accept()
                    } catch (closed: SocketException) {
                        if (socket.isClosed) break
                        throw closed
                    }

                    if (activeClients.incrementAndGet() > MAX_ACTIVE_CLIENTS) {
                        activeClients.decrementAndGet()
                        runCatching { writeResponse(client, 503, errorJson("busy", "A API local já está processando muitas conexões.")) }
                        runCatching { client.close() }
                        continue
                    }

                    scope.launch {
                        try {
                            handleClient(client)
                        } finally {
                            activeClients.decrementAndGet()
                        }
                    }
                }
            } catch (error: Throwable) {
                if (_state.value.status != ApiServerStatus.STOPPED) {
                    _state.value = ApiServerState(
                        ApiServerStatus.ERROR,
                        settings.port,
                        error.message ?: "Falha ao iniciar a API local.",
                    )
                    logger?.error("API_RESPONSE", "Falha no servidor localhost", error)
                }
            } finally {
                synchronized(this@LocalApiServer) {
                    runCatching { ownedSocket?.close() }
                    if (serverSocket === ownedSocket) serverSocket = null
                    acceptJob = null
                    if (_state.value.status != ApiServerStatus.ERROR) {
                        _state.value = ApiServerState(ApiServerStatus.STOPPED, settings.port)
                    }
                }
            }
        }
    }

    @Synchronized
    fun stop() {
        _state.value = ApiServerState(ApiServerStatus.STOPPED, settings.port)
        val socket = serverSocket
        serverSocket = null
        runCatching { socket?.close() }
        acceptJob?.cancel()
        acceptJob = null
    }

    fun ensureStarted() {
        if (_state.value.status != ApiServerStatus.RUNNING && _state.value.status != ApiServerStatus.STARTING) {
            start()
        }
    }

    override fun close() {
        stop()
        scope.cancel()
    }

    private suspend fun handleClient(socket: Socket) {
        socket.use { client ->
            client.soTimeout = 15 * 60 * 1000
            client.tcpNoDelay = true
            val request = runCatching { readRequest(client.getInputStream()) }.getOrElse {
                writeResponse(client, 400, errorJson("invalid_request", it.message ?: "Requisição inválida."))
                return
            }
            if (!isPublicHealth(request.path) && request.headers["authorization"] != "Bearer ${settings.apiKey}") {
                writeResponse(client, 401, errorJson("unauthorized", "Chave da API local inválida."))
                return
            }

            logger?.info("API_REQUEST", "${request.method} ${request.path}")
            if (request.method == "POST" && request.path == "/v1/chat/completions" &&
                runCatching { JSONObject(request.body).optBoolean("stream", false) }.getOrDefault(false)
            ) {
                writeChatStream(client, request.body)
                return
            }

            val response = runCatching { route(request) }.getOrElse { error ->
                val status = when (error) {
                    is IllegalArgumentException -> 400
                    is IllegalStateException -> 409
                    else -> 500
                }
                HttpResponse(status, errorJson("local_ai_error", error.message ?: "Falha interna da IA local."))
            }
            logger?.info("API_RESPONSE", "${request.method} ${request.path} -> ${response.status}")
            writeResponse(client, response.status, response.body)
        }
    }

    private suspend fun route(request: HttpRequest): HttpResponse = when (request.method to request.path) {
        "GET" to "/health", "GET" to "/v1/health" -> healthResponse()
        "GET" to "/v1/models" -> modelsResponse()
        "GET" to "/v1/agents" -> agentsResponse()
        "POST" to "/v1/chat/completions" -> chatCompletion(request.body)
        "POST" to "/v1/agents/run" -> runAgent(request.body)
        else -> HttpResponse(404, errorJson("not_found", "Endpoint não encontrado."))
    }

    private suspend fun healthResponse(): HttpResponse {
        val runtime = modelManager.runtimeState.value
        val active = modelRepository.getActiveModel()
        return HttpResponse(200, JSONObject()
            .put("status", "ok")
            .put("api", "local-ai")
            .put("version", "1")
            .put("runtime_status", runtime.status.name.lowercase())
            .put("runtime_model", runtime.modelName ?: JSONObject.NULL)
            .put("runtime_error", runtime.error ?: JSONObject.NULL)
            .put("active_model", active?.apiModelId ?: JSONObject.NULL)
            .put("active_model_verified", active?.verificationStatus == "VERIFIED")
            .toString())
    }

    private suspend fun modelsResponse(): HttpResponse {
        val data = JSONArray()
        modelRepository.getModels().forEach { model ->
            data.put(JSONObject()
                .put("id", model.apiModelId)
                .put("object", "model")
                .put("created", model.importedAt / 1000)
                .put("owned_by", "local")
                .put("active", model.isActive)
                .put("format", model.format)
                .put("architecture", model.architecture ?: JSONObject.NULL)
                .put("verification_status", model.verificationStatus)
                .put("last_error", model.lastError ?: JSONObject.NULL)
                .put("context_length", model.contextLength))
        }
        return HttpResponse(200, JSONObject().put("object", "list").put("data", data).toString())
    }

    private suspend fun agentsResponse(): HttpResponse {
        val data = JSONArray()
        val modelMap = modelRepository.getModels().associateBy { it.id }
        modelRepository.getAgents().forEach { agent ->
            data.put(JSONObject()
                .put("id", agent.id)
                .put("name", agent.name)
                .put("model", modelMap[agent.modelId]?.apiModelId ?: agent.modelId)
                .put("default", agent.isDefault)
                .put("max_tokens", agent.maxTokens)
                .put("tools", JSONArray()
                    .put("list_recent_conversations")
                    .put("search_conversations")
                    .put("read_conversation")
                    .put("current_time")))
        }
        return HttpResponse(200, JSONObject().put("object", "list").put("data", data).toString())
    }

    private suspend fun chatCompletion(body: String): HttpResponse {
        val p = parseChat(body)
        val (model, output) = if (p.agentId != null) {
            val result = orchestrator.runAgent(p.agentId, p.messages, p.maxTokens, p.temperature)
            result.model to result.output
        } else {
            orchestrator.chatCompletion(p.model, p.messages, p.maxTokens, p.temperature)
        }
        return HttpResponse(200, fullCompletionJson(model.apiModelId, output).toString())
    }

    private suspend fun writeChatStream(socket: Socket, body: String) {
        val out = BufferedOutputStream(socket.getOutputStream())
        var headersSent = false
        try {
            val p = parseChat(body)
            val (model, chunks) = if (p.agentId != null) {
                orchestrator.streamAgent(p.agentId, p.messages, p.maxTokens, p.temperature)
            } else {
                val stream = orchestrator.streamChatCompletion(p.model, p.messages, p.maxTokens, p.temperature)
                stream.model to stream.chunks
            }
            val id = "chatcmpl-${UUID.randomUUID()}"
            val created = System.currentTimeMillis() / 1000
            writeSseHeaders(out)
            headersSent = true
            writeSse(out, chunkJson(id, created, model.apiModelId, JSONObject().put("role", "assistant"), JSONObject.NULL))
            chunks.collect { token ->
                writeSse(out, chunkJson(id, created, model.apiModelId, JSONObject().put("content", token), JSONObject.NULL))
            }
            writeSse(out, chunkJson(id, created, model.apiModelId, JSONObject(), "stop"))
            writeSse(out, "[DONE]")
            logger?.info("API_RESPONSE", "POST /v1/chat/completions -> 200 SSE")
        } catch (t: Throwable) {
            logger?.error("API_RESPONSE", "Falha durante SSE", t)
            runCatching {
                if (!headersSent) writeSseHeaders(out)
                writeSse(out, errorJson("local_ai_error", t.message ?: "Falha durante streaming."))
                writeSse(out, "[DONE]")
            }
        }
    }

    private suspend fun runAgent(body: String): HttpResponse {
        val json = JSONObject(body)
        val messages = when {
            json.has("messages") -> parseMessages(json.getJSONArray("messages"))
            json.has("input") -> listOf(AiChatMessage("user", json.getString("input")))
            else -> throw IllegalArgumentException("Informe 'messages' ou 'input'.")
        }
        val result = orchestrator.runAgent(
            json.optString("agent_id").takeIf { it.isNotBlank() },
            messages,
        )
        return HttpResponse(200, JSONObject()
            .put("id", "agent-${UUID.randomUUID()}")
            .put("object", "agent.run")
            .put("created", System.currentTimeMillis() / 1000)
            .put("agent_id", result.agent.id)
            .put("agent_name", result.agent.name)
            .put("model", result.model.apiModelId)
            .put("tools_used", JSONArray(result.toolsUsed))
            .put("output", result.output)
            .toString())
    }

    private fun parseChat(body: String): ChatParams {
        val json = JSONObject(body)
        val requestedTemperature = json.optDouble("temperature", 0.3).toFloat().coerceIn(0f, 2f)
        require(kotlin.math.abs(requestedTemperature - 0.3f) < 0.0001f) {
            "O runtime llama.cpp Android v0.4.0 usa temperature fixa em 0.3. Remova o parâmetro ou use 0.3."
        }
        return ChatParams(
            model = json.optString("model").takeIf { it.isNotBlank() },
            agentId = json.optString("agent_id").takeIf { it.isNotBlank() },
            messages = parseMessages(json.getJSONArray("messages")),
            maxTokens = json.optInt("max_tokens", 1024).coerceIn(16, 4096),
            temperature = requestedTemperature,
        )
    }

    private fun parseMessages(array: JSONArray): List<AiChatMessage> {
        require(array.length() > 0) { "A lista de mensagens não pode estar vazia." }
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val role = item.optString("role").lowercase()
                require(role in setOf("system", "user", "assistant")) { "Role inválido: $role" }
                add(AiChatMessage(role, item.getString("content")))
            }
        }
    }

    private fun fullCompletionJson(model: String, output: String) = JSONObject()
        .put("id", "chatcmpl-${UUID.randomUUID()}")
        .put("object", "chat.completion")
        .put("created", System.currentTimeMillis() / 1000)
        .put("model", model)
        .put("choices", JSONArray().put(JSONObject()
            .put("index", 0)
            .put("message", JSONObject().put("role", "assistant").put("content", output))
            .put("finish_reason", "stop")))

    private fun chunkJson(
        id: String,
        created: Long,
        model: String,
        delta: JSONObject,
        finishReason: Any,
    ): String = JSONObject()
        .put("id", id)
        .put("object", "chat.completion.chunk")
        .put("created", created)
        .put("model", model)
        .put("choices", JSONArray().put(JSONObject()
            .put("index", 0)
            .put("delta", delta)
            .put("finish_reason", finishReason)))
        .toString()

    private fun writeSseHeaders(out: BufferedOutputStream) {
        out.write(
            ("HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/event-stream; charset=utf-8\r\n" +
                "Cache-Control: no-cache\r\n" +
                "Connection: close\r\n\r\n").toByteArray(StandardCharsets.UTF_8)
        )
        out.flush()
    }

    private fun writeSse(out: BufferedOutputStream, data: String) {
        out.write("data: $data\n\n".toByteArray(StandardCharsets.UTF_8))
        out.flush()
    }

    private fun writeResponse(socket: Socket, status: Int, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val statusText = when (status) {
            200 -> "OK"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            404 -> "Not Found"
            409 -> "Conflict"
            503 -> "Service Unavailable"
            else -> "Internal Server Error"
        }
        BufferedOutputStream(socket.getOutputStream()).use { out ->
            out.write(
                ("HTTP/1.1 $status $statusText\r\n" +
                    "Content-Type: application/json; charset=utf-8\r\n" +
                    "Content-Length: ${bytes.size}\r\n" +
                    "Connection: close\r\n\r\n").toByteArray(StandardCharsets.UTF_8)
            )
            out.write(bytes)
            out.flush()
        }
    }

    private fun readRequest(input: InputStream): HttpRequest {
        val headerBytes = ByteArrayOutputStream()
        var match = 0
        while (headerBytes.size() < MAX_HEADER_BYTES) {
            val value = input.read()
            if (value < 0) break
            headerBytes.write(value)
            match = when {
                match == 0 && value == '\r'.code -> 1
                match == 1 && value == '\n'.code -> 2
                match == 2 && value == '\r'.code -> 3
                match == 3 && value == '\n'.code -> 4
                value == '\r'.code -> 1
                else -> 0
            }
            if (match == 4) break
        }
        require(match == 4) { "Cabeçalho HTTP incompleto ou grande demais." }

        val headerText = headerBytes.toString(StandardCharsets.UTF_8.name())
        val lines = headerText.split("\r\n")
        val requestLine = lines.firstOrNull()?.split(' ') ?: emptyList()
        require(requestLine.size >= 2) { "Linha de requisição HTTP inválida." }
        val method = requestLine[0].uppercase()
        val path = requestLine[1].substringBefore('?')
        val headers = lines.drop(1)
            .filter { it.contains(':') }
            .associate { line ->
                val index = line.indexOf(':')
                line.substring(0, index).trim().lowercase() to line.substring(index + 1).trim()
            }
        val contentLength = headers["content-length"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        require(contentLength <= MAX_BODY_BYTES) { "Corpo HTTP grande demais." }
        val bodyBytes = ByteArray(contentLength)
        var offset = 0
        while (offset < contentLength) {
            val read = input.read(bodyBytes, offset, contentLength - offset)
            require(read >= 0) { "Corpo HTTP incompleto." }
            offset += read
        }
        return HttpRequest(method, path, headers, bodyBytes.toString(StandardCharsets.UTF_8))
    }

    private fun isPublicHealth(path: String): Boolean = path == "/health" || path == "/v1/health"

    private fun errorJson(code: String, message: String): String = JSONObject()
        .put("error", JSONObject().put("code", code).put("message", message))
        .toString()

    private data class HttpRequest(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val body: String,
    )

    private data class HttpResponse(val status: Int, val body: String)

    private data class ChatParams(
        val model: String?,
        val agentId: String?,
        val messages: List<AiChatMessage>,
        val maxTokens: Int,
        val temperature: Float,
    )

    companion object {
        private const val MAX_HEADER_BYTES = 64 * 1024
        private const val MAX_BODY_BYTES = 4 * 1024 * 1024
        private const val MAX_ACTIVE_CLIENTS = 8
    }
}
