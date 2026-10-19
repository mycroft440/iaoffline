package com.example.ialocal.runtime.remote

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.DeadObjectException
import android.os.IBinder
import android.os.RemoteException
import com.example.ialocal.ai.AiChatMessage
import com.example.ialocal.data.AiModelEntity
import com.example.ialocal.diagnostics.AiEventLogger
import com.example.ialocal.runtime.GenerationStage
import com.example.ialocal.runtime.ModelCrashGuard
import com.example.ialocal.runtime.ModelRuntime
import com.example.ialocal.runtime.PromptPrefill
import com.example.ialocal.runtime.RuntimeState
import com.example.ialocal.runtime.RuntimeStatus
import com.example.ialocal.runtime.TurnOptions
import com.example.ialocal.runtime.VerificationResult
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * The app's side of the model runtime, which runs in the ":ai" process ([InferenceService]).
 * When memory runs out, Android stops that process, the biggest one, instead of the app: the
 * request in progress fails with a message, the model gets a lower memory level and the app, its
 * screens and the conversation stay open.
 */
class RemoteModelRuntime(
    context: Context,
    private val logger: AiEventLogger? = null,
    private val crashGuard: ModelCrashGuard? = null,
) : ModelRuntime {
    private val appContext = context.applicationContext
    private val _state = MutableStateFlow(RuntimeState())
    override val state: StateFlow<RuntimeState> = _state.asStateFlow()

    private val bindLock = Mutex()
    @Volatile private var service: IInferenceService? = null
    @Volatile private var connection: ServiceConnection? = null
    /** Loads and answers in progress in the AI process, so its death can end each of them. */
    private val runs = ConcurrentHashMap<String, Run>()

    private class Run(
        val model: AiModelEntity,
        val phase: ModelCrashGuard.Phase,
        val onDeath: (String) -> Unit = {},
    ) {
        /** Set when the AI process died during this run. */
        @Volatile var deathMessage: String? = null
    }

    private val deathLock = Any()

    private val stateListener = object : IInferenceStateListener.Stub() {
        override fun onState(status: String, modelId: String?, modelName: String?, error: String?) {
            val parsed = runCatching { RuntimeStatus.valueOf(status) }.getOrDefault(RuntimeStatus.IDLE)
            _state.value = RuntimeState(parsed, modelId, modelName, error)
        }
    }

    override suspend fun warmUp(model: AiModelEntity, prefill: PromptPrefill?) = withContext(Dispatchers.IO) {
        val prefillJson = prefill?.let(RemoteProtocol::encodePrefill).orEmpty()
        call(model, ModelCrashGuard.Phase.LOAD) { it.warmUp(RemoteProtocol.encodeModel(model), safeLevel(model), prefillJson) }
    }

    override suspend fun verify(model: AiModelEntity, keepLoaded: Boolean): VerificationResult = withContext(Dispatchers.IO) {
        VerificationResult(
            call(model, ModelCrashGuard.Phase.LOAD) {
                it.verify(RemoteProtocol.encodeModel(model), safeLevel(model), keepLoaded)
            },
        )
    }

    override fun streamComplete(
        model: AiModelEntity,
        systemPrompt: String,
        messages: List<AiChatMessage>,
        temperature: Float,
        maxTokens: Int,
        turn: TurnOptions,
        onStage: (GenerationStage) -> Unit,
    ): Flow<String> = callbackFlow {
        val remote = connected(onStage)
        val requestId = UUID.randomUUID().toString()
        val run = Run(model, ModelCrashGuard.Phase.GENERATION) { message -> close(IllegalStateException(message)) }
        val finished = AtomicBoolean(false)
        runs[requestId] = run
        crashGuard?.begin(model.id, model.name, ModelCrashGuard.Phase.GENERATION)
        val callback = object : IInferenceCallback.Stub() {
            override fun onStage(stageJson: String) {
                GenerationStage.decode(stageJson)?.let(onStage)
            }

            override fun onToken(text: String) {
                trySend(text)
            }

            override fun onDone() {
                finished.set(true)
                close()
            }

            override fun onError(message: String) {
                finished.set(true)
                close(IllegalStateException(message))
            }
        }
        try {
            val request = GenerateRequest(model, systemPrompt, messages, temperature, maxTokens, turn, safeLevel(model))
            remote.generate(requestId, RemoteProtocol.encodeRequest(request), callback)
        } catch (dead: DeadObjectException) {
            onAiProcessDied(remote.asBinder())
            close(IllegalStateException(run.deathMessage ?: DEATH_ERROR, dead))
        } catch (failure: RemoteException) {
            close(IllegalStateException(COMMUNICATION_ERROR, failure))
        } catch (failure: IllegalStateException) {
            close(failure)
        }
        awaitClose {
            runs.remove(requestId)
            crashGuard?.end()
            if (!finished.get()) runCatching { remote.cancel(requestId) }
        }
    }.buffer(Channel.UNLIMITED).flowOn(Dispatchers.IO)

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

    override suspend fun unload() = withContext(Dispatchers.IO) {
        val remote = service ?: return@withContext
        runCatching { remote.unload() }.onFailure { logger?.error("MODEL_UNLOAD", "Falha ao liberar o modelo", it) }
        Unit
    }

    override suspend fun releaseIfIdle(): Boolean = withContext(Dispatchers.IO) {
        val remote = service ?: return@withContext false
        runCatching { remote.releaseIfIdle() }.getOrDefault(false)
    }

    private fun safeLevel(model: AiModelEntity): Int = crashGuard?.safeLevel(model.id) ?: 0

    private suspend fun <T> call(model: AiModelEntity, phase: ModelCrashGuard.Phase, block: (IInferenceService) -> T): T {
        val remote = connected()
        val runId = UUID.randomUUID().toString()
        val run = Run(model, phase)
        runs[runId] = run
        crashGuard?.begin(model.id, model.name, phase)
        try {
            return block(remote)
        } catch (dead: DeadObjectException) {
            onAiProcessDied(remote.asBinder())
            throw IllegalStateException(run.deathMessage ?: DEATH_ERROR, dead)
        } catch (failure: RemoteException) {
            throw IllegalStateException(COMMUNICATION_ERROR, failure)
        } finally {
            runs.remove(runId)
            crashGuard?.end()
        }
    }

    /** The AI process, started and bound on first use and again after it was stopped. */
    private suspend fun connected(onStage: (GenerationStage) -> Unit = {}): IInferenceService {
        service?.takeIf { it.asBinder().isBinderAlive }?.let { return it }
        onStage(GenerationStage(GenerationStage.Kind.STARTING_AI))
        return bindLock.withLock {
            service?.takeIf { it.asBinder().isBinderAlive }?.let { return@withLock it }
            // A binding whose process died is dropped before binding again.
            disconnect()
            val ready = CompletableDeferred<IInferenceService>()
            val newConnection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                    val remote = IInferenceService.Stub.asInterface(binder)
                    runCatching {
                        binder.linkToDeath({ onAiProcessDied(binder) }, 0)
                        remote.setStateListener(stateListener)
                        logger?.info("AI_PROCESS", "Processo da IA conectado (pid ${remote.pid()})")
                    }
                    service = remote
                    ready.complete(remote)
                }

                override fun onServiceDisconnected(name: ComponentName) {
                    service = null
                }

                override fun onBindingDied(name: ComponentName) {
                    service = null
                    ready.completeExceptionally(IllegalStateException(START_ERROR))
                }

                override fun onNullBinding(name: ComponentName) {
                    ready.completeExceptionally(IllegalStateException(START_ERROR))
                }
            }
            // BIND_IMPORTANT gives the AI process the app's priority and CPU scheduling, so it
            // answers as fast as in the app's own process.
            val flags = Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT
            val intent = Intent(appContext, InferenceService::class.java)
            if (!appContext.bindService(intent, newConnection, flags)) {
                runCatching { appContext.unbindService(newConnection) }
                throw IllegalStateException(START_ERROR)
            }
            connection = newConnection
            try {
                withTimeout(BIND_TIMEOUT_MS) { ready.await() }
            } catch (timeout: TimeoutCancellationException) {
                disconnect()
                throw IllegalStateException(START_ERROR, timeout)
            } catch (t: Throwable) {
                disconnect()
                throw t
            }
        }
    }

    /**
     * Ends every load and answer the dead process had, counting the death once for the model
     * it was working on. Reported both by the death notice and by a call that hit the dead process.
     */
    private fun onAiProcessDied(deadBinder: IBinder) = synchronized(deathLock) {
        val current = service?.asBinder()
        if (current != null && current !== deadBinder && current.isBinderAlive) return@synchronized
        // Bound again on the next request, so a process stopped for lack of memory is not
        // restarted, empty, while memory is still short.
        disconnect()
        val interrupted = runs.values.toList()
        runs.clear()
        val primary = interrupted.firstOrNull { it.phase == ModelCrashGuard.Phase.LOAD } ?: interrupted.firstOrNull()
        if (primary == null) {
            logger?.info("AI_PROCESS", "Processo da IA encerrado pelo sistema sem tarefa em andamento")
            _state.value = RuntimeState()
            return@synchronized
        }
        val message = deathMessage(primary)
        interrupted.forEach { run ->
            run.deathMessage = message
            run.onDeath(message)
        }
        _state.value = RuntimeState(RuntimeStatus.ERROR, primary.model.id, primary.model.name, message)
    }

    private fun disconnect() {
        service = null
        connection?.let { runCatching { appContext.unbindService(it) } }
        connection = null
    }

    private fun deathMessage(run: Run): String {
        val recovery = crashGuard?.recordAiProcessDeath(run.model.id, run.model.name, run.phase)
        val moment = if (run.phase == ModelCrashGuard.Phase.LOAD) "carregava" else "respondia com"
        return buildString {
            append("O Android encerrou a IA enquanto ela $moment ${run.model.name}, por falta de memória ou falha no motor. ")
            append("O app continua aberto.")
            if (recovery != null) {
                append(" Na próxima tentativa o modelo usa menos memória: pesos lidos do armazenamento e ")
                append(if (recovery.level >= ModelCrashGuard.MAX_LEVEL) "o menor contexto." else "metade do contexto.")
            }
        }
    }

    private companion object {
        const val BIND_TIMEOUT_MS = 30_000L
        const val START_ERROR = "Não foi possível iniciar o processo da IA."
        const val COMMUNICATION_ERROR = "Falha de comunicação com o processo da IA."
        const val DEATH_ERROR = "O Android encerrou a IA. O app continua aberto; tente de novo."
    }
}
