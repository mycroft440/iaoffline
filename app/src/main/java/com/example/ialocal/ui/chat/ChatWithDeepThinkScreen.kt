package com.example.ialocal.ui.chat

import androidx.compose.runtime.Composable

@Composable
fun ChatWithDeepThinkScreen(
    viewModel: ChatViewModel,
    onOpenConversation: (String) -> Unit,
    onReplaceConversation: (String) -> Unit,
    onOpenHistory: () -> Unit,
    onOpenModels: () -> Unit,
    onOpenSettings: () -> Unit,
    adsEnabled: Boolean,
) {
    PixelPerfectHtmlChatScreen(
        viewModel = viewModel,
        onOpenConversation = onOpenConversation,
        onReplaceConversation = onReplaceConversation,
        onOpenHistory = onOpenHistory,
        onOpenModels = onOpenModels,
        onOpenSettings = onOpenSettings,
        adsEnabled = adsEnabled,
    )
}
