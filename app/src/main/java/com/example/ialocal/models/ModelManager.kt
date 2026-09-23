package com.example.ialocal.models

import android.content.Context
import android.net.Uri
import com.example.ialocal.data.AiModelEntity
import com.example.ialocal.data.ModelVerificationStatus
import com.example.ialocal.diagnostics.AiEventLogger
import com.example.ialocal.runtime.ModelRuntime
import com.example.ialocal.runtime.RuntimeState
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

data class ModelRecoverySummary(
    val restored: Int,
    val alreadyInstalled: Int,
    val verificationWarnings: List<String>,
    val failures: List<String>,
) {
    fun toUserMessage(): String = buildString {
        when {
            restored > 0 -> append("$restored IA(s) restaurada(s) sem novo download.")
            alreadyInstalled > 0 && failures.isEmpty() -> append("As IAs encontradas já estão instaladas.")
            else -> append("Nenhuma IA foi restaurada.")
        }
        if (verificationWarnings.isNotEmpty()) {
            append(" ").append(verificationWarnings.size)
                .append(" modelo(s) foram recuperados, mas precisam de uma nova tentativa de ativação por causa da verificação de execução.")
        }
        if (failures.isNotEmpty()) {
            append(" Falhas: ").append(failures.joinToString(" | "))
        }
    }
}

