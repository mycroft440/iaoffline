package com.example.ialocal.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ialocal.data.ConversationListItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onOpenConversation: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenModels: () -> Unit,
) {
    val conversations by viewModel.conversations.collectAsStateWithLifecycle()
    var renameTarget by remember { mutableStateOf<ConversationListItem?>(null) }
    var deleteTarget by remember { mutableStateOf<ConversationListItem?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("IA Local") },
                actions = {
                    IconButton(onClick = { viewModel.createConversation(onOpenConversation) }) {
                        Icon(Icons.Default.Add, contentDescription = "Nova conversa")
                    }
                    IconButton(onClick = onOpenModels) {
                        Icon(Icons.Default.SmartToy, contentDescription = "Modelos e API")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Configurações")
                    }
                },
            )
        },
    ) { padding ->
        if (conversations.isEmpty()) {
            EmptyHome(
                modifier = Modifier.padding(padding),
                onNewChat = { viewModel.createConversation(onOpenConversation) },
            )
        } else {
            val pinned = conversations.filter { it.isPinned }
            val recent = conversations.filterNot { it.isPinned }

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (pinned.isNotEmpty()) {
                    item { SectionTitle("FIXADAS") }
                    items(pinned, key = { it.id }) { conversation ->
                        ConversationCard(
                            conversation = conversation,
                            onOpen = { onOpenConversation(conversation.id) },
                            onRename = { renameTarget = conversation },
                            onPin = { viewModel.setPinned(conversation.id, false) },
                            onDelete = { deleteTarget = conversation },
                        )
                    }
                }

                if (recent.isNotEmpty()) {
                    item { SectionTitle("RECENTES") }
                    items(recent, key = { it.id }) { conversation ->
                        ConversationCard(
                            conversation = conversation,
                            onOpen = { onOpenConversation(conversation.id) },
                            onRename = { renameTarget = conversation },
                            onPin = { viewModel.setPinned(conversation.id, true) },
                            onDelete = { deleteTarget = conversation },
                        )
                    }
                }

                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }

    renameTarget?.let { target ->
        RenameDialog(
            currentName = target.title,
            onDismiss = { renameTarget = null },
            onSave = { newName ->
                viewModel.rename(target.id, newName)
                renameTarget = null
            },
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Excluir conversa?") },
            text = { Text("\"${target.title}\" e todo o histórico serão removidos.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.delete(target.id)
                        deleteTarget = null
                    },
                ) { Text("Excluir") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancelar") }
            },
        )
    }
}

@Composable
private fun EmptyHome(
    modifier: Modifier = Modifier,
    onNewChat: () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Nenhuma conversa ainda", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text("Crie a primeira conversa para começar a testar o chat.")
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = onNewChat) { Text("Nova conversa") }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 2.dp),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun ConversationCard(
    conversation: ConversationListItem,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onPin: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .fillMaxWidth()
            .clickable(onClick = onOpen),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 14.dp, bottom = 14.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (conversation.isPinned) {
                Icon(
                    Icons.Filled.PushPin,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 10.dp),
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = conversation.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = conversation.lastMessage ?: "Conversa vazia",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = formatTime(conversation.updatedAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Column {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Menu da conversa")
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("Renomear") },
                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onRename()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(if (conversation.isPinned) "Desafixar" else "Fixar") },
                        leadingIcon = {
                            Icon(
                                if (conversation.isPinned) Icons.Outlined.PushPin else Icons.Filled.PushPin,
                                contentDescription = null,
                            )
                        },
                        onClick = {
                            menuOpen = false
                            onPin()
                        },
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("Excluir") },
                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun RenameDialog(
    currentName: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var value by remember(currentName) { mutableStateOf(currentName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Renomear conversa") },
        text = {
            TextField(
                value = value,
                onValueChange = { value = it.take(80) },
                singleLine = true,
                label = { Text("Nome") },
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(value) }) { Text("Salvar") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        },
    )
}

private fun formatTime(timestamp: Long): String {
    return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
}
