package com.example.ialocal.runtime

import android.content.Context
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import com.example.ialocal.ai.AiChatMessage
import com.example.ialocal.data.AiModelEntity
import com.example.ialocal.diagnostics.AiEventLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Runtime backed by the official llama.cpp Android binding pinned by the build script. */
class LlamaCppRuntime(
    context: Context,
    private val logger: AiEventLogger? = null,
    private val promptBuilder: PromptContextBuilder = PromptContextBuilder(),
) : ModelRuntime {
    private val appContext = context.applicationContext
    private val mutex = Mutex()
    private val engine by lazy { AiChat.getInferenceEngine(appContext) }
    private val _state = MutableStateFlow(RuntimeState())
    override val state: StateFlow<RuntimeState> = _state.asStateFlow()

    private var loadedModelId: String? = null
    /** setSystemPrompt can only be called directly after a load in the pinned binding. */
    private var requestSessionConsumed = false

    override suspend fun warmUp(model: AiModelEntity) {
        mutex.withLock {
            ensureFreshLoaded(model, forceReload = loadedModelId != model.id || requestSessionConsumed)
        }
    }

    override suspend fun verify(model: AiModelEntity): VerificationResult = mutex.withLock {
        try {
            logger?.info("MODEL_LOAD", "Iniciando teste real de inferência para ${model.name}")
            ensureFreshLoaded(model, forceReload = true)
            engine.setSystemPrompt("Você está em um teste técnico. Siga exatamente a instrução curta do usuário.")
            requestSessionConsumed = true
            _state.value = RuntimeState(RuntimeStatus.GENERATING, model.id, model.name)
            val output = engine.sendUserPrompt("Responda apenas: OK", predictLength = 8)
                .toList().joinToString("").trim()
            require(output.isNotBlank()) { "O runtime carregou o modelo, mas a inferência de teste não gerou texto." }
            logger?.info("INFERENCE", "Smoke test respondeu: ${output.take(80)}")

            // Reset once more, so the first real API request may set its own system prompt.
            ensureFreshLoaded(model, forceReload = true)
            VerificationResult(output)
        } catch (t: Throwable) {
            _state.value = RuntimeState(RuntimeStatus.ERROR, model.id, model.name, humanize(t))
            logger?.error("INFERENCE", "Falha no teste real de inferência", t)
            throw t
        }
    }

    override fun streamComplete(
        model: AiModelEntity,
        systemPrompt: String,
        messages: List<AiChatMessage>,
        temperature: Float,
        maxTokens: Int,
    ): Flow<String> = flow {
        mutex.lock()
        try {
            ensureFreshLoaded(model, forceReload = requestSessionConsumed || loadedModelId != model.id)
            val prepared = promptBuilder.prepare(
                baseSystemPrompt = systemPrompt,
                messages = messages,
                contextTokens = model.contextLength,
                maxOutputTokens = maxTokens.coerceIn(16, 4096),
            )
            if (prepared.truncated) logger?.info("PROMPT_TEMPLATE", "Histórico antigo truncado para caber no contexto")

            engine.setSystemPrompt(prepared.systemPrompt)
            requestSessionConsumed = true
            _state.value = RuntimeState(RuntimeStatus.GENERATING, model.id, model.name)
            logger?.info("INFERENCE", "Gerando com ${model.apiModelId}; maxTokens=$maxTokens; streaming=true")
            var emitted = 0
            engine.sendUserPrompt(
                message = prepared.latestUser,
                predictLength = maxTokens.coerceIn(16, 4096),
            ).collect { chunk ->
                if (chunk.isNotEmpty()) {
                    emitted += chunk.length
                    emit(chunk)
                }
            }
            require(emitted > 0) { "O modelo terminou sem produzir texto." }
            _state.value = RuntimeState(RuntimeStatus.READY, model.id, model.name)
            logger?.info("INFERENCE", "Resposta concluída ($emitted caracteres)")
        } catch (cancel: CancellationException) {
            _state.value = RuntimeState(RuntimeStatus.READY, model.id, model.name)
            logger?.info("INFERENCE", "Geração cancelada pelo usuário")
            throw cancel
        } catch (t: Throwable) {
            _state.value = RuntimeState(RuntimeStatus.ERROR, model.id, model.name, humanize(t))
            logger?.error("INFERENCE", "Falha durante geração", t)
            throw t
        } finally {
            mutex.unlock()
        }
    }

    override suspend fun complete(
        model: AiModelEntity,
        systemPrompt: String,
        messages: List<AiChatMessage>,
        temperature: Float,
        maxTokens: Int,
    ): String {
        val output = streamComplete(model, systemPrompt, messages, temperature, maxTokens)
            .toList().joinToString(separator = "").trim()
        require(output.isNotBlank()) { "O modelo terminou sem produzir texto." }
        return output
    }

    override suspend fun unload() {
        mutex.withLock { unloadLocked() }
    }

    private suspend fun ensureFreshLoaded(model: AiModelEntity, forceReload: Boolean) {
        awaitEngineInitialized()
        if (!forceReload && loadedModelId == model.id && !requestSessionConsumed) {
            _state.value = RuntimeState(RuntimeStatus.READY, model.id, model.name)
            return
        }
        if (loadedModelId != null || engine.state.value is InferenceEngine.State.Error) unloadLocked()

        _state.value = RuntimeState(RuntimeStatus.LOADING, model.id, model.name)
        logger?.info("MODEL_LOAD", "Carregando ${model.name} (${model.filePath})")
        try {
            engine.loadModel(model.filePath)
            loadedModelId = model.id
            requestSessionConsumed = false
            _state.value = RuntimeState(RuntimeStatus.READY, model.id, model.name)
            logger?.info("MODEL_LOAD", "Modelo READY: ${model.apiModelId}")
        } catch (t: Throwable) {
            loadedModelId = null
            requestSessionConsumed = false
            _state.value = RuntimeState(RuntimeStatus.ERROR, model.id, model.name, humanize(t))
            logger?.error("MODEL_LOAD", "Falha ao carregar ${model.name}", t)
            throw IllegalStateException(humanize(t), t)
        }
    }

    private suspend fun awaitEngineInitialized() {
        val current = engine.state.value
        if (current is InferenceEngine.State.Initialized || current is InferenceEngine.State.ModelReady) return
        _state.value = RuntimeState(RuntimeStatus.INITIALIZING)
        val ready = engine.state.first {
            it is InferenceEngine.State.Initialized ||
                it is InferenceEngine.State.ModelReady ||
                it is InferenceEngine.State.Error
        }
        if (ready is InferenceEngine.State.Error) throw ready.exception
    }

    private fun unloadLocked() {
        val current = engine.state.value
        if (current is InferenceEngine.State.Uninitialized || current is InferenceEngine.State.Initializing) {
            loadedModelId = null
            requestSessionConsumed = false
            return
        }
        if (loadedModelId == null && current is InferenceEngine.State.Initialized) return

        _state.value = RuntimeState(RuntimeStatus.UNLOADING, loadedModelId)
        logger?.info("MODEL_UNLOAD", "Liberando contexto nativo")
        runCatching { engine.cleanUp() }
            .onFailure { logger?.error("MODEL_UNLOAD", "Falha ao liberar contexto", it) }
        loadedModelId = null
        requestSessionConsumed = false
        _state.value = RuntimeState(RuntimeStatus.IDLE)
    }

    private fun humanize(t: Throwable): String {
        val raw = t.message.orEmpty()
        return when {
            t::class.java.simpleName.contains("UnsupportedArchitecture", ignoreCase = true) ->
                "A arquitetura deste GGUF não é suportada pelo runtime llama.cpp Android atual."
            raw.contains("Failed to prepare resources", ignoreCase = true) ->
                "O modelo foi lido, mas o runtime não conseguiu criar o contexto nativo. Verifique RAM disponível e compatibilidade."
            raw.contains("Cannot load model", ignoreCase = true) ->
                "O runtime ainda não estava pronto para carregar o modelo."
            raw.isNotBlank() -> raw
            else -> "Falha nativa desconhecida ao executar o modelo."
        }
    }
}
