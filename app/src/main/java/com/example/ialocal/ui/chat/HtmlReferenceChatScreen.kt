package com.example.ialocal.ui.chat

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ialocal.audio.AudioRecorder
import com.example.ialocal.chat.QueuedChatMessage
import com.example.ialocal.data.*
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

private val HBg = Color(0xFF090B11)
private val HSurface = Color(0xFF111420)
private val HCard = Color(0xFF161A29)
private val HBorder = Color(0xFF23283E)
private val HBorderLight = Color(0xFF303650)
private val HIndigo = Color(0xFF6366F1)
private val HIndigoStrong = Color(0xFF4F46E5)
private val HPurple = Color(0xFFA855F7)
private val HEmerald = Color(0xFF34D399)
private val HText = Color(0xFFF1F5F9)
private val HText2 = Color(0xFFCBD5E1)
private val HMuted = Color(0xFF64748B)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HtmlReferenceChatScreen(
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
    val processing by viewModel.isProcessingAttachments.collectAsStateWithLifecycle()
    val queued by viewModel.queuedMessages.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val agents by viewModel.agents.collectAsStateWithLifecycle()
    val models by viewModel.models.collectAsStateWithLifecycle()
    val selectedAgentId by viewModel.selectedAgentId.collectAsStateWithLifecycle()

    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val listState = rememberLazyListState()
    val recorder = remember { AudioRecorder(context) }
    var recording by remember { mutableStateOf(false) }
    var profileSheet by remember { mutableStateOf(false) }
    var modelSheet by remember { mutableStateOf(false) }
    var createProfile by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    var pendingAudio by remember { mutableStateOf<android.net.Uri?>(null) }

    val readyModels = remember(models) { models.filter { it.verificationStatus == ModelVerificationStatus.VERIFIED.name } }
    val selectedAgent = agents.firstOrNull { it.id == selectedAgentId }
        ?: agents.firstOrNull { it.id == conversation?.agentId }
        ?: agents.firstOrNull { it.isDefault }
    val selectedModel = readyModels.firstOrNull { it.id == selectedAgent?.modelId }
        ?: readyModels.firstOrNull { it.isActive }
        ?: readyModels.firstOrNull()
    val modelAgents = remember(agents, selectedModel?.id) { agents.filter { it.modelId == selectedModel?.id } }
    val deepThinkSupported = selectedModel?.let { DeepThinkSupport.capability(it).supported } == true

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) pendingAudio = null
        else pendingAudio?.let {
            pendingAudio = null
            viewModel.importFile(it)
        } ?: runCatching { recorder.start(); recording = true }
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val audio = context.contentResolver.getType(uri)?.startsWith("audio/") == true
            val hasPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            if (audio && !hasPermission) { pendingAudio = uri; micPermission.launch(Manifest.permission.RECORD_AUDIO) }
            else viewModel.importFile(uri)
        }
    }
    val onMic: () -> Unit = {
        if (recording) {
            recorder.stop()?.let(viewModel::addPendingAttachment)
            recording = false
        } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            runCatching { recorder.start(); recording = true }
        } else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    DisposableEffect(Unit) { onDispose { recorder.cancel() } }
    LaunchedEffect(messages.size) { if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex) }
    LaunchedEffect(error) { error?.let { snackbar.showSnackbar(it); viewModel.clearError() } }

    Scaffold(containerColor = HBg, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        ModalNavigationDrawer(
            modifier = Modifier.fillMaxSize().padding(padding),
            drawerState = drawerState,
            drawerContent = {
                ModalDrawerSheet(
                    modifier = Modifier.fillMaxHeight().fillMaxWidth(.84f).widthIn(max = 310.dp),
                    drawerContainerColor = HSurface,
                    drawerContentColor = HText,
                ) {
                    HtmlDrawer(
                        conversations = conversations,
                        currentId = conversation?.id,
                        query = search,
                        onQuery = { search = it },
                        modelReady = selectedModel != null,
                        onNew = { viewModel.createConversation(onOpenConversation); scope.launch { drawerState.close() } },
                        onConversation = { onOpenConversation(it); scope.launch { drawerState.close() } },
                        onHistory = { onOpenHistory(); scope.launch { drawerState.close() } },
                        onSettings = { onOpenSettings(); scope.launch { drawerState.close() } },
                    )
                }
            },
        ) {
            HtmlBody(
                selectedAgent = selectedAgent,
                selectedModel = selectedModel,
                messages = messages,
                listState = listState,
                draft = draft,
                attachments = attachments,
                queued = queued,
                generating = isGenerating,
                processing = processing,
                recording = recording,
                deepThinkSupported = deepThinkSupported,
                onMenu = { scope.launch { drawerState.open() } },
                onProfile = { profileSheet = true },
                onModel = { modelSheet = true },
                onNew = { viewModel.createConversation(onOpenConversation) },
                onDraft = viewModel::setDraft,
                onAttach = { filePicker.launch(arrayOf("text/plain", "text/markdown", "application/pdf", "application/json", "text/csv", "audio/*")) },
                onMic = onMic,
                onSend = viewModel::send,
                onStop = viewModel::stopGeneration,
                onRerun = viewModel::rerunAssistant,
                onRemoveAttachment = viewModel::removePendingAttachment,
                onRemoveQueued = viewModel::removeQueuedMessage,
            )
        }
    }

    if (profileSheet) {
        ModalBottomSheet(
            onDismissRequest = { profileSheet = false },
            containerColor = HSurface,
            contentColor = HText,
            scrimColor = Color.Black.copy(alpha = .72f),
        ) {
            HtmlProfiles(
                selected = selectedAgent,
                agents = modelAgents,
                onSelect = { viewModel.selectAgent(it.id); profileSheet = false },
                onCreate = { profileSheet = false; createProfile = true },
            )
        }
    }
    if (modelSheet) {
        ModalBottomSheet(
            onDismissRequest = { modelSheet = false },
            containerColor = HSurface,
            contentColor = HText,
            scrimColor = Color.Black.copy(alpha = .72f),
        ) {
            HtmlModels(
                selectedModel?.id,
                readyModels,
                onSelect = { viewModel.selectModel(it); modelSheet = false },
                onManage = { modelSheet = false; onOpenModels() },
            )
        }
    }
    if (createProfile && selectedModel != null) {
        HtmlProfileDialog(
            onDismiss = { createProfile = false },
            onSave = { name, prompt ->
                viewModel.createAgentProfile(selectedModel.id, name, prompt) { createProfile = false }
            },
        )
    }
}

