package com.example.ialocal.ui.chat

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ialocal.audio.AudioRecorder
import com.example.ialocal.data.AttachmentEntity
import com.example.ialocal.data.AttachmentType
import com.example.ialocal.data.MessageRole
import com.example.ialocal.data.MessageWithAttachments
import com.example.ialocal.data.PendingAttachment

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val conversation by viewModel.conversation.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val attachments by viewModel.pendingAttachments.collectAsStateWithLifecycle()
    val isGenerating by viewModel.isGenerating.collectAsStateWithLifecycle()
    val isProcessingAttachments by viewModel.isProcessingAttachments.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val agents by viewModel.agents.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val audioRecorder = remember { AudioRecorder(context) }
    var isRecording by remember { mutableStateOf(false) }
    var agentMenuOpen by remember { mutableStateOf(false) }
    var pendingAudioImport by remember { mutableStateOf<android.net.Uri?>(null) }

    val micPermission = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            val importUri = pendingAudioImport
            if (importUri != null) {
                pendingAudioImport = null
                viewModel.importFile(importUri)
            } else {
                runCatching {
                    audioRecorder.start()
                    isRecording = true
                }
            }
        } else {
            pendingAudioImport = null
        }
    }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            val isAudio = context.contentResolver.getType(uri)?.startsWith("audio/") == true
            val granted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED
            if (isAudio && !granted) {
                pendingAudioImport = uri
                micPermission.launch(Manifest.permission.RECORD_AUDIO)
            } else {
                viewModel.importFile(uri)
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { audioRecorder.cancel() }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    LaunchedEffect(error) {
        error?.let {
            snackbar.showSnackbar(it)
            viewModel.clearError()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        conversation?.title ?: "Conversa",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Voltar")
                    }
                },
                actions = {
                    IconButton(onClick = { agentMenuOpen = true }) {
                        Icon(Icons.Default.SmartToy, contentDescription = "Selecionar agente")
                    }
                    DropdownMenu(
                        expanded = agentMenuOpen,
                        onDismissRequest = { agentMenuOpen = false },
                    ) {
                        if (agents.isEmpty()) {
                            DropdownMenuItem(
                                text = { Text("Importe uma IA primeiro") },
                                onClick = { agentMenuOpen = false },
                                enabled = false,
                            )
                        } else {
                            agents.forEach { agent ->
                                val selected = conversation?.agentId == agent.id ||
                                    (conversation?.agentId == null && agent.isDefault)
                                DropdownMenuItem(
                                    text = {
                                        Text((if (selected) "✓ " else "") + agent.name)
                                    },
                                    onClick = {
                                        viewModel.selectAgent(agent.id)
                                        agentMenuOpen = false
                                    },
                                )
                            }
                        }
                    }
                },
            )
        },
        bottomBar = {
            ChatComposer(
                draft = draft,
                onDraftChange = viewModel::setDraft,
                attachments = attachments,
                onRemoveAttachment = viewModel::removePendingAttachment,
                isGenerating = isGenerating,
                isProcessingAttachments = isProcessingAttachments,
                isRecording = isRecording,
                onAttach = {
                    filePicker.launch(
                        arrayOf(
                            "text/plain",
                            "text/markdown",
                            "application/pdf",
                            "application/json",
                            "text/csv",
                            "audio/*",
                        )
                    )
                },
                onMic = {
                    if (isRecording) {
                        audioRecorder.stop()?.let(viewModel::addPendingAttachment)
                        isRecording = false
                    } else {
                        val granted = ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.RECORD_AUDIO,
                        ) == PackageManager.PERMISSION_GRANTED
                        if (granted) {
                            runCatching {
                                audioRecorder.start()
                                isRecording = true
                            }
                        } else {
                            micPermission.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    }
                },
                onSend = viewModel::send,
                onStop = viewModel::stopGeneration,
            )
        },
    ) { padding ->
        if (messages.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Comece uma conversa", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Você pode escrever, anexar um arquivo ou gravar áudio.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item { Spacer(Modifier.height(4.dp)) }
                items(messages, key = { it.message.id }) { item ->
                    MessageBubble(item)
                }
                if (isGenerating) {
                    item {
                        Row(
                            modifier = Modifier.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("IA pensando…", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
        }
    }
}

@Composable
private fun MessageBubble(item: MessageWithAttachments) {
    val isUser = item.message.role == MessageRole.USER.name
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = if (isUser) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
            modifier = Modifier.fillMaxWidth(0.86f),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    if (isUser) "Você" else "IA",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                )
                if (item.message.content.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(item.message.content)
                }
                if (item.attachments.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    item.attachments.forEach { attachment ->
                        StoredAttachmentChip(attachment)
                        Spacer(Modifier.height(4.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun StoredAttachmentChip(attachment: AttachmentEntity) {
    val isAudio = attachment.type == AttachmentType.AUDIO.name
    AssistChip(
        onClick = {},
        label = {
            Text(
                "${attachment.fileName} · ${formatBytes(attachment.sizeBytes)}",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        leadingIcon = {
            Icon(
                if (isAudio) Icons.Default.GraphicEq else Icons.Default.Description,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        },
    )
}

@Composable
private fun ChatComposer(
    draft: String,
    onDraftChange: (String) -> Unit,
    attachments: List<PendingAttachment>,
    onRemoveAttachment: (String) -> Unit,
    isGenerating: Boolean,
    isProcessingAttachments: Boolean,
    isRecording: Boolean,
    onAttach: () -> Unit,
    onMic: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    Surface(tonalElevation = 3.dp) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(horizontal = 8.dp, vertical = 8.dp),
        ) {
            if (attachments.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(attachments, key = { it.id }) { attachment ->
                        PendingAttachmentChip(
                            attachment = attachment,
                            onRemove = { onRemoveAttachment(attachment.id) },
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
            }

            if (isProcessingAttachments) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Processando anexo para a IA…", style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(4.dp))
            }

            if (isRecording) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.errorContainer,
                            RoundedCornerShape(12.dp),
                        )
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Mic, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Gravando áudio… toque no botão vermelho para finalizar.")
                }
                Spacer(Modifier.height(6.dp))
            }

            Row(verticalAlignment = Alignment.Bottom) {
                IconButton(onClick = onAttach, enabled = !isGenerating && !isRecording && !isProcessingAttachments) {
                    Icon(Icons.Default.AttachFile, contentDescription = "Anexar arquivo")
                }

                TextField(
                    value = draft,
                    onValueChange = onDraftChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Digite uma mensagem") },
                    maxLines = 5,
                    enabled = !isGenerating && !isProcessingAttachments,
                )

                IconButton(onClick = onMic, enabled = !isGenerating && !isProcessingAttachments) {
                    Icon(
                        if (isRecording) Icons.Default.Stop else Icons.Default.Mic,
                        contentDescription = if (isRecording) "Parar gravação" else "Gravar áudio",
                        tint = if (isRecording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    )
                }

                if (isGenerating) {
                    IconButton(onClick = onStop) {
                        Icon(Icons.Default.Stop, contentDescription = "Parar geração")
                    }
                } else {
                    IconButton(
                        onClick = onSend,
                        enabled = !isProcessingAttachments && !isRecording && (draft.isNotBlank() || attachments.isNotEmpty()),
                    ) {
                        Icon(Icons.Default.Send, contentDescription = "Enviar")
                    }
                }
            }
        }
    }
}

@Composable
private fun PendingAttachmentChip(
    attachment: PendingAttachment,
    onRemove: () -> Unit,
) {
    AssistChip(
        onClick = {},
        label = {
            Text(
                attachment.fileName,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        leadingIcon = {
            Icon(
                if (attachment.type == AttachmentType.AUDIO) Icons.Default.GraphicEq else Icons.Default.Description,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        },
        trailingIcon = {
            IconButton(onClick = onRemove, modifier = Modifier.size(24.dp)) {
                Icon(Icons.Default.Close, contentDescription = "Remover anexo", modifier = Modifier.size(16.dp))
            }
        },
    )
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1_024 -> "$bytes B"
    bytes < 1_048_576 -> "${bytes / 1_024} KB"
    else -> String.format("%.1f MB", bytes / 1_048_576.0)
}
