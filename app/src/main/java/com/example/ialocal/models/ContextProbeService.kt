package com.example.ialocal.models

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.util.Log
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch

/**
 * Executes one context allocation/inference probe outside the UI process.
 *
 * The manifest places this service in :ai_context_probe. If Android or native code
 * kills that process while allocating a large KV cache, the UI process remains able
 * to observe the Binder death and keep the last successful context already persisted.
 */
class ContextProbeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val engineDelegate = lazy { AiChat.getInferenceEngine(applicationContext) }
    private val engine: InferenceEngine by engineDelegate
    private val messenger = Messenger(IncomingHandler())

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onDestroy() {
        scope.cancel()
        if (engineDelegate.isInitialized()) runCatching { engine.cleanUp() }
        super.onDestroy()
    }

    private inner class IncomingHandler : Handler(Looper.getMainLooper()) {
        override fun handleMessage(message: Message) {
            if (message.what != ContextProbeProtocol.MSG_PROBE) {
                super.handleMessage(message)
                return
            }
            val replyTo = message.replyTo ?: return
            val modelPath = message.data.getString(ContextProbeProtocol.KEY_MODEL_PATH).orEmpty()
            val contextTokens = message.data.getInt(ContextProbeProtocol.KEY_CONTEXT_TOKENS, 0)
            if (modelPath.isBlank() || contextTokens < ModelRepository.MIN_CONTEXT) {
                sendResult(replyTo, false, "Parâmetros inválidos para o probe de contexto.")
                return
            }

            sendStarted(replyTo)
            scope.launch {
                val result = runCatching { runProbe(modelPath, contextTokens) }
                result.onSuccess {
                    sendResult(replyTo, true, "Contexto de $contextTokens tokens carregou e inferiu com sucesso.")
                }.onFailure { error ->
                    Log.e(TAG, "Probe de $contextTokens tokens falhou", error)
                    sendResult(
                        replyTo,
                        false,
                        error.message ?: error::class.java.simpleName.ifBlank { "Falha nativa no probe." },
                    )
                }
            }
        }
    }

    private suspend fun runProbe(modelPath: String, contextTokens: Int) {
        awaitInitializedOrRecover()
        engine.loadModel(modelPath, contextTokens)
        try {
            engine.setSystemPrompt("Teste técnico de memória. Responda de forma mínima.")
            val output = engine.sendUserPrompt("OK", predictLength = 4)
                .toList()
                .joinToString(separator = "")
                .trim()
            require(output.isNotBlank()) {
                "O contexto foi criado, mas a inferência de prova não produziu texto."
            }
        } finally {
            runCatching { engine.cleanUp() }
        }
    }

    private suspend fun awaitInitializedOrRecover() {
        when (engine.state.value) {
            is InferenceEngine.State.Initialized -> return
            is InferenceEngine.State.Error,
            is InferenceEngine.State.ModelReady,
            is InferenceEngine.State.Benchmarking,
            is InferenceEngine.State.ProcessingSystemPrompt,
            is InferenceEngine.State.ProcessingUserPrompt,
            is InferenceEngine.State.Generating,
            is InferenceEngine.State.LoadingModel,
            is InferenceEngine.State.UnloadingModel -> runCatching { engine.cleanUp() }
            is InferenceEngine.State.Uninitialized,
            is InferenceEngine.State.Initializing -> Unit
        }

        val state = engine.state.first {
            it is InferenceEngine.State.Initialized || it is InferenceEngine.State.Error
        }
        if (state is InferenceEngine.State.Error) throw state.exception
    }

    private fun sendStarted(replyTo: Messenger) {
        val message = Message.obtain(null, ContextProbeProtocol.MSG_STARTED).apply {
            data = Bundle().apply { putInt(ContextProbeProtocol.KEY_PID, Process.myPid()) }
        }
        runCatching { replyTo.send(message) }
    }

    private fun sendResult(replyTo: Messenger, success: Boolean, detail: String) {
        val message = Message.obtain(null, ContextProbeProtocol.MSG_RESULT).apply {
            data = Bundle().apply {
                putBoolean(ContextProbeProtocol.KEY_SUCCESS, success)
                putString(ContextProbeProtocol.KEY_DETAIL, detail)
                putInt(ContextProbeProtocol.KEY_PID, Process.myPid())
            }
        }
        runCatching { replyTo.send(message) }
    }

    companion object {
        private const val TAG = "ContextProbeService"
    }
}

internal object ContextProbeProtocol {
    const val MSG_PROBE = 1
    const val MSG_STARTED = 2
    const val MSG_RESULT = 3

    const val KEY_MODEL_PATH = "model_path"
    const val KEY_CONTEXT_TOKENS = "context_tokens"
    const val KEY_SUCCESS = "success"
    const val KEY_DETAIL = "detail"
    const val KEY_PID = "pid"
}
