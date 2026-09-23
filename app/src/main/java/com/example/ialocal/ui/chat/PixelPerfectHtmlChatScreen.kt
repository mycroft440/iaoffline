package com.example.ialocal.ui.chat

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ialocal.ads.ChatAdSchedule
import com.example.ialocal.ads.InlineAdBanner
import com.example.ialocal.audio.AudioRecorder
import com.example.ialocal.data.AgentEntity
import com.example.ialocal.data.AiModelEntity
import com.example.ialocal.data.AttachmentType
import com.example.ialocal.data.ConversationListItem
import com.example.ialocal.data.MessageRole
import com.example.ialocal.data.MessageStatus
import com.example.ialocal.data.MessageWithAttachments
import com.example.ialocal.data.ModelVerificationStatus
import com.example.ialocal.data.PendingAttachment
import com.example.ialocal.models.BuiltInProfile
import com.example.ialocal.models.DeepThinkLevel
import com.example.ialocal.models.DeepThinkStore
import com.example.ialocal.models.DeepThinkSupport
import com.example.ialocal.ui.branding.ProviderLogo
import com.example.ialocal.ui.branding.brandName
import com.example.ialocal.ui.branding.catalogProvider
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val PBg = Color(0xFF090B11)
private val PSurface = Color(0xFF111420)
private val PCard = Color(0xFF161A29)
private val PCardHover = Color(0xFF1D2235)
private val PBorder = Color(0xFF23283E)
private val PIndigo = Color(0xFF6366F1)
private val PIndigoStrong = Color(0xFF4F46E5)
private val PPurple = Color(0xFFA855F7)
private val PEmerald = Color(0xFF34D399)
private val PText = Color(0xFFF1F5F9)
private val PText2 = Color(0xFFCBD5E1)
private val PMuted = Color(0xFF64748B)

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun PixelPerfectHtmlChatScreen(
    viewModel: ChatViewModel,
    onOpenConversation: (String) -> Unit,
    onOpenHistory: () -> Unit,
    onOpenModels: () -> Unit,
    onOpenSettings: () -> Unit,
    adsEnabled: Boolean,
) {
    val context = LocalContext.current
    val conversation by viewModel.conversation.collectAsStateWithLifecycle()
    val conversations by viewModel.conversations.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val attachments by viewModel.pendingAttachments.collectAsStateWithLifecycle()
    val generating by viewModel.isGenerating.collectAsStateWithLifecycle()
    val processing by viewModel.isProcessingAttachments.collectAsStateWithLifecycle()
    val queued by viewModel.queuedMessages.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val agents by viewModel.agents.collectAsStateWithLifecycle()
    val models by viewModel.models.collectAsStateWithLifecycle()
    val selectedAgentId by viewModel.selectedAgentId.collectAsStateWithLifecycle()
    val selectedProfile by viewModel.selectedProfile.collectAsStateWithLifecycle()

    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val snackbar = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val recorder = remember { AudioRecorder(context) }

    var recording by remember { mutableStateOf(false) }
    var profileOpen by remember { mutableStateOf(false) }
    var modelOpen by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    var pendingAudio by remember { mutableStateOf<android.net.Uri?>(null) }

    val readyModels = remember(models) {
        models.filter { it.verificationStatus == ModelVerificationStatus.VERIFIED.name }
    }
    val selectedAgent = agents.firstOrNull { it.id == selectedAgentId }
        ?: agents.firstOrNull { it.id == conversation?.agentId }
        ?: agents.firstOrNull { it.isDefault }
    val selectedModel = readyModels.firstOrNull { it.id == selectedAgent?.modelId }
        ?: readyModels.firstOrNull { it.isActive }
        ?: readyModels.firstOrNull()
    val deepThinkSupported = selectedModel?.let { DeepThinkSupport.capability(it).supported } == true

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) {
            pendingAudio = null
        } else {
            pendingAudio?.let {
                pendingAudio = null
                viewModel.importFile(it)
            } ?: runCatching {
                recorder.start()
                recording = true
            }
        }
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val isAudio = context.contentResolver.getType(uri)?.startsWith("audio/") == true
            val hasPermission = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED
            if (isAudio && !hasPermission) {
                pendingAudio = uri
                micPermission.launch(Manifest.permission.RECORD_AUDIO)
            } else {
                viewModel.importFile(uri)
            }
        }
    }
    val onMic: () -> Unit = {
        if (recording) {
            recorder.stop()?.let(viewModel::addPendingAttachment)
            recording = false
        } else {
            val hasPermission = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED
            if (hasPermission) {
                runCatching {
                    recorder.start()
                    recording = true
                }
            } else {
                micPermission.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    DisposableEffect(Unit) { onDispose { recorder.cancel() } }
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }
    LaunchedEffect(error) {
        error?.let {
            snackbar.showSnackbar(it)
            viewModel.clearError()
        }
    }

    Scaffold(
        containerColor = PBg,
        snackbarHost = { SnackbarHost(snackbar) },
    ) { safePadding ->
        ModalNavigationDrawer(
            modifier = Modifier.fillMaxSize().padding(safePadding),
            drawerState = drawerState,
            drawerContent = {
                ModalDrawerSheet(
                    modifier = Modifier.fillMaxHeight().fillMaxWidth(0.84f).widthIn(max = 310.dp),
                    drawerContainerColor = PSurface,
                    drawerContentColor = PText,
                ) {
                    ExactDrawer(
                        conversations = conversations,
                        currentId = conversation?.id,
                        query = search,
                        onQueryChange = { search = it },
                        modelReady = selectedModel != null,
                        onClose = { scope.launch { drawerState.close() } },
                        onNew = {
                            viewModel.createConversation(onOpenConversation)
                            scope.launch { drawerState.close() }
                        },
                        onConversation = { id ->
                            onOpenConversation(id)
                            scope.launch { drawerState.close() }
                        },
                        onSettings = {
                            onOpenSettings()
                            scope.launch { drawerState.close() }
                        },
                    )
                }
            },
        ) {
            ExactChatBody(
                selectedAgent = selectedAgent,
                profileName = selectedProfile.displayName,
                adsEnabled = adsEnabled,
                selectedModel = selectedModel,
                messages = messages,
                listState = listState,
                draft = draft,
                attachments = attachments,
                queued = queued,
                generating = generating,
                processing = processing,
                recording = recording,
                deepThinkSupported = deepThinkSupported,
                onMenu = { scope.launch { drawerState.open() } },
                onProfile = { profileOpen = true },
                onModel = { modelOpen = true },
                onNew = { viewModel.createConversation(onOpenConversation) },
                onDraftChange = viewModel::setDraft,
                onAttach = {
                    filePicker.launch(
                        arrayOf("text/plain", "text/markdown", "application/pdf", "application/json", "text/csv", "audio/*")
                    )
                },
                onMic = onMic,
                onSend = viewModel::send,
                onStop = viewModel::stopGeneration,
                onRerun = viewModel::rerunAssistant,
                onRemoveAttachment = viewModel::removePendingAttachment,
                onRemoveQueued = viewModel::removeQueuedMessage,
            )
        }
    }

    if (profileOpen) {
        ModalBottomSheet(
            onDismissRequest = { profileOpen = false },
            containerColor = PSurface,
            contentColor = PText,
            scrimColor = Color.Black.copy(alpha = 0.70f),
            dragHandle = null,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        ) {
            ExactProfilesSheet(
                selected = selectedProfile,
                onSelect = {
                    viewModel.selectProfile(it)
                    profileOpen = false
                },
                onManage = {
                    profileOpen = false
                    onOpenSettings()
                },
            )
        }
    }

    if (modelOpen) {
        ModalBottomSheet(
            onDismissRequest = { modelOpen = false },
            containerColor = PSurface,
            contentColor = PText,
            scrimColor = Color.Black.copy(alpha = 0.70f),
            dragHandle = null,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        ) {
            ExactModelsSheet(
                selectedId = selectedModel?.id,
                models = readyModels,
                onSelect = {
                    viewModel.selectModel(it)
                    modelOpen = false
                },
                onManage = {
                    modelOpen = false
                    onOpenModels()
                },
            )
        }
    }

    @Suppress("UNUSED_VARIABLE")
    val keepHistoryCallback = onOpenHistory
}

@Composable
private fun ExactDrawer(
    conversations: List<ConversationListItem>,
    currentId: String?,
    query: String,
    onQueryChange: (String) -> Unit,
    modelReady: Boolean,
    onClose: () -> Unit,
    onNew: () -> Unit,
    onConversation: (String) -> Unit,
    onSettings: () -> Unit,
) {
    val filtered = remember(conversations, query) {
        val normalized = query.trim().lowercase(Locale.getDefault())
        if (normalized.isBlank()) conversations else conversations.filter {
            it.title.lowercase(Locale.getDefault()).contains(normalized) ||
                it.lastMessage.orEmpty().lowercase(Locale.getDefault()).contains(normalized)
        }
    }
    val groups = remember(filtered) { filtered.groupBy { exactHistoryGroup(it.updatedAt) } }
    val memory = rememberExactMemory()

    Column(Modifier.fillMaxSize().background(PSurface)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        modifier = Modifier.size(28.dp),
                        shape = RoundedCornerShape(8.dp),
                        color = PIndigo.copy(alpha = 0.20f),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.SmartToy, null, tint = Color(0xFF818CF8), modifier = Modifier.size(14.dp))
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text("DeepThink Local", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(6.dp).clip(CircleShape)
                                    .background(if (modelReady) PEmerald else PMuted)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                if (modelReady) "NPU Online · On-Device" else "Aguardando modelo local",
                                fontSize = 10.sp,
                                color = if (modelReady) PEmerald else PMuted,
                            )
                        }
                    }
                }
                ExactSquareIconButton(size = 28.dp, onClick = onClose) {
                    Icon(Icons.Default.Close, "Fechar", tint = Color(0xFF94A3B8), modifier = Modifier.size(16.dp))
                }
            }

            Spacer(Modifier.height(12.dp))
            Surface(
                modifier = Modifier.fillMaxWidth().clickable(onClick = onNew),
                shape = RoundedCornerShape(12.dp),
                color = Color.Transparent,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .background(Brush.horizontalGradient(listOf(Color(0xFF4F46E5), Color(0xFF9333EA))))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Add, null, modifier = Modifier.size(12.dp), tint = Color.White)
                    Spacer(Modifier.width(8.dp))
                    Text("Novo chat", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                }
            }

            Spacer(Modifier.height(12.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = PCard,
                border = BorderStroke(1.dp, PBorder.copy(alpha = 0.80f)),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().height(36.dp).padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Search, null, tint = PMuted, modifier = Modifier.size(12.dp))
                    Spacer(Modifier.width(8.dp))
                    BasicTextField(
                        value = query,
                        onValueChange = onQueryChange,
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        textStyle = TextStyle(fontSize = 12.sp, color = PText2),
                        decorationBox = { inner ->
                            if (query.isEmpty()) Text("Buscar conversas...", fontSize = 12.sp, color = PMuted)
                            inner()
                        },
                    )
                }
            }
        }

        HorizontalDivider(color = PBorder.copy(alpha = 0.40f))

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            listOf("Hoje", "Ontem", "Anteriores").forEach { groupName ->
                val groupItems = groups[groupName].orEmpty()
                if (groupItems.isNotEmpty()) {
                    item {
                        Text(
                            groupName.uppercase(Locale.ROOT),
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = PMuted,
                        )
                    }
                    items(groupItems, key = { it.id }) { item ->
                        val active = item.id == currentId
                        Surface(
                            modifier = Modifier.fillMaxWidth().clickable { onConversation(item.id) },
                            shape = RoundedCornerShape(12.dp),
                            color = if (active) Color(0xFF1E1B4B).copy(alpha = 0.40f) else PCard.copy(alpha = 0.60f),
                            border = BorderStroke(
                                1.dp,
                                if (active) PIndigo.copy(alpha = 0.40f) else PBorder.copy(alpha = 0.60f),
                            ),
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Default.Psychology,
                                    null,
                                    tint = if (active) Color(0xFF818CF8) else PMuted,
                                    modifier = Modifier.size(12.dp),
                                )
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        item.title,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = if (active) Color(0xFFC7D2FE) else PText2,
                                    )
                                    item.lastMessage?.takeIf { it.isNotBlank() }?.let {
                                        Text(
                                            it,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            fontSize = 10.5.sp,
                                            color = PMuted,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (filtered.isEmpty()) {
                item {
                    Text(
                        "Nenhuma conversa encontrada",
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                        textAlign = TextAlign.Center,
                        fontSize = 12.sp,
                        color = PMuted,
                    )
                }
            }
        }

        Column(
            modifier = Modifier.fillMaxWidth().background(PBg.copy(alpha = 0.60f)).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = PCard,
                border = BorderStroke(1.dp, PBorder.copy(alpha = 0.60f)),
            ) {
                Column(Modifier.padding(10.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Memória do dispositivo", fontSize = 11.sp, color = Color(0xFF94A3B8))
                        Text(
                            "${memory.used} / ${memory.total}",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFF818CF8),
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(Color(0xFF1E293B))) {
                        Box(
                            Modifier.fillMaxWidth(memory.fraction).fillMaxHeight()
                                .background(Brush.horizontalGradient(listOf(PIndigo, PPurple)))
                        )
                    }
                }
            }
            Surface(
                modifier = Modifier.fillMaxWidth().clickable(onClick = onSettings),
                shape = RoundedCornerShape(12.dp),
                color = PCard,
                border = BorderStroke(1.dp, PBorder),
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Settings, null, tint = Color(0xFF94A3B8), modifier = Modifier.size(12.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Configurações & IA", modifier = Modifier.weight(1f), fontSize = 12.sp, fontWeight = FontWeight.Medium, color = PText2)
                    Text("›", fontSize = 18.sp, color = PMuted)
                }
            }
        }
    }
}

