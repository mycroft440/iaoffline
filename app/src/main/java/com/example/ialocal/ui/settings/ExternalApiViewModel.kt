package com.example.ialocal.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.ialocal.api.ExternalApiChatStore
import com.example.ialocal.api.ExternalApiClient
import com.example.ialocal.api.ExternalApiSettings
import com.example.ialocal.api.RemoteApiConnection
import com.example.ialocal.api.RemoteApiModel
import com.example.ialocal.api.RemoteApiProvider
import com.example.ialocal.api.RemoteChatTurn
import com.example.ialocal.api.SavedRemoteConversation
import com.example.ialocal.api.SavedRemoteMessage
import com.example.ialocal.files.AttachmentContentProcessor
import com.example.ialocal.files.AttachmentImporter
import com.example.ialocal.data.PendingAttachment
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class RemoteDraftAttachment(val id: String, val name: String, val extractedText: String)

/**
 * Remote chats are intentionally separate from the offline Room conversations, agents, and
 * llama.cpp generation manager. Online providers only receive a request when the user sends.
 */
class ExternalApiViewModel(
    private val settings: ExternalApiSettings,
    private val store: ExternalApiChatStore,
    private val importer: AttachmentImporter,
    private val processor: AttachmentContentProcessor,
    private val client: ExternalApiClient = ExternalApiClient(),
) : ViewModel() {
    private val _config = MutableStateFlow(settings.read())
    val config: StateFlow<RemoteApiConnection> = _config.asStateFlow()
    private val _models = MutableStateFlow<List<RemoteApiModel>>(emptyList())
    val models: StateFlow<List<RemoteApiModel>> = _models.asStateFlow()
    private val _conversations = MutableStateFlow(store.load())
    val conversations: StateFlow<List<SavedRemoteConversation>> = _conversations.asStateFlow()
    private val _currentId = MutableStateFlow<String?>(null)
    val currentId: StateFlow<String?> = _currentId.asStateFlow()
    private val _messages = MutableStateFlow<List<SavedRemoteMessage>>(emptyList())
    val messages: StateFlow<List<SavedRemoteMessage>> = _messages.asStateFlow()
    private val _profile = MutableStateFlow("Padrão")
    val profile: StateFlow<String> = _profile.asStateFlow()
    private val _attachments = MutableStateFlow<List<RemoteDraftAttachment>>(emptyList())
    val attachments: StateFlow<List<RemoteDraftAttachment>> = _attachments.asStateFlow()
    private val _processingAttachment = MutableStateFlow(false)
    val processingAttachment: StateFlow<Boolean> = _processingAttachment.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()
    private val _loadingModels = MutableStateFlow(false)
    val loadingModels: StateFlow<Boolean> = _loadingModels.asStateFlow()
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()
    private var answerJob: Job? = null

    fun clearNotice() { _notice.value = null }

    fun save(provider: RemoteApiProvider, baseUrl: String, modelId: String, newKey: String?): Boolean =
        try {
            check(!_busy.value && !_loadingModels.value) { "Aguarde o fim da operação atual." }
            val previous = _config.value
            settings.save(provider, baseUrl, modelId, newKey?.takeIf { it.isNotBlank() })
            val current = settings.read()
            _config.value = current
            if (previous.provider != current.provider || previous.baseUrl != current.baseUrl) _models.value = emptyList()
            if (previous.provider != current.provider || previous.baseUrl != current.baseUrl ||
                previous.modelId != current.modelId) {
                newConversation()
            }
            _notice.value = "Conexão salva. O histórico online fica separado das IAs offline."
            true
        } catch (error: Exception) {
            _notice.value = error.message ?: "Não foi possível salvar a conexão."
            false
        }

    fun selectModel(id: String) {
        if (_busy.value) return
        save(_config.value.provider, _config.value.baseUrl, id, null)
    }

    fun removeCredential() {
        if (_busy.value) return
        settings.removeKey()
        _config.value = settings.read()
        _models.value = emptyList()
        newConversation()
        _notice.value = "Chave removida. O histórico online separado foi preservado."
    }

    fun fetchModels() {
        if (_loadingModels.value || _busy.value) return
        viewModelScope.launch {
            _loadingModels.value = true
            try {
                val result = client.fetchModels(_config.value, settings.apiKey())
                _models.value = result
                _notice.value = if (result.isEmpty()) "Informe o ID do modelo manualmente." else
                    "${result.size} modelos encontrados. Escolha um na lista."
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                _notice.value = error.message ?: "Não foi possível listar os modelos."
            } finally { _loadingModels.value = false }
        }
    }

    fun setProfile(name: String) {
        if (_busy.value) return
        if (name !in PROFILE_NAMES) return
        _profile.value = name
        persistCurrent()
    }

    fun attachFile(uri: Uri) {
        if (_processingAttachment.value || _busy.value) return
        viewModelScope.launch {
            _processingAttachment.value = true
            try {
                val file = withContext(Dispatchers.IO) { importer.import(uri) }
                try {
                    val enriched = processor.enrich(file)
                    val text = enriched.extractedText?.takeIf { it.isNotBlank() }
                        ?: throw IllegalArgumentException(
                            "Este arquivo não tem texto extraível. Imagens exigem uma API multimodal compatível."
                        )
                    _attachments.value = _attachments.value + RemoteDraftAttachment(file.id, file.fileName, text)
                    _notice.value = "Anexo pronto: ${file.fileName}. O conteúdo será enviado à API quando você enviar a mensagem."
                } finally {
                    withContext(Dispatchers.IO) { runCatching { File(file.localPath).delete() } }
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { _notice.value = error.message ?: "Falha ao importar anexo." }
            finally { _processingAttachment.value = false }
        }
    }

    /** Offline voice recording is transcribed on-device before being sent to the remote provider. */
    fun attachRecordedAudio(attachment: PendingAttachment) {
        if (_processingAttachment.value || _busy.value) {
            File(attachment.localPath).delete()
            return
        }
        viewModelScope.launch {
            _processingAttachment.value = true
            try {
                val prepared = processor.enrich(attachment)
                val text = prepared.extractedText?.takeIf { it.isNotBlank() }
                    ?: throw IllegalArgumentException("Não foi possível transcrever este áudio.")
                _attachments.value = _attachments.value + RemoteDraftAttachment(
                    attachment.id,
                    attachment.fileName,
                    text,
                )
                _notice.value = "Áudio transcrito localmente. A transcrição será enviada à API ao enviar."
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                _notice.value = error.message ?: "Falha ao processar gravação."
            } finally {
                withContext(Dispatchers.IO) { runCatching { File(attachment.localPath).delete() } }
                _processingAttachment.value = false
            }
        }
    }

    fun removeAttachment(id: String) {
        _attachments.value = _attachments.value.filterNot { it.id == id }
    }

    fun send(text: String) {
        if (_busy.value || _processingAttachment.value || _loadingModels.value) return
        val typed = text.trim()
        if (typed.isEmpty() && _attachments.value.isEmpty()) return
        val cfg = _config.value
        if (!cfg.hasKey || cfg.modelId.isBlank()) {
            _notice.value = "Configure a chave e escolha um modelo para conversar."
            return
        }
        val opened = _conversations.value.firstOrNull { it.id == _currentId.value }
        if (opened != null && (opened.provider != cfg.provider.name ||
                opened.baseUrl != cfg.baseUrl || opened.modelId != cfg.modelId)) {
            _notice.value = "Esta conversa pertence a outro modelo/provedor. Comece uma nova conversa para responder."
            return
        }
        val attachmentNames = _attachments.value.map { it.name }
        val context = _attachments.value.take(5).joinToString("\n\n") {
            "[Conteúdo de ${it.name}]\n${it.extractedText.take(8_000)}"
        }.take(16_000)
        val content = if (context.isEmpty()) typed else "${typed}\n\n${context}".trim()
        _attachments.value = emptyList()
        _notice.value = null
        ensureConversation(cfg, typed.ifBlank { "Conversa com anexo" })
        _messages.value = _messages.value + SavedRemoteMessage(role = "user", content = content, attachmentNames = attachmentNames)
        persistCurrent()
        generate(cfg)
    }

    fun regenerate() {
        if (_busy.value || _messages.value.isEmpty()) return
        val last = _messages.value.last()
        if (last.role != "assistant" || _messages.value.none { it.role == "user" }) return
        _messages.value = _messages.value.dropLast(1)
        persistCurrent()
        generate(_config.value)
    }

    /** Edit an earlier user turn: remove subsequent remote turns; the edited text goes in composer. */
    fun editMessage(messageId: String): String? {
        if (_busy.value) return null
        val index = _messages.value.indexOfFirst { it.id == messageId && it.role == "user" }
        if (index < 0) return null
        val text = _messages.value[index].content
        _messages.value = _messages.value.take(index)
        persistCurrent()
        return text
    }

    fun stop() {
        answerJob?.cancel()
        client.cancelChat()
    }

    fun newConversation() {
        if (_busy.value) return
        _currentId.value = null
        _messages.value = emptyList()
        _attachments.value = emptyList()
        _profile.value = "Padrão"
    }

    fun openConversation(id: String) {
        if (_busy.value) return
        val chat = _conversations.value.firstOrNull { it.id == id } ?: return
        _currentId.value = id
        _messages.value = chat.messages
        _profile.value = chat.profile.takeIf { it in PROFILE_NAMES } ?: "Padrão"
        _attachments.value = emptyList()
        if (chat.provider != _config.value.provider.name || chat.baseUrl != _config.value.baseUrl ||
            chat.modelId != _config.value.modelId) {
            _notice.value = "Histórico de ${chat.modelId}. Configure o mesmo provedor e modelo para continuar."
        }
    }

    fun deleteConversation(id: String) {
        if (_busy.value && _currentId.value == id) return
        _conversations.value = _conversations.value.filterNot { it.id == id }
        if (_currentId.value == id) newConversation()
        writeHistory()
    }

    private fun ensureConversation(cfg: RemoteApiConnection, first: String) {
        if (_currentId.value != null) return
        val id = UUID.randomUUID().toString()
        _currentId.value = id
        _conversations.value = listOf(
            SavedRemoteConversation(
                id = id,
                title = first.take(48).ifBlank { "Nova conversa" },
                modelId = cfg.modelId,
                provider = cfg.provider.name,
                baseUrl = cfg.baseUrl,
                profile = _profile.value,
                updatedAt = System.currentTimeMillis(),
                messages = emptyList(),
            )
        ) + _conversations.value
    }

    private fun generate(cfg: RemoteApiConnection) {
        if (_busy.value) return
        _messages.value = _messages.value + SavedRemoteMessage(role = "assistant", content = "")
        _busy.value = true
        val answerId = _messages.value.last().id
        val previous = _messages.value.dropLast(1)
        val systemPrompt = PROFILE_PROMPTS[_profile.value].orEmpty()
        val turns = buildList {
            if (systemPrompt.isNotBlank()) add(RemoteChatTurn("system", systemPrompt))
            previous.filter { it.content.isNotBlank() }.forEach { add(RemoteChatTurn(it.role, it.content)) }
        }
        answerJob = viewModelScope.launch {
            try {
                val key = settings.apiKey()
                var savedAt = System.currentTimeMillis()
                client.stream(cfg, key, turns).collect { chunk ->
                    _messages.value = _messages.value.map {
                        if (it.id == answerId) it.copy(content = it.content + chunk) else it
                    }
                    if (System.currentTimeMillis() - savedAt > 1_500L) {
                        persistCurrent()
                        savedAt = System.currentTimeMillis()
                    }
                }
            } catch (cancel: CancellationException) {
                _notice.value = "Resposta interrompida pelo usuário."
                throw cancel
            } catch (error: Exception) {
                _notice.value = error.message ?: "Falha ao consultar a API externa."
            } finally {
                _busy.value = false
                answerJob = null
                persistCurrent()
            }
        }
    }

    private fun persistCurrent() {
        val id = _currentId.value ?: return
        _conversations.value = _conversations.value.map { chat ->
            if (chat.id == id) chat.copy(
                messages = _messages.value,
                profile = _profile.value,
                updatedAt = System.currentTimeMillis(),
            ) else chat
        }.sortedByDescending { it.updatedAt }
        writeHistory()
    }

    private fun writeHistory() {
        val snapshot = _conversations.value
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { store.save(snapshot) }
                .onFailure { _notice.value = "Não foi possível salvar o histórico online." }
        }
    }

    class Factory(
        private val settings: ExternalApiSettings,
        private val store: ExternalApiChatStore,
        private val importer: AttachmentImporter,
        private val processor: AttachmentContentProcessor,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ExternalApiViewModel(settings, store, importer, processor) as T
    }

    companion object {
        val PROFILE_NAMES = listOf("Padrão", "Programador", "Professor", "Criativo")
        private val PROFILE_PROMPTS = mapOf(
            "Programador" to "Você é um assistente de programação. Produza código correto em blocos markdown com linguagem, explique como executar e destaque riscos de segurança.",
            "Professor" to "Você é um professor paciente. Explique conceitos por etapas, com exemplos claros.",
            "Criativo" to "Você é um assistente criativo, imaginativo e útil, sempre respeitando o pedido do usuário.",
        )
    }
}
