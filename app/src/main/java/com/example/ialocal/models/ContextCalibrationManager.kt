package com.example.ialocal.models

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import com.example.ialocal.data.AiModelEntity
import com.example.ialocal.data.ContextCalibrationStatus
import com.example.ialocal.data.ModelVerificationStatus
import com.example.ialocal.diagnostics.AiEventLogger
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Discovers and persists the largest native context that survives on this device/model pair.
 * Every successful candidate is written to Room before a larger candidate is attempted.
 */
class ContextCalibrationManager(
    context: Context,
    private val repository: ModelRepository,
    private val logger: AiEventLogger? = null,
    private val search: ContextSearch = ContextSearch(),
) {
    private val appContext = context.applicationContext
    private val probeClient = ContextProbeClient(appContext)

    suspend fun calibrateIfNeeded(modelId: String): AiModelEntity {
        val model = requireNotNull(repository.getModel(modelId)) { "Modelo não encontrado." }
        if (
            model.contextCalibrationStatus == ContextCalibrationStatus.CALIBRATED.name &&
            model.contextCalibrationKey == calibrationKey()
        ) {
            return model
        }
        return calibrate(modelId)
    }

    suspend fun calibrate(modelId: String): AiModelEntity {
        val model = requireNotNull(repository.getModel(modelId)) { "Modelo não encontrado." }
        require(model.verificationStatus == ModelVerificationStatus.VERIFIED.name) {
            "O modelo precisa passar pela inferência de verificação antes de calibrar o contexto."
        }

        val safeContext = minOf(
            ModelRepository.SAFE_INITIAL_CONTEXT,
            model.declaredContextLength?.takeIf { it > 0 } ?: ModelRepository.SAFE_INITIAL_CONTEXT,
        ).coerceAtLeast(ModelRepository.MIN_CONTEXT)
        val maxContext = (model.declaredContextLength?.takeIf { it >= ModelRepository.MIN_CONTEXT }
            ?: PRODUCT_TARGET_CONTEXT).coerceAtLeast(safeContext)
        val key = calibrationKey()

        repository.startContextCalibration(model.id, key, safeContext)
        logger?.info(
            "CONTEXT_CALIBRATION",
            "Iniciando calibração de ${model.apiModelId}; seguro=$safeContext; teto=$maxContext",
        )

        return try {
            val result = search.search(maxContext) { candidate ->
                var probe = probeClient.probe(model.filePath, candidate)
                if (probe.outcome == ProbeOutcome.PROCESS_DIED) {
                    probe = probe.copy(detail = resolveExitReason(probe.pid) ?: probe.detail)
                }

                if (probe.outcome == ProbeOutcome.SUCCESS) {
                    // Persist before attempting any larger allocation. If the next process dies,
                    // this value remains the last context proven stable on disk.
                    repository.recordContextProbeSuccess(model.id, candidate)
                    logger?.info("CONTEXT_CALIBRATION", "$candidate tokens ✓")
                    true
                } else {
                    val reason = probe.detail.ifBlank { probe.outcome.name }
                    repository.recordContextProbeFailure(model.id, candidate, reason)
                    logger?.info("CONTEXT_CALIBRATION", "$candidate tokens ✗ · $reason")
                    false
                }
            }

            val stable = result.maxStableTokens
            if (stable == null) {
                repository.failContextCalibration(
                    model.id,
                    "Nem o contexto conservador de ${result.firstFailedTokens ?: safeContext} tokens sobreviveu no processo de teste.",
                )
            } else {
                repository.finishContextCalibration(model.id, stable)
                logger?.info(
                    "CONTEXT_CALIBRATION",
                    "Calibração concluída: máximo comprovado=$stable; primeiro limite falho=${result.firstFailedTokens ?: "nenhum"}",
                )
            }
            requireNotNull(repository.getModel(model.id))
        } catch (error: Throwable) {
            val latest = repository.getModel(model.id)
            val lastStable = latest?.calibratedContextLength
            if (lastStable != null) {
                // An unexpected interruption after at least one success must never discard
                // the context that was already proven and persisted.
                repository.finishContextCalibration(model.id, lastStable)
                logger?.error(
                    "CONTEXT_CALIBRATION",
                    "Calibração interrompida; mantendo último contexto comprovado=$lastStable",
                    error,
                )
            } else {
                repository.failContextCalibration(
                    model.id,
                    error.message ?: "Calibração de contexto interrompida antes do primeiro sucesso.",
                )
                logger?.error("CONTEXT_CALIBRATION", "Calibração falhou antes do primeiro sucesso", error)
            }
            requireNotNull(repository.getModel(model.id))
        }
    }

    private fun calibrationKey(): String = buildString {
        append(RUNTIME_CALIBRATION_VERSION)
        append('|').append(Build.FINGERPRINT)
        append('|').append(Build.SUPPORTED_ABIS.joinToString(","))
        append('|').append(Build.VERSION.SDK_INT)
    }

    private suspend fun resolveExitReason(pid: Int?): String? {
        if (pid == null || pid <= 0) return null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return "O processo de inferência foi encerrado durante o teste; Android 10 não expõe a causa histórica detalhada."
        }

        // Give ActivityManager a brief chance to publish the exit record after Binder death.
        delay(EXIT_REASON_SETTLE_MS)
        val activity = appContext.getSystemService(ActivityManager::class.java)
        val info = activity.getHistoricalProcessExitReasons(appContext.packageName, pid, 5)
            .firstOrNull { it.pid == pid }
            ?: return "O processo de inferência morreu durante o teste; causa ainda não disponível no histórico do Android."
        return "Processo encerrado: ${exitReasonName(info.reason)}${info.description?.let { " · $it" } ?: ""}"
    }

    private fun exitReasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_UNKNOWN -> "UNKNOWN"
        else -> "reason=$reason"
    }

    companion object {
        const val PRODUCT_TARGET_CONTEXT = 32768
        private const val RUNTIME_CALIBRATION_VERSION = "llama.cpp-v0.4.0-dynamic-context-v1"
        private const val EXIT_REASON_SETTLE_MS = 300L
    }
}

