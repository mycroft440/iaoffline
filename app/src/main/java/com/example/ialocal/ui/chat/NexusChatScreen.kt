package com.example.ialocal.ui.chat

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ialocal.audio.AudioRecorder
import com.example.ialocal.data.AgentEntity
import com.example.ialocal.data.AttachmentType
import com.example.ialocal.data.ConversationListItem
import com.example.ialocal.data.MessageRole
import com.example.ialocal.data.MessageWithAttachments
import com.example.ialocal.data.ModelVerificationStatus
import com.example.ialocal.data.PendingAttachment
import com.example.ialocal.models.DeepThinkSupport
import com.example.ialocal.models.ModelRepository
import com.example.ialocal.ui.branding.ProviderLogo
import com.example.ialocal.ui.branding.brandName
import com.example.ialocal.ui.branding.catalogProvider
import com.example.ialocal.ui.theme.NexusColors
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NexusChatScreen(
    viewModel: ChatViewModel,
    onOpenConversation: (String) -> Unit,
    onOpenHistory: () -> Unit,
    onOpenModels: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val conversation by viewModel.conversation.collectAsStateWithLifecycle()
    val conversations by viewModel.conversations.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val attachments by viewModel.pendingAttachments.collectAsStateWithLifecycle()
    val isGenerating by viewModel.isGenerating.collectAsStateWithLifecycle()
    val isProcessingAttachments by viewModel.isProcessingAttachments.collectAsStateWithLifecycle()
    val queuedMessages by viewModel.queuedMessages.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val agents by viewModel.agents.collectAsStateWithLifecycle()
    val models by viewModel.models.collectAsStateWithLifecycle()
    val agentUsageCounts by viewModel.agentUsageCounts.collectAsStateWithLifecycle()
    val selectedAgentId by viewModel.selectedAgentId.collectAsStateWithLifecycle()

    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val listState = rememberLazyListState()
    val audioRecorder = remember { AudioRecorder(context) }

    var isRecording by remember { mutableStateOf(false) }
    var modelMenuOpen by remember { mutableStateOf(false) }
    var personaMenuOpen by remember { mutableStateOf(false) }
    var createAgentOpen by remember { mutableStateOf(false) }
    var editAgentOpen by remember { mutableStateOf(false) }
    var pendingDefaultAgent by remember { mutableStateOf<AgentEntity?>(null) }
    var pendingAudioImport by remember { mutableStateOf<android.net.Uri?>(null) }

    val readyModels = remember(models) {
        models.filter { it.verificationStatus == ModelVerificationStatus.VERIFIED.name }
    }
    val selectedAgent = agents.firstOrNull { it.id == selectedAgentId }
        ?: agents.firstOrNull { it.id == conversation?.agentId }
        ?: agents.firstOrNull { it.isDefault }
    val selectedModel = readyModels.firstOrNull { it.id == selectedAgent?.modelId }
        ?: readyModels.firstOrNull { it.isActive }
        ?: readyModels.firstOrNull()
    val modelAgents = remember(agents, selectedModel?.id) {
        val modelId = selectedModel?.id
        if (modelId == null) emptyList() else agents.filter { it.modelId == modelId }
    }
    val deepThinkSupported = selectedModel?.let { DeepThinkSupport.capability(it).supported } == true

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

    val onMic: () -> Unit = {
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
    }

    val sidebar: @Composable () -> Unit = {
        NexusSidebar(
            conversations = conversations,
            currentConversationId = conversation?.id,
            selectedModelReady = selectedModel != null,
            onNewConversation = {
                viewModel.createConversation(onOpenConversation)
                scope.launch { drawerState.close() }
            },
            onOpenConversation = { id ->
                onOpenConversation(id)
                scope.launch { drawerState.close() }
            },
            onOpenHistory = {
                onOpenHistory()
                scope.launch { drawerState.close() }
            },
            onOpenModels = {
                onOpenModels()
                scope.launch { drawerState.close() }
            },
            onOpenSettings = {
                onOpenSettings()
                scope.launch { drawerState.close() }
            },
        )
    }

    Scaffold(
        containerColor = NexusColors.Surface900,
        snackbarHost = { SnackbarHost(snackbar) },
    ) { outerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(outerPadding),
        ) {
            val wide = maxWidth >= 840.dp
            if (wide) {
                Row(Modifier.fillMaxSize()) {
                    Box(Modifier.width(288.dp).fillMaxHeight()) { sidebar() }
                    NexusChatBody(
                        modifier = Modifier.weight(1f),
                        showMenuButton = false,
                        conversationTitle = conversation?.title ?: "Nova Conversa",
                        selectedAgent = selectedAgent,
                        selectedModel = selectedModel,
                        readyModels = readyModels,
                        modelAgents = modelAgents,
                        modelMenuOpen = modelMenuOpen,
                        personaMenuOpen = personaMenuOpen,
                        onModelMenuChange = { modelMenuOpen = it },
                        onPersonaMenuChange = { personaMenuOpen = it },
                        onOpenMenu = {},
                        onSelectModel = viewModel::selectModel,
                        onSelectAgent = {
                            viewModel.selectAgent(it.id)
                            pendingDefaultAgent = it
                        },
                        onEditAgent = { editAgentOpen = true },
                        onCreateAgent = { createAgentOpen = true },
                        onOpenModels = onOpenModels,
                        messages = messages,
                        listState = listState,
                        isGenerating = isGenerating,
                        draft = draft,
                        attachments = attachments,
                        queuedMessages = queuedMessages,
                        isProcessingAttachments = isProcessingAttachments,
                        isRecording = isRecording,
                        deepThinkSupported = deepThinkSupported,
                        onDraftChange = viewModel::setDraft,
                        onAttach = {
                            filePicker.launch(arrayOf("text/plain", "text/markdown", "application/pdf", "application/json", "text/csv", "audio/*"))
                        },
                        onMic = onMic,
                        onSend = viewModel::send,
                        onStop = viewModel::stopGeneration,
                        onRerun = viewModel::rerunAssistant,
                        onRemoveAttachment = viewModel::removePendingAttachment,
                        onRemoveQueuedMessage = viewModel::removeQueuedMessage,
                        onSuggestion = { prompt -> viewModel.setDraft(prompt) },
                    )
                }
            } else {
                ModalNavigationDrawer(
                    drawerState = drawerState,
                    drawerContent = {
                        ModalDrawerSheet(
                            modifier = Modifier.width(288.dp),
                            drawerContainerColor = NexusColors.Surface850,
                        ) { sidebar() }
                    },
                ) {
                    NexusChatBody(
                        modifier = Modifier.fillMaxSize(),
                        showMenuButton = true,
                        conversationTitle = conversation?.title ?: "Nova Conversa",
                        selectedAgent = selectedAgent,
                        selectedModel = selectedModel,
                        readyModels = readyModels,
                        modelAgents = modelAgents,
                        modelMenuOpen = modelMenuOpen,
                        personaMenuOpen = personaMenuOpen,
                        onModelMenuChange = { modelMenuOpen = it },
                        onPersonaMenuChange = { personaMenuOpen = it },
                        onOpenMenu = { scope.launch { drawerState.open() } },
                        onSelectModel = viewModel::selectModel,
                        onSelectAgent = {
                            viewModel.selectAgent(it.id)
                            pendingDefaultAgent = it
                        },
                        onEditAgent = { editAgentOpen = true },
                        onCreateAgent = { createAgentOpen = true },
                        onOpenModels = onOpenModels,
                        messages = messages,
                        listState = listState,
                        isGenerating = isGenerating,
                        draft = draft,
                        attachments = attachments,
                        queuedMessages = queuedMessages,
                        isProcessingAttachments = isProcessingAttachments,
                        isRecording = isRecording,
                        deepThinkSupported = deepThinkSupported,
                        onDraftChange = viewModel::setDraft,
                        onAttach = {
                            filePicker.launch(arrayOf("text/plain", "text/markdown", "application/pdf", "application/json", "text/csv", "audio/*"))
                        },
                        onMic = onMic,
                        onSend = viewModel::send,
                        onStop = viewModel::stopGeneration,
                        onRerun = viewModel::rerunAssistant,
                        onRemoveAttachment = viewModel::removePendingAttachment,
                        onRemoveQueuedMessage = viewModel::removeQueuedMessage,
                        onSuggestion = { prompt -> viewModel.setDraft(prompt) },
                    )
                }
            }
        }
    }

    if (createAgentOpen && selectedModel != null) {
        NexusAgentDialog(
            title = "Criar Novo Perfil de IA",
            initialName = "",
            initialPrompt = "",
            onDismiss = { createAgentOpen = false },
            onSave = { name, prompt ->
                viewModel.createAgentProfile(
                    modelId = selectedModel.id,
                    name = name,
                    systemPrompt = prompt,
                ) { created ->
                    createAgentOpen = false
                    pendingDefaultAgent = created
                }
            },
        )
    }

    if (editAgentOpen && selectedAgent != null) {
        NexusAgentDialog(
            title = "Configurações do Perfil",
            initialName = selectedAgent.name,
            initialPrompt = selectedAgent.systemPrompt,
            onDismiss = { editAgentOpen = false },
            onSave = { name, prompt ->
                viewModel.updateAgent(selectedAgent.copy(name = name, systemPrompt = prompt))
                editAgentOpen = false
            },
        )
    }

    pendingDefaultAgent?.let { agent ->
        AlertDialog(
            onDismissRequest = { pendingDefaultAgent = null },
            containerColor = NexusColors.Surface850,
            title = { Text("Perfil ativo") },
            text = {
                Text("${agent.name} está ativo nesta conversa. Deseja defini-lo também como perfil padrão?")
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setDefaultAgent(agent.id)
                    pendingDefaultAgent = null
                }) { Text("Definir padrão") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDefaultAgent = null }) { Text("Agora não") }
            },
        )
    }
}

