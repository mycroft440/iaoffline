package com.example.ialocal.ui.chat

import androidx.compose.runtime.Composable

@Composable
fun ChatWithDeepThinkScreen(
    viewModel: ChatViewModel,
    onOpenConversation: (String) -> Unit,
    onOpenHistory: () -> Unit,
    onBack: () -> Unit,
    onOpenModels: () -> Unit,
    onOpenCompilers: (String) -> Unit,
    installedLanguageIds: Set<String>,
    onOpenSettings: () -> Unit,
    adsEnabled: Boolean,
) {
    PixelPerfectHtmlChatScreen(
        viewModel = viewModel,
        onOpenConversation = onOpenConversation,
        onOpenHistory = onOpenHistory,
        onBack = onBack,
        onOpenModels = onOpenModels,
        onOpenCompilers = onOpenCompilers,
        installedLanguageIds = installedLanguageIds,
        onOpenSettings = onOpenSettings,
        adsEnabled = adsEnabled,
    )
}