@Composable
private fun HtmlDrawer(
    conversations: List<ConversationListItem>,
    currentId: String?,
    query: String,
    onQuery: (String) -> Unit,
    modelReady: Boolean,
    onNew: () -> Unit,
    onConversation: (String) -> Unit,
    onHistory: () -> Unit,
    onSettings: () -> Unit,
) {
    val filtered = remember(conversations, query) {
        val q = query.trim().lowercase(Locale.getDefault())
        if (q.isBlank()) conversations else conversations.filter {
            it.title.lowercase(Locale.getDefault()).contains(q) || it.lastMessage.orEmpty().lowercase(Locale.getDefault()).contains(q)
        }
    }
    val groups = remember(filtered) { filtered.groupBy { historyGroup(it.updatedAt) } }
    val memory = rememberDeviceMemory()

    Column(Modifier.fillMaxSize().background(HSurface)) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(Modifier.size(28.dp), RoundedCornerShape(9.dp), color = HIndigo.copy(alpha = .18f)) {
                    Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.SmartToy, null, tint = Color(0xFF818CF8), modifier = Modifier.size(15.dp)) }
                }
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("DeepThink Local", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(6.dp).clip(CircleShape).background(if (modelReady) HEmerald else HMuted))
                        Spacer(Modifier.width(5.dp))
                        Text(if (modelReady) "NPU Online · On-Device" else "Aguardando modelo local", fontSize = 9.sp, color = if (modelReady) HEmerald else HMuted)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Surface(
                modifier = Modifier.fillMaxWidth().clickable(onClick = onNew),
                shape = RoundedCornerShape(12.dp),
                color = Color.Transparent,
            ) {
                Row(
                    Modifier.fillMaxWidth().background(Brush.horizontalGradient(listOf(HIndigoStrong, Color(0xFF9333EA)))).padding(10.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Add, null, tint = Color.White, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(7.dp))
                    Text("Novo chat", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                }
            }
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = query,
                onValueChange = onQuery,
                modifier = Modifier.fillMaxWidth().height(44.dp),
                singleLine = true,
                placeholder = { Text("Buscar conversas...", fontSize = 10.sp, color = HMuted) },
                leadingIcon = { Icon(Icons.Default.Search, null, tint = HMuted, modifier = Modifier.size(15.dp)) },
                shape = RoundedCornerShape(12.dp),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = HCard, unfocusedContainerColor = HCard,
                    focusedBorderColor = HIndigo.copy(alpha = .55f), unfocusedBorderColor = HBorder,
                    focusedTextColor = HText2, unfocusedTextColor = HText2,
                ),
            )
        }
        HorizontalDivider(color = HBorder.copy(alpha = .5f))
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 10.dp),
            contentPadding = PaddingValues(vertical = 7.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            listOf("Hoje", "Ontem", "Anteriores").forEach { group ->
                groups[group].orEmpty().takeIf { it.isNotEmpty() }?.let { groupItems ->
                    item { Text(group.uppercase(), modifier = Modifier.padding(4.dp, 7.dp, 4.dp, 3.dp), fontSize = 9.sp, fontWeight = FontWeight.Bold, color = HMuted) }
                    items(groupItems, key = { it.id }) { item ->
                        val active = item.id == currentId
                        Surface(
                            modifier = Modifier.fillMaxWidth().clickable { onConversation(item.id) },
                            shape = RoundedCornerShape(12.dp),
                            color = if (active) Color(0xFF1E1B4B).copy(alpha = .55f) else HCard.copy(alpha = .55f),
                            border = BorderStroke(1.dp, if (active) HIndigo.copy(alpha = .35f) else HBorder.copy(alpha = .65f)),
                        ) {
                            Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.ChatBubbleOutline, null, tint = if (active) Color(0xFF818CF8) else HMuted, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 10.sp, color = HText2)
                                    item.lastMessage?.takeIf { it.isNotBlank() }?.let { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 8.sp, color = HMuted) }
                                }
                            }
                        }
                    }
                }
            }
            if (filtered.isEmpty()) item { Text("Nenhuma conversa encontrada", modifier = Modifier.fillMaxWidth().padding(24.dp), textAlign = TextAlign.Center, fontSize = 10.sp, color = HMuted) }
        }
        HorizontalDivider(color = HBorder.copy(alpha = .5f))
        Column(Modifier.fillMaxWidth().background(HBg.copy(alpha = .55f)).padding(10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Surface(shape = RoundedCornerShape(12.dp), color = HCard, border = BorderStroke(1.dp, HBorder.copy(alpha = .7f))) {
                Column(Modifier.padding(10.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Memória do dispositivo", fontSize = 9.sp, color = HMuted)
                        Text("${memory.used} / ${memory.total}", fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = Color(0xFF818CF8))
                    }
                    Spacer(Modifier.height(6.dp))
                    Box(Modifier.fillMaxWidth().height(5.dp).clip(CircleShape).background(Color(0xFF1E293B))) {
                        Box(Modifier.fillMaxWidth(memory.fraction).fillMaxHeight().background(Brush.horizontalGradient(listOf(HIndigo, HPurple))))
                    }
                }
            }
            DrawerAction(Icons.Default.ChatBubbleOutline, "Abrir histórico completo", onHistory)
            DrawerAction(Icons.Default.Settings, "Configurações & IA", onSettings)
        }
    }
}