@Composable
private fun NexusSidebar(
    conversations: List<ConversationListItem>,
    currentConversationId: String?,
    selectedModelReady: Boolean,
    onNewConversation: () -> Unit,
    onOpenConversation: (String) -> Unit,
    onOpenHistory: () -> Unit,
    onOpenModels: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NexusColors.Surface850),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.size(34.dp),
                shape = RoundedCornerShape(12.dp),
                color = NexusColors.BrandStrong,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                }
            }
            Spacer(Modifier.width(10.dp))
            Text(
                "NexusAI Studio",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = NexusColors.TextPrimary,
            )
        }
        HorizontalDivider(color = NexusColors.BorderSoft)

        Button(
            onClick = onNewConversation,
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = NexusColors.BrandStrong),
            shape = RoundedCornerShape(12.dp),
        ) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(8.dp))
            Text("Nova Conversa", fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(conversations, key = { it.id }) { item ->
                val active = item.id == currentConversationId
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenConversation(item.id) },
                    color = if (active) NexusColors.Surface750 else Color.Transparent,
                    shape = RoundedCornerShape(12.dp),
                    border = if (active) BorderStroke(1.dp, NexusColors.Border) else null,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.ChatBubbleOutline,
                            contentDescription = null,
                            tint = if (active) NexusColors.Brand else NexusColors.TextMuted,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                item.title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = if (active) NexusColors.TextPrimary else NexusColors.TextSecondary,
                            )
                            item.lastMessage?.takeIf { it.isNotBlank() }?.let {
                                Text(
                                    it,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    fontSize = 10.sp,
                                    color = NexusColors.TextMuted,
                                )
                            }
                        }
                    }
                }
            }
        }

        HorizontalDivider(color = NexusColors.BorderSoft)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(NexusColors.Surface900.copy(alpha = 0.55f))
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            NexusSidebarAction(Icons.Default.Memory, "Modelos & API", onOpenModels)
            NexusSidebarAction(Icons.Default.Settings, "Configurações", onOpenSettings)
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (selectedModelReady) NexusColors.Emerald else NexusColors.TextMuted)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (selectedModelReady) "Pronto · modelo offline verificado" else "Aguardando modelo verificado",
                    fontSize = 10.sp,
                    color = NexusColors.TextMuted,
                )
            }
        }
    }
}

