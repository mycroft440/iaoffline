package com.example.ialocal.models

import android.net.Uri
import com.example.ialocal.data.AiModelEntity
import com.example.ialocal.data.ModelVerificationStatus
import com.example.ialocal.diagnostics.AiEventLogger
import com.example.ialocal.runtime.ModelRuntime
import com.example.ialocal.runtime.RuntimeState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Coordinates persistence, downloads and native runtime so activation follows a successful real inference. */
class ModelManager(
    private val repository: ModelRepository,
    private val runtime: ModelRuntime,
    private val downloader: ModelDownloader,
    private val logger: AiEventLogger? = null,
) {
    val runtimeState: StateFlow<RuntimeState> = runtime.state
    val catalog: List<CatalogModel> = ModelCatalog.entries

    private val _downloadState = MutableStateFlow(ModelDownloadState())
    val downloadState: StateFlow<ModelDownloadState> = _downloadState.asStateFlow()

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

    suspend fun downloadAndVerify(catalogId: String): AiModelEntity {
        check(!_downloadState.value.isBusy) { "Já existe um download ou verificação de modelo em andamento." }
        val catalogModel = ModelCatalog.requireById(catalogId)
        return try {
            val file = downloader.download(catalogModel) { _downloadState.value = it }
            _downloadState.value = _downloadState.value.copy(
                phase = ModelDownloadPhase.IMPORTING,
                downloadedBytes = file.length(),
                totalBytes = file.length(),
                message = "Registrando modelo no app…",
            )
            val imported = repository.importDownloadedGguf(file, catalogModel)
            _downloadState.value = _downloadState.value.copy(
                phase = ModelDownloadPhase.VERIFYING_MODEL,
                message = "Executando teste real de inferência…",
            )
            val verified = verifyAndActivate(imported.id)
            _downloadState.value = _downloadState.value.copy(
                phase = ModelDownloadPhase.COMPLETE,
                message = "${catalogModel.displayName} instalado e verificado.",
            )
            verified
        } catch (cancel: CancellationException) {
            _downloadState.value = _downloadState.value.copy(
                phase = ModelDownloadPhase.CANCELLED,
                message = "Download pausado. Ao tentar novamente, o app continua do arquivo parcial quando possível.",
            )
            throw cancel
        } catch (t: Throwable) {
            _downloadState.value = _downloadState.value.copy(
                phase = ModelDownloadPhase.ERROR,
                message = t.message ?: "Falha ao baixar ou verificar o modelo.",
            )
            logger?.error("MODEL_DOWNLOAD", "Falha no fluxo de catálogo para ${catalogModel.displayName}", t)
            throw t
        }
    }

    fun resetDownloadState() {
        if (!_downloadState.value.isBusy) _downloadState.value = ModelDownloadState()
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
