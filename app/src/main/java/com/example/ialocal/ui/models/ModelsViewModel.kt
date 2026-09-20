package com.example.ialocal.ui.models

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.ialocal.api.ApiServerState
import com.example.ialocal.api.ApiSettingsRepository
import com.example.ialocal.api.LocalApiServer
import com.example.ialocal.data.AgentEntity
import com.example.ialocal.data.AiModelEntity
import com.example.ialocal.data.ModelVerificationStatus
import com.example.ialocal.diagnostics.IntegrationSelfTest
import com.example.ialocal.diagnostics.IntegrationTestState
import com.example.ialocal.models.ModelImportPreview
import com.example.ialocal.models.ModelManager
import com.example.ialocal.models.ModelRepository
import com.example.ialocal.runtime.RuntimeState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ModelsViewModel(
    private val repository: ModelRepository,
    private val manager: ModelManager,
    private val apiServer: LocalApiServer,
    private val apiSettings: ApiSettingsRepository,
    private val integrationSelfTest: IntegrationSelfTest,
) : ViewModel() {
    val models: StateFlow<List<AiModelEntity>> = repository.models
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val agents: StateFlow<List<AgentEntity>> = repository.agents
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val serverState: StateFlow<ApiServerState> = apiServer.state
    val runtimeState: StateFlow<RuntimeState> = manager.runtimeState
    val downloadState = manager.downloadState
    val catalog = manager.catalog

    private val _isImporting = MutableStateFlow(false)
    val isImporting: StateFlow<Boolean> = _isImporting.asStateFlow()

    private val _operationText = MutableStateFlow<String?>(null)
    val operationText: StateFlow<String?> = _operationText.asStateFlow()

    private val _preview = MutableStateFlow<ModelImportPreview?>(null)
    val preview: StateFlow<ModelImportPreview?> = _preview.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _apiKey = MutableStateFlow(apiSettings.apiKey)
    val apiKey: StateFlow<String> = _apiKey.asStateFlow()

    private val _integrationTest = MutableStateFlow(IntegrationTestState())
    val integrationTest: StateFlow<IntegrationTestState> = _integrationTest.asStateFlow()

    val baseUrl: String get() = apiSettings.baseUrl

    fun inspectModel(uri: Uri) {
        if (_isImporting.value || manager.downloadState.value.isBusy) return
        _isImporting.value = true
        _operationText.value = "Validando GGUF…"
        _error.value = null
        viewModelScope.launch {
            runCatching { manager.inspect(uri) }
                .onSuccess { _preview.value = it }
                .onFailure { _error.value = it.message ?: "Falha ao validar o modelo." }
            _isImporting.value = false
            _operationText.value = null
        }
    }

    fun dismissPreview() { _preview.value = null }

    fun confirmImport() {
        val selected = _preview.value ?: return
        if (_isImporting.value || manager.downloadState.value.isBusy) return
        _preview.value = null
        _isImporting.value = true
        _operationText.value = "Copiando e verificando modelo…"
        _error.value = null
        viewModelScope.launch {
            runCatching { manager.importAndVerify(selected) }
                .onFailure { _error.value = it.message ?: "O modelo foi importado, mas falhou no teste de inferência." }
            _isImporting.value = false
            _operationText.value = null
        }
    }

    fun downloadCatalogModel(catalogId: String) {
        if (_isImporting.value || _operationText.value != null || manager.downloadState.value.isBusy) return
        _error.value = null
        runCatching { manager.startBackgroundDownload(catalogId) }
            .onFailure { _error.value = it.message ?: "Não foi possível iniciar o download em segundo plano." }
    }

    /** Pauses the foreground-service transfer while preserving the partial file for HTTP Range resume. */
    fun cancelDownload() {
        manager.pauseBackgroundDownload()
    }

    /** Ends the foreground transfer and discards downloader-owned partial/complete staging files. */
    fun endDownload() {
        manager.endBackgroundDownload()
    }

    fun clearDownloadState() {
        manager.resetDownloadState()
    }

    fun activate(id: String) = viewModelScope.launch {
        val model = repository.getModel(id) ?: return@launch
        _operationText.value = if (model.verificationStatus == ModelVerificationStatus.VERIFIED.name) {
            "Carregando ${model.name}…"
        } else {
            "Testando ${model.name}…"
        }
        runCatching {
            if (model.verificationStatus == ModelVerificationStatus.VERIFIED.name) manager.load(id)
            else manager.retryVerification(id)
        }.onFailure { _error.value = it.message ?: "Falha ao ativar o modelo." }
        _operationText.value = null
    }

    fun prepareForChat(id: String, onReady: () -> Unit) {
        if (_operationText.value != null || _isImporting.value || manager.downloadState.value.isBusy) return
        _operationText.value = "Preparando I.A…"
        _error.value = null
        viewModelScope.launch {
            val model = repository.getModel(id)
            if (model == null) {
                _error.value = "Modelo não encontrado."
                _operationText.value = null
                return@launch
            }
            _operationText.value = if (model.verificationStatus == ModelVerificationStatus.VERIFIED.name) {
                "Carregando ${model.name}…"
            } else {
                "Testando ${model.name}…"
            }
            runCatching {
                if (model.verificationStatus == ModelVerificationStatus.VERIFIED.name) manager.load(id)
                else manager.retryVerification(id)
            }.onSuccess {
                onReady()
            }.onFailure {
                _error.value = it.message ?: "Não foi possível iniciar o chat com esta I.A."
            }
            _operationText.value = null
        }
    }

    fun unload() = viewModelScope.launch {
        _operationText.value = "Liberando modelo da memória…"
        runCatching { manager.unload() }
            .onFailure { _error.value = it.message ?: "Falha ao liberar o modelo." }
        _operationText.value = null
    }

    fun delete(id: String) = viewModelScope.launch {
        runCatching { manager.delete(id) }
            .onFailure { _error.value = it.message ?: "Falha ao excluir o modelo." }
    }

    fun setDefaultAgent(id: String) = viewModelScope.launch {
        runCatching { repository.setDefaultAgent(id) }
            .onFailure { _error.value = it.message ?: "Falha ao selecionar o agente." }
    }

    fun saveAgent(agent: AgentEntity) = viewModelScope.launch {
        runCatching { repository.updateAgent(agent) }
            .onFailure { _error.value = it.message ?: "Falha ao salvar o agente." }
    }

    fun startServer() = apiServer.start()
    fun stopServer() = apiServer.stop()

    fun regenerateKey() { _apiKey.value = apiSettings.regenerateApiKey() }

    fun runIntegrationTest() {
        if (_integrationTest.value.running) return
        viewModelScope.launch {
            runCatching { integrationSelfTest.run { _integrationTest.value = it } }
                .onFailure { error ->
                    _integrationTest.value = _integrationTest.value.copy(
                        running = false,
                        summary = "Teste interrompido: ${error.message ?: "erro desconhecido"}",
                    )
                    _error.value = error.message ?: "Falha inesperada no teste completo da integração."
                }
        }
    }

    fun clearError() { _error.value = null }

    class Factory(
        private val repository: ModelRepository,
        private val manager: ModelManager,
        private val apiServer: LocalApiServer,
        private val apiSettings: ApiSettingsRepository,
        private val integrationSelfTest: IntegrationSelfTest,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ModelsViewModel(repository, manager, apiServer, apiSettings, integrationSelfTest) as T
    }
}