@Composable
private fun ExactChatBody(
    selectedAgent: AgentEntity?,
    profileName: String,
    adsEnabled: Boolean,
    selectedModel: AiModelEntity?,
    messages: List<MessageWithAttachments>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    draft: String,
    attachments: List<PendingAttachment>,
    queued: List<QueuedChatMessage>,
    generating: Boolean,
    processing: Boolean,
    recording: Boolean,
    deepThinkSupported: Boolean,
    onMenu: () -> Unit,
    onProfile: () -> Unit,
    onModel: () -> Unit,
    onNew: () -> Unit,
    onDraftChange: (String) -> Unit,
    onAttach: () -> Unit,
    onMic: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onRerun: (String) -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onRemoveQueued: (Int) -> Unit,
) {
    val sendingAssistantId = if (generating) {
        messages.lastOrNull {
            it.message.role == MessageRole.ASSISTANT.name &&
                it.message.status == MessageStatus.SENDING.name
        }?.message?.id
    } else {
        null
    }
    val bannerMessageIds = remember(messages) {
        ChatAdSchedule.assistantIdsWithBanner(
            messages,
            isUser = { it.message.role == MessageRole.USER.name },
            id = { it.message.id },
        )
    }

    Column(Modifier.fillMaxSize().background(PBg)) {
        ExactTopBar(
            agent = profileName,
            model = selectedModel?.name ?: "Modelo",
            onMenu = onMenu,
            onProfile = onProfile,
            onModel = onModel,
        )

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (messages.isEmpty()) {
                ExactWelcome(
                    modifier = Modifier.fillMaxSize(),
                    profile = profileName,
                    modelAvailable = selectedModel != null,
                    deepThink = deepThinkSupported,
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    items(messages, key = { it.message.id }) { item ->
                        if (item.message.id == sendingAssistantId && item.message.content.isBlank()) {
                            ExactLoading()
                        } else {
                            ExactMessage(
                                item = item,
                                canRerun = !generating,
                                showAd = adsEnabled &&
                                    item.message.id in bannerMessageIds &&
                                    item.message.status != MessageStatus.SENDING.name,
                            ) { onRerun(item.message.id) }
                        }
                    }
                    if (generating && sendingAssistantId == null) item { ExactLoading() }
                }
            }
        }

        ExactComposer(
            draft = draft,
            attachments = attachments,
            queued = queued,
            generating = generating,
            processing = processing,
            recording = recording,
            agent = selectedAgent,
            model = selectedModel,
            deepThinkSupported = deepThinkSupported,
            onDraftChange = onDraftChange,
            onAttach = onAttach,
            onMic = onMic,
            onSend = onSend,
            onStop = onStop,
            onRemoveAttachment = onRemoveAttachment,
            onRemoveQueued = onRemoveQueued,
        )
    }
}

