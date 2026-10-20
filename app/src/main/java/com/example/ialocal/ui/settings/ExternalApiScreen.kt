package com.example.ialocal.ui.settings

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ialocal.api.RemoteApiModel
import com.example.ialocal.api.RemoteApiProvider
import com.example.ialocal.api.SavedRemoteConversation
import com.example.ialocal.api.SavedRemoteMessage
import com.example.ialocal.audio.AudioRecorder
import com.example.ialocal.ui.chat.AnswerPart
import com.example.ialocal.ui.chat.CanvasRef
import com.example.ialocal.ui.chat.CodeCanvasCard
import com.example.ialocal.ui.chat.CodeCanvasDialog
import com.example.ialocal.ui.chat.CodeCanvases
import com.example.ialocal.ui.chat.InlineCodeBlock
import com.example.ialocal.ui.chat.splitAnswer
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Online chat mirrors offline actions and Canvas, but its messages live in the remote-only store.
 * Native GGUF chats, tools and models are NOT sent to a cloud provider.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExternalApiScreen(
    viewModel: ExternalApiViewModel,
    installedLanguageIds: Set<String>,
    onOpenCompilers: (String) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val configuration by viewModel.config.collectAsStateWithLifecycle()
    val models by viewModel.models.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val conversations by viewModel.conversations.collectAsStateWithLifecycle()
    val currentId by viewModel.currentId.collectAsStateWithLifecycle()
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val availableProfiles by viewModel.availableProfiles.collectAsStateWithLifecycle()
    val attachments by viewModel.attachments.collectAsStateWithLifecycle()
    val processing by viewModel.processingAttachment.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val loadingModels by viewModel.loadingModels.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    var provider by remember { mutableStateOf(configuration.provider) }
    var url by remember { mutableStateOf(configuration.baseUrl) }
    var modelId by remember { mutableStateOf(configuration.modelId) }
    var newKey by remember { mutableStateOf("") }
    var draft by remember { mutableStateOf("") }
    var chooseModel by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(!configuration.hasKey) }
    var showHistory by remember { mutableStateOf(false) }
    var showProfile by remember { mutableStateOf(false) }
    var canvasRef by remember { mutableStateOf<CanvasRef?>(null) }
    val chatScroll = rememberLazyListState()
    val clipboard = remember(context) { context.getSystemService(ClipboardManager::class.java) }
    val speaker = remember(context) { TextToSpeech(context.applicationContext) { } }
    DisposableEffect(speaker) { onDispose { speaker.stop(); speaker.shutdown() } }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.attachFile(uri)
    }
    val recorder = remember(context) { AudioRecorder(context) }
    var recording by remember { mutableStateOf(false) }
    val microphonePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) runCatching { recorder.start(); recording = true }
    }
    DisposableEffect(recorder) { onDispose { recorder.cancel() } }

    LaunchedEffect(configuration) {
        provider = configuration.provider
        url = configuration.baseUrl
        modelId = configuration.modelId
    }
    LaunchedEffect(messages.size, messages.lastOrNull()?.content?.length) {
        if (messages.isNotEmpty() &&
            (chatScroll.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1) >= messages.size - 3) {
            chatScroll.scrollToItem(messages.size - 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Chat com API", fontWeight = FontWeight.Bold)
                        Text(
                            "${configuration.provider.label} · ${configuration.modelId.ifBlank { "Escolha um modelo" }}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar") }
                },
                actions = {
                    IconButton(onClick = { showHistory = true }) { Icon(Icons.Default.History, "Histórico online") }
                    IconButton(onClick = { viewModel.newConversation(); draft = ""; showSettings = !configuration.hasKey }, enabled = !busy) {
                        Icon(Icons.Default.Add, "Nova conversa")
                    }
                    IconButton(onClick = { showSettings = !showSettings }) {
                        Icon(Icons.Default.Settings, "Configurar API")
                    }
                },
            )
        },
    ) { inner ->
        Column(
            modifier = Modifier.fillMaxSize().padding(inner).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (showSettings) {
                Card(Modifier.fillMaxWidth().weight(1f)) {
                    Column(
                        Modifier.verticalScroll(rememberScrollState()).padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text("Conectar IA online", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(
                            "Use sua chave do OpenRouter, OpenAI ou de outra API HTTPS compatível. " +
                                "Somente as mensagens desta conversa online são enviadas ao provedor escolhido.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            RemoteApiProvider.entries.forEach { option ->
                                FilterChip(
                                    selected = provider == option,
                                    onClick = {
                                        if (provider != option) {
                                            val wasDefault = url == provider.defaultUrl
                                            provider = option
                                            if (wasDefault || option != RemoteApiProvider.CUSTOM) url = option.defaultUrl
                                        }
                                    },
                                    label = { Text(option.label) },
                                )
                            }
                        }
                        OutlinedTextField(
                            value = url,
                            onValueChange = { url = it },
                            label = { Text("URL base HTTPS") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = newKey,
                            onValueChange = { newKey = it },
                            label = {
                                Text(if (configuration.hasKey) "Nova chave (vazio mantém a atual)" else "Chave de API")
                            },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = modelId,
                            onValueChange = { modelId = it },
                            label = { Text("ID do modelo (OpenRouter, OpenAI, etc.)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                enabled = !busy && !loadingModels,
                                onClick = {
                                    if (viewModel.save(provider, url, modelId, newKey)) {
                                        newKey = ""
                                        if (viewModel.config.value.hasKey && modelId.isNotBlank()) showSettings = false
                                    }
                                },
                            ) { Text("Salvar e conversar") }
                            OutlinedButton(
                                enabled = !busy && !loadingModels,
                                onClick = {
                                    if (viewModel.save(provider, url, modelId, newKey)) {
                                        newKey = ""
                                        viewModel.fetchModels()
                                    }
                                },
                            ) {
                                if (loadingModels) CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp)
                                Text(" Listar IAs")
                            }
                        }
                        if (models.isNotEmpty()) {
                            OutlinedButton(onClick = { chooseModel = true }, enabled = !busy) {
                                Text("Escolher modelo (${models.size})")
                            }
                        }
                        if (configuration.hasKey) {
                            TextButton(onClick = viewModel::removeCredential, enabled = !busy) {
                                Text("Remover chave de API")
                            }
                        }
                        Text(
                            "O provedor pode cobrar pelo uso. Os modelos GGUF instalados e seu histórico offline são independentes desta área.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            } else {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    FilterChip(
                        selected = true,
                        onClick = { showSettings = true },
                        label = { Text(configuration.modelId.ifBlank { "Selecionar IA online" }, maxLines = 1) },
                    )
                    FilterChip(
                        selected = false,
                        onClick = { showProfile = true },
                        label = { Text(profile) },
                        leadingIcon = { Icon(Icons.Default.AutoAwesome, null, Modifier.size(17.dp)) },
                    )
                    Text("Online", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
                if (notice != null) {
                    Text(notice.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                }
                HorizontalDivider()
                LazyColumn(
                    state = chatScroll,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (messages.isEmpty()) {
                        item {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(top = 42.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Icon(Icons.Default.SmartToy, contentDescription = null, modifier = Modifier.size(38.dp))
                                Text("Converse com uma IA online", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "Mesmo ambiente de conversa: código no Canvas, histórico separado, arquivos, perfis e respostas em tempo real.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    items(messages, key = { it.id }) { message ->
                        Column(
                            Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(
                                    if (message.role == "assistant") Icons.Default.SmartToy else Icons.Default.Chat,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(22.dp),
                                )
                                Text(
                                    if (message.role == "assistant") configuration.modelId.ifBlank { "IA online" } else "Você",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                            if (message.role == "assistant") {
                                if (message.content.isEmpty()) {
                                    Text(
                                        if (busy) "Pensando e gerando resposta…" else "Resposta vazia. Tente novamente.",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                } else {
                                    val parts = remember(message.content) { splitAnswer(message.content) }
                                    var codeIndex = 0
                                    parts.forEach { part ->
                                        when (part) {
                                            is AnswerPart.Prose -> if (part.text.isNotBlank()) {
                                                Card(
                                                    Modifier.fillMaxWidth(),
                                                    colors = CardDefaults.cardColors(
                                                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                                                    ),
                                                ) {
                                                    Text(
                                                        part.text,
                                                        modifier = Modifier.padding(13.dp),
                                                        style = MaterialTheme.typography.bodyMedium,
                                                    )
                                                }
                                            }
                                            is AnswerPart.Code -> {
                                                val index = codeIndex++
                                                if (CodeCanvases.isCanvas(part)) {
                                                    CodeCanvasCard(
                                                        canvas = remember(part) { CodeCanvases.of(part) },
                                                        createdAt = message.createdAt,
                                                    ) { canvasRef = CanvasRef(message.id, index) }
                                                } else {
                                                    InlineCodeBlock(part)
                                                }
                                            }
                                        }
                                    }
                                }
                            } else {
                                Card(
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                                    ),
                                ) {
                                    Column(Modifier.padding(12.dp)) {
                                        Text(message.content, style = MaterialTheme.typography.bodyMedium)
                                        if (message.attachmentNames.isNotEmpty()) {
                                            Text(
                                                message.attachmentNames.joinToString(prefix = "Anexos: "),
                                                style = MaterialTheme.typography.labelSmall,
                                            )
                                        }
                                    }
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton(onClick = {
                                    clipboard.setPrimaryClip(ClipData.newPlainText("Mensagem da API", message.content))
                                }) {
                                    Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(16.dp))
                                    Text(" Copiar")
                                }
                                if (message.role == "assistant") {
                                    TextButton(onClick = {
                                        speaker.speak(message.content.take(4_000), TextToSpeech.QUEUE_FLUSH, null, message.id)
                                    }) {
                                        Icon(Icons.Default.VolumeUp, null, modifier = Modifier.size(16.dp))
                                        Text(" Ouvir")
                                    }
                                    if (message.id == messages.lastOrNull()?.id) {
                                        TextButton(onClick = viewModel::regenerate, enabled = !busy) {
                                            Icon(Icons.Default.Refresh, null, modifier = Modifier.size(16.dp))
                                            Text(" Refazer")
                                        }
                                    }
                                } else {
                                    TextButton(
                                        onClick = {
                                            val edited = viewModel.editMessage(message.id)
                                            if (edited != null) draft = edited
                                        },
                                        enabled = !busy,
                                    ) {
                                        Icon(Icons.Default.Edit, null, modifier = Modifier.size(16.dp))
                                        Text(" Editar")
                                    }
                                }
                            }
                        }
                    }
                }
                if (attachments.isNotEmpty()) {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        attachments.forEach { item ->
                            FilterChip(
                                selected = true,
                                onClick = { viewModel.removeAttachment(item.id) },
                                label = { Text("${item.name}  ×", maxLines = 1) },
                            )
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    IconButton(
                        enabled = !busy && !processing,
                        onClick = {
                            filePicker.launch(arrayOf("text/*", "application/json", "application/pdf", "audio/*"))
                        },
                    ) {
                        Icon(Icons.Default.AttachFile, "Anexar documento ou áudio")
                    }
                    IconButton(
                        enabled = !busy && !processing,
                        onClick = {
                            if (recording) {
                                recorder.stop()?.let(viewModel::attachRecordedAudio)
                                recording = false
                            } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                                == PackageManager.PERMISSION_GRANTED) {
                                runCatching { recorder.start(); recording = true }
                            } else {
                                microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                    ) {
                        Icon(
                            if (recording) Icons.Default.Stop else Icons.Default.Mic,
                            if (recording) "Parar gravação" else "Gravar áudio",
                            tint = if (recording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        modifier = Modifier.weight(1f),
                        label = { Text(if (processing) "Preparando anexo…" else "Escreva sua mensagem") },
                        maxLines = 4,
                        enabled = !busy && !processing,
                    )
                    if (busy) {
                        IconButton(onClick = viewModel::stop) {
                            Icon(Icons.Default.Stop, "Parar resposta", tint = MaterialTheme.colorScheme.error)
                        }
                    } else {
                        IconButton(
                            enabled = !processing && (draft.isNotBlank() || attachments.isNotEmpty()),
                            onClick = {
                                viewModel.send(draft)
                                if (configuration.hasKey && configuration.modelId.isNotBlank()) draft = ""
                            },
                        ) { Icon(Icons.Default.Send, "Enviar") }
                    }
                }
            }
        }
    }

    if (chooseModel) {
        RemoteModelPicker(
            models = models,
            onSelected = {
                viewModel.selectModel(it.id)
                modelId = it.id
                chooseModel = false
                showSettings = false
            },
            onDismiss = { chooseModel = false },
        )
    }
    if (showHistory) {
        RemoteHistoryPicker(
            conversations = conversations,
            selected = currentId,
            onSelected = { viewModel.openConversation(it); showHistory = false; showSettings = false },
            onDelete = viewModel::deleteConversation,
            onDismiss = { showHistory = false },
        )
    }
    if (showProfile) {
        AlertDialog(
            onDismissRequest = { showProfile = false },
            title = { Text("Perfil da conversa online") },
            text = {
                Column {
                    (listOf("Padrão") + availableProfiles.map { it.displayName }).forEach { name ->
                        TextButton(
                            onClick = { viewModel.setProfile(name); showProfile = false },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(if (profile == name) "✓ $name" else name) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showProfile = false }) { Text("Fechar") } },
        )
    }
    canvasRef?.let { reference ->
        val opened = messages.firstOrNull { it.id == reference.messageId }
            ?.let { CodeCanvases.at(it.content, reference.codeIndex) }
        if (opened != null) {
            CodeCanvasDialog(
                canvas = opened,
                installedLanguageIds = installedLanguageIds,
                onOpenCompilers = { id -> canvasRef = null; onOpenCompilers(id) },
                onDismiss = { canvasRef = null },
            )
        } else {
            LaunchedEffect(reference) { canvasRef = null }
        }
    }
}

@Composable
private fun RemoteHistoryPicker(
    conversations: List<SavedRemoteConversation>,
    selected: String?,
    onSelected: (String) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var search by remember { mutableStateOf("") }
    val matched = remember(conversations, search) {
        conversations.filter { it.title.contains(search, ignoreCase = true) ||
            it.modelId.contains(search, ignoreCase = true) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Histórico de APIs · separado") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = search, onValueChange = { search = it },
                    label = { Text("Buscar conversa online") }, modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(modifier = Modifier.height(360.dp)) {
                    items(matched, key = { it.id }) { chat ->
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            TextButton(
                                onClick = { onSelected(chat.id) },
                                modifier = Modifier.weight(1f),
                            ) {
                                Column(Modifier.fillMaxWidth()) {
                                    Text(
                                        if (chat.id == selected) "• ${chat.title}" else chat.title,
                                        maxLines = 2,
                                    )
                                    Text(
                                        chat.modelId,
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            IconButton(onClick = { onDelete(chat.id) }) {
                                Icon(Icons.Default.Delete, "Excluir conversa")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fechar") } },
    )
}

@Composable
private fun RemoteModelPicker(
    models: List<RemoteApiModel>,
    onSelected: (RemoteApiModel) -> Unit,
    onDismiss: () -> Unit,
) {
    var search by remember { mutableStateOf("") }
    val matched = remember(models, search) {
        models.filter { it.id.contains(search, ignoreCase = true) ||
            it.name.contains(search, ignoreCase = true) }.take(80)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Escolher modelo da API") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = search, onValueChange = { search = it },
                    label = { Text("Buscar por nome ou ID") }, modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(modifier = Modifier.height(320.dp)) {
                    items(matched, key = { it.id }) { model ->
                        TextButton(onClick = { onSelected(model) }, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(model.name, fontWeight = FontWeight.SemiBold)
                                Text(model.id, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fechar") } },
    )
}
