package com.example.ialocal.chat

import android.content.Context
import com.example.ialocal.agent.AiOrchestrator
import com.example.ialocal.ai.AiChatMessage
import com.example.ialocal.ai.AiChatRequest
import com.example.ialocal.ai.AiGateway
import com.example.ialocal.data.AgentEntity
import com.example.ialocal.data.ChatRepository
import com.example.ialocal.data.MessageRole
import com.example.ialocal.data.MessageStatus
import com.example.ialocal.data.MessageWithAttachments
import com.example.ialocal.data.ModelVerificationStatus
import com.example.ialocal.data.PendingAttachment
import com.example.ialocal.models.ModelManager
import com.example.ialocal.models.ModelRepository
import com.example.ialocal.models.ModelCatalog
import com.example.ialocal.models.provider
import com.example.ialocal.runtime.GenerationStage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext

/**
 * A streaming answer already supports cooperative cancellation. Native startup, verification
 * and prompt reading can be blocking, so they require the isolated AI process to be interrupted.
 */
internal fun shouldInterruptNativeWork(isActive: Boolean, stage: GenerationStage.Kind?): Boolean =
    isActive && stage != GenerationStage.Kind.WRITING && stage != GenerationStage.Kind.REASONING

/**
 * One answer per turn, as the model sees the conversation: "Refazer" keeps the old answer in the chat
 * and adds the new one after it, so of answers in a row ([isAnswer]) only the last one with text
 * ([isEmpty] false) is kept. Otherwise the model read both answers as one, and the next message could
 * not continue the context of the answer it last wrote.
 */
internal fun <T> oneAnswerPerTurn(items: List<T>, isAnswer: (T) -> Boolean, isEmpty: (T) -> Boolean): List<T> {
    val result = ArrayList<T>(items.size)
    var answers = mutableListOf<T>()
    fun keepLastAnswer() {
        if (answers.isEmpty()) return
        result += answers.lastOrNull { !isEmpty(it) } ?: answers.last()
        answers = mutableListOf()
    }
    for (item in items) {
        if (isAnswer(item)) {
            answers += item
        } else {
            keepLastAnswer()
            result += item
        }
    }
    keepLastAnswer()
    return result
}

/** The answer being written, ahead of the database ([ChatGenerationManager.Session.streaming]). */
data class StreamingReply(val messageId: String, val content: String)

/**
 * [stored] with the answer being written as it is now: the database has it only every few seconds.
 * Only while the stored message is still being sent, and never shorter than what is stored.
 */
internal fun withStreamingReply(stored: List<MessageWithAttachments>, streaming: StreamingReply?): List<MessageWithAttachments> {
    if (streaming == null) return stored
    return stored.map { item ->
        val message = item.message
        if (message.id == streaming.messageId && message.status == MessageStatus.SENDING.name &&
            streaming.content.length >= message.content.length
        ) {
            item.copy(message = message.copy(content = streaming.content))
        } else {
            item
        }
    }
}

data class QueuedChatMessage(
    val content: String,
    val attachments: List<PendingAttachment> = emptyList(),
)

/** What the notification says the AI is doing: thinking before the answer, then writing it. */
enum class AnswerPhase { THINKING, WRITING }

/** The notification's line for one answer: "<modelo> - Pensando . . ." / "Escrevendo . . .". */
data class AnswerNotice(val modelName: String?, val phase: AnswerPhase)

private fun noticeModelName(modelName: String?): String = modelName?.takeIf { it.isNotBlank() } ?: "I.A offline"

/**
 * The answering notification's title: "Lfm2.5 8B - Pensando . . ." while the AI reads and reasons,
 * then "Lfm2.5 8B - Escrevendo . . .", with [dots] (1 to 3) filling up like a loading indicator.
 */
internal fun answeringTitle(notice: AnswerNotice?, dots: Int): String {
    val action = if (notice?.phase == AnswerPhase.WRITING) "Escrevendo" else "Pensando"
    return "${noticeModelName(notice?.modelName)} - $action " + List(dots.coerceIn(1, 3)) { "." }.joinToString(" ")
}

