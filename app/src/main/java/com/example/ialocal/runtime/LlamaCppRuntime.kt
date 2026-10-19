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
import java.util.concurrent.atomic.AtomicInteger
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

/**
 * Runtime backed by the official llama.cpp Android binding pinned by the build script. It runs in
 * the ":ai" process ([com.example.ialocal.runtime.remote.InferenceService]); the app talks to it
 * through [com.example.ialocal.runtime.remote.RemoteModelRuntime].
 */
class LlamaCppRuntime(
    context: Context,
    private val logger: AiEventLogger? = null,
    private val promptBuilder: PromptContextBuilder = PromptContextBuilder(),
    /** Memory level for a model after crashes, decided by the app ([ModelCrashGuard]). */
    private val safeLevelOf: (modelId: String) -> Int = { 0 },
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
    /** The system prompt and earlier turns the native context holds, prepared before a message. */
    private var primed: Primed? = null
    /** Counts requests, so a prefill still running stops at the next turn when one arrives. */
    private val requests = AtomicInteger()

    private data class Primed(val modelId: String, val key: String, val history: List<AiChatMessage>)

    override suspend fun warmUp(model: AiModelEntity, prefill: PromptPrefill?) = withContext(nativeDispatcher) {
        val ticket = requests.incrementAndGet()
        mutex.withLock {
            ensureLoaded(model)
            if (prefill != null) prime(model, prefill, ticket)
        }
    }

    override suspend fun verify(model: AiModelEntity, keepLoaded: Boolean): VerificationResult = withContext(nativeDispatcher) {
        requests.incrementAndGet()
        mutex.withLock {
            var finalState = RuntimeState()
            var passed = false
            try {
                logger?.info("MODEL_LOAD", "Iniciando teste real de inferência para ${model.name}")
                ensureLoaded(model)
                session = null
                primed = null
                runCatching {
                    Os.unsetenv(ASSISTANT_PREFIX_ENV)
                    // The test prompt must not replace the model's saved system prompt.
                    Os.unsetenv(PROMPT_CACHE_ENV)
                    Os.unsetenv(SYSTEM_PROMPT_TAIL_ENV)
                }
                engine.setSystemPrompt("Você está em um teste técnico. Siga exatamente a instrução curta do usuário.")
                _state.value = RuntimeState(RuntimeStatus.GENERATING, model.id, model.name)
                val output = engine.sendUserPrompt("Responda apenas: OK", predictLength = 8)
                    .toList().joinToString("").trim()
                require(output.isNotBlank()) { "O runtime carregou o modelo, mas a inferência de teste não gerou texto." }
                logger?.info("INFERENCE", "Smoke test respondeu: ${output.take(80)}")
                passed = true
                VerificationResult(output)
            } catch (t: Throwable) {
                finalState = RuntimeState(RuntimeStatus.ERROR, model.id, model.name, humanize(t))
                _state.value = finalState
                logger?.error("INFERENCE", "Falha no teste real de inferência", t)
                throw t
            } finally {
                // A test after a download or an import frees the multi-GB model until the user opens
                // it; a test right before an answer keeps it, so the answer does not load it again.
                if (keepLoaded && passed) {
                    _state.value = RuntimeState(RuntimeStatus.READY, model.id, model.name)
                } else {
                    unloadLocked(finalState)
                }
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
        // A prefill still running for another message stops at its next turn.
        requests.incrementAndGet()
        mutex.lock()
        try {
            ensureLoaded(model)
            val outputTokens = maxTokens.coerceIn(RuntimeLimits.MIN_OUTPUT_TOKENS, RuntimeLimits.MAX_OUTPUT_TOKENS)
            val prepared = promptBuilder.prepare(
                baseSystemPrompt = systemPrompt,
                messages = messages,
                contextTokens = loadedContextTokens,
                maxOutputTokens = outputTokens,
            )
            val latestUser = prepared.latestUser + turn.userSuffix
            val sessionKey = sessionKey(prepared.systemPrompt, turn.templateThinking)
            val previous = session
            val ready = primed
            // Invalid until this answer ends normally.
            session = null
            primed = null
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
            } else if (ready == Primed(model.id, sessionKey, prepared.history)) {
                logger?.info("PROMPT_TEMPLATE", "Instruções e ${prepared.history.size} turnos já processados antes do envio")
            } else {
                if (prepared.truncated) logger?.info("PROMPT_TEMPLATE", "Histórico antigo truncado para caber no contexto")
                setSystemPrompt(model, prepared.systemPrompt, turn)
                prepared.history.forEach { engine.addHistoryMessage(it.role, it.content) }
                logger?.info("PROMPT_TEMPLATE", "Conversa montada com ${prepared.history.size} turnos anteriores")
            }

            _state.value = RuntimeState(RuntimeStatus.GENERATING, model.id, model.name)
            logger?.info("INFERENCE", "Gerando com ${model.apiModelId}; maxTokens=$outputTokens; streaming=true")
            runCatching { Os.unsetenv(PERF_ENV) }
            val ioBefore = ProcessIo.read()
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
            AnswerSpeed.parse(runCatching { Os.getenv(PERF_ENV) }.getOrNull())?.let { speed ->
                logger?.info("SPEED", speed.describe(ioBefore, ProcessIo.read()))
            }
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

    /**
     * Processes the system prompt and the conversation so far while the user writes, so the next
     * message only has its own text left to read. Stops between turns if a request arrives.
     */
    private suspend fun prime(model: AiModelEntity, prefill: PromptPrefill, ticket: Int) {
        if (requests.get() != ticket) return
        val prepared = promptBuilder.prepareHistory(
            baseSystemPrompt = prefill.systemPrompt,
            messages = prefill.history,
            contextTokens = loadedContextTokens,
            maxOutputTokens = prefill.maxTokens.coerceIn(RuntimeLimits.MIN_OUTPUT_TOKENS, RuntimeLimits.MAX_OUTPUT_TOKENS),
        )
        val key = sessionKey(prepared.systemPrompt, prefill.turn.templateThinking)
        val target = Primed(model.id, key, prepared.history)
        // The context already holds this conversation, after its last answer or a previous prefill.
        if (primed == target || session?.holds(model.id, key, prepared.conversation) == true) return

        session = null
        primed = null
        _state.value = RuntimeState(RuntimeStatus.LOADING, model.id, model.name)
        try {
            runCatching { Os.setenv(THINKING_ENV, if (prefill.turn.templateThinking) "1" else "0", true) }
            setSystemPrompt(model, prepared.systemPrompt, prefill.turn)
            for (message in prepared.history) {
                if (requests.get() != ticket) {
                    logger?.info("PROMPT_TEMPLATE", "Preparação interrompida por uma nova solicitação")
                    return
                }
                engine.addHistoryMessage(message.role, message.content)
            }
            primed = target
            logger?.info("PROMPT_TEMPLATE", "Instruções e ${prepared.history.size} turnos processados antes da próxima mensagem")
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (t: Throwable) {
            // Only time is lost: the next message processes the conversation (or reloads) as before.
            logger?.error("PROMPT_TEMPLATE", "Falha ao preparar a conversa antes do envio", t)
            _state.value = RuntimeState(RuntimeStatus.ERROR, model.id, model.name, humanize(t))
        } finally {
            if (engine.state.value is InferenceEngine.State.ModelReady) {
                _state.value = RuntimeState(RuntimeStatus.READY, model.id, model.name)
            }
        }
    }

    /**
     * Processes [systemPrompt]. The native side restores what it saved of this model's system
     * prompt, up to the part that changes daily ([TurnOptions.systemPromptTail]), and saves it
     * again when it had to process it.
     */
    private suspend fun setSystemPrompt(model: AiModelEntity, systemPrompt: String, turn: TurnOptions) {
        val tail = turn.systemPromptTail.trimEnd()
        runCatching {
            val file = PromptCache.file(appContext, model.id, turn.templateThinking)
            file.parentFile?.mkdirs()
            Os.setenv(PROMPT_CACHE_ENV, file.path, true)
            if (tail.isNotEmpty() && systemPrompt.endsWith(tail)) Os.setenv(SYSTEM_PROMPT_TAIL_ENV, tail, true)
            else Os.unsetenv(SYSTEM_PROMPT_TAIL_ENV)
        }
        engine.setSystemPrompt(systemPrompt)
    }

    private fun sessionKey(systemPrompt: String, templateThinking: Boolean) =
        systemPrompt + "\u0000thinking=" + templateThinking

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
    private suspend fun ensureLoaded(model: AiModelEntity) {
        awaitEngineInitialized()
        if (loadedModelId == model.id && engine.state.value is InferenceEngine.State.ModelReady) {
            _state.value = RuntimeState(RuntimeStatus.READY, model.id, model.name)
            return
        }
        if (loadedModelId != null || engine.state.value is InferenceEngine.State.Error) unloadLocked()

        _state.value = RuntimeState(RuntimeStatus.LOADING, model.id, model.name)
        val catalogEntry = ModelCatalog.entries.firstOrNull { model.apiModelId.startsWith(it.apiIdPrefix) }
        val fileBytes = File(model.filePath).length()
        val layout = runCatching { GgufInspector().inspect(File(model.filePath)) }.getOrNull()
        // Per-layer embeddings stay in the file (the native loader reads them on demand), so only
        // the rest of the weights takes RAM: about half of a Gemma 4 E2B/E4B file.
        val onDemandBytes = layout?.onDemandBytes ?: 0L
        val modelBytes = (fileBytes - onDemandBytes).coerceAtLeast(0L)
        // Read after the previous model was freed, so its memory counts as free.
        val memory = DeviceMemory.read(appContext)
        // A model that already made the app close for lack of memory loads with less of it.
        val safeLevel = safeLevelOf(model.id)
        val plan = RuntimeLimits.planMemory(modelBytes, memory, layout?.expertBytes ?: 0L, safeLevel)
        if (!plan.fits) {
            val message = "${model.name} precisa de cerca de ${gigabytes(plan.minimumBytes)} de RAM, mas a IA pode usar " +
                "${gigabytes(plan.budgetBytes)} agora (a RAM livre menos 0,5 GB para o Android e o app). Carregar " +
                "assim faria o celular travar lendo o modelo do armazenamento. Feche outros apps ou escolha um modelo menor."
            _state.value = RuntimeState(RuntimeStatus.ERROR, model.id, model.name, message)
            logger?.error("MODEL_LOAD", message)
            throw IllegalStateException(message)
        }
        val lowMemory = plan.lowMemory
        val kvBudgetMb = plan.kvBudgetBytes / MB
        // Read by the patched native loader: file-backed weights for models that do not fit
        // comfortably in RAM, experts read from storage for a mixture of experts that does not fit
        // at all, the memory that sizes the (Q4_0) context and the presence penalty the model card
        // recommends (the other sampling settings come from the GGUF itself).
        runCatching {
            Os.setenv(LOW_MEMORY_ENV, if (lowMemory) "1" else "0", true)
            Os.setenv(MOE_STREAM_ENV, if (plan.streamsExperts) "1" else "0", true)
            Os.setenv(CONTEXT_TOKENS_ENV, RuntimeLimits.MAX_CONTEXT_TOKENS.toString(), true)
            Os.setenv(KV_BUDGET_ENV, kvBudgetMb.toString(), true)
            Os.setenv(PRESENCE_PENALTY_ENV, (catalogEntry?.presencePenalty ?: 0f).toString(), true)
            Os.unsetenv(ACTIVE_CONTEXT_ENV)
        }
        logger?.info(
            "MODEL_LOAD",
            "Carregando ${model.name} (${model.filePath}); pesos na RAM ${modelBytes / MB} MB" +
                (if (onDemandBytes > 0) " (+${onDemandBytes / MB} MB lidos do arquivo sob demanda)" else "") +
                "; RAM livre ${memory.availableBytes / MB} MB de ${memory.totalBytes / MB} MB; " +
                "limite da IA ${plan.budgetBytes / MB} MB; memória para contexto: $kvBudgetMb MB" +
                (if (plan.streamsExperts) {
                    "; especialistas MoE (${(layout?.expertBytes ?: 0L) / MB} MB) lidos do armazenamento conforme o uso" +
                        if (lowMemory) "" else ", o restante dos pesos mantido na RAM"
                } else {
                    ""
                }) +
                (if (lowMemory) " em modo de pouca memória" else "") +
                if (safeLevel > 0) " (nível de economia $safeLevel após fechamento por falta de memória)" else "",
        )
        try {
            engine.loadModel(model.filePath)
            loadedContextTokens = runCatching { Os.getenv(ACTIVE_CONTEXT_ENV)?.toIntOrNull() }.getOrNull()
                ?.takeIf { it > 0 } ?: RuntimeLimits.MIN_CONTEXT_TOKENS
            logger?.info("MODEL_LOAD", "Contexto alocado: $loadedContextTokens tokens")
            loadedModelId = model.id
            session = null
            primed = null
            _state.value = RuntimeState(RuntimeStatus.READY, model.id, model.name)
            logger?.info("MODEL_LOAD", "Modelo READY: ${model.apiModelId}")
        } catch (t: Throwable) {
            loadedModelId = null
            session = null
            primed = null
            val message = humanize(t)
            _state.value = RuntimeState(RuntimeStatus.ERROR, model.id, model.name, message)
            logger?.error("MODEL_LOAD", "Falha ao carregar ${model.name}: $message", t)
            throw IllegalStateException(message, t)
        }
    }

    private fun gigabytes(bytes: Long): String =
        String.format(java.util.Locale.forLanguageTag("pt-BR"), "%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))

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
            primed = null
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
        primed = null
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
        private const val MOE_STREAM_ENV = "IAOFFLINE_MOE_STREAM"
        private const val PERF_ENV = "IAOFFLINE_PERF"
        private const val ASSISTANT_PREFIX_ENV = "IAOFFLINE_ASSISTANT_PREFIX"
        private const val THINKING_ENV = "IAOFFLINE_ENABLE_THINKING"
        private const val SESSION_CLEAN_ENV = "IAOFFLINE_SESSION_CLEAN"
        private const val SESSION_TOKENS_ENV = "IAOFFLINE_SESSION_TOKENS"
        private const val ACTIVE_CONTEXT_ENV = "IAOFFLINE_ACTIVE_CONTEXT_TOKENS"
        private const val PROMPT_CACHE_ENV = "IAOFFLINE_PROMPT_CACHE"
        private const val SYSTEM_PROMPT_TAIL_ENV = "IAOFFLINE_SYSTEM_PROMPT_TAIL"
        private const val MAX_NATIVE_DETAIL_CHARS = 900
        private const val MB = 1024L * 1024
    }
}
