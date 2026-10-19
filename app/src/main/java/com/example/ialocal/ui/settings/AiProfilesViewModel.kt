package com.example.ialocal.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.ialocal.models.AiProfile
import com.example.ialocal.models.ModelRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AiProfilesViewModel(
    private val repository: ModelRepository,
) : ViewModel() {
    val profiles: StateFlow<List<AiProfile>> = repository.profiles
    val selectedProfile: StateFlow<AiProfile> = repository.selectedProfile

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    fun select(profile: AiProfile) {
        runCatching { repository.setSelectedProfile(profile) }
            .onFailure { _statusMessage.value = it.message ?: "Não foi possível selecionar este perfil." }
    }

    /** Creates a profile when [editingId] is null, otherwise changes that one. */
    fun save(editingId: String?, name: String, description: String, systemPrompt: String) = launchAction {
        if (editingId == null) {
            repository.createCustomProfile(name, description, systemPrompt)
            "Perfil ${name.trim()} criado."
        } else {
            repository.updateCustomProfile(editingId, name, description, systemPrompt)
            "Perfil ${name.trim()} salvo."
        }
    }

    fun delete(profile: AiProfile) = launchAction {
        repository.deleteCustomProfile(profile.id)
        "Perfil ${profile.displayName} excluído."
    }

    fun clearStatusMessage() {
        _statusMessage.value = null
    }

    /** Runs one change at a time and reports its outcome as [statusMessage]. */
    private fun launchAction(action: suspend () -> String) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            _statusMessage.value = runCatching { action() }
                .getOrElse { it.message ?: "Não foi possível salvar o perfil." }
            _busy.value = false
        }
    }

    class Factory(
        private val repository: ModelRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            AiProfilesViewModel(repository) as T
    }
}