@Composable
private fun NexusSidebarAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = NexusColors.Brand, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(10.dp))
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = NexusColors.TextSecondary)
    }
}

@Composable
private fun NexusChatBody(
    modifier: Modifier,
    showMenuButton: Boolean,
    conversationTitle: String,
    selectedAgent: AgentEntity?,
    selectedModel: com.example.ialocal.data.AiModelEntity?,
    readyModels: List<com.example.ialocal.data.AiModelEntity>,
    modelAgents: List<AgentEntity>,
    modelMenuOpen: Boolean,
    personaMenuOpen: Boolean,
    onModelMenuChange: (Boolean) -> Unit,
    onPersonaMenuChange: (Boolean) -> Unit,
    onOpenMenu: () -> Unit,
    onSelectModel: (String) -> Unit,
    onSelectAgent: (AgentEntity) -> Unit,
    onEditAgent: () -> Unit,
    onCreateAgent: () -> Unit,
    onOpenModels: () -> Unit,
    messages: List<MessageWithAttachments>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    isGenerating: Boolean,
    draft: String,
    attachments: List<PendingAttachment>,
    queuedMessages: List<QueuedChatMessage>,
    isProcessingAttachments: Boolean,
    isRecording: Boolean,
    deepThinkSupported: Boolean,
    onDraftChange: (String) -> Unit,
    onAttach: () -> Unit,
    onMic: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onRerun: (String) -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onRemoveQueuedMessage: (Int) -> Unit,
    onSuggestion: (String) -> Unit,
) {
    Column(modifier.background(NexusColors.Surface900)) {
        NexusTopBar(
            showMenuButton = showMenuButton,
            conversationTitle = conversationTitle,
            selectedAgent = selectedAgent,
            selectedModel = selectedModel,
            readyModels = readyModels,
            modelAgents = modelAgents,
            modelMenuOpen = modelMenuOpen,
            personaMenuOpen = personaMenuOpen,
            onModelMenuChange = onModelMenuChange,
            onPersonaMenuChange = onPersonaMenuChange,
            onOpenMenu = onOpenMenu,
            onSelectModel = onSelectModel,
            onSelectAgent = onSelectAgent,
            onEditAgent = onEditAgent,
            onCreateAgent = onCreateAgent,
            onOpenModels = onOpenModels,
        )

        if (messages.isEmpty()) {
            NexusWelcome(
                modifier = Modifier.weight(1f),
                personaName = selectedAgent?.name ?: "Assistente Geral",
                deepThinkSupported = deepThinkSupported,
                onSuggestion = onSuggestion,
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 22.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp),
            ) {
                items(messages, key = { it.message.id }) { item ->
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        NexusMessage(
                            item = item,
                            personaName = selectedAgent?.name,
                            canRerun = !isGenerating,
                            onRerun = { onRerun(item.message.id) },
                            modifier = Modifier.fillMaxWidth().widthIn(max = 780.dp),
                        )
                    }
                }
                if (isGenerating) {
                    item {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            NexusLoading(modifier = Modifier.fillMaxWidth().widthIn(max = 780.dp))
                        }
                    }
                }
            }
        }

        NexusComposer(
            draft = draft,
            attachments = attachments,
            queuedMessages = queuedMessages,
            isGenerating = isGenerating,
            isProcessingAttachments = isProcessingAttachments,
            isRecording = isRecording,
            selectedAgent = selectedAgent,
            selectedModel = selectedModel,
            deepThinkSupported = deepThinkSupported,
            onDraftChange = onDraftChange,
            onAttach = onAttach,
            onMic = onMic,
            onSend = onSend,
            onStop = onStop,
            onRemoveAttachment = onRemoveAttachment,
            onRemoveQueuedMessage = onRemoveQueuedMessage,
        )
    }
}