@Composable
private fun DrawerAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Surface(Modifier.fillMaxWidth().clickable(onClick = onClick), RoundedCornerShape(12.dp), color = HCard, border = BorderStroke(1.dp, HBorder)) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = HMuted, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(8.dp)); Text(label, fontSize = 10.sp, color = HText2)
        }
    }
}

@Composable
private fun HtmlBody(
    selectedAgent: AgentEntity?, selectedModel: AiModelEntity?, messages: List<MessageWithAttachments>,
    listState: androidx.compose.foundation.lazy.LazyListState, draft: String, attachments: List<PendingAttachment>,
    queued: List<QueuedChatMessage>, generating: Boolean, processing: Boolean, recording: Boolean, deepThinkSupported: Boolean,
    onMenu: () -> Unit, onProfile: () -> Unit, onModel: () -> Unit, onNew: () -> Unit, onDraft: (String) -> Unit,
    onAttach: () -> Unit, onMic: () -> Unit, onSend: () -> Unit, onStop: () -> Unit,
    onRerun: (String) -> Unit, onRemoveAttachment: (String) -> Unit, onRemoveQueued: (Int) -> Unit,
) {
    Column(Modifier.fillMaxSize().background(HBg)) {
        HtmlTopBar(selectedAgent?.name ?: "Perfil", selectedModel?.name ?: "Modelo", onMenu, onProfile, onModel)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (messages.isEmpty()) HtmlWelcome(Modifier.fillMaxSize(), selectedAgent?.name ?: "Assistente Geral", deepThinkSupported)
            else {
                LazyColumn(
                    state = listState, modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 15.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    items(messages, key = { it.message.id }) { item -> HtmlMessage(item, !generating) { onRerun(item.message.id) } }
                    if (generating) item { HtmlLoading() }
                }
                Surface(
                    modifier = Modifier.align(Alignment.TopEnd).padding(10.dp).clickable(onClick = onNew),
                    shape = RoundedCornerShape(50), color = HCard.copy(alpha = .94f), border = BorderStroke(1.dp, HBorder),
                ) {
                    Row(Modifier.padding(horizontal = 9.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Add, null, tint = HText2, modifier = Modifier.size(11.dp)); Spacer(Modifier.width(4.dp)); Text("Novo chat", fontSize = 9.sp, color = HText2)
                    }
                }
            }
        }
        HtmlComposer(
            draft, attachments, queued, generating, processing, recording, selectedAgent, selectedModel, deepThinkSupported,
            onDraft, onAttach, onMic, onSend, onStop, onRemoveAttachment, onRemoveQueued,
        )
    }
}

@Composable
private fun HtmlTopBar(agent: String, model: String, onMenu: () -> Unit, onProfile: () -> Unit, onModel: () -> Unit) {
    Surface(color = HBg, border = BorderStroke(.5.dp, HBorder.copy(alpha = .55f))) {
        Row(
            Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Surface(Modifier.size(32.dp).clickable(onClick = onMenu), CircleShape, HCard, border = BorderStroke(1.dp, HBorder)) {
                    Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.Menu, "Histórico e configurações", tint = HText2, modifier = Modifier.size(16.dp)) }
                }
                Selector(Modifier.weight(1f, false).widthIn(max = 165.dp), Icons.Default.Code, agent, onProfile)
            }
            Spacer(Modifier.width(6.dp))
            Selector(Modifier.widthIn(max = 140.dp), Icons.Default.Memory, model, onModel)
        }
    }
}