@Composable
private fun ExactTopBar(
    agent: String,
    model: String,
    onMenu: () -> Unit,
    onProfile: () -> Unit,
    onModel: () -> Unit,
) {
    Surface(color = PBg, border = BorderStroke(0.5.dp, PBorder.copy(alpha = 0.40f))) {
        Row(
            modifier = Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Surface(
                    modifier = Modifier.size(32.dp).clickable(onClick = onMenu),
                    shape = CircleShape,
                    color = PCard,
                    border = BorderStroke(1.dp, PBorder),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        StaggeredMenuIcon(Modifier.size(15.dp), color = PText2)
                    }
                }
                ExactSelector(
                    modifier = Modifier.widthIn(max = 142.dp),
                    icon = null,
                    textIcon = "</>",
                    label = agent,
                    maxLabelWidth = 90.dp,
                    onClick = onProfile,
                )
            }

            ExactSelector(
                modifier = Modifier.widthIn(max = 136.dp),
                icon = Icons.Default.Memory,
                textIcon = null,
                label = model,
                maxLabelWidth = 85.dp,
                onClick = onModel,
            )
        }
    }
}

@Composable
private fun ExactSelector(
    modifier: Modifier,
    icon: ImageVector?,
    textIcon: String?,
    label: String,
    maxLabelWidth: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(50),
        color = PCard,
        border = BorderStroke(1.dp, PBorder),
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (textIcon != null) {
                Text(textIcon, fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = Color(0xFF818CF8))
            } else if (icon != null) {
                Icon(icon, null, tint = Color(0xFF818CF8), modifier = Modifier.size(11.dp))
            }
            Spacer(Modifier.width(6.dp))
            Text(
                label,
                modifier = Modifier.widthIn(max = maxLabelWidth),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = PText2,
            )
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Default.ArrowDropDown, null, tint = Color(0xFF94A3B8), modifier = Modifier.size(11.dp))
        }
    }
}

