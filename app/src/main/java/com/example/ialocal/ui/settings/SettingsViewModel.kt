package com.example.ialocal.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.ialocal.data.ThemeMode
import com.example.ialocal.data.ThemeRepository
import com.example.ialocal.ui.codeeditor.CodeLanguagePackDescriptor
import com.example.ialocal.ui.codeeditor.CodeLanguagePackRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val repository: ThemeRepository,
    private val languagePacks: CodeLanguagePackRepository,
) : ViewModel() {
    val themeMode: StateFlow<ThemeMode> = repository.themeMode.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        ThemeMode.SYSTEM,
    )

    val installedLanguageIds: StateFlow<Set<String>> = languagePacks.installedIds
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            languagePacks.installedIds.value,
        )

    val availableLanguagePacks: List<CodeLanguagePackDescriptor>
        get() = CodeLanguagePackRepository.AVAILABLE_PACKS

    private val _busyLanguageId = MutableStateFlow<String?>(null)
    val busyLanguageId: StateFlow<String?> = _busyLanguageId.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    fun setTheme(mode: ThemeMode) {
        viewModelScope.launch { repository.setThemeMode(mode) }
    }

    fun downloadLanguage(id: String) {
        if (_busyLanguageId.value != null) return
        viewModelScope.launch {
            _busyLanguageId.value = id
            _statusMessage.value = null
            runCatching { languagePacks.download(id) }
                .onSuccess { pack ->
                    _statusMessage.value = pack.displayName + " baixado para o editor."
                }
                .onFailure { error ->
                    _statusMessage.value = "Falha ao baixar linguagem: " +
                        (error.message ?: "erro desconhecido")
                }
            _busyLanguageId.value = null
        }
    }

    fun removeLanguage(id: String) {
        if (_busyLanguageId.value != null) return
        viewModelScope.launch {
            _busyLanguageId.value = id
            _statusMessage.value = null
            runCatching { languagePacks.remove(id) }
                .onSuccess {
                    _statusMessage.value = "Pacote de linguagem removido."
                }
                .onFailure { error ->
                    _statusMessage.value = "Falha ao remover linguagem: " +
                        (error.message ?: "erro desconhecido")
                }
            _busyLanguageId.value = null
        }
    }

    fun clearStatusMessage() {
        _statusMessage.value = null
    }

    class Factory(
        private val repository: ThemeRepository,
        private val languagePacks: CodeLanguagePackRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SettingsViewModel(repository, languagePacks) as T
    }
}
