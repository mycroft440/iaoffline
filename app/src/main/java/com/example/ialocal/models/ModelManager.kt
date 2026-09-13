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
import kotlinx.coroutines.sync.Mutex

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
    private val operationMutex = Mutex()

    suspend fun inspect(uri: Uri): ModelImportPreview = repository.inspectForImport(uri)

    suspend fun importAndVerify(preview: ModelImportPreview): AiModelEntity = exclusiveOperation {
        val model = repository.importGguf(preview)
        verifyAndActivateLocked(model.id)
    }

    suspend fun downloadAndVerify(catalogId: String): AiModelEntity = exclusiveOperation {
        val catalogModel = ModelCatalog.requireById(catalogId)
        try {
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
            val verified = verifyAndActivateLocked(imported.id)
            _downloadState.value = _downloadState.value.copy(
                phase = ModelDownloadPhase.COMPLETE,
                message = "${catalogModel.displayName} instalado e verificado.",
            )
            verified
        } catch (cancel: CancellationException) {
            _downloadState.value = _downloadState.value.copy(
                phase = ModelDownloadPhase.CANCELLED,
                message = "Operação pausada. Um download parcial será continuado quando possível.",
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

    suspend fun verifyAndActivate(modelId: String): AiModelEntity = exclusiveOperation {
        verifyAndActivateLocked(modelId)
    }

    private suspend fun verifyAndActivateLocked(modelId: String): AiModelEntity {
        val original = requireNotNull(repository.getModel(modelId)) { "Modelo não encontrado." }
        repository.markVerifying(original.id)
        return try {
            val result = runtime.verify(original)
            logger?.info("INFERENCE", "Verificação concluída para ${original.apiModelId}: ${result.output.take(40)}")
            repository.markVerified(original.id)
            repository.activateModel(original.id)
            repository.repairSelections()
            requireNotNull(repository.getModel(original.id))
        } catch (cancel: CancellationException) {
            repository.restoreVerification(original)
            throw cancel
        } catch (t: Throwable) {
            val message = t.message ?: "Falha ao verificar o modelo."
            repository.markVerificationError(original.id, message)
            repository.repairSelections()
            throw t
        }
    }

    suspend fun load(modelId: String) = exclusiveOperation {
        val model = requireNotNull(repository.getModel(modelId)) { "Modelo não encontrado." }
        require(model.verificationStatus == ModelVerificationStatus.VERIFIED.name) {
            "Verifique o modelo com uma inferência real antes de carregá-lo para uso."
        }
        runtime.warmUp(model)
        repository.activateModel(model.id)
        repository.repairSelections()
    }

    suspend fun retryVerification(modelId: String): AiModelEntity = verifyAndActivate(modelId)

    suspend fun delete(modelId: String) = exclusiveOperation {
        if (runtime.state.value.modelId == modelId) runtime.unload()
        repository.deleteModel(modelId)
    }

    suspend fun unload() = exclusiveOperation { runtime.unload() }

    private suspend fun <T> exclusiveOperation(block: suspend () -> T): T {
        check(operationMutex.tryLock()) {
            "Já existe uma operação de modelo em andamento. Aguarde a conclusão antes de iniciar outra."
        }
        return try {
            block()
        } finally {
            operationMutex.unlock()
        }
    }
}
