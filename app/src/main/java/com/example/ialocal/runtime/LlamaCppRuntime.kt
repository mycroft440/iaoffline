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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
    private val engineDelegate = lazy { AiChat.getInferenceEngine(appContext) }
    private val engine by engineDelegate
    private val _state = MutableStateFlow(RuntimeState())
    override val state: StateFlow<RuntimeState> = _state.asStateFlow()

    /** The performance settings the app sent ([RuntimeTuning]). */
    @Volatile private var tuning = RuntimeTuning()
    private var loadedModelId: String? = null
    /** Context the native side allocated for the loaded model. */
    private var loadedContextTokens = RuntimeLimits.MIN_CONTEXT_TOKENS
    /** The settings the model was loaded with: when they change, the next request loads it again. */
    private var loadedKey: LoadKey? = null
    /** How the loaded model's weights are held, for freeing it and warming it up again. */
    private var loadedLayout: LoadedLayout? = null
    /**
     * Experts per token the loaded model was trimmed to ("Modo rápido de MoE"), or null when it uses
     * the number in its GGUF. Part of the keys of its saved prompts and conversations: a context
     * computed with the other number of experts is not reused.
     */
    private var loadedFastExperts: Int? = null
    /** The context was freed on memory trim while the model, read from its file, stayed loaded. */
    private var contextReleased = false
    /** The conversation the native context holds, if the last answer ended normally. */
    private var session: ChatSession? = null
    /** Whether [session] is worth writing to storage ([SessionStore.worthSaving]) and already is. */
    private var sessionWorthSaving = false
    private var sessionSaved = false
    /** The system prompt and earlier turns the native context holds, prepared before a message. */
    private var primed: Primed? = null
    /** The user turn "Refazer" or an edit of it goes back to, recorded when its answer started. */
    private var turnMark: TurnMark? = null
    /** Counts requests, so a prefill still running stops at the next turn when one arrives. */
    private val requests = AtomicInteger()
    /** What holds [mutex], told to an answer that has to wait for it ([GenerationStage.BUSY_ANSWER]…). */
    @Volatile private var busyWith: String? = null
    /** Set while the model loads or its context is created: that native work cannot be interrupted. */
    @Volatile private var loading = false
    /** The coroutine holding [mutex], so a cancelled request only interrupts its own native work. */
    @Volatile private var activeJob: Job? = null
    /** Cores the native threads fill in with only up to 4, read once from /proc/cpuinfo. */
    private val efficiencyCpus by lazy { CpuCores.efficiencyCpus() }
    /** RAM the phone is sold with, which decides the automatic settings. */
    private val deviceGigabytes by lazy { DeviceMemory.read(appContext).advertisedGigabytes }
    /** The last conversations answered, kept on storage with their native context. */
    private val savedSessions = SessionStore.of(appContext) {
        tuning.readyConversations.coerceIn(1, MAX_READY_CONVERSATIONS)
    }
    /** Writes [savedSessions] after an answer, once the model is free. */
    private val background = CoroutineScope(SupervisorJob() + nativeDispatcher)
    /** Each model file's GGUF layout, read once instead of before every load. */
    private val layouts = HashMap<String, GgufInspector.Metadata?>()

    init {
        // KleidiAI packs each weight twice on CPUs with SME (Dimensity 9500, Snapdragon 8 Elite
        // Gen 5) when more threads than SME units run it: about twice the RAM for the weights. The
        // NEON/i8mm kernels every other phone uses are kept instead. Read when the backend starts.
        runCatching { Os.setenv(KLEIDIAI_SME_ENV, "0", true) }
    }

    private data class Primed(val modelId: String, val key: String, val history: List<AiChatMessage>)

    /** The turns before the marked user turn ([conversation]), of which the first [skipped] are not in the context. */
    private data class TurnMark(
        val modelId: String,
        val key: String,
        val conversation: List<AiChatMessage>,
        val skipped: Int,
    ) {
        fun matches(modelId: String, key: String, request: List<AiChatMessage>): Boolean =
            modelId == this.modelId && key == this.key && ChatSession.sameTurns(conversation, request)
    }

    /** The settings a load depends on; the rest apply to each request. */
    private data class LoadKey(
        val performance: PerformanceLevel,
        val longContext: TuningSwitch,
        val longPromptMoe: TuningSwitch,
        val fastMoe: TuningSwitch,
        val performanceBlocked: Boolean,
        val safeLevel: Int,
    )

    private data class LoadedLayout(
        val filePath: String,
        val fileBytes: Long,
        val onDemandBytes: Long,
        /**
         * Weights read from the file (low-memory mode, or the experts of a mixture of experts whose
         * other weights have the optimized copy): the kernel may drop them when memory is short.
         */
        val fileBacked: Boolean,
        val streamsExperts: Boolean,
    )

    override fun setTuning(tuning: RuntimeTuning) {
        this.tuning = tuning
    }

    override suspend fun warmUp(model: AiModelEntity, prefill: PromptPrefill?) = withContext(nativeDispatcher) {
        val ticket = requests.incrementAndGet()
        mutex.withLock {
            busyWith = if (prefill != null) GenerationStage.BUSY_PREFILL else GenerationStage.BUSY_LOAD
            activeJob = currentCoroutineContext()[Job]
            try {
                val alreadyLoaded = isLoaded(model)
                ensureLoaded(model)
                if (alreadyLoaded) warmWeights()
                if (prefill != null) prime(model, prefill, ticket)
            } finally {
                busyWith = null
                activeJob = null
            }
        }
    }

    override suspend fun verify(model: AiModelEntity, keepLoaded: Boolean): VerificationResult = withContext(nativeDispatcher) {
        requests.incrementAndGet()
        mutex.withLock {
            busyWith = GenerationStage.BUSY_VERIFY
            var finalState = RuntimeState()
            var passed = false
            try {
                logger?.info("MODEL_LOAD", "Iniciando teste real de inferência para ${model.name}")
                saveSessionLocked()
                ensureLoaded(model)
                session = null
                primed = null
                turnMark = null
                runCatching {
                    Os.unsetenv(ASSISTANT_PREFIX_ENV)
                    // The test prompt must not replace the model's saved system prompt.
                    Os.unsetenv(PROMPT_CACHE_ENV)
                    Os.unsetenv(SYSTEM_PROMPT_TAIL_ENV)
                    Os.unsetenv(TURN_MARK_ENV)
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
                busyWith = null
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
        onStage: (GenerationStage) -> Unit,
    ): Flow<String> = flow {
        require(kotlin.math.abs(temperature - FIXED_TEMPERATURE) < 0.0001f) {
            "O binding llama.cpp Android não aceita temperature por requisição; ele usa a recomendada pelo modelo ou $FIXED_TEMPERATURE."
        }
        // A prefill still running for another message stops at its next turn.
        requests.incrementAndGet()
        if (!mutex.tryLock()) {
            // The preparation started while the user wrote stops in the middle of what it reads;
            // another answer, a test or a load holds the model until it ends.
            if (busyWith == GenerationStage.BUSY_PREFILL) requestNativeAbort()
            onStage(GenerationStage(GenerationStage.Kind.WAITING_FOR_AI, busyWith ?: GenerationStage.BUSY_ANSWER))
            mutex.lock()
        }
        busyWith = GenerationStage.BUSY_ANSWER
        activeJob = currentCoroutineContext()[Job]
        try {
            ensureLoaded(model, onStage)
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
            val mark = turnMark
            val continues = previous != null && previous.continuesWith(model.id, sessionKey, prepared.conversation)
            val rewinds = !continues && mark != null && mark.matches(model.id, sessionKey, prepared.conversation)
            // Another conversation replaces the one in the context: it is kept on storage first.
            if (previous != null && !continues && !rewinds) saveSessionLocked()
            // Invalid until this answer ends normally.
            session = null
            primed = null
            runCatching {
                Os.setenv(THINKING_ENV, if (turn.templateThinking) "1" else "0", true)
                if (turn.assistantPrefix.isEmpty()) Os.unsetenv(ASSISTANT_PREFIX_ENV)
                else Os.setenv(ASSISTANT_PREFIX_ENV, turn.assistantPrefix, true)
                Os.setenv(TURN_MARK_ENV, if (turn.markTurn) "1" else "0", true)
                Os.setenv(CHECKPOINT_ENV, checkpointCapMb(model).toString(), true)
            }
            // How many of the conversation's first turns the context leaves out, once it holds the
            // rest; null while it does not hold the conversation.
            var skipped: Int? = null
            if (continues) {
                val held = if (previous!!.hasRoomFor(latestUser.length, outputTokens, loadedContextTokens)) {
                    onStage(GenerationStage(GenerationStage.Kind.CONTEXT_REUSED, GenerationStage.REUSED_SESSION))
                    logger?.info("PROMPT_TEMPLATE", "Continuando a conversa no contexto carregado (${previous.tokens} tokens)")
                    previous
                } else {
                    dropOldestTurns(previous, latestUser.length, outputTokens)?.also {
                        onStage(GenerationStage(GenerationStage.Kind.CONTEXT_REUSED, GenerationStage.REUSED_TRIMMED))
                    }
                }
                skipped = held?.skipped
            }
            if (skipped == null && ready == Primed(model.id, sessionKey, prepared.history)) {
                onStage(GenerationStage(GenerationStage.Kind.CONTEXT_REUSED, GenerationStage.REUSED_PREFILL))
                logger?.info("PROMPT_TEMPLATE", "Instruções e ${prepared.history.size} turnos já processados antes do envio")
                skipped = prepared.conversation.size - prepared.history.size
            }
            if (skipped == null && rewinds && rewindToMark(latestUser.length, outputTokens)) {
                onStage(GenerationStage(GenerationStage.Kind.CONTEXT_REUSED, GenerationStage.REUSED_REWIND))
                skipped = mark!!.skipped
            }
            if (skipped == null) {
                skipped = restoreSaved(model, sessionKey, prepared.conversation, onStage) { saved ->
                    saved.hasRoomFor(latestUser.length, outputTokens, loadedContextTokens)
                }?.skipped
            }
            val contextSkipped: Int = skipped ?: run {
                if (prepared.truncated) logger?.info("PROMPT_TEMPLATE", "Histórico antigo truncado para caber no contexto")
                turnMark = null
                setSystemPrompt(model, prepared.systemPrompt, turn, onStage)
                addHistory(prepared.history) { done ->
                    onStage(GenerationStage(GenerationStage.Kind.READING_HISTORY, done = done, total = prepared.history.size))
                    true
                }
                logger?.info("PROMPT_TEMPLATE", "Conversa montada com ${prepared.history.size} turnos anteriores")
                prepared.conversation.size - prepared.history.size
            }
            if (turn.markTurn) turnMark = TurnMark(model.id, sessionKey, prepared.conversation, contextSkipped)

            _state.value = RuntimeState(RuntimeStatus.GENERATING, model.id, model.name)
            logger?.info("INFERENCE", "Gerando com ${model.apiModelId}; maxTokens=$outputTokens; streaming=true")
            runCatching { Os.unsetenv(PERF_ENV) }
            val ioBefore = ProcessIo.read()
            val reply = StringBuilder()
            // gpt-oss answers in the harmony format; everything else passes through unchanged.
            val harmony = HarmonyNormalizer()
            // The native side reads the message before it writes the first word.
            onStage(GenerationStage(GenerationStage.Kind.READING_MESSAGE))
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
            val answerSpeed = AnswerSpeed.parse(runCatching { Os.getenv(PERF_ENV) }.getOrNull())
            if (ended && tokens != null) {
                val finished = ChatSession(
                    modelId = model.id,
                    key = sessionKey,
                    conversation = prepared.conversation.drop(contextSkipped) +
                        AiChatMessage(PromptContextBuilder.USER, prepared.latestUser),
                    reply = reply.toString(),
                    tokens = tokens,
                    skipped = contextSkipped,
                    opening = (prepared.conversation.firstOrNull()?.content ?: prepared.latestUser)
                        .take(ChatSession.OPENING_CHARS),
                )
                session = finished
                sessionSaved = false
                sessionWorthSaving = SessionStore.worthSaving(tokens, answerSpeed)
                if (sessionWorthSaving) saveWhenFree(finished)
            }
            _state.value = RuntimeState(RuntimeStatus.READY, model.id, model.name)
            logger?.info("INFERENCE", "Resposta concluída (${reply.length} caracteres)")
            answerSpeed?.let { speed ->
                logger?.info("SPEED", speed.describe(ioBefore, ProcessIo.read()))
                onStage(
                    GenerationStage(
                        GenerationStage.Kind.ANSWER_SPEED,
                        done = speed.answerTokens,
                        total = speed.answerMillis.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
                    ),
                )
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
            busyWith = null
            activeJob = null
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

    override suspend fun saveSessionNow() = withContext(nativeDispatcher) {
        // An answer in progress saves its own conversation when it ends.
        if (!mutex.tryLock()) return@withContext
        try {
            saveSessionLocked()
        } finally {
            mutex.unlock()
        }
        Unit
    }

    /**
     * Ends the prompt the native side is reading, from any thread: the request it belongs to ends as
     * cancelled and the model stays loaded. With [owner], only when that request (a job of the AI
     * process) is the one holding the model. False while a model loads or is tested, which cannot be
     * interrupted: the app then stops the whole AI process.
     */
    fun abortPromptReading(owner: Job? = null): Boolean {
        val busy = busyWith
        if (loading || busy == GenerationStage.BUSY_VERIFY || busy == GenerationStage.BUSY_LOAD) return false
        val active = activeJob
        if (owner != null && (active == null || !owner.isAncestorOf(active))) return true
        requestNativeAbort()
        return true
    }

    private fun requestNativeAbort() {
        if (engineDelegate.isInitialized()) runCatching { engine.requestAbort() }
    }

    private fun Job.isAncestorOf(job: Job): Boolean = this == job || children.any { it.isAncestorOf(job) }

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

        // Another conversation replaces the one in the context: it is kept on storage first.
        saveSessionLocked()
        session = null
        primed = null
        turnMark = null
        _state.value = RuntimeState(RuntimeStatus.LOADING, model.id, model.name)
        try {
            restoreSaved(model, key, prepared.conversation)?.let { saved ->
                session = saved
                sessionWorthSaving = true
                sessionSaved = true
                return
            }
            runCatching { Os.setenv(THINKING_ENV, if (prefill.turn.templateThinking) "1" else "0", true) }
            setSystemPrompt(model, prepared.systemPrompt, prefill.turn)
            val complete = addHistory(prepared.history) { requests.get() == ticket }
            if (!complete) {
                logger?.info("PROMPT_TEMPLATE", "Preparação interrompida por uma nova solicitação")
                return
            }
            primed = target
            logger?.info("PROMPT_TEMPLATE", "Instruções e ${prepared.history.size} turnos processados antes da próxima mensagem")
        } catch (cancel: CancellationException) {
            // A message sent meanwhile ended the native decode (requestAbort); only a cancelled
            // preparation itself goes on as cancelled.
            if (!currentCoroutineContext().isActive) throw cancel
            logger?.info("PROMPT_TEMPLATE", "Preparação interrompida por uma nova solicitação")
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
     * Decodes [history] after the system prompt, several turns per native call; [onGroup] hears how
     * many turns are done before each call and returns false to stop there. Returns whether all were.
     */
    private suspend fun addHistory(history: List<AiChatMessage>, onGroup: (done: Int) -> Boolean): Boolean {
        var done = 0
        for (group in historyGroups(history)) {
            if (!onGroup(done + group.size)) return false
            engine.addHistoryMessages(group.map { it.role }, group.map { it.content })
            done += group.size
        }
        return true
    }

    /** The turns of [history] in groups of about [HISTORY_GROUP_CHARS]; a longer turn is a group of its own. */
    private fun historyGroups(history: List<AiChatMessage>): List<List<AiChatMessage>> {
        val groups = mutableListOf<List<AiChatMessage>>()
        var current = mutableListOf<AiChatMessage>()
        var chars = 0
        for (message in history) {
            if (current.isNotEmpty() && chars + message.content.length > HISTORY_GROUP_CHARS) {
                groups += current
                current = mutableListOf()
                chars = 0
            }
            current += message
            chars += message.content.length
        }
        if (current.isNotEmpty()) groups += current
        return groups
    }

    /**
     * Processes [systemPrompt]. The native side restores what it saved of this model's system
     * prompt, up to the part that changes daily ([TurnOptions.systemPromptTail]), and saves it
     * again when it had to process it. Each profile's prompt has a file of its own.
     */
    private suspend fun setSystemPrompt(
        model: AiModelEntity,
        systemPrompt: String,
        turn: TurnOptions,
        onStage: (GenerationStage) -> Unit = {},
    ) {
        val tail = turn.systemPromptTail.trimEnd()
        val stable = if (tail.isNotEmpty() && systemPrompt.endsWith(tail)) systemPrompt.dropLast(tail.length) else systemPrompt
        val file = PromptCache.file(appContext, model.id, turn.templateThinking, stable + expertsKeySuffix())
        runCatching {
            file.parentFile?.mkdirs()
            Os.setenv(PROMPT_CACHE_ENV, file.path, true)
            if (tail.isNotEmpty() && systemPrompt.endsWith(tail)) Os.setenv(SYSTEM_PROMPT_TAIL_ENV, tail, true)
            else Os.unsetenv(SYSTEM_PROMPT_TAIL_ENV)
        }
        val saved = runCatching { file.length() > 0L }.getOrDefault(false)
        onStage(
            GenerationStage(
                if (saved) GenerationStage.Kind.RESTORING_INSTRUCTIONS else GenerationStage.Kind.READING_INSTRUCTIONS,
            ),
        )
        engine.setSystemPrompt(systemPrompt)
    }

    /**
     * Drops the oldest turns of [previous] from the context when the next message would not fit,
     * instead of reading the conversation again; null when the context cannot drop them (recurrent
     * models, a session from an older version) and the conversation is read again.
     */
    private suspend fun dropOldestTurns(previous: ChatSession, latestUserChars: Int, outputTokens: Int): ChatSession? {
        val turns = previous.turnsToDrop(latestUserChars, outputTokens, loadedContextTokens)
        if (turns == 0) return null
        val tokens = engine.dropOldestTurns(turns)
        if (tokens < 0) return null
        turnMark = null
        val trimmed = previous.dropped(turns, tokens)
        logger?.info("PROMPT_TEMPLATE", "As $turns mensagens mais antigas saíram do contexto para a conversa continuar ($tokens tokens)")
        return trimmed.takeIf { it.hasRoomFor(latestUserChars, outputTokens, loadedContextTokens) }
    }

    /**
     * Takes the context back to the start of the marked user turn ("Refazer" or an edit of it), so
     * only the new message is read; false when it cannot (the native side then emptied the context).
     */
    private suspend fun rewindToMark(latestUserChars: Int, outputTokens: Int): Boolean {
        val position = engine.rewindLastTurn()
        if (position < 0) {
            turnMark = null
            return false
        }
        val reserve = ChatSession.continuationReserve(outputTokens, loadedContextTokens)
        if (position + latestUserChars / 3 + reserve + REWIND_HEADROOM_TOKENS > loadedContextTokens) return false
        logger?.info("PROMPT_TEMPLATE", "Voltando ao início da última mensagem ($position tokens), sem ler a conversa de novo")
        return true
    }

    /**
     * Restores a conversation saved on storage when [conversation] (the turns before the next
     * message) continues it and [fits] it, instead of reading it again. Returns it when the native
     * context holds it again, as right after its last answer.
     */
    private suspend fun restoreSaved(
        model: AiModelEntity,
        key: String,
        conversation: List<AiChatMessage>,
        onStage: (GenerationStage) -> Unit = {},
        fits: (ChatSession) -> Boolean = { true },
    ): ChatSession? {
        val saved = savedSessions.find { it.continuesWith(model.id, key, conversation) && fits(it) } ?: return null
        turnMark = null
        val tokens = try {
            engine.restoreSession(saved.stateFile.path)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (t: Throwable) {
            logger?.error("PROMPT_TEMPLATE", "Falha ao restaurar a conversa salva", t)
            -1
        }
        if (tokens != saved.session.tokens) {
            logger?.info("PROMPT_TEMPLATE", "A conversa salva não coube no contexto carregado; lendo a conversa")
            return null
        }
        savedSessions.touch(saved)
        onStage(GenerationStage(GenerationStage.Kind.CONTEXT_REUSED, GenerationStage.REUSED_STORAGE))
        logger?.info("PROMPT_TEMPLATE", "Conversa restaurada do armazenamento ($tokens tokens), sem lê-la de novo")
        return saved.session
    }

    /**
     * Writes [finished] to storage when the model has been idle for a while, unless something else
     * used the context first: a tool's result or the next message continues it live, and saves its
     * own answer. Leaving the app ([saveSessionNow]) or another conversation saves it sooner.
     */
    private fun saveWhenFree(finished: ChatSession) {
        background.launch {
            delay(SAVE_DELAY_MS)
            mutex.withLock {
                if (session === finished) saveSessionLocked()
            }
        }
    }

    /** Writes [session] to storage if reading it again would be slow and it is not saved yet; holds [mutex]. */
    private suspend fun saveSessionLocked() {
        val current = session ?: return
        if (sessionSaved || !sessionWorthSaving || contextReleased || loadedModelId != current.modelId) return
        val before = busyWith
        busyWith = GenerationStage.BUSY_SAVE
        try {
            val file = savedSessions.beginSave(current)
            if (engine.saveSession(file.path)) {
                savedSessions.finishSave(current)
                sessionSaved = true
                logger?.info("PROMPT_TEMPLATE", "Conversa salva no armazenamento (${current.tokens} tokens)")
            } else {
                logger?.info("PROMPT_TEMPLATE", "Não foi possível salvar a conversa no armazenamento")
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (t: Throwable) {
            logger?.error("PROMPT_TEMPLATE", "Falha ao salvar a conversa no armazenamento", t)
        } finally {
            busyWith = before
        }
    }

    private fun sessionKey(systemPrompt: String, templateThinking: Boolean) =
        systemPrompt + "\u0000thinking=" + templateThinking + expertsKeySuffix()

    /** Empty unless the loaded model uses fewer experts than its GGUF ("Modo rápido de MoE"). */
    private fun expertsKeySuffix(): String = loadedFastExperts?.let { "\u0000experts=$it" }.orEmpty()

    /**
     * Copy of the recurrent state kept at the start of each user turn, so "Refazer" works for
     * recurrent and hybrid models too ([RuntimeTuning.rewind]); 0 turns it off.
     */
    private fun checkpointCapMb(model: AiModelEntity): Int = when {
        safeLevelOf(model.id) > 0 -> 0
        tuning.rewind == TuningSwitch.OFF -> 0
        tuning.rewind == TuningSwitch.ON -> LARGE_CHECKPOINT_MB
        deviceGigabytes >= LARGE_CHECKPOINT_MIN_GB -> LARGE_CHECKPOINT_MB
        else -> SMALL_CHECKPOINT_MB
    }

    override suspend fun releaseIfIdle(): Boolean = withContext(nativeDispatcher) {
        // Busy means loading or answering, possibly for the background service: leave it alone.
        if (loadedModelId == null || !mutex.tryLock()) return@withContext false
        try {
            saveSessionLocked()
            val layout = loadedLayout
            if (layout?.fileBacked == true && !contextReleased) {
                // The weights are mapped from the file and the kernel drops them as it needs: only
                // the context is memory Android cannot take back. Coming back creates it again
                // instead of loading the model.
                logger?.info("MODEL_UNLOAD", "Pouca memória no aparelho: liberando o contexto da IA ociosa")
                engine.releaseContext()
                contextReleased = true
                session = null
                primed = null
                turnMark = null
            } else {
                logger?.info("MODEL_UNLOAD", "Pouca memória no aparelho: liberando o modelo ocioso")
                unloadLocked()
            }
            true
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (t: Throwable) {
            logger?.error("MODEL_UNLOAD", "Falha ao liberar a memória da IA ociosa", t)
            unloadLocked()
            true
        } finally {
            mutex.unlock()
        }
    }

    private fun isLoaded(model: AiModelEntity): Boolean =
        engineDelegate.isInitialized() && loadedModelId == model.id && !contextReleased &&
            engine.state.value is InferenceEngine.State.ModelReady && loadedKey == loadKeyFor(model.id)

    private fun loadKeyFor(modelId: String) = LoadKey(
        performance = tuning.performance,
        longContext = tuning.longContext,
        longPromptMoe = tuning.longPromptMoe,
        fastMoe = tuning.fastMoe,
        performanceBlocked = modelId in tuning.performanceBlocked,
        safeLevel = safeLevelOf(modelId),
    )

    /**
     * Asks the kernel to read again, in the background, the weights it dropped from the page cache
     * while the app was away, before the next answer needs them one page fault at a time. Only for
     * weights read from the file that fit in the free RAM; per-layer embeddings are read on demand.
     */
    private fun warmWeights() {
        val layout = loadedLayout ?: return
        if (!layout.fileBacked || layout.streamsExperts || layout.onDemandBytes > 0) return
        val free = DeviceMemory.read(appContext).availableBytes
        if (free < layout.fileBytes + WARM_HEADROOM_BYTES) return
        runCatching { engine.adviseWillNeed(layout.filePath) }
    }

    private fun layoutOf(model: AiModelEntity): GgufInspector.Metadata? {
        val file = File(model.filePath)
        val key = "${file.path}:${file.length()}:${file.lastModified()}"
        return layouts.getOrPut(key) { runCatching { GgufInspector().inspect(file) }.getOrNull() }
    }

    /**
     * Loads [model] unless it is already loaded and healthy. The model stays loaded between
     * messages, so a conversation does not pay for a reload (and its weight repacking) each turn.
     */
    private suspend fun ensureLoaded(model: AiModelEntity, onStage: (GenerationStage) -> Unit = {}) {
        awaitEngineInitialized(onStage)
        val loadKey = loadKeyFor(model.id)
        if (loadedModelId == model.id && engine.state.value is InferenceEngine.State.ModelReady && loadedKey == loadKey) {
            if (!contextReleased || restoreContext(model, onStage)) {
                _state.value = RuntimeState(RuntimeStatus.READY, model.id, model.name)
                return
            }
        }
        // Another model, or other settings: the conversation in the context is kept on storage.
        if (loadedModelId != null && !contextReleased) saveSessionLocked()
        if (loadedModelId != null || engine.state.value is InferenceEngine.State.Error) unloadLocked()

        _state.value = RuntimeState(RuntimeStatus.LOADING, model.id, model.name)
        val catalogEntry = ModelCatalog.entries.firstOrNull { model.apiModelId.startsWith(it.apiIdPrefix) }
        val fileBytes = File(model.filePath).length()
        val layout = layoutOf(model)
        // Per-layer embeddings stay in the file (the native loader reads them on demand), so only
        // the rest of the weights takes RAM: about half of a Gemma 4 E2B/E4B file.
        val onDemandBytes = layout?.onDemandBytes ?: 0L
        val modelBytes = (fileBytes - onDemandBytes).coerceAtLeast(0L)
        // Read after the previous model was freed, so its memory counts as free.
        val memory = DeviceMemory.read(appContext)
        // A model that already made the app close for lack of memory loads with less of it.
        val safeLevel = loadKey.safeLevel
        val weights = layout?.let {
            RuntimeLimits.WeightProfile(
                known = true,
                repackableBytes = it.repackableBytes,
                fixedRepackableBytes = it.fixedRepackableBytes,
                requantizedBytes = it.requantizedBytes,
                largestRequantizedValues = it.largestRequantizedValues,
                recurrent = it.recurrent,
            )
        } ?: RuntimeLimits.WeightProfile()
        val plan = RuntimeLimits.planMemory(
            residentWeightsBytes = modelBytes,
            memory = memory,
            expertBytes = layout?.expertBytes ?: 0L,
            safeLevel = safeLevel,
            weights = weights,
            tuning = tuning,
            performanceBlocked = loadKey.performanceBlocked,
        )
        if (!plan.fits) {
            val message = "${model.name} precisa de cerca de ${gigabytes(plan.minimumBytes)} de RAM, mas a IA pode usar " +
                "${gigabytes(plan.budgetBytes)} agora (a RAM livre menos 0,5 GB para o Android e o app). Carregar " +
                "assim faria o celular travar lendo o modelo do armazenamento. Feche outros apps ou escolha um modelo menor."
            _state.value = RuntimeState(RuntimeStatus.ERROR, model.id, model.name, message)
            logger?.error("MODEL_LOAD", message)
            throw IllegalStateException(message)
        }
        val lowMemory = plan.lowMemory || RuntimeLimits.needsPlainWeights(model.architecture)
        val repackRelease = plan.repackRelease && !lowMemory
        // Experts mapped from the file and the rest of the weights repacked (step 24 of the native patch).
        val mappedExperts = plan.mappedExperts && !lowMemory && !plan.streamsExperts
        val kvBudgetMb = plan.kvBudgetBytes / MB
        // "Modo rápido de MoE": the GGUF's own key, replaced through llama.cpp's metadata override.
        val expertsKey = layout?.expertUsedCountKey
        val fastExperts = layout?.takeIf { expertsKey != null }?.let {
            RuntimeLimits.fastMoeExperts(it.expertUsedCount, it.expertCount, loadKey.fastMoe, plan.streamsExperts)
        }
        // Read by the patched native loader: file-backed weights for models that do not fit
        // comfortably in RAM, experts read from storage for a mixture of experts that does not fit
        // at all, the memory that sizes the (Q4_0) context, the presence penalty the model card
        // recommends (the other sampling settings come from the GGUF itself), the efficiency
        // cores, which the CPU threads only fill in with, the micro-batch for reading prompts and
        // whether each repacked tensor's pages are dropped from the file as soon as it is copied.
        val efficiency = efficiencyCpus
        runCatching {
            if (efficiency == null) Os.unsetenv(EFFICIENCY_CPUS_ENV)
            else Os.setenv(EFFICIENCY_CPUS_ENV, CpuCores.encode(efficiency), true)
            Os.setenv(LOW_MEMORY_ENV, if (lowMemory) "1" else "0", true)
            Os.setenv(MOE_STREAM_ENV, if (plan.streamsExperts) "1" else "0", true)
            Os.setenv(CONTEXT_TOKENS_ENV, RuntimeLimits.MAX_CONTEXT_TOKENS.toString(), true)
            Os.setenv(KV_BUDGET_ENV, kvBudgetMb.toString(), true)
            Os.setenv(PRESENCE_PENALTY_ENV, (catalogEntry?.presencePenalty ?: 0f).toString(), true)
            Os.setenv(UBATCH_ENV, plan.ubatch.toString(), true)
            Os.setenv(REPACK_RELEASE_ENV, if (repackRelease) "1" else "0", true)
            Os.setenv(MAPPED_EXPERTS_ENV, if (mappedExperts) "1" else "0", true)
            if (fastExperts != null) Os.setenv(MOE_EXPERTS_ENV, "$expertsKey=$fastExperts", true)
            else Os.unsetenv(MOE_EXPERTS_ENV)
            // Experts read from storage: where they are counted and how much of the most used to read
            // into memory after the load (step 23 b of the native patch).
            if (plan.streamsExperts) {
                val stats = ExpertStats.file(appContext, model.id)
                stats.parentFile?.mkdirs()
                Os.setenv(EXPERT_STATS_ENV, stats.path, true)
                Os.setenv(EXPERT_PRELOAD_ENV, (plan.expertPreloadBytes / MB).toString(), true)
            } else {
                Os.unsetenv(EXPERT_STATS_ENV)
                Os.unsetenv(EXPERT_PRELOAD_ENV)
            }
            Os.unsetenv(ACTIVE_CONTEXT_ENV)
            Os.unsetenv(ACTIVE_THREADS_ENV)
        }
        logger?.info(
            "MODEL_LOAD",
            "Carregando ${model.name} (${model.filePath}); pesos na RAM ${modelBytes / MB} MB" +
                (if (onDemandBytes > 0) " (+${onDemandBytes / MB} MB lidos do arquivo sob demanda)" else "") +
                "; RAM livre ${memory.availableBytes / MB} MB de ${memory.totalBytes / MB} MB; " +
                "limite da IA ${plan.budgetBytes / MB} MB; memória para contexto: $kvBudgetMb MB" +
                (if (plan.longContextBytes > 0) " (+${plan.longContextBytes / MB} MB do contexto longo)" else "") +
                (if (plan.streamsExperts) {
                    "; especialistas MoE (${(layout?.expertBytes ?: 0L) / MB} MB) lidos do armazenamento conforme o uso" +
                        if (lowMemory) "" else ", o restante dos pesos mantido na RAM"
                } else {
                    ""
                }) +
                (when {
                    lowMemory -> " em modo de pouca memória"
                    mappedExperts -> "; especialistas MoE (${(layout?.expertBytes ?: 0L) / MB} MB) mapeados do arquivo, " +
                        "cópia otimizada só dos pesos lidos a cada token"
                    else -> "; cópia otimizada dos pesos na RAM"
                }) +
                (if (repackRelease) " (páginas do arquivo liberadas ao copiar)" else "") +
                (if (plan.ubatch != RuntimeLimits.DEFAULT_UBATCH) "; micro-lote de ${plan.ubatch} tokens" else "") +
                (if (fastExperts != null) "; modo rápido de MoE: $fastExperts especialistas por token em vez de ${layout?.expertUsedCount}" else "") +
                (if (plan.expertPreloadBytes > 0) "; até ${plan.expertPreloadBytes / MB} MB dos especialistas mais usados lidos após carregar" else "") +
                if (safeLevel > 0) " (nível de economia $safeLevel após fechamento por falta de memória)" else "",
        )
        onStage(GenerationStage(GenerationStage.Kind.LOADING_MODEL, model.name, total = (fileBytes / MB).toInt()))
        loading = true
        try {
            engine.loadModel(model.filePath)
            loadedContextTokens = activeContextTokens()
            val threads = runCatching { Os.getenv(ACTIVE_THREADS_ENV) }.getOrNull()
            val writingThreads = runCatching { Os.getenv(ACTIVE_GENERATION_THREADS_ENV) }.getOrNull()
            // What the CPU backend variant llama.cpp chose can use (step 22 b of the native patch).
            val cpuFeatures = runCatching { Os.getenv(CPU_FEATURES_ENV) }.getOrNull()
            logger?.info(
                "MODEL_LOAD",
                buildString {
                    append("Contexto alocado: $loadedContextTokens tokens")
                    if (threads != null) append("; $threads threads da CPU")
                    if (writingThreads != null && writingThreads != threads) append(" ($writingThreads ao escrever)")
                    if (!efficiency.isNullOrEmpty()) append("; núcleos de eficiência: ${CpuCores.encode(efficiency)}")
                    if (!cpuFeatures.isNullOrBlank()) append("; CPU: $cpuFeatures")
                },
            )
            loadedModelId = model.id
            loadedKey = loadKey
            loadedLayout = LoadedLayout(model.filePath, fileBytes, onDemandBytes, lowMemory || mappedExperts, plan.streamsExperts)
            loadedFastExperts = fastExperts
            contextReleased = false
            session = null
            primed = null
            turnMark = null
            _state.value = RuntimeState(RuntimeStatus.READY, model.id, model.name)
            logger?.info("MODEL_LOAD", "Modelo READY: ${model.apiModelId}")
        } catch (t: Throwable) {
            loadedModelId = null
            loadedKey = null
            loadedLayout = null
            loadedFastExperts = null
            session = null
            primed = null
            turnMark = null
            val message = humanize(t)
            _state.value = RuntimeState(RuntimeStatus.ERROR, model.id, model.name, message)
            logger?.error("MODEL_LOAD", "Falha ao carregar ${model.name}: $message", t)
            throw IllegalStateException(message, t)
        } finally {
            loading = false
        }
    }

    /**
     * Creates the context of the loaded model again after [releaseIfIdle] freed it; false (and the
     * model freed) when it cannot, so the model is loaded again.
     */
    private suspend fun restoreContext(model: AiModelEntity, onStage: (GenerationStage) -> Unit): Boolean {
        onStage(GenerationStage(GenerationStage.Kind.LOADING_MODEL, model.name))
        _state.value = RuntimeState(RuntimeStatus.LOADING, model.id, model.name)
        loading = true
        return try {
            engine.restoreContext()
            contextReleased = false
            loadedContextTokens = activeContextTokens()
            logger?.info("MODEL_LOAD", "Contexto da IA criado de novo ($loadedContextTokens tokens), sem carregar o modelo")
            true
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (t: Throwable) {
            logger?.error("MODEL_LOAD", "Falha ao criar de novo o contexto; carregando o modelo", t)
            unloadLocked()
            false
        } finally {
            loading = false
        }
    }

    private fun activeContextTokens(): Int =
        runCatching { Os.getenv(ACTIVE_CONTEXT_ENV)?.toIntOrNull() }.getOrNull()
            ?.takeIf { it > 0 } ?: RuntimeLimits.MIN_CONTEXT_TOKENS

    private fun gigabytes(bytes: Long): String =
        String.format(java.util.Locale.forLanguageTag("pt-BR"), "%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))

    private suspend fun awaitEngineInitialized(onStage: (GenerationStage) -> Unit = {}) {
        val current = engine.state.value
        if (current is InferenceEngine.State.Initialized || current is InferenceEngine.State.ModelReady) return
        onStage(GenerationStage(GenerationStage.Kind.STARTING_AI))
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
        session = null
        primed = null
        turnMark = null
        if (current is InferenceEngine.State.Uninitialized || current is InferenceEngine.State.Initializing) {
            loadedModelId = null
            loadedKey = null
            loadedLayout = null
            loadedFastExperts = null
            contextReleased = false
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
        loadedKey = null
        loadedLayout = null
        loadedFastExperts = null
        contextReleased = false
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
        private const val ACTIVE_THREADS_ENV = "IAOFFLINE_ACTIVE_THREADS"
        private const val ACTIVE_GENERATION_THREADS_ENV = "IAOFFLINE_ACTIVE_GENERATION_THREADS"
        private const val PROMPT_CACHE_ENV = "IAOFFLINE_PROMPT_CACHE"
        private const val SYSTEM_PROMPT_TAIL_ENV = "IAOFFLINE_SYSTEM_PROMPT_TAIL"
        private const val EFFICIENCY_CPUS_ENV = "IAOFFLINE_EFFICIENCY_CPUS"
        private const val UBATCH_ENV = "IAOFFLINE_UBATCH"
        private const val REPACK_RELEASE_ENV = "IAOFFLINE_REPACK_RELEASE"
        private const val MAPPED_EXPERTS_ENV = "IAOFFLINE_MAPPED_EXPERTS"
        private const val TURN_MARK_ENV = "IAOFFLINE_TURN_MARK"
        private const val CHECKPOINT_ENV = "IAOFFLINE_CHECKPOINT_MAX_MB"
        private const val MOE_EXPERTS_ENV = "IAOFFLINE_MOE_EXPERTS"
        private const val CPU_FEATURES_ENV = "IAOFFLINE_CPU_FEATURES"
        private const val EXPERT_STATS_ENV = "IAOFFLINE_EXPERT_STATS"
        private const val EXPERT_PRELOAD_ENV = "IAOFFLINE_EXPERT_PRELOAD_MB"
        private const val KLEIDIAI_SME_ENV = "GGML_KLEIDIAI_SME"
        private const val MAX_NATIVE_DETAIL_CHARS = 900
        /**
         * Writing the context (tens to hundreds of MB) holds the model, so it waits until the user
         * is not about to send the next message; leaving the app saves it at once.
         */
        private const val SAVE_DELAY_MS = 20_000L
        /** About 4,000 tokens: several turns per native call, which still stops between groups. */
        private const val HISTORY_GROUP_CHARS = 12_000
        private const val REWIND_HEADROOM_TOKENS = 256
        /** Free RAM left after reading the weights again into the page cache. */
        private const val WARM_HEADROOM_BYTES = 1024L * 1024 * 1024
        /** Recurrent state copies kept for "Refazer": up to 64 MB below 10 GB of RAM, 160 MB above. */
        private const val SMALL_CHECKPOINT_MB = 64
        private const val LARGE_CHECKPOINT_MB = 160
        private const val LARGE_CHECKPOINT_MIN_GB = 10.0
        private const val MAX_READY_CONVERSATIONS = 10
        private const val MB = 1024L * 1024
    }
}