/** The last step, once the answer is ready: "Lfm2.5 8B - Respondido". */
internal fun answeredTitle(modelName: String?): String = "${noticeModelName(modelName)} - Respondido"

/** The model/profile the user had selected when a message was sent. */
data class GenerationTarget(
    val preferredAgentId: String?,
    val selectedModelId: String?,
)

/**
 * Owns chat generation outside any screen lifecycle, so leaving the chat or backgrounding the app
 * never interrupts an answer. While anything is generating, [ChatGenerationService] keeps the
 * process in the foreground and the CPU awake.
 */
class ChatGenerationManager(
    context: Context,
    private val repository: ChatRepository,
    private val aiGateway: AiGateway,
    private val modelRepository: ModelRepository,
    private val modelManager: ModelManager,
    private val orchestrator: AiOrchestrator,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val sessions = ConcurrentHashMap<String, Session>()
    /** The latest [prepare]; each one waits for the previous, so they never overlap. */
    private var warmUp: Job? = null

    private val _activeGenerations = MutableStateFlow(0)
    val activeGenerations: StateFlow<Int> = _activeGenerations.asStateFlow()
    private val generatingConversationIds = mutableListOf<String>()
    private val _notificationConversationId = MutableStateFlow<String?>(null)
    /** The conversation opened by tapping the foreground notification. */
    val notificationConversationId: StateFlow<String?> = _notificationConversationId.asStateFlow()
    private val _notices = MutableStateFlow<Map<String, AnswerNotice>>(emptyMap())
    /** What each conversation being answered is doing, for the foreground notification. */
    val notices: StateFlow<Map<String, AnswerNotice>> = _notices.asStateFlow()

    private fun setNotice(conversationId: String, notice: AnswerNotice?) {
        _notices.update { if (notice == null) it - conversationId else it + (conversationId to notice) }
    }

    /**
     * At most [MAX_CONCURRENT_ANSWERS] conversations are answered at once. The semaphore is fair, so
     * a message sent from a third conversation waits and is answered in the order it arrived.
     */
    private val answerSlots = Semaphore(MAX_CONCURRENT_ANSWERS)

    class Session internal constructor() {
        internal val _isGenerating = MutableStateFlow(false)
        val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()
        internal val _queued = MutableStateFlow<List<QueuedChatMessage>>(emptyList())
        val queued: StateFlow<List<QueuedChatMessage>> = _queued.asStateFlow()
        internal val _error = MutableStateFlow<String?>(null)
        val error: StateFlow<String?> = _error.asStateFlow()
        internal val _resolvedAgentId = MutableStateFlow<String?>(null)
        /** The agent actually used by the latest generation in this conversation. */
        val resolvedAgentId: StateFlow<String?> = _resolvedAgentId.asStateFlow()
        internal val _waitingForSlot = MutableStateFlow(false)
        /** True while this conversation's message waits for other conversations to be answered. */
        val waitingForSlot: StateFlow<Boolean> = _waitingForSlot.asStateFlow()
        internal val _progress = MutableStateFlow<GenerationProgress?>(null)
        /** What the answer being prepared is doing, step by step; null when nothing is generating. */
        val progress: StateFlow<GenerationProgress?> = _progress.asStateFlow()
        internal val _streaming = MutableStateFlow<StreamingReply?>(null)
        /**
         * The answer being written, updated several times per second; the database gets it every
         * few seconds, since each write re-reads the conversation and redraws the chat.
         */
        val streaming: StateFlow<StreamingReply?> = _streaming.asStateFlow()
        internal var job: Job? = null
        internal var queuedTarget: GenerationTarget? = null

        /** Called from the AI's threads too, so the update is atomic. */
        internal fun report(stage: GenerationStage) {
            val now = System.currentTimeMillis()
            _progress.update { (it ?: GenerationProgress()).advance(stage, now) }
        }
    }

    fun session(conversationId: String): Session = sessions.getOrPut(conversationId) { Session() }

    /**
     * Loads the AI a message sent now would use and processes its instructions and this
     * conversation in the background, while the user writes, so the answer starts sooner. Skipped
     * while answers are running: they need the model, and their conversations stay in its context.
     */
    fun prepare(conversationId: String, target: GenerationTarget) {
        if (_activeGenerations.value > 0) return
        val previous = warmUp
        warmUp = scope.launch {
            previous?.join()
            if (_activeGenerations.value > 0) return@launch
            try {
                val agent = resolveAgent(conversationId, target, verify = false) ?: return@launch
                val history = modelHistory(repository.getMessages(conversationId))
                // A message sent meanwhile already loads the model and reads the conversation.
                if (_activeGenerations.value > 0) return@launch
                orchestrator.warmUpAgent(agent.id, history)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Throwable) {
                // Only time is lost; sending the message loads the model again and reports the error.
            }
        }
    }

    /**
     * "Carregar a IA ao abrir o app": loads the default profile's model and processes its instructions
     * while the user is still on the home screen, so the first answer starts without the load.
     */
    fun preload() {
        if (_activeGenerations.value > 0) return
        val previous = warmUp
        warmUp = scope.launch {
            previous?.join()
            if (_activeGenerations.value > 0) return@launch
            try {
                orchestrator.warmUpAgent(null, emptyList())
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Throwable) {
                // Only time is lost; the first message loads the model and reports the error.
            }
        }
    }

    fun send(conversationId: String, outgoing: QueuedChatMessage, target: GenerationTarget) {
        val session = session(conversationId)
        session._error.value = null
        startOrQueue(conversationId, session, outgoing, target)
    }

    private fun startOrQueue(
        conversationId: String,
        session: Session,
        outgoing: QueuedChatMessage,
        target: GenerationTarget,
    ) {
        if (session._isGenerating.value) {
            session._queued.value = session._queued.value + outgoing
            session.queuedTarget = target
        } else {
            launchGeneration(conversationId, session, target, "Falha ao gerar a resposta.") {
                // The message shows up in the chat right away, even if it has to wait for a slot.
                val historyBeforeSend = modelHistory(repository.getMessages(conversationId))
                repository.addMessage(conversationId, MessageRole.USER, outgoing.content, outgoing.attachments)
                awaitAnswerSlot(session) {
                    generateAssistant(
                        conversationId,
                        session,
                        target,
                        historyBeforeSend + AiChatMessage("user", outgoing.content),
                        outgoing.attachments,
                    )
                }
            }
        }
    }

    fun rerun(conversationId: String, messageId: String, target: GenerationTarget) {
        val session = session(conversationId)
        if (session._isGenerating.value) return
        launchGeneration(conversationId, session, target, "Falha ao refazer a resposta.") {
            val snapshot = repository.getMessages(conversationId)
            val assistantIndex = snapshot.indexOfFirst {
                it.message.id == messageId && it.message.role == MessageRole.ASSISTANT.name
            }
            if (assistantIndex < 0) return@launchGeneration
            val userIndex = (assistantIndex - 1 downTo 0).firstOrNull {
                snapshot[it].message.role == MessageRole.USER.name
            } ?: return@launchGeneration
            val history = modelHistory(snapshot.take(userIndex + 1))
            awaitAnswerSlot(session) { generateAssistant(conversationId, session, target, history, emptyList()) }
        }
    }

    /** Before the first token, native model loading and prompt processing may ignore cancellation. */
    private fun Session.needsNativeInterruption(): Boolean =
        shouldInterruptNativeWork(job?.isActive == true, _progress.value?.current?.stage?.kind)

    /** Only an explicit stop cancels this conversation's active answer and its pending turns. */
    fun stop(conversationId: String) {
        sessions[conversationId]?.let { session ->
            val interruptNative = _activeGenerations.value == 1 && session.needsNativeInterruption()
            session._queued.value = emptyList()
            session.queuedTarget = null
            // Abort before cancelling coroutines: callbackFlow removes the running request
            // immediately on cancellation, but JNI may still be loading it in the AI process.
            if (interruptNative) modelManager.interruptNativeWork()
            session.job?.cancel()
            // Preparation is independent of the chat's response job. Cancel it as well.
            if (_activeGenerations.value <= 1) warmUp?.cancel()
        }
    }

    /** The notification's Parar button cancels every answer and pending turn. */
    fun stopAll() {
        val interruptNative = sessions.values.any { it.needsNativeInterruption() }
        // Kill blocking native work while its request is still registered.
        if (interruptNative) modelManager.interruptNativeWork()
        sessions.values.forEach { session ->
            session._queued.value = emptyList()
            session.queuedTarget = null
            session.job?.cancel()
        }
        warmUp?.cancel()
    }

    fun removeQueued(conversationId: String, index: Int): QueuedChatMessage? {
        val session = sessions[conversationId] ?: return null
        val current = session._queued.value
        val removed = current.getOrNull(index) ?: return null
        session._queued.value = current.filterIndexed { itemIndex, _ -> itemIndex != index }
        return removed
    }

    fun clearError(conversationId: String) {
        sessions[conversationId]?._error?.value = null
    }

    private fun launchGeneration(
        conversationId: String,
        session: Session,
        target: GenerationTarget,
        failureMessage: String,
        block: suspend () -> Unit,
    ) {
        session._isGenerating.value = true
        session._progress.value = null
        session._streaming.value = null
        // The latest responding conversation is what the notification opens. Track all active
        // sessions so stopping one cannot redirect the notification to an unrelated chat.
        generatingConversationIds.add(conversationId)
        // A new answer replaces this conversation's "Respondido" notification.
        ChatGenerationService.cancelAnswered(appContext, conversationId)
        setNotice(conversationId, AnswerNotice(modelName = null, phase = AnswerPhase.THINKING))
        _notificationConversationId.value = conversationId
        if (_activeGenerations.value == 0) ChatGenerationService.start(appContext)
        _activeGenerations.value += 1
        session.job = scope.launch {
            try {
                block()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (t: Throwable) {
                session._error.value = t.message ?: failureMessage
            } finally {
                session.job = null
                session._isGenerating.value = false
                session._progress.value = null
                // Before the next queued turn, which sets its own notice.
                setNotice(conversationId, null)
                // Start the next queued turn before releasing this one so the count never drops to
                // zero in between and the foreground service is not stopped mid-queue.
                startNextQueued(conversationId, session, target)
                generatingConversationIds.remove(conversationId)
                _notificationConversationId.value = generatingConversationIds.lastOrNull()
                _activeGenerations.value -= 1
            }
        }
    }

    private suspend fun awaitAnswerSlot(session: Session, block: suspend () -> Unit) {
        if (!answerSlots.tryAcquire()) {
            session._waitingForSlot.value = true
            session.report(GenerationStage(GenerationStage.Kind.QUEUED))
            try {
                answerSlots.acquire()
            } finally {
                session._waitingForSlot.value = false
            }
        }
        try {
            block()
        } finally {
            answerSlots.release()
        }
    }

    private fun startNextQueued(conversationId: String, session: Session, previousTarget: GenerationTarget) {
        val next = session._queued.value.firstOrNull() ?: return
        session._queued.value = session._queued.value.drop(1)
        startOrQueue(conversationId, session, next, session.queuedTarget ?: previousTarget)
    }

    /**
     * The profile, and so the model, that answers a message sent now. With [verify], a model not
     * yet tested is tested first and stays loaded for the answer; without it, such a model gives null.
     */
    private suspend fun resolveAgent(
        conversationId: String,
        target: GenerationTarget,
        verify: Boolean,
        onStage: (GenerationStage) -> Unit = {},
    ): AgentEntity? {
        val profileName = modelRepository.selectedProfile.value.displayName
        val conversation = repository.getConversation(conversationId)
        val preferredAgentId = target.preferredAgentId ?: conversation?.agentId
        var preferredAgent = preferredAgentId?.let { modelRepository.getAgent(it) }
        if (preferredAgent?.name != profileName) {
            val modelId = target.selectedModelId ?: preferredAgent?.modelId
                ?: modelRepository.getModels().firstOrNull()?.id
            preferredAgent = modelId?.let { id ->
                modelRepository.ensureStarterProfiles(id)
                modelRepository.getAgents().firstOrNull { it.modelId == id && it.name == profileName }
            }
        }
        if (preferredAgent != null) {
            val selectedModel = requireNotNull(modelRepository.getModel(preferredAgent.modelId)) {
                "O modelo selecionado não está mais disponível."
            }
            if (selectedModel.verificationStatus != ModelVerificationStatus.VERIFIED.name) {
                if (!verify) return null
                onStage(GenerationStage(GenerationStage.Kind.VERIFYING, selectedModel.name))
                modelManager.retryVerification(selectedModel.id, keepLoaded = true)
            }
        }
        return modelRepository.resolveAgentForUse(preferredAgent?.id)
    }

    private suspend fun generateAssistant(
        conversationId: String,
        session: Session,
        target: GenerationTarget,
        history: List<AiChatMessage>,
        attachments: List<PendingAttachment>,
    ) {
        val conversation = repository.getConversation(conversationId)
        val agent = resolveAgent(conversationId, target, verify = true, onStage = session::report)
            ?: throw IllegalStateException("Nenhum perfil com modelo local disponível pôde ser preparado para uso.")
        session._resolvedAgentId.value = agent.id
        if (conversation?.agentId != agent.id) {
            repository.setConversationAgent(conversationId, agent.id)
        }

        // Store the actual answering model's identity, not just the company. After the user
        // switches models, old replies must retain their correct model family and size.
        val answeringModel = modelRepository.getModel(agent.modelId)
        val provider = answeringModel?.provider()
        val answeringModelName = answeringModel?.let { model ->
            ModelCatalog.entries.firstOrNull { model.apiModelId.startsWith(it.apiIdPrefix) }
                ?.displayName ?: model.name
        }
        var phase = AnswerPhase.THINKING
        setNotice(conversationId, AnswerNotice(answeringModelName, phase))
        val replyId = repository.addMessage(conversationId, MessageRole.ASSISTANT, "", status = MessageStatus.SENDING)
        val startedAt = System.currentTimeMillis()
        var reasoningStartedAt: Long? = null
        var reasoningEndedAt: Long? = null
        val accumulated = StringBuilder()
        // Tokens written and the time spent writing them, summed over the model's turns (a tool
        // call makes more than one); reported by the AI after each turn's last word.
        val writtenTokens = AtomicLong()
        val writingMs = AtomicLong()
        fun finalContent(): String {
            val now = System.currentTimeMillis()
            val reasoningMs = reasoningStartedAt?.let { start -> (reasoningEndedAt ?: now) - start }
            val tokensPerSecond = writingMs.get().takeIf { it > 0 && writtenTokens.get() > 0 }
                ?.let { ms -> writtenTokens.get() * 1000.0 / ms }
            return AssistantContent.withTimings(accumulated.toString(), now - startedAt, reasoningMs, provider, tokensPerSecond, answeringModelName)
        }
        try {
            // The chat shows the text from memory a few times per second; each database write
            // re-reads the whole conversation and redraws it, taking CPU from the AI's threads, which
            // all wait for the slowest one, so the text is saved only every few seconds.
            coroutineScope {
                var unsaved = false
                val saver = launch {
                    var savedAt = System.currentTimeMillis()
                    while (true) {
                        delay(UI_UPDATE_INTERVAL_MS)
                        if (!unsaved) continue
                        unsaved = false
                        val text = accumulated.toString()
                        session._streaming.value = StreamingReply(replyId, text)
                        val now = System.currentTimeMillis()
                        if (now - savedAt >= DATABASE_SAVE_INTERVAL_MS) {
                            savedAt = now
                            repository.updateMessageContent(replyId, text)
                        }
                    }
                }
                try {
                    aiGateway.streamChat(
                        AiChatRequest(
                            conversationId = conversationId,
                            messages = history,
                            attachments = attachments,
                            agentId = agent.id,
                            onStage = { stage ->
                                if (stage.kind == GenerationStage.Kind.ANSWER_SPEED) {
                                    writtenTokens.addAndGet(stage.done.toLong())
                                    writingMs.addAndGet(stage.total.toLong())
                                } else {
                                    session.report(stage)
                                }
                            },
                        )
                    ).collect { chunk ->
                        accumulated.append(chunk)
                        // Only the end of the text can hold a tag that just arrived.
                        val tail = accumulated.substring(maxOf(0, accumulated.length - chunk.length - TAG_WINDOW))
                        if (reasoningStartedAt == null && AssistantContent.hasReasoningStarted(tail)) {
                            reasoningStartedAt = System.currentTimeMillis()
                            session.report(GenerationStage(GenerationStage.Kind.REASONING))
                        }
                        if (reasoningStartedAt != null && reasoningEndedAt == null && AssistantContent.hasReasoningEnded(tail)) {
                            reasoningEndedAt = System.currentTimeMillis()
                        }
                        // Writing once answer text arrives: after the reasoning, or from the start
                        // when the model does not reason. Checked only until it happens.
                        if (phase == AnswerPhase.THINKING) {
                            val writing = when {
                                reasoningStartedAt == null -> accumulated.isNotBlank()
                                reasoningEndedAt == null -> false
                                else -> AssistantContent.answerForHistory(accumulated.toString()).isNotBlank()
                            }
                            if (writing) {
                                phase = AnswerPhase.WRITING
                                setNotice(conversationId, AnswerNotice(answeringModelName, phase))
                            }
                        }
                        unsaved = true
                    }
                } finally {
                    saver.cancel()
                }
            }
            repository.updateMessageContent(replyId, finalContent())
            repository.updateMessageStatus(replyId, MessageStatus.COMPLETE)
            ChatGenerationService.notifyAnswered(appContext, conversationId, answeringModelName)
        } catch (cancel: CancellationException) {
            withContext(NonCancellable) {
                repository.updateMessageContent(replyId, finalContent())
                repository.updateMessageStatus(replyId, MessageStatus.COMPLETE)
            }
            throw cancel
        } catch (t: Throwable) {
            // What was written stays, as the chat showed it.
            if (accumulated.isNotEmpty()) repository.updateMessageContent(replyId, accumulated.toString())
            repository.updateMessageStatus(replyId, MessageStatus.ERROR)
            throw t
        }
    }

    private fun modelHistory(items: List<MessageWithAttachments>): List<AiChatMessage> =
        oneAnswerPerTurn(
            items,
            isAnswer = { it.message.role == MessageRole.ASSISTANT.name },
            isEmpty = { AssistantContent.answerForHistory(it.message.content).isBlank() },
        ).map(::toAiMessage)

    private fun toAiMessage(item: MessageWithAttachments): AiChatMessage {
        var remaining = MAX_HISTORY_ATTACHMENT_CHARS
        val contexts = buildList {
            item.attachments.forEach { attachment ->
                if (remaining <= 0) return@forEach
                val raw = attachment.extractedText?.takeIf { it.isNotBlank() } ?: return@forEach
                val excerpt = raw.take(minOf(MAX_HISTORY_ATTACHMENT_PER_FILE, remaining))
                add("${attachment.fileName}:\n$excerpt")
                remaining -= excerpt.length
            }
        }
        val baseContent = if (item.message.role == MessageRole.ASSISTANT.name) {
            AssistantContent.answerForHistory(item.message.content)
        } else {
            item.message.content
        }
        val content = if (contexts.isEmpty()) baseContent else
            baseContent + "\n\n[Contexto persistido dos anexos]\n" + contexts.joinToString("\n\n---\n\n")
        return AiChatMessage(item.message.role.lowercase(), content)
    }

    companion object {
        const val MAX_CONCURRENT_ANSWERS = 2
        /** How often the chat shows the answer being written. */
        private const val UI_UPDATE_INTERVAL_MS = 120L
        /** How often the answer being written is saved to the database. */
        private const val DATABASE_SAVE_INTERVAL_MS = 2_000L
        /** Longer than any reasoning tag ("<|channel>thought", "</reasoning>"). */
        private const val TAG_WINDOW = 32
        private const val MAX_HISTORY_ATTACHMENT_PER_FILE = 4_000
        private const val MAX_HISTORY_ATTACHMENT_CHARS = 6_000
    }
}
