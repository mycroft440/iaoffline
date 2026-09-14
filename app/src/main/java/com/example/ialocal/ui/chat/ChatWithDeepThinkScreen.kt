package com.example.ialocal.ui.chat

import androidx.compose.runtime.Composable

// Adds optional controls around the existing chat screen.
@Composable
fun ChatWithDeepThinkScreen(
    viewModel: ChatViewModel,
    onOpenConversation: (String) -> Unit,
    onOpenHistory: () -> Unit,
    onOpenModels: () -> Unit,
) {
    ChatScreen(
        viewModel = viewModel,
        onOpenConversation = onOpenConversation,
        onOpenHistory = onOpenHistory,
        onOpenModels = onOpenModels,
    )
}