private enum class ProbeOutcome { SUCCESS, FAILURE, PROCESS_DIED, TIMEOUT }

private data class ProbeResult(
    val outcome: ProbeOutcome,
    val detail: String,
    val pid: Int? = null,
)

/** Binder/Messenger client for the dedicated :ai_context_probe service process. */
private class ContextProbeClient(private val context: Context) {
    suspend fun probe(modelPath: String, contextTokens: Int): ProbeResult {
        val deferred = CompletableDeferred<ProbeResult>()
        val requestSent = AtomicBoolean(false)
        var servicePid: Int? = null

        val replyHandler = object : Handler(Looper.getMainLooper()) {
            override fun handleMessage(message: Message) {
                when (message.what) {
                    ContextProbeProtocol.MSG_STARTED -> {
                        servicePid = message.data.getInt(ContextProbeProtocol.KEY_PID, 0).takeIf { it > 0 }
                    }
                    ContextProbeProtocol.MSG_RESULT -> {
                        val success = message.data.getBoolean(ContextProbeProtocol.KEY_SUCCESS, false)
                        deferred.complete(
                            ProbeResult(
                                outcome = if (success) ProbeOutcome.SUCCESS else ProbeOutcome.FAILURE,
                                detail = message.data.getString(ContextProbeProtocol.KEY_DETAIL).orEmpty(),
                                pid = message.data.getInt(ContextProbeProtocol.KEY_PID, 0).takeIf { it > 0 }
                                    ?: servicePid,
                            )
                        )
                    }
                }
            }
        }
        val replyMessenger = Messenger(replyHandler)

        lateinit var connection: ServiceConnection
        connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                if (binder == null || !requestSent.compareAndSet(false, true)) return
                val request = Message.obtain(null, ContextProbeProtocol.MSG_PROBE).apply {
                    replyTo = replyMessenger
                    data = Bundle().apply {
                        putString(ContextProbeProtocol.KEY_MODEL_PATH, modelPath)
                        putInt(ContextProbeProtocol.KEY_CONTEXT_TOKENS, contextTokens)
                    }
                }
                runCatching { Messenger(binder).send(request) }
                    .onFailure {
                        deferred.complete(
                            ProbeResult(ProbeOutcome.FAILURE, it.message ?: "Falha ao iniciar processo de teste.", servicePid)
                        )
                    }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                deferred.complete(
                    ProbeResult(
                        ProbeOutcome.PROCESS_DIED,
                        "O processo de inferência foi encerrado durante o teste de $contextTokens tokens.",
                        servicePid,
                    )
                )
            }

            override fun onBindingDied(name: ComponentName?) = onServiceDisconnected(name)

            override fun onNullBinding(name: ComponentName?) {
                deferred.complete(
                    ProbeResult(ProbeOutcome.FAILURE, "O serviço de calibração não forneceu um Binder.", servicePid)
                )
            }
        }

        val intent = Intent(context, ContextProbeService::class.java)
        val bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        if (!bound) return ProbeResult(ProbeOutcome.FAILURE, "Não foi possível iniciar o processo de calibração.")

        val result = withTimeoutOrNull(PROBE_TIMEOUT_MS) { deferred.await() }
            ?: ProbeResult(
                ProbeOutcome.TIMEOUT,
                "O teste de $contextTokens tokens excedeu o tempo máximo; o contexto foi tratado como instável.",
                servicePid,
            )
        runCatching { context.unbindService(connection) }
        return result
    }

    companion object {
        // Large mobile models may take a while to mmap/load on slower storage.
        private const val PROBE_TIMEOUT_MS = 10L * 60L * 1000L
    }
}
