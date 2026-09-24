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
    /** setSystemPrompt can only be called directly after a load in the pinned binding. */
    private var requestSessionConsumed = false

    override suspend fun warmUp(model: AiModelEntity) = withContext(nativeDispatcher) {
        mutex.withLock {
            ensureFreshLoaded(model, forceReload = loadedModelId != model.id || requestSessionConsumed)
        }
    }

    override suspend fun verify(model: AiModelEntity): VerificationResult = withContext(nativeDispatcher) {
        mutex.withLock {
            var finalState = RuntimeState()
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
            ensureFreshLoaded(model, forceReload = requestSessionConsumed || loadedModelId != model.id)
            val prepared = promptBuilder.prepare(
                baseSystemPrompt = systemPrompt,
                messages = messages,
                contextTokens = loadedContextTokens,
                maxOutputTokens = maxTokens.coerceIn(RuntimeLimits.MIN_OUTPUT_TOKENS, RuntimeLimits.MAX_OUTPUT_TOKENS),
            )
            if (prepared.truncated) logger?.info("PROMPT_TEMPLATE", "Histórico antigo truncado para caber no contexto")

            engine.setSystemPrompt(prepared.systemPrompt)
            requestSessionConsumed = true
            _state.value = RuntimeState(RuntimeStatus.GENERATING, model.id, model.name)
            logger?.info("INFERENCE", "Gerando com ${model.apiModelId}; maxTokens=$maxTokens; streaming=true")
            var emitted = 0
            // gpt-oss answers in the harmony format; everything else passes through unchanged.
            val harmony = HarmonyNormalizer()
            engine.sendUserPrompt(
                message = prepared.latestUser,
                predictLength = maxTokens.coerceIn(RuntimeLimits.MIN_OUTPUT_TOKENS, RuntimeLimits.MAX_OUTPUT_TOKENS),
            ).collect { chunk ->
                val text = harmony.push(chunk)
                if (text.isNotEmpty()) {
                    emitted += text.length
                    emit(text)
                }
            }
            harmony.finish().takeIf { it.isNotEmpty() }?.let { text ->
                emitted += text.length
                emit(text)
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

    private suspend fun ensureFreshLoaded(model: AiModelEntity, forceReload: Boolean) {
        awaitEngineInitialized()
        if (!forceReload && loadedModelId == model.id && !requestSessionConsumed) {
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
            requestSessionConsumed = false
            _state.value = RuntimeState(RuntimeStatus.READY, model.id, model.name)
            logger?.info("MODEL_LOAD", "Modelo READY: ${model.apiModelId}")
        } catch (t: Throwable) {
            loadedModelId = null
            requestSessionConsumed = false
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
            requestSessionConsumed = false
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
        requestSessionConsumed = false
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
        private const val ACTIVE_CONTEXT_ENV = "IAOFFLINE_ACTIVE_CONTEXT_TOKENS"
        private const val MAX_NATIVE_DETAIL_CHARS = 900
    }
}
