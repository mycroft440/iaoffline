package com.example.ialocal.ui.catalog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.ialocal.data.AiModelEntity
import com.example.ialocal.models.CatalogDownloadManager
import com.example.ialocal.models.CatalogDownloadState
import com.example.ialocal.models.CatalogModel
import com.example.ialocal.models.ModelCatalog
import com.example.ialocal.models.ModelManager
import com.example.ialocal.models.ModelRepository
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch


data class CatalogItemUi(
    val model: CatalogModel,
    val download: CatalogDownloadState,
    val installed: Boolean,
    val installing: Boolean,
    val installError: String? = null,
)

class CatalogViewModel(
    private val repository: ModelRepository,
    private val manager: ModelManager,
    private val downloads: CatalogDownloadManager,
) : ViewModel() {
    private val installedModels: StateFlow<List<AiModelEntity>> = repository.models
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val downloadStates = MutableStateFlow<Map<String, CatalogDownloadState>>(emptyMap())
    private val installingId = MutableStateFlow<String?>(null)
    private val installErrors = MutableStateFlow<Map<String, String>>(emptyMap())
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    val items: StateFlow<List<CatalogItemUi>> = combine(
        installedModels,
        downloadStates,
        installingId,
        installErrors,
    ) { installed, states, activeInstall, errors ->
        ModelCatalog.items.map { item ->
            CatalogItemUi(
                model = item,
                download = states[item.id] ?: CatalogDownloadState.Idle,
                installed = installed.any { sameFile(it.filePath, downloads.destinationFile(item)) },
                installing = activeInstall == item.id,
                installError = errors[item.id],
            )
        }
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        ModelCatalog.items.map {
            CatalogItemUi(it, CatalogDownloadState.Idle, installed = false, installing = false)
        },
    )

    init {
        viewModelScope.launch {
            while (isActive) {
                refreshDownloads()
                installNextCompletedDownload()
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    fun download(modelId: String) {
        val model = ModelCatalog.byId(modelId) ?: return
        if (items.value.firstOrNull { it.model.id == modelId }?.installed == true) return

        val current = downloads.state(model)
        if (current is CatalogDownloadState.Failed) downloads.cancel(modelId)
        installErrors.value = installErrors.value - modelId

        runCatching { downloads.start(model) }
            .onFailure { _message.value = it.message ?: "Não foi possível iniciar o download." }
        refreshDownloads()
    }

    fun cancel(modelId: String) {
        if (installingId.value == modelId) return
        downloads.cancel(modelId)
        installErrors.value = installErrors.value - modelId
        refreshDownloads()
    }

    fun retryInstall(modelId: String) {
        installErrors.value = installErrors.value - modelId
        viewModelScope.launch { installNextCompletedDownload(preferredId = modelId) }
    }

    fun clearMessage() {
        _message.value = null
    }

    private fun refreshDownloads() {
        downloadStates.value = ModelCatalog.items.associate { it.id to downloads.state(it) }
    }

    private suspend fun installNextCompletedDownload(preferredId: String? = null) {
        if (installingId.value != null) return

        val installed = installedModels.value
        val candidates = if (preferredId != null) {
            ModelCatalog.items.filter { it.id == preferredId }
        } else {
            ModelCatalog.items
        }
        val item = candidates.firstOrNull { candidate ->
            val state = downloadStates.value[candidate.id]
            state is CatalogDownloadState.Successful &&
                installErrors.value[candidate.id] == null &&
                installed.none { sameFile(it.filePath, state.file) }
        } ?: return
        val file = (downloadStates.value[item.id] as? CatalogDownloadState.Successful)?.file ?: return

        installingId.value = item.id
        _message.value = "Download concluído. Verificando ${item.name}…"
        try {
            manager.installDownloaded(file, "${item.name} ${item.variant.substringBefore('·').trim()}")
            downloads.forget(item.id)
            installErrors.value = installErrors.value - item.id
            _message.value = "${item.name} instalado, verificado e pronto para uso."
        } catch (t: Throwable) {
            val wasAdopted = repository.getModels().any { sameFile(it.filePath, file) }
            if (wasAdopted) {
                downloads.forget(item.id)
                _message.value = "Modelo baixado e registrado, mas a verificação falhou: ${t.message ?: "erro desconhecido"}. Você pode tentar novamente em Modelos."
            } else {
                val message = t.message ?: "Falha ao instalar o GGUF baixado."
                installErrors.value = installErrors.value + (item.id to message)
                _message.value = message
            }
        } finally {
            installingId.value = null
            refreshDownloads()
        }
    }

    private fun sameFile(path: String, file: File): Boolean {
        return runCatching { File(path).canonicalPath == file.canonicalPath }.getOrDefault(false)
    }

    class Factory(
        private val repository: ModelRepository,
        private val manager: ModelManager,
        private val downloads: CatalogDownloadManager,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            CatalogViewModel(repository, manager, downloads) as T
    }

    companion object {
        private const val POLL_INTERVAL_MS = 1_000L
    }
}