@Composable
private fun Selector(modifier: Modifier, icon: ImageVector, label: String, onClick: () -> Unit) {
    Surface(modifier.clickable(onClick = onClick), RoundedCornerShape(50), HCard, border = BorderStroke(1.dp, HBorder)) {
        Row(Modifier.padding(horizontal = 9.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = Color(0xFF818CF8), modifier = Modifier.size(13.dp)); Spacer(Modifier.width(5.dp))
            Text(label, modifier = Modifier.weight(1f, false), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 9.sp, color = HText2)
            Spacer(Modifier.width(3.dp)); Icon(Icons.Default.ArrowDropDown, null, tint = HMuted, modifier = Modifier.size(13.dp))
        }
    }
}

@Composable
private fun HtmlWelcome(modifier: Modifier, profile: String, deepThink: Boolean) {
    Box(modifier.padding(horizontal = 24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Como posso ajudar você agora?", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text("Você está conversando com o perfil $profile. Tudo é processado localmente no aparelho.", fontSize = 11.sp, lineHeight = 16.sp, color = Color(0xFF94A3B8), textAlign = TextAlign.Center)
            if (deepThink) {
                Spacer(Modifier.height(13.dp))
                Surface(shape = RoundedCornerShape(50), color = Color(0xFF581C87).copy(alpha = .27f), border = BorderStroke(1.dp, HPurple.copy(alpha = .22f))) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(6.dp).clip(CircleShape).background(Color(0xFFC084FC))); Spacer(Modifier.width(6.dp))
                        Text("DeepThink disponível para este modelo.", fontSize = 9.sp, color = Color(0xFFD8B4FE))
                    }
                }
            }
        }
    }
}

@Composable
private fun HtmlMessage(item: MessageWithAttachments, canRerun: Boolean, onRerun: () -> Unit) {
    if (item.message.role == MessageRole.USER.name) HtmlUser(item) else HtmlAssistant(item, canRerun, onRerun)
}

@Composable
private fun HtmlUser(item: MessageWithAttachments) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        Column(Modifier.widthIn(max = 330.dp), horizontalAlignment = Alignment.End) {
            Surface(
                shape = RoundedCornerShape(topStart = 16.dp, topEnd = 4.dp, bottomStart = 16.dp, bottomEnd = 16.dp),
                color = Color.Transparent,
            ) {
                Column(Modifier.background(Brush.horizontalGradient(listOf(Color(0xFF4F46E5), Color(0xFF6D28D9)))).padding(12.dp)) {
                    if (item.attachments.isNotEmpty()) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            items(item.attachments, key = { it.id }) { a ->
                                Surface(shape = RoundedCornerShape(8.dp), color = Color.White.copy(alpha = .09f), border = BorderStroke(1.dp, Color.White.copy(alpha = .12f))) {
                                    Row(Modifier.padding(horizontal = 7.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(if (a.type == AttachmentType.AUDIO.name) Icons.Default.GraphicEq else Icons.Default.Description, null, tint = Color(0xFFE0E7FF), modifier = Modifier.size(11.dp)); Spacer(Modifier.width(4.dp))
                                        Text(a.fileName, maxLines = 1, fontSize = 8.sp, color = Color(0xFFE0E7FF))
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(7.dp))
                    }
                    Text(item.message.content, fontSize = 11.sp, lineHeight = 17.sp, color = Color.White)
                }
            }
            Text(formatTime(item.message.createdAt), modifier = Modifier.padding(top = 4.dp, end = 3.dp), fontSize = 8.sp, color = HMuted)
        }
    }
}

