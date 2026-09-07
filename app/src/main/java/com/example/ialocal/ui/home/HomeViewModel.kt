package com.example.ialocal.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.ialocal.data.ChatRepository
import com.example.ialocal.data.ConversationListItem
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HomeViewModel(
    private val repository: ChatRepository,
) : ViewModel() {
    val conversations: StateFlow<List<ConversationListItem>> = repository.conversations.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )

    fun createConversation(onCreated: (String) -> Unit) {
        viewModelScope.launch {
            onCreated(repository.createConversation())
        }
    }

    fun rename(id: String, title: String) {
        viewModelScope.launch { repository.renameConversation(id, title) }
    }

    fun setPinned(id: String, pinned: Boolean) {
        viewModelScope.launch { repository.setPinned(id, pinned) }
    }

    fun delete(id: String) {
        viewModelScope.launch { repository.deleteConversation(id) }
    }

    class Factory(
        private val repository: ChatRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return HomeViewModel(repository) as T
        }
    }
}
