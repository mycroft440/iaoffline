package com.example.ialocal.ui.chat

import androidx.compose.runtime.Composable

@Composable
fun ChatWithDeepThinkScreen(
    viewModel: ChatViewModel,
    onOpenConversation: (String) -> Unit,
    onOpenHistory: () -> Unit,
    onOpenModels: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    NexusChatScreen(
        viewModel = viewModel,
        onOpenConversation = onOpenConversation,
        onOpenHistory = onOpenHistory,
        onOpenModels = onOpenModels,
        onOpenSettings = onOpenSettings,
    )
}