@Composable
private fun HtmlAssistant(item: MessageWithAttachments, canRerun: Boolean, onRerun: () -> Unit) {
    val parsed = remember(item.message.content) { parseAssistant(item.message.content) }
    var openReasoning by remember(item.message.id) { mutableStateOf(true) }
    val clipboard = LocalClipboardManager.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        Surface(Modifier.size(30.dp), RoundedCornerShape(10.dp), color = Color(0xFF181C2D), border = BorderStroke(1.dp, HIndigo.copy(alpha = .28f))) {
            Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.SmartToy, null, tint = Color(0xFF818CF8), modifier = Modifier.size(14.dp)) }
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("DeepThink Local", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFA5B4FC))
                Spacer(Modifier.width(6.dp)); Text(formatTime(item.message.createdAt), fontSize = 8.sp, color = HMuted)
            }
            if (!parsed.reasoning.isNullOrBlank()) {
                Spacer(Modifier.height(6.dp))
                Surface(
                    Modifier.fillMaxWidth().clickable { openReasoning = !openReasoning },
                    RoundedCornerShape(11.dp), HCard.copy(alpha = .75f), border = BorderStroke(1.dp, HBorder),
                ) {
                    Column(Modifier.padding(9.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Psychology, null, tint = Color(0xFFC084FC), modifier = Modifier.size(13.dp)); Spacer(Modifier.width(5.dp))
                                Text(if (openReasoning) "Ocultar raciocínio" else "Ver raciocínio", fontSize = 9.sp, color = HText2)
                                parsed.ms?.let { Spacer(Modifier.width(5.dp)); Text("• ${formatDuration(it)}", fontSize = 8.sp, color = HMuted) }
                            }
                            Icon(if (openReasoning) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, null, tint = HMuted, modifier = Modifier.size(15.dp))
                        }
                        AnimatedVisibility(openReasoning) {
                            Column { Spacer(Modifier.height(7.dp)); HorizontalDivider(color = HBorder); Spacer(Modifier.height(7.dp)); Text(parsed.reasoning, fontSize = 9.sp, lineHeight = 14.sp, color = Color(0xFF94A3B8)) }
                        }
                    }
                }
            }
            if (parsed.answer.isNotBlank()) {
                Spacer(Modifier.height(7.dp))
                Surface(Modifier.fillMaxWidth(), RoundedCornerShape(topStart = 4.dp, topEnd = 15.dp, bottomStart = 15.dp, bottomEnd = 15.dp), HCard, border = BorderStroke(1.dp, HBorder)) {
                    Text(parsed.answer, modifier = Modifier.padding(12.dp), fontSize = 11.sp, lineHeight = 18.sp, color = HText2)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { clipboard.setText(AnnotatedString(parsed.answer)) }, contentPadding = PaddingValues(horizontal = 5.dp, vertical = 0.dp)) { Text("Copiar", fontSize = 8.sp, color = HMuted) }
                    Text("·", color = HMuted, fontSize = 8.sp)
                    TextButton(onClick = onRerun, enabled = canRerun, contentPadding = PaddingValues(horizontal = 5.dp, vertical = 0.dp)) { Text("Regenerar", fontSize = 8.sp, color = HMuted) }
                    Text("· NPU Local", fontSize = 8.sp, color = HEmerald.copy(alpha = .8f))
                }
            }
        }
    }
}

@Composable
private fun HtmlLoading() {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        Surface(Modifier.size(30.dp), RoundedCornerShape(10.dp), color = Color(0xFF181C2D), border = BorderStroke(1.dp, HIndigo.copy(alpha = .28f))) {
            Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.SmartToy, null, tint = Color(0xFF818CF8), modifier = Modifier.size(14.dp)) }
        }
        Surface(shape = RoundedCornerShape(12.dp), color = HCard, border = BorderStroke(1.dp, HBorder)) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(13.dp), strokeWidth = 1.5.dp, color = Color(0xFFC084FC)); Spacer(Modifier.width(7.dp)); Text("Pensando...", fontSize = 9.sp, color = HText2)
            }
        }
    }
}