/** Coordinates persistence, downloads and native runtime so activation follows a successful real inference. */
class ModelManager(
    context: Context,
    private val repository: ModelRepository,
    private val runtime: ModelRuntime,
    private val downloader: ModelDownloader,
    private val logger: AiEventLogger? = null,
) {
    private val appContext = context.applicationContext
    private val persistentDownloads = PublicModelDownloads(appContext)

    val runtimeState: StateFlow<RuntimeState> = runtime.state
    val catalog: List<CatalogModel> = ModelCatalog.entries

    private val _downloadState = MutableStateFlow(ModelDownloadState())
    val downloadState: StateFlow<ModelDownloadState> = _downloadState.asStateFlow()

    private val queuePreferences = appContext.getSharedPreferences(QUEUE_PREFS, Context.MODE_PRIVATE)
    private val queueLock = Any()
    // Loaded in a fresh process, so an ACTIVE entry was interrupted; it goes back to the front of the line.
    private val _downloadQueue = MutableStateFlow(
        DownloadQueue.decode(queuePreferences.getString(KEY_QUEUE, null)).recoverInterrupted(),
    )
    /** Every download the user asked for, in order: the active one, the waiting ones, paused and failed. */
    val downloadQueue: StateFlow<DownloadQueue> = _downloadQueue.asStateFlow()

    /** True while [ModelDownloadService] owns a transfer (including the pause between queued ones). */
    @Volatile internal var transferRunning = false
        private set

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

    /** Finds catalog models in the shared folder and in older Downloads folders. */
    suspend fun discoverPersistedCatalogModels(): List<CatalogModel> = withContext(Dispatchers.IO) {
        persistentDownloads.discoverCatalogModels()
    }

    /**
     * Restores catalog GGUFs from a selected legacy folder without network access.
     */
    suspend fun restorePersistedModels(
        treeUri: Uri,
        onProgress: (String) -> Unit = {},
    ): ModelRecoverySummary {
        check(!_downloadState.value.isBusy) {
            "Pause o download atual antes de restaurar IAs salvas."
        }
        val candidates = persistentDownloads.catalogFilesFromTree(treeUri)
        require(candidates.isNotEmpty()) {
            "Nenhum GGUF reconhecido do catálogo foi encontrado na pasta selecionada."
        }

        val installedCatalogIds = repository.getModels()
            .mapNotNull(::catalogIdForInstalledModel)
            .toMutableSet()
        var restored = 0
        var alreadyInstalled = 0
        val verificationWarnings = mutableListOf<String>()
        val failures = mutableListOf<String>()
        val stagingDir = File(appContext.filesDir, "model-downloads").apply { mkdirs() }

        candidates.forEach { candidate ->
            val catalogModel = candidate.model
            if (!installedCatalogIds.add(catalogModel.id)) {
                alreadyInstalled += 1
                return@forEach
            }

            val staging = File(stagingDir, "${catalogModel.id}.gguf")
            try {
                onProgress("Validando ${catalogModel.displayName} salvo no aparelho…")
                persistentDownloads.copyVerifiedModelToStaging(candidate, staging)

                onProgress("Restaurando ${catalogModel.displayName} no app…")
                val imported = repository.importDownloadedGguf(staging, catalogModel)
                restored += 1

                onProgress("Testando ${catalogModel.displayName} no aparelho…")
                try {
                    verifyAndActivate(imported.id)
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (t: Throwable) {
                    verificationWarnings += catalogModel.displayName
                    logger?.error(
                        "MODEL_RECOVERY",
                        "${catalogModel.displayName} foi restaurado, mas falhou na verificação de execução.",
                        t,
                    )
                }
            } catch (cancel: CancellationException) {
                staging.delete()
                throw cancel
            } catch (t: Throwable) {
                staging.delete()
                installedCatalogIds.remove(catalogModel.id)
                failures += "${catalogModel.displayName}: ${t.message ?: "falha desconhecida"}"
                logger?.error("MODEL_RECOVERY", "Falha ao restaurar ${catalogModel.displayName}", t)
            }
        }

        return ModelRecoverySummary(
            restored = restored,
            alreadyInstalled = alreadyInstalled,
            verificationWarnings = verificationWarnings,
            failures = failures,
        )
    }

    /**
     * Adds a catalog model to the download queue. Downloads run one at a time in a foreground service,
     * so leaving the screen does not cancel them; the next queued one starts as soon as the current
     * one is installed (or fails).
     */
    fun enqueueDownload(catalogId: String) {
        ModelCatalog.requireById(catalogId)
        updateQueue { it.enqueue(catalogId) }
        startNextIfIdle()
    }

    /** Continues a paused or failed download from its partial file, ahead of the waiting ones. */
    fun resumeDownload(catalogId: String) {
        ModelCatalog.requireById(catalogId)
        updateQueue { it.resume(catalogId) }
        startNextIfIdle()
    }

    /** Removes a download from the list, discarding its partial file; an active one is ended first. */
    fun removeDownload(catalogId: String) {
        if (_downloadQueue.value.entry(catalogId)?.status == DownloadEntryStatus.ACTIVE) {
            ModelDownloadService.end(appContext, catalogId)
            return
        }
        updateQueue { it.remove(catalogId) }
        discardDownloadFiles(catalogId)
        if (_downloadState.value.catalogId == catalogId && !_downloadState.value.isBusy) {
            _downloadState.value = ModelDownloadState()
        }
    }

    /** After the app process was killed mid-download, continues the queue from the saved files. */
    fun resumeQueueAfterRestart() {
        if (transferRunning || _downloadState.value.isBusy) return
        startNextIfIdle()
    }

    /** Bytes already saved for a download that is not running, to show its progress. */
    fun savedDownloadBytes(catalogId: String): Long {
        val downloadDir = File(appContext.filesDir, "model-downloads")
        val complete = File(downloadDir, "$catalogId.gguf")
        return if (complete.isFile) complete.length() else File(downloadDir, "$catalogId.part").length()
    }

    private fun startNextIfIdle() {
        val next = synchronized(queueLock) {
            if (transferRunning || _downloadQueue.value.active != null) return
            _downloadQueue.value.nextQueued?.catalogId
        } ?: return
        runCatching { ModelDownloadService.start(appContext, next) }
            .onFailure { error ->
                updateQueue { it.markFailed(next, error.message ?: "Não foi possível iniciar o download em segundo plano.") }
            }
    }

    /** Called by the service when it takes ownership of a transfer. */
    internal fun onTransferStarted(catalogId: String) {
        synchronized(queueLock) {
            transferRunning = true
            updateQueue { it.markActive(catalogId) }
        }
    }

    /**
     * Called by the service when a transfer ended on its own (installed or failed). Records the
     * outcome and claims the next queued download, if any, for the same service to run.
     */
    internal fun onTransferFinished(catalogId: String): String? {
        val state = _downloadState.value
        updateQueue { queue ->
            if (state.catalogId == catalogId && state.phase == ModelDownloadPhase.COMPLETE) {
                queue.remove(catalogId)
            } else {
                queue.markFailed(catalogId, state.message ?: "O download foi interrompido.")
            }
        }
        return claimNextQueued()
    }

    /** Marks the next waiting download active for the running service, or releases the service. */
    internal fun claimNextQueued(): String? = synchronized(queueLock) {
        val next = _downloadQueue.value.nextQueued?.catalogId
        if (next != null) {
            updateQueue { it.markActive(next) }
        } else {
            transferRunning = false
        }
        next
    }

    /** Drops an entry the service cannot run, whatever its status. */
    internal fun forgetDownload(catalogId: String) {
        updateQueue { it.remove(catalogId) }
        discardDownloadFiles(catalogId)
    }

    /** Called when the service stops without claiming a next download (e.g. Android's time limit). */
    internal fun onTransferServiceStopped() {
        transferRunning = false
    }

    private fun updateQueue(change: (DownloadQueue) -> DownloadQueue) {
        synchronized(queueLock) {
            val updated = change(_downloadQueue.value)
            if (updated == _downloadQueue.value) return
            _downloadQueue.value = updated
            queuePreferences.edit().putString(KEY_QUEUE, updated.encode()).apply()
        }
    }

    private suspend fun installedCatalogModel(catalogModel: CatalogModel): AiModelEntity? =
        repository.getModels().firstOrNull { it.apiModelId.startsWith(catalogModel.apiIdPrefix) }

    private fun discardDownloadFiles(catalogId: String) {
        val downloadDir = File(appContext.filesDir, "model-downloads")
        File(downloadDir, "$catalogId.part").delete()
        File(downloadDir, "$catalogId.gguf").delete()
    }

    fun pauseBackgroundDownload() {
        ModelDownloadService.pause(appContext, _downloadState.value.catalogId)
    }

    fun endBackgroundDownload() {
        ModelDownloadService.end(appContext, _downloadState.value.catalogId)
    }

    suspend fun downloadAndVerify(catalogId: String): AiModelEntity {
        check(!_downloadState.value.isBusy) { "Já existe um download ou verificação de modelo em andamento." }
        val catalogModel = ModelCatalog.requireById(catalogId)
        // Android can restart the download service after the app was killed during the final test
        // (e.g. out of memory). The model is already registered by then, so never download it again.
        installedCatalogModel(catalogModel)?.let { installed ->
            _downloadState.value = ModelDownloadState(
                catalogId = catalogId,
                phase = ModelDownloadPhase.COMPLETE,
                message = "${catalogModel.displayName} já está instalada.",
            )
            logger?.info("MODEL_DOWNLOAD", "Download ignorado: ${catalogModel.displayName} já está instalada.")
            return installed
        }
        return try {
            val file = downloader.download(catalogModel) { _downloadState.value = it }
            _downloadState.value = _downloadState.value.copy(
                phase = ModelDownloadPhase.VERIFYING_FILE,
                downloadedBytes = file.length(),
                totalBytes = file.length(),
                message = "Salvando cópia permanente em ${PublicModelDownloads.FOLDER_NAME}…",
            )
            persistentDownloads.ensurePersistedVerifiedModel(catalogModel, file)

            _downloadState.value = _downloadState.value.copy(
                phase = ModelDownloadPhase.IMPORTING,
                downloadedBytes = file.length(),
                totalBytes = file.length(),
                message = "Registrando modelo no app…",
            )
            val imported = repository.importDownloadedGguf(file, catalogModel)
            verifyDownloadedModel(catalogModel, imported)
        } catch (offline: NetworkUnavailableException) {
            _downloadState.value = _downloadState.value.copy(
                phase = ModelDownloadPhase.DOWNLOADING,
                downloadedBytes = offline.downloadedBytes,
                message = "Internet indisponível. Aguardando conexão; o download continuará automaticamente do ponto salvo.",
            )
            logger?.info(
                "MODEL_DOWNLOAD",
                "Internet indisponível; download aguardando reconexão para ${catalogModel.displayName} em ${offline.downloadedBytes} bytes.",
            )
            throw offline
        } catch (cancel: CancellationException) {
            _downloadState.value = _downloadState.value.copy(
                phase = ModelDownloadPhase.CANCELLED,
                message = "Download pausado. Ao continuar, o app retoma do arquivo parcial quando possível.",
            )
            throw cancel
        } catch (t: Throwable) {
            _downloadState.value = _downloadState.value.copy(
                phase = ModelDownloadPhase.ERROR,
                message = t.message ?: "Falha ao baixar ou instalar o modelo.",
            )
            logger?.error("MODEL_DOWNLOAD", "Falha no fluxo de catálogo para ${catalogModel.displayName}", t)
            throw t
        }
    }

    /** Called by the service immediately before retrying an offline transfer. */
    internal fun prepareNetworkRetry(catalogId: String) {
        val current = _downloadState.value
        if (
            current.catalogId == catalogId &&
            current.phase == ModelDownloadPhase.DOWNLOADING &&
            current.message?.startsWith("Internet indisponível") == true
        ) {
            _downloadState.value = current.copy(phase = ModelDownloadPhase.CANCELLED)
        }
    }

    internal fun markDownloadPausedByUser(catalogId: String?) {
        val current = _downloadState.value
        val message = "Download pausado. Toque em Continuar para retomar do ponto salvo."
        if (catalogId == null || current.catalogId == catalogId) {
            _downloadState.value = current.copy(phase = ModelDownloadPhase.CANCELLED, message = message)
        }
        (catalogId ?: current.catalogId)?.let { id -> updateQueue { it.markPaused(id, message) } }
    }

    internal fun markDownloadPausedBySystem(catalogId: String?) {
        val current = _downloadState.value
        val message = "O Android pausou o download por um limite do sistema. Toque em Continuar para retomar do ponto salvo."
        if (catalogId == null || current.catalogId == catalogId) {
            _downloadState.value = current.copy(phase = ModelDownloadPhase.CANCELLED, message = message)
        }
        (catalogId ?: current.catalogId)?.let { id -> updateQueue { it.markPaused(id, message) } }
    }

    /** Ends the pending transfer and discards only downloader-owned files, never an installed model. */
    internal fun discardDownload(catalogId: String) {
        discardDownloadFiles(catalogId)
        updateQueue { it.remove(catalogId) }
        _downloadState.value = ModelDownloadState()
        logger?.info("MODEL_DOWNLOAD", "Download encerrado pelo usuário: $catalogId")
    }

    private suspend fun verifyDownloadedModel(
        catalogModel: CatalogModel,
        imported: AiModelEntity,
    ): AiModelEntity {
        _downloadState.value = _downloadState.value.copy(
            phase = ModelDownloadPhase.VERIFYING_MODEL,
            message = "Executando teste real de inferência…",
        )
        return try {
            val verified = verifyAndActivate(imported.id)
            _downloadState.value = _downloadState.value.copy(
                phase = ModelDownloadPhase.COMPLETE,
                message = "${catalogModel.displayName} instalado, verificado e salvo em ${PublicModelDownloads.FOLDER_NAME}.",
            )
            verified
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (t: Throwable) {
            // The GGUF has already been downloaded, hash-verified, inspected and registered. A runtime
            // failure (most commonly RAM pressure on larger catalog entries) must not be reported as a
            // download failure or cause the installed model to disappear. The user can retry later.
            logger?.error(
                "MODEL_VERIFY",
                "${catalogModel.displayName} foi instalado, mas não passou na verificação de execução.",
                t,
            )
            val retained = repository.getModel(imported.id) ?: throw t
            _downloadState.value = _downloadState.value.copy(
                phase = ModelDownloadPhase.COMPLETE,
                message = buildString {
                    append(catalogModel.displayName)
                    append(" foi baixado, salvo de forma persistente e instalado. A ativação automática não foi concluída")
                    t.message?.takeIf { it.isNotBlank() }?.let { append(": ").append(it) }
                    append(". Você pode tentar ativar novamente depois.")
                },
            )
            retained
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
        val model = repository.getModel(modelId)
        val catalogModel = model?.let { installed ->
            ModelCatalog.entries.firstOrNull { installed.apiModelId.startsWith(it.apiIdPrefix) }
        }
        if (runtime.state.value.modelId == modelId) runtime.unload()
        if (catalogModel != null) persistentDownloads.deletePersistedModel(catalogModel)
        repository.deleteModel(modelId)
    }

    suspend fun unload() = runtime.unload()

    private companion object {
        const val QUEUE_PREFS = "model_download_queue"
        const val KEY_QUEUE = "entries"
    }

    private fun catalogIdForInstalledModel(model: AiModelEntity): String? =
        ModelCatalog.entries.firstOrNull { model.apiModelId.startsWith(it.apiIdPrefix) }?.id
}
