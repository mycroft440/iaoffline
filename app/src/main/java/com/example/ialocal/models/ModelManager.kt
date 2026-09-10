package com.example.ialocal.models

import android.net.Uri
import com.example.ialocal.data.AiModelEntity
import com.example.ialocal.data.ModelVerificationStatus
import com.example.ialocal.diagnostics.AiEventLogger
import com.example.ialocal.runtime.ModelRuntime
import com.example.ialocal.runtime.RuntimeState
import java.io.File
import kotlinx.coroutines.flow.StateFlow

/** Coordinates persistence and native runtime so activation only follows a successful real inference. */
class ModelManager(
    private val repository: ModelRepository,
    private val runtime: ModelRuntime,
    private val contextCalibration: ContextCalibrationManager,
    private val logger: AiEventLogger? = null,
) {
    val runtimeState: StateFlow<RuntimeState> = runtime.state

    suspend fun inspect(uri: Uri): ModelImportPreview = repository.inspectForImport(uri)

    suspend fun importAndVerify(preview: ModelImportPreview): AiModelEntity {
        val model = repository.importGguf(preview)
        return finishNewModel(model)
    }

    suspend fun installDownloaded(file: File, suggestedName: String): AiModelEntity {
        val model = repository.adoptDownloadedGguf(file, suggestedName)
        return finishNewModel(model)
    }

    private suspend fun finishNewModel(model: AiModelEntity): AiModelEntity {
        return try {
            verifyAndActivate(model.id)
            // Free the UI process before the dedicated probe process starts allocating
            // progressively larger KV caches.
            runtime.unload()
            contextCalibration.calibrateIfNeeded(model.id)
        } catch (t: Throwable) {
            // Keep the imported/downloaded file so the user can retry after freeing RAM.
            throw t
        }
    }

    suspend fun verifyAndActivate(modelId: String): AiModelEntity {
        val model = requireNotNull(repository.getModel(modelId)) { "Modelo não encontrado." }
        repository.markVerifying(model.id)
        return try {
            val result = runtime.verify(model)
            logger?.info("INFERENCE", "Verificação concluída para ${model.apiModelId}: ${result.output.take(40)}")
            repository.markVerified(model.id)
            repository.activateModel(model.id)
            val agent = repository.getAgentForModel(model.id)
            if (repository.getDefaultAgent() == null && agent != null) repository.setDefaultAgent(agent.id)
            requireNotNull(repository.getModel(model.id))
        } catch (t: Throwable) {
            val message = t.message ?: "Falha ao verificar o modelo."
            repository.markVerificationError(model.id, message)
            throw t
        }
    }

    suspend fun load(modelId: String) {
        val model = requireNotNull(repository.getModel(modelId)) { "Modelo não encontrado." }
        require(model.verificationStatus == ModelVerificationStatus.VERIFIED.name) {
            "Verifique o modelo com uma inferência real antes de carregá-lo para uso."
        }
        // Always unload before asking calibrateIfNeeded. If the saved calibration key is
        // stale after an OS/runtime update, this avoids having two copies of the model in RAM.
        runtime.unload()
        val calibrated = contextCalibration.calibrateIfNeeded(modelId)
        runtime.warmUp(calibrated)
        repository.activateModel(calibrated.id)
    }

    suspend fun retryVerification(modelId: String): AiModelEntity {
        verifyAndActivate(modelId)
        runtime.unload()
        return contextCalibration.calibrateIfNeeded(modelId)
    }

    suspend fun recalibrateContext(modelId: String): AiModelEntity {
        runtime.unload()
        return contextCalibration.calibrate(modelId)
    }

    suspend fun delete(modelId: String) {
        if (runtime.state.value.modelId == modelId) runtime.unload()
        repository.deleteModel(modelId)
    }

    suspend fun unload() = runtime.unload()
}