@Composable
private fun HtmlComposer(
    draft: String, attachments: List<PendingAttachment>, queued: List<QueuedChatMessage>, generating: Boolean,
    processing: Boolean, recording: Boolean, agent: AgentEntity?, model: AiModelEntity?, deepThinkSupported: Boolean,
    onDraft: (String) -> Unit, onAttach: () -> Unit, onMic: () -> Unit, onSend: () -> Unit, onStop: () -> Unit,
    onRemoveAttachment: (String) -> Unit, onRemoveQueued: (Int) -> Unit,
) {
    Surface(color = HBg) {
        Column(Modifier.fillMaxWidth().imePadding().padding(horizontal = 12.dp, vertical = 9.dp)) {
            AnimatedVisibility(queued.isNotEmpty()) {
                Column(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    queued.take(2).forEachIndexed { i, q ->
                        Surface(Modifier.fillMaxWidth(), RoundedCornerShape(9.dp), HCard, border = BorderStroke(1.dp, HBorder)) {
                            Row(Modifier.padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("${i + 1}", fontSize = 8.sp, color = Color(0xFF818CF8)); Spacer(Modifier.width(7.dp)); Text(q.content, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 9.sp, color = HText2)
                                IconButton({ onRemoveQueued(i) }, modifier = Modifier.size(28.dp)) { Icon(Icons.Default.Close, "Remover", tint = HMuted, modifier = Modifier.size(12.dp)) }
                            }
                        }
                    }
                }
            }
            if (attachments.isNotEmpty()) {
                LazyRow(Modifier.padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(attachments, key = { it.id }) { a ->
                        Surface(shape = RoundedCornerShape(9.dp), color = HCard, border = BorderStroke(1.dp, HIndigo.copy(alpha = .36f))) {
                            Row(Modifier.padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(if (a.type == AttachmentType.AUDIO) Icons.Default.GraphicEq else Icons.Default.Description, null, tint = Color(0xFF818CF8), modifier = Modifier.size(12.dp)); Spacer(Modifier.width(5.dp))
                                Text(a.fileName, modifier = Modifier.widthIn(max = 145.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 9.sp, color = HText2)
                                IconButton({ onRemoveAttachment(a.id) }, modifier = Modifier.size(27.dp)) { Icon(Icons.Default.Close, "Remover anexo", tint = HMuted, modifier = Modifier.size(12.dp)) }
                            }
                        }
                    }
                }
            }
            AnimatedVisibility(processing || recording) {
                Surface(Modifier.fillMaxWidth().padding(bottom = 6.dp), RoundedCornerShape(9.dp), if (recording) Color(0xFF3A1720) else HCard, border = BorderStroke(1.dp, if (recording) Color(0xFF7F1D1D) else HBorder)) {
                    Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (processing) CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp) else Icon(Icons.Default.Mic, null, tint = Color(0xFFFB7185), modifier = Modifier.size(13.dp))
                        Spacer(Modifier.width(6.dp)); Text(if (processing) "Processando anexo..." else "Gravando áudio... toque no microfone para finalizar.", fontSize = 8.sp, color = HText2)
                    }
                }
            }
            Surface(shape = RoundedCornerShape(17.dp), color = HCard, border = BorderStroke(1.dp, HBorder)) {
                Column {
                    TextField(
                        value = draft, onValueChange = onDraft, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp, max = 140.dp),
                        placeholder = { Text(if (generating) "Digite para adicionar à fila..." else "Envie uma mensagem ou anexe arquivos...", fontSize = 10.sp, color = HMuted) },
                        enabled = !processing, maxLines = 5,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { if (draft.isNotBlank() || attachments.isNotEmpty()) onSend() }),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 11.sp, lineHeight = 17.sp),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, disabledContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, disabledIndicatorColor = Color.Transparent,
                            focusedTextColor = HText, unfocusedTextColor = HText,
                        ),
                    )
                    HorizontalDivider(color = HBorder.copy(alpha = .7f))
                    Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = onAttach, enabled = !generating && !recording && !processing) { Icon(Icons.Default.AttachFile, "Anexar arquivo", tint = HMuted, modifier = Modifier.size(17.dp)) }
                            if (deepThinkSupported && agent != null && model != null) HtmlDeepThink(agent, model)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            IconButton(onClick = onMic, enabled = !generating && !processing) { Icon(if (recording) Icons.Default.Stop else Icons.Default.Mic, if (recording) "Parar gravação" else "Gravar áudio", tint = if (recording) Color(0xFFFB7185) else HMuted, modifier = Modifier.size(17.dp)) }
                            if (generating) FilledIconButton(onClick = onStop, modifier = Modifier.size(34.dp), shape = RoundedCornerShape(10.dp), colors = ButtonDefaults.filledIconButtonColors(containerColor = Color(0xFF7F1D1D))) { Icon(Icons.Default.Stop, "Parar", modifier = Modifier.size(14.dp)) }
                            FilledIconButton(
                                onClick = onSend, enabled = !processing && !recording && (draft.isNotBlank() || attachments.isNotEmpty()),
                                modifier = Modifier.size(34.dp), shape = RoundedCornerShape(10.dp), colors = ButtonDefaults.filledIconButtonColors(containerColor = HIndigoStrong),
                            ) { Icon(Icons.Default.Send, "Enviar", modifier = Modifier.size(14.dp)) }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 5.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(if (generating) "Enviar adiciona à fila" else "Enter envia · use nova linha pelo teclado", fontSize = 8.sp, color = HMuted)
                Text("${draft.length} caracteres", fontSize = 8.sp, fontFamily = FontFamily.Monospace, color = HMuted)
            }
            Box(Modifier.width(112.dp).height(4.dp).align(Alignment.CenterHorizontally).clip(CircleShape).background(Color(0xFF334155).copy(alpha = .65f)))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HtmlDeepThink(agent: AgentEntity, model: AiModelEntity) {
    val context = LocalContext.current
    val store = remember(context) { DeepThinkStore(context.applicationContext) }
    var open by remember(agent.id) { mutableStateOf(false) }
    var enabled by remember(agent.id) { mutableStateOf(store.isEnabled(agent.id)) }
    var level by remember(agent.id) { mutableStateOf(store.getLevel(agent.id)) }
    val accent = when { !enabled -> HMuted; level == DeepThinkLevel.HIGH -> Color(0xFFFBBF24); level == DeepThinkLevel.MEDIUM -> Color(0xFF818CF8); else -> Color(0xFF22D3EE) }
    val label = if (enabled) when (level) { DeepThinkLevel.HIGH -> "DeepThink: Máximo"; DeepThinkLevel.MEDIUM -> "DeepThink: Profundo"; DeepThinkLevel.LOW -> "DeepThink: Rápido"; DeepThinkLevel.AUTO -> "DeepThink: Automático" } else "DeepThink: Desativado"
    Surface(Modifier.clickable { open = true }, RoundedCornerShape(11.dp), HSurface, border = BorderStroke(1.dp, accent.copy(alpha = .5f))) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(accent)); Spacer(Modifier.width(5.dp)); Text(label, fontSize = 8.sp, color = HText2); Icon(Icons.Default.ArrowDropDown, null, tint = HMuted, modifier = Modifier.size(12.dp))
        }
    }
    if (open) ModalBottomSheet(onDismissRequest = { open = false }, containerColor = HSurface, contentColor = HText, scrimColor = Color.Black.copy(alpha = .75f)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Psychology, null, tint = Color(0xFFC084FC)); Spacer(Modifier.width(8.dp)); Column { Text("Nível de Pensamento", fontSize = 13.sp, fontWeight = FontWeight.Bold); Text("DeepThink Reasoning Budget", fontSize = 8.sp, fontFamily = FontFamily.Monospace, color = Color(0xFFD8B4FE)) } }
            Text("Defina o volume de reflexão lógica e o orçamento de geração dedicado a planejar e validar a resposta.", fontSize = 9.sp, lineHeight = 14.sp, color = Color(0xFF94A3B8))
            fun choose(newLevel: DeepThinkLevel?) {
                if (newLevel == null) { store.setEnabled(agent.id, false); enabled = false }
                else { store.setLevel(agent.id, newLevel); store.setEnabled(agent.id, true); level = newLevel; enabled = true }
                open = false
            }
            DtOption(Icons.Default.VerifiedUser, "Máximo (Auditoria Lógica)", "Maior orçamento para validação detalhada e casos extremos.", Color(0xFFFBBF24), enabled && level == DeepThinkLevel.HIGH) { choose(DeepThinkLevel.HIGH) }
            DtOption(Icons.Default.Psychology, "Profundo (Avançado)", "Análise extensa com melhor equilíbrio de latência.", Color(0xFF818CF8), enabled && level == DeepThinkLevel.MEDIUM) { choose(DeepThinkLevel.MEDIUM) }
            DtOption(Icons.Default.Bolt, "Rápido (Leve)", "Raciocínio conciso para respostas diretas.", Color(0xFF22D3EE), enabled && level == DeepThinkLevel.LOW) { choose(DeepThinkLevel.LOW) }
            DtOption(Icons.Default.Block, "Desativado", "Sem orçamento adicional de raciocínio.", HMuted, !enabled) { choose(null) }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun DtOption(icon: ImageVector, title: String, body: String, accent: Color, selected: Boolean, onClick: () -> Unit) {
    Surface(Modifier.fillMaxWidth().clickable(onClick = onClick), RoundedCornerShape(11.dp), HCard, border = BorderStroke(1.dp, if (selected) accent.copy(alpha = .65f) else HBorder)) {
        Row(Modifier.padding(11.dp), verticalAlignment = Alignment.Top) {
            Icon(icon, null, tint = accent, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(8.dp)); Column(Modifier.weight(1f)) { Text(title, fontSize = 10.sp, fontWeight = FontWeight.SemiBold); Text(body, modifier = Modifier.padding(top = 3.dp), fontSize = 8.sp, lineHeight = 12.sp, color = Color(0xFF94A3B8)) }
            if (selected) Icon(Icons.Default.CheckCircle, null, tint = accent, modifier = Modifier.size(14.dp))
        }
    }
}

