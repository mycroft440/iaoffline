package com.example.ialocal.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun ChatWithDeepThinkScreen(
    viewModel: ChatViewModel,
    onOpenConversation: (String) -> Unit,
    onOpenHistory: () -> Unit,
    onOpenModels: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        NexusChatScreen(
            viewModel = viewModel,
            onOpenConversation = onOpenConversation,
            onOpenHistory = onOpenHistory,
            onOpenModels = onOpenModels,
            onOpenSettings = onOpenSettings,
        )
        NexusProfileLibraryAction(
            viewModel = viewModel,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 70.dp, end = 12.dp),
        )
    }
}