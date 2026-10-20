package com.example.ialocal.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.ialocal.api.ExternalApiClient
import com.example.ialocal.api.ExternalApiSettings
import com.example.ialocal.api.RemoteApiConnection
import com.example.ialocal.api.RemoteApiModel
import com.example.ialocal.api.RemoteApiProvider
import com.example.ialocal.api.RemoteChatTurn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class OnlineChatMessage(
    val role: String,
    val content: String,
)

class ExternalApiViewModel(
    private val settings: ExternalApiSettings,
    private val client: ExternalApiClient = ExternalApiClient(),
) : ViewModel() {
    private val _config = MutableStateFlow(settings.read())
    val config: StateFlow<RemoteApiConnection> = _config.asStateFlow()
    private val _models = MutableStateFlow<List<RemoteApiModel>>(emptyList())
    val models: StateFlow<List<RemoteApiModel>> = _models.asStateFlow()
    private val _messages = MutableStateFlow<List<OnlineChatMessage>>(emptyList())
    val messages: StateFlow<List<OnlineChatMessage>> = _messages.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()
    private val _loadingModels = MutableStateFlow(false)
    val loadingModels: StateFlow<Boolean> = _loadingModels.asStateFlow()
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()
    private var answerJob: Job? = null

    init {
        // Do not fetch automatically: this screen only uses mobile data when requested.
    }

    fun clearNotice() { _notice.value = null }

    /**
     * Password field is blank after saving. Blank = keep existing credential; an explicit
     * remove button deletes the stored credential.
     */
    fun save(provider: RemoteApiProvider, baseUrl: String, modelId: String, newKey: String?): Boolean {
        return try {
            check(!_busy.value) { "Pare a resposta antes de mudar a configuração da API." }
            settings.save(provider, baseUrl, modelId, newKey?.takeIf { it.isNotBlank() })
            _config.value = settings.read()
            _models.value = emptyList()
            _messages.value = emptyList() // do not mix histories from different providers/models
            _notice.value = "Configuração salva. Selecione um modelo e envie sua mensagem."
            true
        } catch (error: Exception) {
            _notice.value = error.message ?: "Não foi possível salvar a configuração."
            false
        }
    }

    fun selectModel(id: String) {
        if (_busy.value) return
        save(_config.value.provider, _config.value.baseUrl, id, null)
    }

    fun removeCredential() {
        if (_busy.value) return
        settings.removeKey()
        _config.value = settings.read()
        _messages.value = emptyList()
        _models.value = emptyList()
        _notice.value = "Chave de API removida deste aparelho."
    }

    fun fetchModels() {
        if (_loadingModels.value || _busy.value) return
        viewModelScope.launch {
            _loadingModels.value = true
            try {
                val key = settings.apiKey()
                val list = client.fetchModels(_config.value, key)
                _models.value = list
                _notice.value = if (list.isEmpty()) {
                    "A API não retornou modelos. Insira o ID manualmente."
                } else {
                    "${list.size} modelos encontrados. Escolha um na lista."
                }
            } catch (error: Exception) {
                _notice.value = error.message ?: "Não foi possível listar os modelos."
            } finally {
                _loadingModels.value = false
            }
        }
    }

    fun send(message: String) {
        val text = message.trim()
        if (text.isEmpty() || _busy.value) return
        val connection = _config.value
        if (!connection.hasKey || connection.modelId.isBlank()) {
            _notice.value = "Configure sua chave e escolha um modelo para conversar."
            return
        }
        val next = _messages.value + OnlineChatMessage("user", text)
        val history = next.map { RemoteChatTurn(it.role, it.content) }
        _messages.value = next + OnlineChatMessage("assistant", "")
        _busy.value = true
        answerJob = viewModelScope.launch {
            try {
                val key = settings.apiKey()
                client.stream(connection, key, history).collect { chunk ->
                    _messages.value = _messages.value.toMutableList().also { messages ->
                        val old = messages.last()
                        messages[messages.lastIndex] = old.copy(content = old.content + chunk)
                    }
                }
            } catch (cancel: CancellationException) {
                _notice.value = "Resposta interrompida."
                throw cancel
            } catch (error: Exception) {
                _notice.value = error.message ?: "Falha ao consultar a API externa."
            } finally {
                _busy.value = false
                answerJob = null
            }
        }
    }

    fun stop() { answerJob?.cancel() }

    fun newConversation() {
        if (_busy.value) return
        _messages.value = emptyList()
        _notice.value = "Nova conversa iniciada."
    }

    class Factory(private val settings: ExternalApiSettings) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ExternalApiViewModel(settings) as T
    }
}