@Composable
private fun HtmlProfiles(selected: AgentEntity?, agents: List<AgentEntity>, onSelect: (AgentEntity) -> Unit, onCreate: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.VerifiedUser, null, tint = Color(0xFF818CF8)); Spacer(Modifier.width(8.dp)); Text("Selecionar Perfil de IA", fontSize = 13.sp, fontWeight = FontWeight.Bold) }
        agents.forEach { agent -> SheetOption(Icons.Default.Psychology, agent.name, agent.systemPrompt.take(100), agent.id == selected?.id) { onSelect(agent) } }
        Surface(Modifier.fillMaxWidth().clickable(onClick = onCreate), RoundedCornerShape(11.dp), Color.Transparent, border = BorderStroke(1.dp, HBorderLight)) { Text("+ Criar novo perfil personalizado", Modifier.padding(11.dp), textAlign = TextAlign.Center, fontSize = 9.sp, color = Color(0xFF94A3B8)) }
        Spacer(Modifier.height(18.dp))
    }
}

@Composable
private fun HtmlModels(selectedId: String?, models: List<AiModelEntity>, onSelect: (String) -> Unit, onManage: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Memory, null, tint = Color(0xFFC084FC)); Spacer(Modifier.width(8.dp)); Text("Modelos Locais & Dispositivo", fontSize = 13.sp, fontWeight = FontWeight.Bold) }
        Text("Modelos verificados rodam localmente no aparelho.", fontSize = 9.sp, color = Color(0xFF94A3B8))
        models.forEach { model ->
            val provider = model.catalogProvider()
            Surface(Modifier.fillMaxWidth().clickable { onSelect(model.id) }, RoundedCornerShape(11.dp), HCard, border = BorderStroke(1.dp, if (model.id == selectedId) HPurple.copy(alpha = .4f) else HBorder)) {
                Row(Modifier.padding(11.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (provider != null) ProviderLogo(provider, size = 25.dp) else Icon(Icons.Default.Memory, null, tint = Color(0xFF818CF8), modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(9.dp)); Column(Modifier.weight(1f)) { Text(model.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 10.sp, fontWeight = FontWeight.SemiBold); Text(provider?.let { "${it.brandName()} · Offline" } ?: "Disponível offline", fontSize = 8.sp, color = Color(0xFF94A3B8)) }
                    if (model.id == selectedId) Text("Ativo", fontSize = 8.sp, color = HEmerald)
                }
            }
        }
        TextButton(onClick = onManage, modifier = Modifier.fillMaxWidth()) { Text("Gerenciar modelos offline", fontSize = 9.sp) }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun SheetOption(icon: ImageVector, title: String, body: String, selected: Boolean, onClick: () -> Unit) {
    Surface(Modifier.fillMaxWidth().clickable(onClick = onClick), RoundedCornerShape(11.dp), HCard, border = BorderStroke(1.dp, if (selected) HIndigo.copy(alpha = .4f) else HBorder)) {
        Row(Modifier.padding(11.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(Modifier.size(29.dp), RoundedCornerShape(9.dp), color = HIndigo.copy(alpha = .15f)) { Box(contentAlignment = Alignment.Center) { Icon(icon, null, tint = Color(0xFF818CF8), modifier = Modifier.size(14.dp)) } }
            Spacer(Modifier.width(9.dp)); Column(Modifier.weight(1f)) { Text(title, fontSize = 10.sp, fontWeight = FontWeight.SemiBold); Text(body, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 8.sp, color = Color(0xFF94A3B8)) }; if (selected) Icon(Icons.Default.Check, null, tint = Color(0xFF818CF8), modifier = Modifier.size(14.dp))
        }
    }
}

@Composable
private fun HtmlProfileDialog(onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }; var prompt by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = HSurface, title = { Text("Criar novo perfil", fontSize = 14.sp) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(9.dp)) { OutlinedTextField(name, { name = it.take(80) }, Modifier.fillMaxWidth(), label = { Text("Nome") }, singleLine = true); OutlinedTextField(prompt, { prompt = it }, Modifier.fillMaxWidth(), label = { Text("Prompt da persona") }, minLines = 4, maxLines = 7) } },
        confirmButton = { Button(onClick = { onSave(name.trim(), prompt.trim()) }, enabled = name.isNotBlank() && prompt.isNotBlank()) { Text("Salvar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

private data class Parsed(val reasoning: String?, val answer: String, val ms: Long?)
private fun parseAssistant(raw: String): Parsed {
    val meta = ReasoningMeta.find(raw)?.groupValues?.getOrNull(1)?.toLongOrNull()
    val clean = ReasoningMeta.replace(raw, "").trim()
    ReasoningBlock.find(clean)?.let { return Parsed(it.groupValues[1].trim(), clean.removeRange(it.range).trim(), meta) }
    val open = ReasoningOpen.find(clean)
    return if (open != null) Parsed(clean.substring(open.range.last + 1).trim(), clean.substring(0, open.range.first).trim(), meta) else Parsed(null, clean, meta)
}
private val ReasoningOpen = Regex("(?is)<think(?:ing)?>")
private val ReasoningBlock = Regex("(?is)<think(?:ing)?>(.*?)</think(?:ing)?>")
private val ReasoningMeta = Regex("(?is)\\s*<!--nexus_reasoning_ms:(\\d+)-->\\s*$")
private fun formatDuration(ms: Long): String = if (ms < 60_000) String.format(Locale.getDefault(), "%.1f s", ms.coerceAtLeast(0L) / 1000.0) else "${ms / 60_000}m ${(ms % 60_000) / 1000}s"
private fun formatTime(timestamp: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
private fun historyGroup(timestamp: Long): String {
    val now = Calendar.getInstance(); val day = Calendar.getInstance().apply { timeInMillis = timestamp }
    fun key(c: Calendar) = Triple(c.get(Calendar.YEAR), c.get(Calendar.DAY_OF_YEAR), c.get(Calendar.ERA))
    if (key(now) == key(day)) return "Hoje"
    now.add(Calendar.DAY_OF_YEAR, -1)
    return if (key(now) == key(day)) "Ontem" else "Anteriores"
}
private data class MemoryStats(val used: String, val total: String, val fraction: Float)
@Composable private fun rememberDeviceMemory(): MemoryStats {
    val context = LocalContext.current
    return remember(context) {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo(); manager.getMemoryInfo(info)
        val total = info.totalMem.coerceAtLeast(1L); val used = (total - info.availMem).coerceAtLeast(0L); val gb = 1024.0 * 1024.0 * 1024.0
        MemoryStats(String.format(Locale.getDefault(), "%.1f GB", used / gb), String.format(Locale.getDefault(), "%.1f GB", total / gb), (used.toDouble() / total).toFloat().coerceIn(0f, 1f))
    }
}