@Composable
private fun StaggeredMenuIcon(modifier: Modifier, color: Color) {
    Canvas(modifier) {
        val stroke = 1.8.dp.toPx()
        val ys = listOf(size.height * 0.25f, size.height * 0.50f, size.height * 0.75f)
        drawLine(color, Offset(size.width * 0.10f, ys[0]), Offset(size.width * 0.78f, ys[0]), stroke, StrokeCap.Round)
        drawLine(color, Offset(size.width * 0.27f, ys[1]), Offset(size.width * 0.94f, ys[1]), stroke, StrokeCap.Round)
        drawLine(color, Offset(size.width * 0.10f, ys[2]), Offset(size.width * 0.66f, ys[2]), stroke, StrokeCap.Round)
    }
}

@Composable
private fun ExactWelcome(modifier: Modifier, profile: String, modelAvailable: Boolean, deepThink: Boolean) {
    Box(modifier.padding(horizontal = 24.dp, vertical = 24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "Como posso ajudar você agora?",
                fontSize = 24.sp,
                lineHeight = 29.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                if (modelAvailable) {
                    "Você está conversando com o perfil $profile. Tudo é processado localmente no aparelho."
                } else {
                    "Perfil $profile selecionado. Baixe uma IA offline para começar a conversar."
                },
                modifier = Modifier.widthIn(max = 320.dp),
                fontSize = 13.sp,
                lineHeight = 20.sp,
                color = Color(0xFF94A3B8),
                textAlign = TextAlign.Center,
            )
            if (deepThink) {
                Spacer(Modifier.height(16.dp))
                Surface(
                    shape = RoundedCornerShape(50),
                    color = Color(0xFF581C87).copy(alpha = 0.30f),
                    border = BorderStroke(1.dp, PPurple.copy(alpha = 0.20f)),
                ) {
                    Row(
                        Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(6.dp).clip(CircleShape).background(Color(0xFFC084FC)))
                        Spacer(Modifier.width(6.dp))
                        Text("DeepThink disponível para este modelo.", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Color(0xFFD8B4FE))
                    }
                }
            }
        }
    }
}

@Composable
private fun ExactMessage(item: MessageWithAttachments, canRerun: Boolean, showAd: Boolean, onRerun: () -> Unit) {
    if (item.message.role == MessageRole.USER.name) {
        ExactUserMessage(item)
    } else {
        ExactAssistantMessage(item, canRerun, showAd, onRerun)
    }
}

@Composable
private fun ExactUserMessage(item: MessageWithAttachments) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        Column(Modifier.fillMaxWidth(0.88f), horizontalAlignment = Alignment.End) {
            Surface(
                shape = RoundedCornerShape(topStart = 16.dp, topEnd = 4.dp, bottomStart = 16.dp, bottomEnd = 16.dp),
                color = Color.Transparent,
            ) {
                Column(
                    Modifier.background(Brush.linearGradient(listOf(Color(0xFF4F46E5), Color(0xFF4338CA))))
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    if (item.attachments.isNotEmpty()) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(item.attachments, key = { it.id }) { attachment ->
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = Color.Black.copy(alpha = 0.30f),
                                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f)),
                                ) {
                                    Row(
                                        Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(
                                            if (attachment.type == AttachmentType.AUDIO.name) Icons.Default.GraphicEq else Icons.Default.Description,
                                            null,
                                            tint = Color(0xFFC7D2FE),
                                            modifier = Modifier.size(12.dp),
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            attachment.fileName,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.widthIn(max = 180.dp),
                                            fontSize = 12.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = Color(0xFFC7D2FE),
                                        )
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                    Text(item.message.content, fontSize = 13.5.sp, lineHeight = 20.sp, color = Color.White)
                }
            }
            Text(
                formatExactTime(item.message.createdAt),
                modifier = Modifier.padding(top = 4.dp, end = 4.dp),
                fontSize = 10.sp,
                color = PMuted,
            )
        }
    }
}

