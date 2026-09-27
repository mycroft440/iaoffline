package com.example.ialocal.runtime

import android.app.ActivityManager
import android.content.Context
import android.system.Os
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import com.example.ialocal.agent.HarmonyNormalizer
import com.example.ialocal.ai.AiChatMessage
import com.example.ialocal.data.AiModelEntity
import com.example.ialocal.diagnostics.AiEventLogger
import com.example.ialocal.models.ModelCatalog
import java.io.File
import java.io.IOException
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
    /**
     * Conversation in the native context that the next request may continue. The model stays
     * loaded between requests either way: a new system prompt resets the native conversation.
     */
    private var conversation: NativeConversation? = null

    override suspend fun warmUp(model: AiModelEntity) = withContext(nativeDispatcher) {
        mutex.withLock { ensureLoaded(model, forceReload = false) }
    }

    override suspend fun verify(model: AiModelEntity): VerificationResult = withContext(nativeDispatcher) {
        mutex.withLock {
            var finalState = RuntimeState()
            try {
                logger?.info("MODEL_LOAD", "Iniciando teste real de inferência para ${model.name}")
                ensureLoaded(model, forceReload = true)
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
    ): Flow<String> = flow {
        require(kotlin.math.abs(temperature - FIXED_TEMPERATURE) < 0.0001f) {
            "O binding llama.cpp Android v0.4.0 usa temperature fixa em $FIXED_TEMPERATURE."
        }
        mutex.lock()
        try {
            ensureLoaded(model, forceReload = false)
            val outputTokens = maxTokens.coerceIn(RuntimeLimits.MIN_OUTPUT_TOKENS, RuntimeLimits.MAX_OUTPUT_TOKENS)
            val continued = conversation?.continuation(model.id, systemPrompt, messages, outputTokens)
            // Until this answer ends normally, the context holds no conversation to continue.
            conversation = null
            _state.value = RuntimeState(RuntimeStatus.GENERATING, model.id, model.name)
            logger?.info("INFERENCE", "Gerando com ${model.apiModelId}; maxTokens=$maxTokens; streaming=true")
            var emitted = 0
            // gpt-oss answers in the harmony format; everything else passes through unchanged.
            val harmony = HarmonyNormalizer()

            suspend fun answer(userPrompt: String) {
                engine.sendUserPrompt(message = userPrompt, predictLength = outputTokens).collect { chunk ->
                    val text = harmony.push(chunk)
                    if (text.isNotEmpty()) {
                        emitted += text.length
                        emit(text)
                    }
                }
            }

            suspend fun rebuildAndAnswer() {
                val prepared = promptBuilder.prepare(
                    baseSystemPrompt = systemPrompt,
                    messages = messages,
                    contextTokens = loadedContextTokens,
                    maxOutputTokens = outputTokens,
                )
                if (prepared.truncated) logger?.info("PROMPT_TEMPLATE", "Histórico antigo truncado para caber no contexto")
                // Resets the native conversation; the model itself stays loaded.
                engine.setSystemPrompt(prepared.systemPrompt)
                answer(prepared.latestUser)
            }

            if (continued == null) {
                rebuildAndAnswer()
            } else {
                logger?.info("INFERENCE", "Continuando a conversa que já está no contexto: só a nova mensagem é processada")
                try {
                    answer(continued)
                } catch (rejected: IOException) {
                    // The native side rejects the message before generating anything.
                    if (rejectionCode(rejected) == null) throw rejected
                    logger?.info("INFERENCE", "A conversa no contexto não pôde continuar; reconstruindo o prompt")
                    rebuildAndAnswer()
                }
            }
            harmony.finish().takeIf { it.isNotEmpty() }?.let { text ->
                emitted += text.length
                emit(text)
            }
            require(emitted > 0) { "O modelo terminou sem produzir texto." }
            conversation = finishedConversation(model, systemPrompt, messages)
            _state.value = RuntimeState(RuntimeStatus.READY, model.id, model.name)
            logger?.info("INFERENCE", "Resposta concluída ($emitted caracteres)")
        } catch (cancel: CancellationException) {
            _state.value = RuntimeState(RuntimeStatus.READY, model.id, model.name)
            logger?.info("INFERENCE", "Geração cancelada pelo usuário")
            throw cancel
        } catch (t: Throwable) {
            val message = humanize(t)
            _state.value = RuntimeState(RuntimeStatus.ERROR, model.id, model.name, message)
            logger?.error("INFERENCE", "Falha durante geração", t)
            throw if (rejectionCode(t) != null) IllegalStateException(message, t) else t
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
    ): String {
        val output = streamComplete(model, systemPrompt, messages, temperature, maxTokens)
            .toList().joinToString(separator = "").trim()
        require(output.isNotBlank()) { "O modelo terminou sem produzir texto." }
        return output
    }

    override suspend fun unload() = withContext(nativeDispatcher) {
        mutex.withLock { unloadLocked() }
    }

    /**
     * Loads [model] unless it is already loaded and idle. Reloading a 5-9 GB model for every
     * message cost seconds to minutes on a weak phone (weights read again from storage and
     * repacked), so it now happens only when the model changes or after an error.
     */
    private suspend fun ensureLoaded(model: AiModelEntity, forceReload: Boolean) {
        awaitEngineInitialized()
        if (!forceReload && loadedModelId == model.id && engine.state.value is InferenceEngine.State.ModelReady) {
            _state.value = RuntimeState(RuntimeStatus.READY, model.id, model.name)
            return
        }
        if (loadedModelId != null || engine.state.value is InferenceEngine.State.Error) unloadLocked()

        _state.value = RuntimeState(RuntimeStatus.LOADING, model.id, model.name)
        val experimental = ModelCatalog.entries.firstOrNull { model.apiModelId.startsWith(it.apiIdPrefix) }
            ?.isExperimental == true
        val modelBytes = File(model.filePath).length()
        val totalMemory = deviceTotalMemory()
        val lowMemory = experimental || RuntimeLimits.needsLowMemoryMode(modelBytes, totalMemory)
        val kvBudgetMb = RuntimeLimits.kvCacheBudgetBytes(modelBytes, totalMemory, experimental) / (1024 * 1024)
        // Read by the patched native loader: file-backed weights for models that do not fit
        // comfortably in RAM, and the memory that sizes the (Q4_0) context.
        runCatching {
            Os.setenv(LOW_MEMORY_ENV, if (lowMemory) "1" else "0", true)
            Os.setenv(CONTEXT_TOKENS_ENV, RuntimeLimits.MAX_CONTEXT_TOKENS.toString(), true)
            Os.setenv(KV_BUDGET_ENV, kvBudgetMb.toString(), true)
            Os.unsetenv(ACTIVE_CONTEXT_ENV)
        }
        logger?.info(
            "MODEL_LOAD",
            "Carregando ${model.name} (${model.filePath}); memória para contexto: $kvBudgetMb MB" +
                if (lowMemory) " em modo de pouca memória" else "",
        )
        try {
            engine.loadModel(model.filePath)
            loadedContextTokens = runCatching { Os.getenv(ACTIVE_CONTEXT_ENV)?.toIntOrNull() }.getOrNull()
                ?.takeIf { it > 0 } ?: RuntimeLimits.MIN_CONTEXT_TOKENS
            logger?.info("MODEL_LOAD", "Contexto alocado: $loadedContextTokens tokens")
            loadedModelId = model.id
            _state.value = RuntimeState(RuntimeStatus.READY, model.id, model.name)
            logger?.info("MODEL_LOAD", "Modelo READY: ${model.apiModelId}")
        } catch (t: Throwable) {
            loadedModelId = null
            val message = humanize(t)
            _state.value = RuntimeState(RuntimeStatus.ERROR, model.id, model.name, message)
            logger?.error("MODEL_LOAD", "Falha ao carregar ${model.name}: $message", t)
            throw IllegalStateException(message, t)
        }
    }

    private fun deviceTotalMemory(): Long {
        val activityManager = appContext.getSystemService(ActivityManager::class.java) ?: return 0L
        return ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo).totalMem
    }

    /** The conversation the context now holds, when the answer ended normally and can continue. */
    private fun finishedConversation(
        model: AiModelEntity,
        systemPrompt: String,
        messages: List<AiChatMessage>,
    ): NativeConversation? {
        // Published by the patched native binding when generation stops.
        val stop = runCatching { Os.getenv(LAST_STOP_ENV) }.getOrNull()
        val used = runCatching { Os.getenv(CONTEXT_USED_ENV)?.toIntOrNull() }.getOrNull()
        if (stop != "eog" || used == null) return null
        return NativeConversation(model.id, systemPrompt, messages, used, loadedContextTokens)
    }

    private suspend fun awaitEngineInitialized() {
        // A failed load or answer leaves the engine in Error until cleanUp() resets it and frees
        // what was loaded. Without this, every later request failed with that same old error.
        if (engine.state.value is InferenceEngine.State.Error) unloadLocked()
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
        conversation = null
        val current = engine.state.value
        if (current is InferenceEngine.State.Uninitialized || current is InferenceEngine.State.Initializing) {
            loadedModelId = null
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
        _state.value = finalState
    }

    /** Code of a user prompt the patched native binding refused, or null for other errors. */
    private fun rejectionCode(t: Throwable): Int? =
        t.message?.takeIf { it.startsWith(USER_PROMPT_REJECTED) }
            ?.removePrefix(USER_PROMPT_REJECTED)?.trim()?.toIntOrNull()

    private fun humanize(t: Throwable): String {
        val raw = t.message.orEmpty()
        return when {
            rejectionCode(t) == CODE_CONTEXT_FULL ->
                "A conversa não coube no contexto deste modelo, que não consegue descartar mensagens antigas. Comece uma nova conversa ou envie uma mensagem mais curta."
            rejectionCode(t) != null ->
                "O modelo não conseguiu processar a mensagem, provavelmente por falta de memória. Feche outros apps e tente novamente."
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
        private const val ACTIVE_CONTEXT_ENV = "IAOFFLINE_ACTIVE_CONTEXT_TOKENS"
        private const val LAST_STOP_ENV = "IAOFFLINE_LAST_STOP"
        private const val CONTEXT_USED_ENV = "IAOFFLINE_CONTEXT_USED"
        /** Prefix of the error the patched binding throws when it refuses a user prompt. */
        private const val USER_PROMPT_REJECTED = "IAOFFLINE_USER_PROMPT_REJECTED:"
        /** The prompt does not fit and this model's context cannot drop old messages. */
        private const val CODE_CONTEXT_FULL = 3
        private const val MAX_NATIVE_DETAIL_CHARS = 900
    }
}