@Composable
private fun NexusTopBar(
    showMenuButton: Boolean,
    conversationTitle: String,
    selectedAgent: AgentEntity?,
    selectedModel: com.example.ialocal.data.AiModelEntity?,
    readyModels: List<com.example.ialocal.data.AiModelEntity>,
    modelAgents: List<AgentEntity>,
    modelMenuOpen: Boolean,
    personaMenuOpen: Boolean,
    onModelMenuChange: (Boolean) -> Unit,
    onPersonaMenuChange: (Boolean) -> Unit,
    onOpenMenu: () -> Unit,
    onSelectModel: (String) -> Unit,
    onSelectAgent: (AgentEntity) -> Unit,
    onEditAgent: () -> Unit,
    onCreateAgent: () -> Unit,
    onOpenModels: () -> Unit,
) {
    Surface(
        color = NexusColors.Surface850.copy(alpha = 0.96f),
        border = BorderStroke(0.5.dp, NexusColors.BorderSoft),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (showMenuButton) {
                    IconButton(onClick = onOpenMenu) {
                        Icon(Icons.Default.Menu, contentDescription = "Abrir menu", tint = NexusColors.TextMuted)
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        conversationTitle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = NexusColors.TextPrimary,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.VerifiedUser, contentDescription = null, tint = NexusColors.Brand, modifier = Modifier.size(13.dp))
                        Spacer(Modifier.width(5.dp))
                        Text(
                            selectedAgent?.name ?: "Sem perfil ativo",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontSize = 10.sp,
                            color = Color(0xFFA5B4FC),
                        )
                    }
                }
            }

            Spacer(Modifier.width(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                Box {
                    NexusSelectorButton(
                        icon = Icons.Default.Code,
                        label = selectedAgent?.name ?: "Perfil",
                        onClick = {
                            onModelMenuChange(false)
                            onPersonaMenuChange(true)
                        },
                    )
                    DropdownMenu(
                        expanded = personaMenuOpen,
                        onDismissRequest = { onPersonaMenuChange(false) },
                        modifier = Modifier.widthIn(min = 260.dp, max = 330.dp).background(NexusColors.Surface850),
                    ) {
                        Text(
                            "PERFIL / PERSONALIDADE",
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = NexusColors.TextMuted,
                        )
                        modelAgents.forEach { agent ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(agent.name, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                        Text(
                                            agent.systemPrompt.take(90),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            fontSize = 10.sp,
                                            color = NexusColors.TextMuted,
                                        )
                                    }
                                },
                                leadingIcon = { Icon(Icons.Default.Psychology, contentDescription = null, tint = NexusColors.Brand) },
                                trailingIcon = {
                                    if (agent.id == selectedAgent?.id) Icon(Icons.Default.Check, contentDescription = null, tint = NexusColors.Brand)
                                },
                                onClick = {
                                    onSelectAgent(agent)
                                    onPersonaMenuChange(false)
                                },
                            )
                        }
                        HorizontalDivider(color = NexusColors.BorderSoft)
                        DropdownMenuItem(
                            text = { Text("Criar Perfil", fontSize = 12.sp) },
                            leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) },
                            onClick = {
                                onPersonaMenuChange(false)
                                onCreateAgent()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Configurar perfil atual", fontSize = 12.sp) },
                            leadingIcon = { Icon(Icons.Default.Tune, contentDescription = null) },
                            enabled = selectedAgent != null,
                            onClick = {
                                onPersonaMenuChange(false)
                                onEditAgent()
                            },
                        )
                    }
                }

                Box {
                    NexusSelectorButton(
                        icon = Icons.Default.Memory,
                        label = selectedModel?.name ?: "Modelo",
                        onClick = {
                            onPersonaMenuChange(false)
                            onModelMenuChange(true)
                        },
                    )
                    DropdownMenu(
                        expanded = modelMenuOpen,
                        onDismissRequest = { onModelMenuChange(false) },
                        modifier = Modifier.widthIn(min = 250.dp, max = 320.dp).background(NexusColors.Surface850),
                    ) {
                        Text(
                            "SELECIONAR MODELO",
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = NexusColors.TextMuted,
                        )
                        if (readyModels.isEmpty()) {
                            DropdownMenuItem(
                                text = { Text("Nenhum modelo verificado", fontSize = 12.sp) },
                                enabled = false,
                                onClick = {},
                            )
                        } else {
                            readyModels.forEach { model ->
                                val provider = model.catalogProvider()
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(model.name, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                            Text(
                                                provider?.let { "${it.brandName()} · Offline" } ?: "Disponível offline",
                                                fontSize = 10.sp,
                                                color = NexusColors.TextMuted,
                                            )
                                        }
                                    },
                                    leadingIcon = {
                                        if (provider != null) ProviderLogo(provider, size = 24.dp)
                                        else Icon(Icons.Default.Memory, contentDescription = null, tint = NexusColors.Purple)
                                    },
                                    trailingIcon = {
                                        if (model.id == selectedModel?.id) Icon(Icons.Default.Check, contentDescription = null, tint = NexusColors.Brand)
                                    },
                                    onClick = {
                                        onSelectModel(model.id)
                                        onModelMenuChange(false)
                                    },
                                )
                            }
                        }
                        HorizontalDivider(color = NexusColors.BorderSoft)
                        DropdownMenuItem(
                            text = { Text("Gerenciar modelos offline", fontSize = 12.sp) },
                            leadingIcon = { Icon(Icons.Default.SmartToy, contentDescription = null) },
                            onClick = {
                                onModelMenuChange(false)
                                onOpenModels()
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NexusSelectorButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = NexusColors.Surface800,
        border = BorderStroke(1.dp, NexusColors.Border),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = NexusColors.Brand, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 120.dp),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = NexusColors.TextSecondary,
            )
            Spacer(Modifier.width(3.dp))
            Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = NexusColors.TextMuted, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun NexusWelcome(
    modifier: Modifier,
    personaName: String,
    deepThinkSupported: Boolean,
    onSuggestion: (String) -> Unit,
) {
    Box(modifier.fillMaxWidth().padding(horizontal = 18.dp), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.fillMaxWidth().widthIn(max = 690.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = Color(0xFF191A35),
                border = BorderStroke(1.dp, NexusColors.Brand.copy(alpha = 0.42f)),
            ) {
                Box(Modifier.padding(14.dp), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.SmartToy, contentDescription = null, tint = Color(0xFF818CF8), modifier = Modifier.size(40.dp))
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "Como posso ajudar você agora?",
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    buildString {
                        append("Você está conversando com o perfil ")
                        append(personaName)
                        append(". Tudo é processado localmente no aparelho.")
                    },
                    color = NexusColors.TextMuted,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                )
                if (deepThinkSupported) {
                    Spacer(Modifier.height(5.dp))
                    Text("DeepThink disponível para este modelo.", color = Color(0xFFC084FC), fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun NexusSuggestion(
    modifier: Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accent: Color,
    title: String,
    body: String,
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = NexusColors.Surface800.copy(alpha = 0.72f),
        border = BorderStroke(1.dp, NexusColors.Border),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(7.dp))
                Text(title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = accent)
            }
            Spacer(Modifier.height(7.dp))
            Text(body, fontSize = 11.sp, lineHeight = 16.sp, color = NexusColors.TextSecondary)
        }
    }
}

