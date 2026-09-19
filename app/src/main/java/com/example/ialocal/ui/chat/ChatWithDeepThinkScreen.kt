package com.example.ialocal.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
    var menuExpanded by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        NexusChatScreen(
            viewModel = viewModel,
            onOpenConversation = onOpenConversation,
            onOpenHistory = onOpenHistory,
            onOpenModels = onOpenModels,
            onOpenSettings = onOpenSettings,
        )

        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(top = 8.dp, start = 4.dp),
        ) {
            IconButton(onClick = { menuExpanded = true }) {
                Icon(
                    imageVector = Icons.Default.Menu,
                    contentDescription = "Abrir menu do chat",
                )
            }

            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
            ) {
                DropdownMenuItem(
                    text = { Text("Histórico") },
                    onClick = {
                        menuExpanded = false
                        onOpenHistory()
                    },
                )
                DropdownMenuItem(
                    text = { Text("Modelos & API") },
                    onClick = {
                        menuExpanded = false
                        onOpenModels()
                    },
                )
                DropdownMenuItem(
                    text = { Text("Configurações") },
                    onClick = {
                        menuExpanded = false
                        onOpenSettings()
                    },
                )
            }
        }

        NexusProfileLibraryAction(
            viewModel = viewModel,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 70.dp, end = 12.dp),
        )
    }
}