@Composable
private fun ExactAssistantMessage(item: MessageWithAttachments, canRerun: Boolean, showAd: Boolean, onRerun: () -> Unit) {
    val parsed = remember(item.message.content) { exactParseAssistant(item.message.content) }
    var reasoningOpen by remember(item.message.id) { mutableStateOf(true) }
    val clipboard = LocalClipboardManager.current

    Row(
        modifier = Modifier.fillMaxWidth(0.96f),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Surface(
            modifier = Modifier.size(32.dp).padding(top = 1.dp),
            shape = RoundedCornerShape(12.dp),
            color = Color(0xFF181C2D),
            border = BorderStroke(1.dp, PIndigo.copy(alpha = 0.30f)),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Default.SmartToy, null, tint = Color(0xFF818CF8), modifier = Modifier.size(13.dp))
            }
        }

        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!parsed.reasoning.isNullOrBlank()) {
                val accent = Color(0xFFFBBF24)
                Surface(
                    modifier = Modifier.fillMaxWidth().clickable { reasoningOpen = !reasoningOpen },
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFF121522),
                    border = BorderStroke(1.dp, accent.copy(alpha = 0.60f)),
                ) {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth().background(Color(0xFF3B0764).copy(alpha = 0.20f)).padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Psychology, null, tint = accent, modifier = Modifier.size(12.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "Pensamento (Máximo)${parsed.ms?.let { " · ${formatExactDuration(it)}" }.orEmpty()}",
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Medium,
                                    color = accent,
                                )
                            }
                            Icon(
                                if (reasoningOpen) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                null,
                                tint = accent,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                        AnimatedVisibility(reasoningOpen) {
                            Text(
                                parsed.reasoning,
                                modifier = Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.20f)).padding(12.dp),
                                fontSize = 12.sp,
                                lineHeight = 18.sp,
                                fontFamily = FontFamily.Monospace,
                                color = Color(0xFF94A3B8),
                            )
                        }
                    }
                }
            }

            if (parsed.answer.isNotBlank()) {
                if (showAd) {
                    val (beforeAd, afterAd) = remember(parsed.answer) { ChatAdSchedule.splitForBanner(parsed.answer) }
                    if (beforeAd.isNotBlank()) ExactAnswerBubble(beforeAd)
                    InlineAdBanner(enabled = true)
                    if (afterAd.isNotBlank()) ExactAnswerBubble(afterAd)
                } else {
                    ExactAnswerBubble(parsed.answer)
                }
                Row(
                    modifier = Modifier.padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ExactInlineAction("Copiar") { clipboard.setText(AnnotatedString(parsed.answer)) }
                    Text("·", fontSize = 12.sp, color = PMuted)
                    ExactInlineAction("Regenerar", enabled = canRerun, onClick = onRerun)
                    Text("·", fontSize = 12.sp, color = PMuted)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Bolt, null, tint = PEmerald.copy(alpha = 0.80f), modifier = Modifier.size(11.dp))
                        Spacer(Modifier.width(3.dp))
                        Text("NPU Local", fontSize = 11.sp, color = PEmerald.copy(alpha = 0.80f))
                    }
                }
            }
        }
    }
}

@Composable
private fun ExactAnswerBubble(text: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 16.dp),
        color = PCard,
        border = BorderStroke(1.dp, PBorder),
    ) {
        Text(
            text,
            modifier = Modifier.padding(16.dp),
            fontSize = 13.5.sp,
            lineHeight = 20.sp,
            color = PText2,
        )
    }
}

@Composable
private fun ExactInlineAction(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Text(
        label,
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick).padding(vertical = 4.dp),
        fontSize = 12.sp,
        color = if (enabled) PMuted else PMuted.copy(alpha = 0.45f),
    )
}

@Composable
private fun ExactLoading() {
    Row(
        modifier = Modifier.fillMaxWidth(0.96f),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Surface(
            modifier = Modifier.size(32.dp),
            shape = RoundedCornerShape(12.dp),
            color = Color(0xFF181C2D),
            border = BorderStroke(1.dp, PIndigo.copy(alpha = 0.30f)),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Default.SmartToy, null, tint = Color(0xFF818CF8), modifier = Modifier.size(13.dp))
            }
        }
        Surface(shape = RoundedCornerShape(12.dp), color = PCard, border = BorderStroke(1.dp, PBorder)) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp, color = Color(0xFFC084FC))
                Spacer(Modifier.width(8.dp))
                Text("Pensando...", fontSize = 12.sp, color = PText2)
            }
        }
    }
}