@Composable
private fun NexusMessage(
    item: MessageWithAttachments,
    personaName: String?,
    canRerun: Boolean,
    onRerun: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val user = item.message.role == MessageRole.USER.name
    val parsed = remember(item.message.content) { parseAssistantContent(item.message.content) }
    var reasoningExpanded by remember(item.message.id) { mutableStateOf(false) }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        Surface(
            modifier = Modifier.size(32.dp),
            shape = RoundedCornerShape(11.dp),
            color = if (user) NexusColors.BrandStrong else NexusColors.Surface800,
            border = if (user) null else BorderStroke(1.dp, NexusColors.Border),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    if (user) Icons.Default.VerifiedUser else Icons.Default.SmartToy,
                    contentDescription = null,
                    tint = if (user) Color.White else Color(0xFF818CF8),
                    modifier = Modifier.size(16.dp),
                )
            }
        }

        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (user) "Você" else "NexusAI",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (user) NexusColors.TextPrimary else Color(0xFFA5B4FC),
                )
                if (!user && !personaName.isNullOrBlank()) {
                    Spacer(Modifier.width(7.dp))
                    Surface(
                        color = Color(0xFF1E1B4B),
                        shape = RoundedCornerShape(6.dp),
                        border = BorderStroke(1.dp, Color(0xFF312E81)),
                    ) {
                        Text(
                            personaName,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                            fontSize = 9.sp,
                            color = Color(0xFF818CF8),
                        )
                    }
                }
                Spacer(Modifier.width(7.dp))
                Text(formatTime(item.message.createdAt), fontSize = 9.sp, color = NexusColors.TextMuted)
            }

            Spacer(Modifier.height(8.dp))
            if (item.attachments.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(item.attachments, key = { it.id }) { attachment ->
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = NexusColors.Surface800,
                            border = BorderStroke(1.dp, NexusColors.Border),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    if (attachment.type == AttachmentType.AUDIO.name) Icons.Default.GraphicEq else Icons.Default.Description,
                                    contentDescription = null,
                                    tint = NexusColors.Brand,
                                    modifier = Modifier.size(14.dp),
                                )
                                Spacer(Modifier.width(5.dp))
                                Text(attachment.fileName, maxLines = 1, fontSize = 10.sp, color = NexusColors.TextSecondary)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(7.dp))
            }

            if (user) {
                Surface(
                    shape = RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 16.dp),
                    color = NexusColors.Brand.copy(alpha = 0.14f),
                    border = BorderStroke(1.dp, NexusColors.Brand.copy(alpha = 0.34f)),
                ) {
                    Text(
                        item.message.content,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                        color = NexusColors.TextPrimary,
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                    )
                }
            } else {
                if (!parsed.reasoning.isNullOrBlank()) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { reasoningExpanded = !reasoningExpanded },
                        shape = RoundedCornerShape(12.dp),
                        color = NexusColors.Surface800.copy(alpha = 0.72f),
                        border = BorderStroke(1.dp, NexusColors.BorderSoft),
                    ) {
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Default.Psychology,
                                        contentDescription = null,
                                        tint = Color(0xFFC084FC),
                                        modifier = Modifier.size(15.dp),
                                    )
                                    Spacer(Modifier.width(7.dp))
                                    Text(
                                        if (reasoningExpanded) "Ocultar raciocínio" else "Ver raciocínio",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = NexusColors.TextSecondary,
                                    )
                                    parsed.reasoningMs?.let {
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            "• ${formatReasoningDuration(it)}",
                                            fontSize = 10.sp,
                                            color = NexusColors.TextMuted,
                                        )
                                    }
                                }
                                Icon(
                                    if (reasoningExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                    contentDescription = null,
                                    tint = NexusColors.TextMuted,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                            AnimatedVisibility(reasoningExpanded) {
                                Column {
                                    Spacer(Modifier.height(9.dp))
                                    HorizontalDivider(color = NexusColors.BorderSoft)
                                    Spacer(Modifier.height(9.dp))
                                    Text(
                                        parsed.reasoning,
                                        color = NexusColors.TextMuted,
                                        fontSize = 11.sp,
                                        lineHeight = 17.sp,
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                } else if (parsed.reasoningMs != null) {
                    Text(
                        "Tempo de resposta: ${formatReasoningDuration(parsed.reasoningMs)}",
                        fontSize = 9.sp,
                        color = NexusColors.TextMuted,
                    )
                    Spacer(Modifier.height(7.dp))
                }

                if (parsed.answer.isNotBlank()) {
                    Text(
                        parsed.answer,
                        color = NexusColors.TextSecondary,
                        fontSize = 13.sp,
                        lineHeight = 21.sp,
                    )
                }

                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick = onRerun,
                    enabled = canRerun && parsed.answer.isNotBlank(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("Rerun", fontSize = 10.sp)
                }
            }
        }
    }
}

