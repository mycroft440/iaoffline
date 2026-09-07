package com.example.ialocal.models

import android.net.Uri
import com.example.ialocal.data.AiModelEntity
import com.example.ialocal.data.ModelVerificationStatus
import com.example.ialocal.diagnostics.AiEventLogger
import com.example.ialocal.runtime.ModelRuntime
import com.example.ialocal.runtime.RuntimeState
import kotlinx.coroutines.flow.StateFlow

/** Coordinates persistence and native runtime so activation only follows a successful real inference. */
class ModelManager(
    private val repository: ModelRepository,
    private val runtime: ModelRuntime,
    private val logger: AiEventLogger? = null,
) {
    val runtimeState: StateFlow<RuntimeState> = runtime.state

    suspend fun inspect(uri: Uri): ModelImportPreview = repository.inspectForImport(uri)

    suspend fun importAndVerify(preview: ModelImportPreview): AiModelEntity {
        val model = repository.importGguf(preview)
        return try {
            verifyAndActivate(model.id)
        } catch (t: Throwable) {
            // Keep the imported file so the user can retry after freeing RAM.
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
        runtime.warmUp(model)
        repository.activateModel(model.id)
    }

    suspend fun retryVerification(modelId: String): AiModelEntity = verifyAndActivate(modelId)

    suspend fun delete(modelId: String) {
        if (runtime.state.value.modelId == modelId) runtime.unload()
        repository.deleteModel(modelId)
    }

    suspend fun unload() = runtime.unload()
}