@Composable
private fun ExactComposer(
    draft: String,
    attachments: List<PendingAttachment>,
    queued: List<QueuedChatMessage>,
    generating: Boolean,
    processing: Boolean,
    recording: Boolean,
    agent: AgentEntity?,
    model: AiModelEntity?,
    deepThinkSupported: Boolean,
    onDraftChange: (String) -> Unit,
    onAttach: () -> Unit,
    onMic: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onRemoveQueued: (Int) -> Unit,
) {
    Column(Modifier.fillMaxWidth().imePadding().background(PBg)) {
        if (attachments.isNotEmpty()) {
            LazyRow(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(attachments, key = { it.id }) { attachment ->
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = PCard,
                        border = BorderStroke(1.dp, PIndigo.copy(alpha = 0.40f)),
                    ) {
                        Row(
                            Modifier.padding(start = 12.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                if (attachment.type == AttachmentType.AUDIO) Icons.Default.GraphicEq else Icons.Default.Description,
                                null,
                                tint = Color(0xFF818CF8),
                                modifier = Modifier.size(13.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                attachment.fileName,
                                modifier = Modifier.widthIn(max = 180.dp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                color = PText2,
                            )
                            ExactSquareIconButton(size = 28.dp, onClick = { onRemoveAttachment(attachment.id) }) {
                                Icon(Icons.Default.Close, "Remover anexo", tint = Color(0xFF94A3B8), modifier = Modifier.size(14.dp))
                            }
                        }
                    }
                }
            }
        }

        AnimatedVisibility(queued.isNotEmpty()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                queued.take(2).forEachIndexed { index, queuedMessage ->
                    Surface(shape = RoundedCornerShape(10.dp), color = PCard, border = BorderStroke(1.dp, PBorder)) {
                        Row(Modifier.padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("${index + 1}", fontSize = 10.sp, color = Color(0xFF818CF8))
                            Spacer(Modifier.width(8.dp))
                            Text(queuedMessage.content, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 11.sp, color = PText2)
                            ExactSquareIconButton(size = 30.dp, onClick = { onRemoveQueued(index) }) {
                                Icon(Icons.Default.Close, "Remover da fila", tint = PMuted, modifier = Modifier.size(13.dp))
                            }
                        }
                    }
                }
            }
        }

        AnimatedVisibility(processing || recording) {
            Surface(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
                shape = RoundedCornerShape(10.dp),
                color = if (recording) Color(0xFF3A1720) else PCard,
                border = BorderStroke(1.dp, if (recording) Color(0xFF7F1D1D) else PBorder),
            ) {
                Row(Modifier.padding(9.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (processing) CircularProgressIndicator(Modifier.size(13.dp), strokeWidth = 1.5.dp)
                    else Icon(Icons.Default.Mic, null, tint = Color(0xFFFB7185), modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (processing) "Processando anexo..." else "Gravando áudio... toque no microfone para finalizar.",
                        fontSize = 11.sp,
                        color = PText2,
                    )
                }
            }
        }

        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = PCard,
                border = BorderStroke(1.dp, PBorder),
                shadowElevation = 4.dp,
            ) {
                Column(Modifier.padding(horizontal = 10.dp, vertical = 10.dp)) {
                    BasicTextField(
                        value = draft,
                        onValueChange = onDraftChange,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp, max = 120.dp),
                        enabled = !processing,
                        textStyle = TextStyle(fontSize = 13.5.sp, lineHeight = 20.sp, color = PText),
                        decorationBox = { inner ->
                            Box(Modifier.fillMaxWidth()) {
                                if (draft.isEmpty()) {
                                    Text(
                                        if (generating) "Digite para adicionar à fila..." else "Envie uma mensagem ou anexe arquivos...",
                                        fontSize = 13.5.sp,
                                        lineHeight = 20.sp,
                                        color = PMuted,
                                    )
                                }
                                inner()
                            }
                        },
                    )

                    HorizontalDivider(color = PBorder.copy(alpha = 0.40f))
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ExactSquareIconButton(
                                size = 32.dp,
                                onClick = onAttach,
                                enabled = !generating && !recording && !processing,
                            ) {
                                Icon(Icons.Default.AttachFile, "Anexar arquivo", tint = Color(0xFF94A3B8), modifier = Modifier.size(14.dp))
                            }
                            if (deepThinkSupported && agent != null && model != null) {
                                ExactDeepThinkSelector(agent, model)
                            }
                        }

                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            ExactSquareIconButton(size = 32.dp, onClick = onMic, enabled = !generating && !processing) {
                                Icon(
                                    if (recording) Icons.Default.Stop else Icons.Default.Mic,
                                    if (recording) "Parar gravação" else "Gravar áudio",
                                    tint = if (recording) Color(0xFFFB7185) else Color(0xFF94A3B8),
                                    modifier = Modifier.size(14.dp),
                                )
                            }
                            if (generating) {
                                Surface(
                                    modifier = Modifier.size(32.dp).clickable(onClick = onStop),
                                    shape = RoundedCornerShape(12.dp),
                                    color = Color(0xFF7F1D1D),
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(Icons.Default.Stop, "Parar", tint = Color.White, modifier = Modifier.size(12.dp))
                                    }
                                }
                            }
                            Surface(
                                modifier = Modifier.size(32.dp).clickable(
                                    enabled = model != null && !processing && !recording && (draft.isNotBlank() || attachments.isNotEmpty()),
                                    onClick = onSend,
                                ),
                                shape = RoundedCornerShape(12.dp),
                                color = if (model != null && !processing && !recording && (draft.isNotBlank() || attachments.isNotEmpty())) PIndigoStrong else Color(0xFF1E293B),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Default.Send,
                                        "Enviar",
                                        tint = if (model != null && !processing && !recording && (draft.isNotBlank() || attachments.isNotEmpty())) Color.White else PMuted,
                                        modifier = Modifier.size(12.dp).rotate(-12f),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (model == null) "Baixe uma IA offline para enviar mensagens" else if (generating) "Enviar adiciona à fila" else "Enter envia · use nova linha pelo teclado",
                    fontSize = 11.sp,
                    color = PMuted,
                )
                Text("${draft.length} caracteres", fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, color = PMuted)
            }

            Box(
                Modifier.width(128.dp).height(4.dp).align(Alignment.CenterHorizontally)
                    .clip(CircleShape).background(Color(0xFF334155).copy(alpha = 0.60f))
            )
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ExactDeepThinkSelector(agent: AgentEntity, model: AiModelEntity) {
    val context = LocalContext.current
    val store = remember(context) { DeepThinkStore(context.applicationContext) }
    var open by remember(agent.id) { mutableStateOf(false) }
    var enabled by remember(agent.id) { mutableStateOf(store.isEnabled(agent.id)) }
    var level by remember(agent.id) { mutableStateOf(store.getLevel(agent.id)) }

    val accent = when {
        !enabled -> PMuted
        level == DeepThinkLevel.HIGH -> Color(0xFFFBBF24)
        level == DeepThinkLevel.MEDIUM -> Color(0xFF818CF8)
        level == DeepThinkLevel.LOW -> Color(0xFF22D3EE)
        else -> Color(0xFF818CF8)
    }
    val label = if (!enabled) "DeepThink: Desativado" else when (level) {
        DeepThinkLevel.HIGH -> "DeepThink: Máximo"
        DeepThinkLevel.MEDIUM -> "DeepThink: Profundo"
        DeepThinkLevel.LOW -> "DeepThink: Rápido"
        DeepThinkLevel.AUTO -> "DeepThink: Automático"
    }

    Surface(
        modifier = Modifier.clickable { open = true },
        shape = RoundedCornerShape(12.dp),
        color = PSurface,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.45f)),
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(accent))
            Spacer(Modifier.width(6.dp))
            Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = PText2)
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Default.ArrowDropDown, null, tint = Color(0xFF94A3B8), modifier = Modifier.size(10.dp))
        }
    }

    if (open) {
        ModalBottomSheet(
            onDismissRequest = { open = false },
            containerColor = PSurface,
            contentColor = PText,
            scrimColor = Color.Black.copy(alpha = 0.75f),
            dragHandle = null,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        ) {
            Column(
                Modifier.fillMaxWidth().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        modifier = Modifier.size(32.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = PPurple.copy(alpha = 0.20f),
                        border = BorderStroke(1.dp, PPurple.copy(alpha = 0.30f)),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Psychology, null, tint = Color(0xFFC084FC), modifier = Modifier.size(14.dp))
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("Nível de Pensamento", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Text("DeepThink Reasoning Budget", fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, color = Color(0xFFD8B4FE))
                    }
                }
                HorizontalDivider(color = PBorder.copy(alpha = 0.40f))
                Text(
                    "Defina o volume de reflexão lógica e o orçamento de geração dedicado a planejar e validar a resposta.",
                    fontSize = 11.sp,
                    lineHeight = 17.sp,
                    color = Color(0xFF94A3B8),
                )

                fun select(newLevel: DeepThinkLevel?) {
                    if (newLevel == null) {
                        store.setEnabled(agent.id, false)
                        enabled = false
                    } else {
                        store.setLevel(agent.id, newLevel)
                        store.setEnabled(agent.id, true)
                        level = newLevel
                        enabled = true
                    }
                    open = false
                }

                ExactDeepThinkOption(
                    icon = Icons.Default.Shield,
                    title = "Máximo (Auditoria Lógica)",
                    body = "Maior orçamento para validação detalhada, contraprovas e casos extremos.",
                    accent = Color(0xFFFBBF24),
                    selected = enabled && level == DeepThinkLevel.HIGH,
                ) { select(DeepThinkLevel.HIGH) }
                ExactDeepThinkOption(
                    icon = Icons.Default.Psychology,
                    title = "Profundo (Avançado)",
                    body = "Análise extensa de premissas, trade-offs e cenários complexos.",
                    accent = Color(0xFF818CF8),
                    selected = enabled && level == DeepThinkLevel.MEDIUM,
                ) { select(DeepThinkLevel.MEDIUM) }
                ExactDeepThinkOption(
                    icon = Icons.Default.Bolt,
                    title = "Rápido (Leve)",
                    body = "Raciocínio conciso para respostas diretas e menor latência.",
                    accent = Color(0xFF22D3EE),
                    selected = enabled && level == DeepThinkLevel.LOW,
                ) { select(DeepThinkLevel.LOW) }
                ExactDeepThinkOption(
                    icon = Icons.Default.Block,
                    title = "Desativado",
                    body = "Sem orçamento adicional de raciocínio.",
                    accent = PMuted,
                    selected = !enabled,
                ) { select(null) }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun ExactDeepThinkOption(
    icon: ImageVector,
    title: String,
    body: String,
    accent: Color,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = PCard,
        border = BorderStroke(1.dp, if (selected) accent.copy(alpha = 0.60f) else PBorder),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            Icon(icon, null, tint = accent, modifier = Modifier.size(12.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = if (accent == PMuted) PText2 else Color.White)
                Text(body, modifier = Modifier.padding(top = 3.dp), fontSize = 11.sp, lineHeight = 15.sp, color = Color(0xFF94A3B8))
            }
            if (selected) Icon(Icons.Default.CheckCircle, null, tint = accent, modifier = Modifier.size(14.dp))
        }
    }
}

