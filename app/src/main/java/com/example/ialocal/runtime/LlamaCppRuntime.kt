package com.example.ialocal.runtime

import android.content.Context
import android.system.Os
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import com.example.ialocal.agent.HarmonyNormalizer
import com.example.ialocal.ai.AiChatMessage
import com.example.ialocal.data.AiModelEntity
import com.example.ialocal.diagnostics.AiEventLogger
import com.example.ialocal.models.GgufInspector
import com.example.ialocal.models.ModelCatalog
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Runtime backed by the official llama.cpp Android binding pinned by the build script. */
class LlamaCppRuntime(
    context: Context,
    private val logger: AiEventLogger? = null,
    private val promptBuilder: PromptContextBuilder = PromptContextBuilder(),
    private val crashGuard: ModelCrashGuard? = null,
) : ModelRuntime {
    private val appContext = context.applicationContext
    private val mutex = Mutex()
    private val nativeDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val engine by lazy { AiChat.getInferenceEngine(appContext) }
    private val _state = MutableStateFlow(RuntimeState())
    override val state: StateFlow<RuntimeState> = _state.asStateFlow()

    private var loadedModelId: String? = null
    /** Context the native side allocated for the loaded model. */
    private var loadedContextTokens = RuntimeLimits.MIN_CONTEXT_TOKENS
    /** The conversation the native context holds, if the last answer ended normally. */
    private var session: ChatSession? = null

    override suspend fun warmUp(model: AiModelEntity) = withContext(nativeDispatcher) {
        mutex.withLock { ensureLoaded(model) }
    }

    override suspend fun verify(model: AiModelEntity): VerificationResult = withContext(nativeDispatcher) {
        mutex.withLock {
            var finalState = RuntimeState()
            try {
                logger?.info("MODEL_LOAD", "Iniciando teste real de inferência para ${model.name}")
                ensureLoaded(model, forceReload = true)
                runCatching { Os.unsetenv(ASSISTANT_PREFIX_ENV) }
                crashGuard?.begin(model.id, model.name, ModelCrashGuard.Phase.GENERATION)
                engine.setSystemPrompt("Você está em um teste técnico. Siga exatamente a instrução curta do usuário.")
                _state.value = RuntimeState(RuntimeStatus.GENERATING, model.id, model.name)
                val output = engine.sendUserPrompt("Responda apenas: OK", predictLength = 8)
                    .toList().joinToString("").trim()
                require(output.isNotBlank()) { "O runtime carregou o modelo, mas a inferência de teste não gerou texto." }
                logger?.info("INFERENCE", "Smoke test respondeu: ${output.take(80)}")
                VerificationResult(output)
            } catch (t: Throwable) {
                finalState = RuntimeState(RuntimeStatus.ERROR, model.id, model.name, humanize(t))
                _state.value = finalState
                logger?.error("INFERENCE", "Falha no teste real de inferência", t)
                throw t
            } finally {
                crashGuard?.end()
                // A verification is only a smoke test. Do not keep a multi-GB model resident in RAM
                // before the user actually opens/activates it.
                unloadLocked(finalState)
            }
        }
    }

    override fun streamComplete(
        model: AiModelEntity,
        systemPrompt: String,
        messages: List<AiChatMessage>,
        temperature: Float,
        maxTokens: Int,
        turn: TurnOptions,
    ): Flow<String> = flow {
        require(kotlin.math.abs(temperature - FIXED_TEMPERATURE) < 0.0001f) {
            "O binding llama.cpp Android não aceita temperature por requisição; ele usa a recomendada pelo modelo ou $FIXED_TEMPERATURE."
        }
        mutex.lock()
        try {
            ensureLoaded(model)
            // Reading the conversation and answering fill the context memory.
            crashGuard?.begin(model.id, model.name, ModelCrashGuard.Phase.GENERATION)
            val outputTokens = maxTokens.coerceIn(RuntimeLimits.MIN_OUTPUT_TOKENS, RuntimeLimits.MAX_OUTPUT_TOKENS)
            val prepared = promptBuilder.prepare(
                baseSystemPrompt = systemPrompt,
                messages = messages,
                contextTokens = loadedContextTokens,
                maxOutputTokens = outputTokens,
            )
            val latestUser = prepared.latestUser + turn.userSuffix
            val sessionKey = prepared.systemPrompt + "\u0000thinking=" + turn.templateThinking
            val previous = session
            // Invalid until this answer ends normally.
            session = null
            runCatching {
                Os.setenv(THINKING_ENV, if (turn.templateThinking) "1" else "0", true)
                if (turn.assistantPrefix.isEmpty()) Os.unsetenv(ASSISTANT_PREFIX_ENV)
                else Os.setenv(ASSISTANT_PREFIX_ENV, turn.assistantPrefix, true)
            }
            val continuing = previous != null &&
                previous.continuesWith(model.id, sessionKey, prepared.conversation) &&
                previous.hasRoomFor(latestUser.length, outputTokens, loadedContextTokens)
            if (continuing) {
                logger?.info("PROMPT_TEMPLATE", "Continuando a conversa no contexto carregado (${previous?.tokens} tokens)")
            } else {
                if (prepared.truncated) logger?.info("PROMPT_TEMPLATE", "Histórico antigo truncado para caber no contexto")
                engine.setSystemPrompt(prepared.systemPrompt)
                prepared.history.forEach { engine.addHistoryMessage(it.role, it.content) }
                logger?.info("PROMPT_TEMPLATE", "Conversa montada com ${prepared.history.size} turnos anteriores")
            }

            _state.value = RuntimeState(RuntimeStatus.GENERATING, model.id, model.name)
            logger?.info("INFERENCE", "Gerando com ${model.apiModelId}; maxTokens=$outputTokens; streaming=true")
            val reply = StringBuilder()
            // gpt-oss answers in the harmony format; everything else passes through unchanged.
            val harmony = HarmonyNormalizer()
            engine.sendUserPrompt(message = latestUser, predictLength = outputTokens).collect { chunk ->
                val text = harmony.push(chunk)
                if (text.isNotEmpty()) {
                    reply.append(text)
                    emit(text)
                }
            }
            harmony.finish().takeIf { it.isNotEmpty() }?.let { text ->
                reply.append(text)
                emit(text)
            }
            require(reply.isNotEmpty()) { "O modelo terminou sem produzir texto." }
            val ended = runCatching { Os.getenv(SESSION_CLEAN_ENV) == "1" }.getOrDefault(false)
            val tokens = runCatching { Os.getenv(SESSION_TOKENS_ENV)?.toIntOrNull() }.getOrNull()
            if (ended && tokens != null) {
                session = ChatSession(
                    modelId = model.id,
                    key = sessionKey,
                    conversation = prepared.conversation + AiChatMessage(PromptContextBuilder.USER, prepared.latestUser),
                    reply = reply.toString(),
                    tokens = tokens,
                )
            }
            _state.value = RuntimeState(RuntimeStatus.READY, model.id, model.name)
            logger?.info("INFERENCE", "Resposta concluída (${reply.length} caracteres)")
        } catch (cancel: CancellationException) {
            _state.value = RuntimeState(RuntimeStatus.READY, model.id, model.name)
            logger?.info("INFERENCE", "Geração cancelada pelo usuário")
            throw cancel
        } catch (t: Throwable) {
            _state.value = RuntimeState(RuntimeStatus.ERROR, model.id, model.name, humanize(t))
            logger?.error("INFERENCE", "Falha durante geração", t)
            throw t
        } finally {
            crashGuard?.end()
            mutex.unlock()
        }
    }.flowOn(nativeDispatcher)

    override suspend fun complete(
        model: AiModelEntity,
        systemPrompt: String,
        messages: List<AiChatMessage>,
        temperature: Float,
        maxTokens: Int,
        turn: TurnOptions,
    ): String {
        val output = streamComplete(model, systemPrompt, messages, temperature, maxTokens, turn)
            .toList().joinToString(separator = "").trim()
        require(output.isNotBlank()) { "O modelo terminou sem produzir texto." }
        return output
    }

    override suspend fun unload() = withContext(nativeDispatcher) {
        mutex.withLock { unloadLocked() }
    }

    override suspend fun releaseIfIdle(): Boolean = withContext(nativeDispatcher) {
        // Busy means loading or answering, possibly for the background service: leave it alone.
        if (loadedModelId == null || !mutex.tryLock()) return@withContext false
        try {
            logger?.info("MODEL_UNLOAD", "Pouca memória no aparelho: liberando o modelo ocioso")
            unloadLocked()
            true
        } finally {
            mutex.unlock()
        }
    }

    /**
     * Loads [model] unless it is already loaded and healthy. The model stays loaded between
     * messages, so a conversation does not pay for a reload (and its weight repacking) each turn.
     */
    private suspend fun ensureLoaded(model: AiModelEntity, forceReload: Boolean = false) {
        awaitEngineInitialized()
        if (!forceReload && loadedModelId == model.id && engine.state.value is InferenceEngine.State.ModelReady) {
            _state.value = RuntimeState(RuntimeStatus.READY, model.id, model.name)
            return
        }
        if (loadedModelId != null || engine.state.value is InferenceEngine.State.Error) unloadLocked()

        _state.value = RuntimeState(RuntimeStatus.LOADING, model.id, model.name)
        val catalogEntry = ModelCatalog.entries.firstOrNull { model.apiModelId.startsWith(it.apiIdPrefix) }
        val experimental = catalogEntry?.isExperimental == true
        val fileBytes = File(model.filePath).length()
        // Per-layer embeddings stay in the file (the native loader reads them on demand), so only
        // the rest of the weights takes RAM: about half of a Gemma 4 E2B/E4B file.
        val onDemandBytes = runCatching { GgufInspector().inspect(File(model.filePath)).onDemandBytes }.getOrDefault(0L)
        val modelBytes = (fileBytes - onDemandBytes).coerceAtLeast(0L)
        val memory = DeviceMemory.read(appContext)
        val totalMemory = memory.totalBytes
        // A model that already made the app close for lack of memory loads with less of it.
        val safeLevel = crashGuard?.safeLevel(model.id) ?: 0
        val lowMemory = experimental || safeLevel > 0 || RuntimeLimits.needsLowMemoryMode(modelBytes, totalMemory)
        val kvBudgetMb = RuntimeLimits.safeKvBudgetBytes(
            RuntimeLimits.kvCacheBudgetBytes(modelBytes, totalMemory, experimental),
            safeLevel,
        ) / (1024 * 1024)
        // Read by the patched native loader: file-backed weights for models that do not fit
        // comfortably in RAM, the memory that sizes the (Q4_0) context and the presence penalty
        // the model card recommends (the other sampling settings come from the GGUF itself).
        runCatching {
            Os.setenv(LOW_MEMORY_ENV, if (lowMemory) "1" else "0", true)
            Os.setenv(CONTEXT_TOKENS_ENV, RuntimeLimits.MAX_CONTEXT_TOKENS.toString(), true)
            Os.setenv(KV_BUDGET_ENV, kvBudgetMb.toString(), true)
            Os.setenv(PRESENCE_PENALTY_ENV, (catalogEntry?.presencePenalty ?: 0f).toString(), true)
            Os.unsetenv(ACTIVE_CONTEXT_ENV)
        }
        logger?.info(
            "MODEL_LOAD",
            "Carregando ${model.name} (${model.filePath}); pesos na RAM ${modelBytes / MB} MB" +
                (if (onDemandBytes > 0) " (+${onDemandBytes / MB} MB lidos do arquivo sob demanda)" else "") +
                "; RAM livre ${memory.availableBytes / MB} MB de " +
                "${memory.totalBytes / MB} MB; memória para contexto: $kvBudgetMb MB" +
                (if (lowMemory) " em modo de pouca memória" else "") +
                if (safeLevel > 0) " (nível de economia $safeLevel após fechamento por falta de memória)" else "",
        )
        try {
            crashGuard?.begin(model.id, model.name, ModelCrashGuard.Phase.LOAD)
            try {
                engine.loadModel(model.filePath)
            } finally {
                crashGuard?.end()
            }
            loadedContextTokens = runCatching { Os.getenv(ACTIVE_CONTEXT_ENV)?.toIntOrNull() }.getOrNull()
                ?.takeIf { it > 0 } ?: RuntimeLimits.MIN_CONTEXT_TOKENS
            logger?.info("MODEL_LOAD", "Contexto alocado: $loadedContextTokens tokens")
            loadedModelId = model.id
            session = null
            _state.value = RuntimeState(RuntimeStatus.READY, model.id, model.name)
            logger?.info("MODEL_LOAD", "Modelo READY: ${model.apiModelId}")
        } catch (t: Throwable) {
            loadedModelId = null
            session = null
            val message = humanize(t)
            _state.value = RuntimeState(RuntimeStatus.ERROR, model.id, model.name, message)
            logger?.error("MODEL_LOAD", "Falha ao carregar ${model.name}: $message", t)
            throw IllegalStateException(message, t)
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

    private fun unloadLocked(finalState: RuntimeState = RuntimeState()) {
        val current = engine.state.value
        if (current is InferenceEngine.State.Uninitialized || current is InferenceEngine.State.Initializing) {
            loadedModelId = null
            session = null
            _state.value = finalState
            return
        }
        if (loadedModelId == null && current is InferenceEngine.State.Initialized) {
            _state.value = finalState
            return
        }

        _state.value = RuntimeState(RuntimeStatus.UNLOADING, loadedModelId)
        logger?.info("MODEL_UNLOAD", "Liberando contexto nativo")
        runCatching { engine.cleanUp() }
            .onFailure { logger?.error("MODEL_UNLOAD", "Falha ao liberar contexto", it) }
        loadedModelId = null
        session = null
        _state.value = finalState
    }

    private fun humanize(t: Throwable): String {
        val raw = t.message.orEmpty()
        return when {
            raw.contains("após as tentativas de compatibilidade", ignoreCase = true) -> buildString {
                append("O runtime não conseguiu carregar este GGUF nos modos otimizado, mmap conservador e compatibilidade em CPU.")
                nativeDetail(raw)?.let { append(" Detalhe técnico: ").append(it) }
                append(" Feche outros apps para liberar RAM e tente novamente; se persistir, o detalhe acima indica a causa reportada pelo llama.cpp.")
            }
            t::class.java.simpleName.contains("UnsupportedArchitecture", ignoreCase = true) ->
                "O runtime nativo recusou o GGUF durante o carregamento. Essa exceção não identifica, por si só, uma arquitetura incompatível; tente liberar RAM e carregar novamente."
            raw.contains("Failed to prepare resources", ignoreCase = true) -> buildString {
                append("O modelo foi carregado, mas o aparelho não conseguiu criar nem o contexto reduzido de inferência.")
                nativeDetail(raw)?.let { append(" Detalhe técnico: ").append(it) }
                append(" Libere RAM fechando outros apps e tente novamente.")
            }
            raw.contains("Cannot load model", ignoreCase = true) ->
                "O runtime ainda não estava pronto para carregar o modelo."
            raw.isNotBlank() -> raw
            else -> "Falha nativa desconhecida ao executar o modelo."
        }
    }

    private fun nativeDetail(raw: String): String? {
        val detail = raw.substringAfter("Detalhe nativo:", missingDelimiterValue = "")
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString(" · ")
            .trim()
        return detail.takeIf { it.isNotBlank() }?.take(MAX_NATIVE_DETAIL_CHARS)
    }

    companion object {
        const val FIXED_TEMPERATURE = 0.3f
        private const val LOW_MEMORY_ENV = "IAOFFLINE_LOW_MEMORY"
        private const val CONTEXT_TOKENS_ENV = "IAOFFLINE_CONTEXT_TOKENS"
        private const val KV_BUDGET_ENV = "IAOFFLINE_KV_BUDGET_MB"
        private const val PRESENCE_PENALTY_ENV = "IAOFFLINE_PRESENCE_PENALTY"
        private const val ASSISTANT_PREFIX_ENV = "IAOFFLINE_ASSISTANT_PREFIX"
        private const val THINKING_ENV = "IAOFFLINE_ENABLE_THINKING"
        private const val SESSION_CLEAN_ENV = "IAOFFLINE_SESSION_CLEAN"
        private const val SESSION_TOKENS_ENV = "IAOFFLINE_SESSION_TOKENS"
        private const val ACTIVE_CONTEXT_ENV = "IAOFFLINE_ACTIVE_CONTEXT_TOKENS"
        private const val MAX_NATIVE_DETAIL_CHARS = 900
        private const val MB = 1024L * 1024
    }
}
