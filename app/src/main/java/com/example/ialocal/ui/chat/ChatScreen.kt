package com.example.ialocal.ui.chat

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ialocal.audio.AudioRecorder
import com.example.ialocal.data.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onOpenConversation: (String) -> Unit,
    onOpenHistory: () -> Unit,
    onOpenModels: () -> Unit,
) {
    val context = LocalContext.current
    val conversation by viewModel.conversation.collectAsStateWithLifecycle()
    val conversations by viewModel.conversations.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val attachments by viewModel.pendingAttachments.collectAsStateWithLifecycle()
    val isGenerating by viewModel.isGenerating.collectAsStateWithLifecycle()
    val isProcessingAttachments by viewModel.isProcessingAttachments.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val agents by viewModel.agents.collectAsStateWithLifecycle()
    val models by viewModel.models.collectAsStateWithLifecycle()

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val audioRecorder = remember { AudioRecorder(context) }
    var isRecording by remember { mutableStateOf(false) }
    var modelMenuOpen by remember { mutableStateOf(false) }
    var agentSettingsOpen by remember { mutableStateOf(false) }
    var pendingAudioImport by remember { mutableStateOf<android.net.Uri?>(null) }

    val readyModels = remember(models) {
        models.filter { it.verificationStatus == ModelVerificationStatus.VERIFIED.name }
    }
    val selectedAgent = agents.firstOrNull { it.id == conversation?.agentId }
        ?: agents.firstOrNull { it.isDefault }
    val selectedModel = readyModels.firstOrNull { it.id == selectedAgent?.modelId }
        ?: readyModels.firstOrNull { it.isActive }
        ?: readyModels.firstOrNull()

    val micPermission = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            pendingAudioImport?.let {
                pendingAudioImport = null
                viewModel.importFile(it)
            } ?: runCatching {
                audioRecorder.start()
                isRecording = true
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
            val hasMicPermission = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED
            if (isAudio && !hasMicPermission) {
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
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    LaunchedEffect(error) {
        error?.let {
            snackbar.showSnackbar(it)
            viewModel.clearError()
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ChatDrawer(
                conversations = conversations,
                currentConversationId = conversation?.id,
                agentSettingsEnabled = selectedAgent != null,
                onNewConversation = {
                    scope.launch {
                        drawerState.close()
                        viewModel.createConversation(onOpenConversation)
                    }
                },
                onOpenConversation = { id ->
                    scope.launch {
                        drawerState.close()
                        onOpenConversation(id)
                    }
                },
                onOpenHistory = {
                    scope.launch {
                        drawerState.close()
                        onOpenHistory()
                    }
                },
                onOpenAgentSettings = {
                    scope.launch {
                        drawerState.close()
                        if (selectedAgent != null) agentSettingsOpen = true
                    }
                },
                onOpenModels = {
                    scope.launch {
                        drawerState.close()
                        onOpenModels()
                    }
                },
            )
        },
    ) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "Abrir histórico e opções")
                        }
                    },
                    title = {
                        Box {
                            TextButton(onClick = { modelMenuOpen = true }) {
                                Column(horizontalAlignment = Alignment.Start) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            selectedModel?.name ?: "Selecionar modelo",
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurface,
                                        )
                                        Spacer(Modifier.width(3.dp))
                                        Icon(
                                            Icons.Default.KeyboardArrowDown,
                                            contentDescription = "Trocar modelo",
                                            modifier = Modifier.size(20.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Text(
                                        if (selectedModel != null) "Offline" else "Nenhum modelo pronto",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            DropdownMenu(
                                expanded = modelMenuOpen,
                                onDismissRequest = { modelMenuOpen = false },
                            ) {
                                if (readyModels.isEmpty()) {
                                    DropdownMenuItem(
                                        text = { Text("Nenhum modelo baixado e verificado") },
                                        onClick = {},
                                        enabled = false,
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Gerenciar modelos offline") },
                                        leadingIcon = { Icon(Icons.Default.SmartToy, null) },
                                        onClick = {
                                            modelMenuOpen = false
                                            onOpenModels()
                                        },
                                    )
                                } else {
                                    readyModels.forEach { model ->
                                        DropdownMenuItem(
                                            text = {
                                                Column {
                                                    Text(model.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                    Text(
                                                        "Disponível offline",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    )
                                                }
                                            },
                                            leadingIcon = {
                                                if (model.id == selectedModel?.id) {
                                                    Icon(Icons.Default.Check, contentDescription = "Selecionado")
                                                } else {
                                                    Spacer(Modifier.size(24.dp))
                                                }
                                            },
                                            onClick = {
                                                viewModel.selectModel(model.id)
                                                modelMenuOpen = false
                                            },
                                        )
                                    }
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
                                "text/plain", "text/markdown", "application/pdf",
                                "application/json", "text/csv", "audio/*",
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
                EmptyChat(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    modelName = selectedModel?.name,
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(horizontal = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    item { Spacer(Modifier.height(8.dp)) }
                    items(messages, key = { it.message.id }) { MessageBubble(it) }
                    if (isGenerating) {
                        item {
                            Row(
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "Pensando…",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    item { Spacer(Modifier.height(10.dp)) }
                }
            }
        }
    }

    if (agentSettingsOpen && selectedAgent != null) {
        AgentSettingsDialog(
            agent = selectedAgent,
            onDismiss = { agentSettingsOpen = false },
            onSave = {
                viewModel.updateAgent(it)
                agentSettingsOpen = false
            },
        )
    }
}

@Composable
private fun ChatDrawer(
    conversations: List<ConversationListItem>,
    currentConversationId: String?,
    agentSettingsEnabled: Boolean,
    onNewConversation: () -> Unit,
    onOpenConversation: (String) -> Unit,
    onOpenHistory: () -> Unit,
    onOpenAgentSettings: () -> Unit,
    onOpenModels: () -> Unit,
) {
    ModalDrawerSheet(modifier = Modifier.width(320.dp)) {
        Column(Modifier.fillMaxSize().padding(horizontal = 10.dp)) {
            Spacer(Modifier.height(14.dp))
            Text(
                "I.A Off-line",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            NavigationDrawerItem(
                label = { Text("Nova conversa") },
                selected = false,
                icon = { Icon(Icons.Default.Add, null) },
                onClick = onNewConversation,
            )
            NavigationDrawerItem(
                label = { Text("Configurações do agente") },
                selected = false,
                icon = { Icon(Icons.Default.Tune, null) },
                onClick = { if (agentSettingsEnabled) onOpenAgentSettings() },
                modifier = Modifier.alpha(if (agentSettingsEnabled) 1f else 0.45f),
            )
            NavigationDrawerItem(
                label = { Text("Modelos offline") },
                selected = false,
                icon = { Icon(Icons.Default.SmartToy, null) },
                onClick = onOpenModels,
            )
            HorizontalDivider(Modifier.padding(vertical = 10.dp))
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Histórico", modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                TextButton(onClick = onOpenHistory) { Text("Ver tudo") }
            }
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(conversations.take(12), key = { it.id }) { item ->
                    NavigationDrawerItem(
                        label = {
                            Column {
                                Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                item.lastMessage?.takeIf { it.isNotBlank() }?.let {
                                    Text(
                                        it,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        },
                        selected = item.id == currentConversationId,
                        icon = { Icon(Icons.Default.ChatBubbleOutline, null) },
                        onClick = { onOpenConversation(item.id) },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun EmptyChat(modifier: Modifier, modelName: String?) {
    Column(
        modifier = modifier.padding(horizontal = 28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(58.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Default.AutoAwesome, null, modifier = Modifier.size(30.dp))
            }
        }
        Spacer(Modifier.height(18.dp))
        Text(
            "Como posso ajudar?",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(7.dp))
        Text(
            modelName?.let { "$it · executando no aparelho" }
                ?: "Baixe e verifique um modelo para começar",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
            shape = RoundedCornerShape(22.dp),
            color = if (isUser) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent,
            modifier = if (isUser) Modifier.widthIn(max = 320.dp) else Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(if (isUser) 13.dp else 4.dp)) {
                if (item.message.content.isNotBlank()) {
                    Text(item.message.content, style = MaterialTheme.typography.bodyLarge)
                }
                if (item.attachments.isNotEmpty()) {
                    Spacer(Modifier.height(7.dp))
                    item.attachments.forEach { attachment ->
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
                                    if (attachment.type == AttachmentType.AUDIO.name) Icons.Default.GraphicEq else Icons.Default.Description,
                                    null,
                                    Modifier.size(18.dp),
                                )
                            },
                        )
                    }
                }
            }
        }
    }
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
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            if (attachments.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(attachments, key = { it.id }) { attachment ->
                        AssistChip(
                            onClick = {},
                            label = { Text(attachment.fileName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            leadingIcon = {
                                Icon(
                                    if (attachment.type == AttachmentType.AUDIO) Icons.Default.GraphicEq else Icons.Default.Description,
                                    null,
                                    Modifier.size(18.dp),
                                )
                            },
                            trailingIcon = {
                                IconButton(
                                    onClick = { onRemoveAttachment(attachment.id) },
                                    modifier = Modifier.size(24.dp),
                                ) {
                                    Icon(Icons.Default.Close, "Remover anexo", Modifier.size(16.dp))
                                }
                            },
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
            }

            if (isProcessingAttachments) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Processando anexo…", style = MaterialTheme.typography.bodySmall)
                }
            }

            if (isRecording) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp)
                        .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(18.dp))
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Mic, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Gravando áudio… toque em parar para finalizar.", style = MaterialTheme.typography.bodySmall)
                }
            }

            Surface(
                shape = RoundedCornerShape(30.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 2.dp,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 5.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    IconButton(
                        onClick = onAttach,
                        enabled = !isGenerating && !isRecording && !isProcessingAttachments,
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Anexar arquivo")
                    }

                    TextField(
                        value = draft,
                        onValueChange = onDraftChange,
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Pergunte qualquer coisa") },
                        maxLines = 5,
                        enabled = !isGenerating && !isProcessingAttachments,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            disabledIndicatorColor = Color.Transparent,
                        ),
                    )

                    IconButton(onClick = onMic, enabled = !isGenerating && !isProcessingAttachments) {
                        Icon(
                            if (isRecording) Icons.Default.Stop else Icons.Default.Mic,
                            contentDescription = if (isRecording) "Parar gravação" else "Gravar áudio",
                            tint = if (isRecording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    FilledIconButton(
                        onClick = if (isGenerating) onStop else onSend,
                        enabled = isGenerating || (!isProcessingAttachments && !isRecording && (draft.isNotBlank() || attachments.isNotEmpty())),
                        modifier = Modifier.size(44.dp),
                    ) {
                        Icon(
                            if (isGenerating) Icons.Default.Stop else Icons.Default.Send,
                            contentDescription = if (isGenerating) "Parar geração" else "Enviar",
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AgentSettingsDialog(
    agent: AgentEntity,
    onDismiss: () -> Unit,
    onSave: (AgentEntity) -> Unit,
) {
    var name by remember(agent.id) { mutableStateOf(agent.name) }
    var prompt by remember(agent.id) { mutableStateOf(agent.systemPrompt) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Configurações do agente") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                TextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nome do agente") },
                    singleLine = true,
                )
                TextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    label = { Text("Instruções do agente") },
                    minLines = 5,
                    maxLines = 10,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        agent.copy(
                            name = name.trim().ifBlank { agent.name },
                            systemPrompt = prompt.trim(),
                        )
                    )
                },
            ) { Text("Salvar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1_024 -> "$bytes B"
    bytes < 1_048_576 -> "${bytes / 1_024} KB"
    else -> String.format("%.1f MB", bytes / 1_048_576.0)
}