@Composable
private fun NexusLoading(modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(13.dp)) {
        Surface(
            modifier = Modifier.size(32.dp),
            shape = RoundedCornerShape(11.dp),
            color = NexusColors.Surface800,
            border = BorderStroke(1.dp, NexusColors.Brand.copy(alpha = 0.38f)),
        ) {
            Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Color(0xFF818CF8))
            }
        }
        Surface(
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(16.dp),
            color = NexusColors.Surface850,
            border = BorderStroke(1.dp, NexusColors.BorderSoft),
        ) {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(Color(0xFF818CF8)))
                    Spacer(Modifier.width(8.dp))
                    Text("NexusAI está formulando a resposta...", fontSize = 11.sp, color = NexusColors.TextSecondary)
                }
                Spacer(Modifier.height(10.dp))
                Surface(
                    modifier = Modifier.width(176.dp).height(5.dp),
                    shape = CircleShape,
                    color = NexusColors.Surface750,
                ) {
                    Box(Modifier.fillMaxSize().background(NexusColors.Brand))
                }
            }
        }
    }
}

@Composable
private fun NexusComposer(
    draft: String,
    attachments: List<PendingAttachment>,
    queuedMessages: List<QueuedChatMessage>,
    isGenerating: Boolean,
    isProcessingAttachments: Boolean,
    isRecording: Boolean,
    selectedAgent: AgentEntity?,
    selectedModel: com.example.ialocal.data.AiModelEntity?,
    deepThinkSupported: Boolean,
    onDraftChange: (String) -> Unit,
    onAttach: () -> Unit,
    onMic: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onRemoveQueuedMessage: (Int) -> Unit,
) {
    Surface(
        color = NexusColors.Surface850.copy(alpha = 0.98f),
        border = BorderStroke(0.5.dp, NexusColors.BorderSoft),
    ) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 780.dp)
                    .imePadding()
                    .padding(horizontal = 12.dp, vertical = 11.dp),
            ) {
                AnimatedVisibility(queuedMessages.isNotEmpty()) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            if (queuedMessages.size == 1) "1 mensagem na fila" else "${queuedMessages.size} mensagens na fila",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFFA5B4FC),
                        )
                        queuedMessages.take(3).forEachIndexed { index, queued ->
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(11.dp),
                                color = NexusColors.Surface800,
                                border = BorderStroke(1.dp, NexusColors.Border),
                            ) {
                                Row(
                                    modifier = Modifier.padding(start = 10.dp, top = 6.dp, bottom = 6.dp, end = 3.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        "${index + 1}",
                                        fontSize = 9.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = NexusColors.Brand,
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        queued.content,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                        fontSize = 11.sp,
                                        color = NexusColors.TextSecondary,
                                    )
                                    IconButton(onClick = { onRemoveQueuedMessage(index) }, modifier = Modifier.size(28.dp)) {
                                        Icon(Icons.Default.Close, contentDescription = "Remover da fila", modifier = Modifier.size(13.dp), tint = NexusColors.TextMuted)
                                    }
                                }
                            }
                        }
                        if (queuedMessages.size > 3) {
                            Text(
                                "+ ${queuedMessages.size - 3} aguardando",
                                fontSize = 9.sp,
                                color = NexusColors.TextMuted,
                            )
                        }
                    }
                }

                if (attachments.isNotEmpty()) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        items(attachments, key = { it.id }) { attachment ->
                            Surface(
                                shape = RoundedCornerShape(11.dp),
                                color = NexusColors.Surface800,
                                border = BorderStroke(1.dp, NexusColors.Border),
                            ) {
                                Row(
                                    modifier = Modifier.padding(start = 9.dp, top = 5.dp, bottom = 5.dp, end = 3.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        if (attachment.type == AttachmentType.AUDIO) Icons.Default.GraphicEq else Icons.Default.Description,
                                        contentDescription = null,
                                        tint = NexusColors.Brand,
                                        modifier = Modifier.size(14.dp),
                                    )
                                    Spacer(Modifier.width(5.dp))
                                    Text(
                                        attachment.fileName,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.widthIn(max = 140.dp),
                                        fontSize = 10.sp,
                                        color = NexusColors.TextSecondary,
                                    )
                                    IconButton(onClick = { onRemoveAttachment(attachment.id) }, modifier = Modifier.size(26.dp)) {
                                        Icon(Icons.Default.Close, contentDescription = "Remover anexo", modifier = Modifier.size(13.dp), tint = NexusColors.TextMuted)
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }

                AnimatedVisibility(isProcessingAttachments || isRecording) {
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        shape = RoundedCornerShape(11.dp),
                        color = if (isRecording) Color(0xFF3A1720) else NexusColors.Surface800,
                        border = BorderStroke(1.dp, if (isRecording) Color(0xFF7F1D1D) else NexusColors.Border),
                    ) {
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (isProcessingAttachments) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                            else Icon(Icons.Default.Mic, contentDescription = null, tint = Color(0xFFFB7185), modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (isProcessingAttachments) "Processando anexo…" else "Gravando áudio… toque no microfone para finalizar.",
                                fontSize = 10.sp,
                                color = NexusColors.TextSecondary,
                            )
                        }
                    }
                }

                Surface(
                    shape = RoundedCornerShape(17.dp),
                    color = NexusColors.Surface800.copy(alpha = 0.96f),
                    border = BorderStroke(1.dp, NexusColors.Border),
                ) {
                    Column {
                        TextField(
                            value = draft,
                            onValueChange = onDraftChange,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 52.dp, max = 168.dp),
                            placeholder = {
                                Text(
                                    if (isGenerating) "Digite para adicionar à fila..." else "Envie uma mensagem ou anexe arquivos...",
                                    color = NexusColors.TextMuted,
                                    fontSize = 12.sp,
                                )
                            },
                            maxLines = 6,
                            enabled = !isProcessingAttachments,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 19.sp),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                disabledContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                disabledIndicatorColor = Color.Transparent,
                                focusedTextColor = NexusColors.TextPrimary,
                                unfocusedTextColor = NexusColors.TextPrimary,
                            ),
                        )
                        HorizontalDivider(color = NexusColors.Border.copy(alpha = 0.65f))
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 7.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                IconButton(
                                    onClick = onAttach,
                                    enabled = !isGenerating && !isRecording && !isProcessingAttachments,
                                ) {
                                    Icon(Icons.Default.AttachFile, contentDescription = "Anexar arquivo", tint = NexusColors.TextMuted, modifier = Modifier.size(18.dp))
                                }

                                if (deepThinkSupported && selectedAgent != null && selectedModel != null) {
                                    DeepThinkOverlay(agent = selectedAgent, model = selectedModel)
                                }

                                IconButton(onClick = onMic, enabled = !isGenerating && !isProcessingAttachments) {
                                    Icon(
                                        if (isRecording) Icons.Default.Stop else Icons.Default.Mic,
                                        contentDescription = if (isRecording) "Parar gravação" else "Gravar áudio",
                                        tint = if (isRecording) Color(0xFFFB7185) else NexusColors.TextMuted,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (isGenerating) {
                                    FilledIconButton(
                                        onClick = onStop,
                                        colors = ButtonDefaults.filledIconButtonColors(containerColor = Color(0xFF7F1D1D)),
                                        shape = RoundedCornerShape(11.dp),
                                        modifier = Modifier.size(42.dp),
                                    ) {
                                        Icon(Icons.Default.Stop, contentDescription = "Parar geração", modifier = Modifier.size(17.dp))
                                    }
                                }
                                FilledIconButton(
                                    onClick = onSend,
                                    enabled = !isProcessingAttachments && !isRecording && (draft.isNotBlank() || attachments.isNotEmpty()),
                                    colors = ButtonDefaults.filledIconButtonColors(containerColor = NexusColors.BrandStrong),
                                    shape = RoundedCornerShape(11.dp),
                                    modifier = Modifier.size(42.dp),
                                ) {
                                    Icon(
                                        Icons.Default.Send,
                                        contentDescription = if (isGenerating) "Adicionar mensagem à fila" else "Enviar",
                                        modifier = Modifier.size(17.dp),
                                    )
                                }
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        if (isGenerating) "Enviar adiciona à fila · será enviada após a resposta atual" else "Enter envia · use nova linha pelo teclado",
                        fontSize = 9.sp,
                        color = NexusColors.TextMuted,
                    )
                    Text("${draft.length} caracteres", fontSize = 9.sp, color = NexusColors.TextMuted, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

@Composable
private fun NexusAgentDialog(
    title: String,
    initialName: String,
    initialPrompt: String,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    var prompt by remember(initialPrompt) { mutableStateOf(initialPrompt) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = NexusColors.Surface850,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(9.dp),
                    color = NexusColors.Brand.copy(alpha = 0.18f),
                    border = BorderStroke(1.dp, NexusColors.Brand.copy(alpha = 0.35f)),
                ) {
                    Icon(Icons.Default.Psychology, contentDescription = null, tint = NexusColors.Brand, modifier = Modifier.padding(7.dp).size(17.dp))
                }
                Spacer(Modifier.width(9.dp))
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(80) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Nome do Perfil") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Prompt da Persona") },
                    minLines = 5,
                    maxLines = 9,
                )
                Text(
                    "Este prompt define o comportamento, tom de voz e regras de resposta do modelo local.",
                    fontSize = 10.sp,
                    color = NexusColors.TextMuted,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(name.trim(), prompt.trim()) },
                enabled = name.isNotBlank() && prompt.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = NexusColors.BrandStrong),
            ) { Text("Salvar e Ativar Perfil") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

private data class ParsedAssistantContent(
    val reasoning: String?,
    val answer: String,
    val reasoningMs: Long?,
)

private fun parseAssistantContent(raw: String): ParsedAssistantContent {
    val metadataMatch = REASONING_METADATA.find(raw)
    val reasoningMs = metadataMatch?.groupValues?.getOrNull(1)?.toLongOrNull()
    val clean = REASONING_METADATA.replace(raw, "").trim()

    REASONING_BLOCK.find(clean)?.let { block ->
        return ParsedAssistantContent(
            reasoning = block.groupValues[1].trim(),
            answer = clean.removeRange(block.range).trim(),
            reasoningMs = reasoningMs,
        )
    }

    val open = REASONING_OPEN.find(clean)
    if (open != null) {
        return ParsedAssistantContent(
            reasoning = clean.substring(open.range.last + 1).trim(),
            answer = clean.substring(0, open.range.first).trim(),
            reasoningMs = reasoningMs,
        )
    }

    return ParsedAssistantContent(
        reasoning = null,
        answer = clean,
        reasoningMs = reasoningMs,
    )
}

private fun formatReasoningDuration(ms: Long): String {
    val seconds = ms.coerceAtLeast(0L) / 1000.0
    return if (seconds < 60.0) {
        String.format(Locale.getDefault(), "%.1f s", seconds)
    } else {
        val minutes = (seconds / 60).toInt()
        val remaining = (seconds % 60).toInt()
        "${minutes}m ${remaining}s"
    }
}

private val REASONING_OPEN = Regex("(?is)<think(?:ing)?>")
private val REASONING_BLOCK = Regex("(?is)<think(?:ing)?>(.*?)</think(?:ing)?>")
private val REASONING_METADATA = Regex("(?is)\\s*<!--nexus_reasoning_ms:(\\d+)-->\\s*$")

private fun formatTime(timestamp: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