@Composable
private fun ExactProfilesSheet(
    selected: BuiltInProfile,
    onSelect: (BuiltInProfile) -> Unit,
    onManage: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("</>", fontSize = 16.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = Color(0xFF818CF8))
            Spacer(Modifier.width(8.dp))
            Text("Selecionar Perfil de IA", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }
        BuiltInProfile.entries.forEach { profile ->
            Surface(
                modifier = Modifier.fillMaxWidth().clickable { onSelect(profile) },
                shape = RoundedCornerShape(12.dp),
                color = PCard,
                border = BorderStroke(1.dp, if (profile == selected) PIndigo.copy(alpha = 0.40f) else PBorder),
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        modifier = Modifier.size(32.dp),
                        shape = RoundedCornerShape(8.dp),
                        color = PIndigo.copy(alpha = 0.20f),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text("</>", fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = Color(0xFF818CF8))
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(profile.displayName, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                        Text(profile.description, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 11.sp, color = Color(0xFF94A3B8))
                    }
                    if (profile == selected) Icon(Icons.Default.Check, null, tint = Color(0xFF818CF8), modifier = Modifier.size(12.dp))
                }
            }
        }
        TextButton(onClick = onManage, modifier = Modifier.fillMaxWidth()) {
            Text("Gerenciar perfis em Configurações", fontSize = 12.sp)
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun ExactModelsSheet(
    selectedId: String?,
    models: List<AiModelEntity>,
    onSelect: (String) -> Unit,
    onManage: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Memory, null, tint = Color(0xFFC084FC), modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text("Modelos Locais & Dispositivo", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }
        Text("Modelos verificados rodam localmente no aparelho.", fontSize = 11.sp, color = Color(0xFF94A3B8))
        models.forEach { model ->
            val provider = model.catalogProvider()
            Surface(
                modifier = Modifier.fillMaxWidth().clickable { onSelect(model.id) },
                shape = RoundedCornerShape(12.dp),
                color = PCard,
                border = BorderStroke(1.dp, if (model.id == selectedId) PPurple.copy(alpha = 0.40f) else PBorder),
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (provider != null) ProviderLogo(provider, size = 32.dp)
                    else Surface(Modifier.size(32.dp), RoundedCornerShape(8.dp), color = PPurple.copy(alpha = 0.18f)) {
                        Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.Memory, null, tint = Color(0xFFC084FC), modifier = Modifier.size(14.dp)) }
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(model.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                        Text(
                            provider?.let { "${it.brandName()} · Local" } ?: "Modelo local verificado",
                            fontSize = 11.sp,
                            color = Color(0xFF94A3B8),
                        )
                    }
                    Text(
                        if (model.id == selectedId) "Ativo" else "Instalado",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (model.id == selectedId) PEmerald else Color(0xFF94A3B8),
                    )
                }
            }
        }
        TextButton(onClick = onManage, modifier = Modifier.fillMaxWidth()) {
            Text("Gerenciar modelos offline", fontSize = 12.sp)
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun ExactSquareIconButton(
    size: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier.size(size).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

private data class ExactParsedAssistant(
    val reasoning: String?,
    val answer: String,
    val ms: Long?,
)

private fun exactParseAssistant(raw: String): ExactParsedAssistant {
    val metadata = EXACT_REASONING_METADATA.find(raw)
    val ms = metadata?.groupValues?.getOrNull(1)?.toLongOrNull()
    val clean = EXACT_REASONING_METADATA.replace(raw, "").trim()
    val block = EXACT_REASONING_BLOCK.find(clean)
    if (block != null) {
        return ExactParsedAssistant(
            reasoning = block.groupValues[1].trim(),
            answer = clean.removeRange(block.range).trim(),
            ms = ms,
        )
    }
    val open = EXACT_REASONING_OPEN.find(clean)
    if (open != null) {
        return ExactParsedAssistant(
            reasoning = clean.substring(open.range.last + 1).trim(),
            answer = clean.substring(0, open.range.first).trim(),
            ms = ms,
        )
    }
    return ExactParsedAssistant(null, clean, ms)
}

private val EXACT_REASONING_OPEN = Regex("(?is)<think(?:ing)?>")
private val EXACT_REASONING_BLOCK = Regex("(?is)<think(?:ing)?>(.*?)</think(?:ing)?>")
private val EXACT_REASONING_METADATA = Regex("(?is)\\s*<!--nexus_reasoning_ms:(\\d+)-->\\s*$")

private fun formatExactTime(timestamp: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))

private fun formatExactDuration(ms: Long): String {
    val seconds = ms.coerceAtLeast(0L) / 1000.0
    return if (seconds < 60.0) String.format(Locale.getDefault(), "%.1fs", seconds)
    else "${(seconds / 60).toInt()}m ${(seconds % 60).toInt()}s"
}

private fun exactHistoryGroup(timestamp: Long): String {
    val now = Calendar.getInstance()
    val day = Calendar.getInstance().apply { timeInMillis = timestamp }
    val nowDay = now.get(Calendar.DAY_OF_YEAR)
    val itemDay = day.get(Calendar.DAY_OF_YEAR)
    val sameYear = now.get(Calendar.YEAR) == day.get(Calendar.YEAR)
    return when {
        sameYear && itemDay == nowDay -> "Hoje"
        sameYear && itemDay == nowDay - 1 -> "Ontem"
        else -> "Anteriores"
    }
}

private data class ExactMemoryInfo(val used: String, val total: String, val fraction: Float)

@Composable
private fun rememberExactMemory(): ExactMemoryInfo {
    val context = LocalContext.current
    return remember(context) {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(info)
        val totalGb = info.totalMem / 1_073_741_824.0
        val usedGb = (info.totalMem - info.availMem) / 1_073_741_824.0
        ExactMemoryInfo(
            used = String.format(Locale.getDefault(), "%.1f GB", usedGb),
            total = String.format(Locale.getDefault(), "%.0f GB", totalGb),
            fraction = if (info.totalMem > 0L) ((info.totalMem - info.availMem).toFloat() / info.totalMem.toFloat()).coerceIn(0f, 1f) else 0f,
        )
    }
}
