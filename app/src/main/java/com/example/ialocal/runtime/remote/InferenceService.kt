package com.example.ialocal.runtime.remote

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Process
import com.example.ialocal.diagnostics.AiEventLogger
import com.example.ialocal.runtime.LlamaCppRuntime
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Hosts the llama.cpp runtime in the ":ai" process. The model's weights, context and native
 * crashes stay in this process: when memory runs out, Android stops it (the biggest one) and the
 * app, its screens and the conversation stay open.
 */
class InferenceService : Service() {
    private val logger by lazy { AiEventLogger(applicationContext) }
    /** Memory level the app chose for each model after earlier crashes (ModelCrashGuard). */
    private val safeLevels = ConcurrentHashMap<String, Int>()
    private val runtime by lazy {
        LlamaCppRuntime(applicationContext, logger, safeLevelOf = { modelId -> safeLevels[modelId] ?: 0 })
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val generations = ConcurrentHashMap<String, Job>()
    private var stateForwarding: Job? = null

    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        scope.cancel()
        runCatching { runBlocking { runtime.unload() } }
        super.onDestroy()
    }

    private val binder = object : IInferenceService.Stub() {
        override fun pid(): Int = Process.myPid()

        override fun setStateListener(listener: IInferenceStateListener) {
            stateForwarding?.cancel()
            stateForwarding = scope.launch {
                runtime.state.collect { state ->
                    runCatching { listener.onState(state.status.name, state.modelId, state.modelName, state.error) }
                }
            }
        }

        override fun warmUp(modelJson: String, safeLevel: Int) = forApp {
            val model = RemoteProtocol.decodeModel(modelJson)
            safeLevels[model.id] = safeLevel
            runBlocking { runtime.warmUp(model) }
        }

        override fun verify(modelJson: String, safeLevel: Int): String = forApp {
            val model = RemoteProtocol.decodeModel(modelJson)
            safeLevels[model.id] = safeLevel
            runBlocking { runtime.verify(model) }.output
        }

        override fun generate(requestId: String, requestJson: String, callback: IInferenceCallback) {
            val request = forApp { RemoteProtocol.decodeRequest(requestJson) }
            safeLevels[request.model.id] = request.safeLevel
            generations[requestId] = scope.launch {
                try {
                    runtime.streamComplete(
                        model = request.model,
                        systemPrompt = request.systemPrompt,
                        messages = request.messages,
                        temperature = request.temperature,
                        maxTokens = request.maxTokens,
                        turn = request.turn,
                    ).collect { callback.onToken(it) }
                    callback.onDone()
                } catch (cancel: CancellationException) {
                    // The app stopped the answer; what was sent stays.
                    runCatching { callback.onDone() }
                    throw cancel
                } catch (t: Throwable) {
                    runCatching { callback.onError(t.message ?: "Falha nativa ao gerar a resposta.") }
                } finally {
                    generations.remove(requestId)
                }
            }
        }

        override fun cancel(requestId: String) {
            generations[requestId]?.cancel()
        }

        override fun unload() = forApp { runBlocking { runtime.unload() } }

        override fun releaseIfIdle(): Boolean = forApp { runBlocking { runtime.releaseIfIdle() } }
    }

    /** Binder carries IllegalStateException with its message to the app; other exceptions it does not. */
    private inline fun <T> forApp(block: () -> T): T = try {
        block()
    } catch (t: Throwable) {
        throw IllegalStateException(t.message ?: "Falha no processo da IA.", t)
    }
}
