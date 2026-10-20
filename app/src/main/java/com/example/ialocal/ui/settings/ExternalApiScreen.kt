package com.example.ialocal.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ialocal.api.RemoteApiModel
import com.example.ialocal.api.RemoteApiProvider

/**
 * "Utilizar API" connects to online LLMs; it does not toggle the localhost API server.
 * No cloud request is made until the user requests a model list or sends a message.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExternalApiScreen(
    viewModel: ExternalApiViewModel,
    onBack: () -> Unit,
) {
    val configuration by viewModel.config.collectAsStateWithLifecycle()
    val models by viewModel.models.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
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
    val chatScroll = rememberLazyListState()

    LaunchedEffect(configuration) {
        provider = configuration.provider
        url = configuration.baseUrl
        modelId = configuration.modelId
    }
    LaunchedEffect(messages.size, messages.lastOrNull()?.content?.length) {
        if (messages.isNotEmpty()) chatScroll.animateScrollToItem(messages.size - 1)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Utilizar API externa") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar") }
                },
                actions = {
                    TextButton(onClick = { showSettings = !showSettings }) {
                        Text(if (showSettings) "Ocultar ajustes" else "Configurar")
                    }
                },
            )
        },
    ) { inner ->
        Column(
            modifier = Modifier.fillMaxSize().padding(inner).padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (showSettings) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Conectar uma IA pela internet", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Use sua própria conta e chave do provedor. As mensagens desta conversa serão enviadas ao serviço escolhido. As IAs instaladas continuam 100% offline.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                            label = { Text("URL base HTTPS (termina em /v1)") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = newKey,
                            onValueChange = { newKey = it },
                            label = { Text(if (configuration.hasKey) "Nova chave (vazio = manter atual)" else "Chave de API") },
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = modelId,
                            onValueChange = { modelId = it },
                            label = { Text("ID do modelo, ex.: openai/gpt-4o-mini") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                enabled = !busy,
                                onClick = {
                                    if (viewModel.save(provider, url, modelId, newKey)) newKey = ""
                                },
                            ) { Text("Salvar conexão") }
                            OutlinedButton(
                                enabled = !busy && !loadingModels,
                                onClick = {
                                    if (viewModel.save(provider, url, modelId, newKey)) {
                                        newKey = ""
                                        viewModel.fetchModels()
                                    }
                                },
                            ) {
                                if (loadingModels) CircularProgressIndicator(Modifier.width(16.dp).height(16.dp), strokeWidth = 2.dp)
                                Text(" Listar modelos")
                            }
                        }
                        if (models.isNotEmpty()) {
                            OutlinedButton(onClick = { chooseModel = true }, enabled = !busy) {
                                Text("Escolher da lista (${models.size})")
                            }
                        }
                        if (configuration.hasKey) {
                            TextButton(onClick = viewModel::removeCredential, enabled = !busy) {
                                Text("Remover chave armazenada")
                            }
                        }
                    }
                }
            } else {
                Text(
                    "${configuration.provider.label} · ${configuration.modelId.ifBlank { "Escolha um modelo em Configurar" }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (notice != null) {
                Text(
                    notice.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Conversa online", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                TextButton(onClick = viewModel::newConversation, enabled = !busy) { Text("Nova conversa") }
            }
            HorizontalDivider()
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                state = chatScroll,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (messages.isEmpty()) {
                    item {
                        Text(
                            "Configure a chave, selecione um modelo e envie sua primeira mensagem. A API pode cobrar pelo uso, conforme sua conta.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                items(messages) { message ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                if (message.role == "user") "Você" else configuration.modelId.ifBlank { "IA online" },
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                message.content.ifEmpty { if (busy) "Respondendo…" else "Sem resposta do provedor." },
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    label = { Text("Mensagem para a IA online") },
                    modifier = Modifier.weight(1f),
                    maxLines = 4,
                    enabled = !busy,
                )
                if (busy) {
                    OutlinedButton(onClick = viewModel::stop, modifier = Modifier.padding(top = 8.dp)) { Text("Parar") }
                } else {
                    Button(
                        onClick = {
                            viewModel.send(draft)
                            if (configuration.hasKey && configuration.modelId.isNotBlank()) draft = ""
                        },
                        enabled = draft.isNotBlank(),
                        modifier = Modifier.padding(top = 8.dp),
                    ) {
                        Icon(Icons.Default.Send, contentDescription = "Enviar")
                    }
                }
            }
        }
    }

    if (chooseModel) {
        RemoteModelPicker(
            models = models,
            onSelected = { choice ->
                viewModel.selectModel(choice.id)
                modelId = choice.id
                chooseModel = false
            },
            onDismiss = { chooseModel = false },
        )
    }
}

@Composable
private fun RemoteModelPicker(
    models: List<RemoteApiModel>,
    onSelected: (RemoteApiModel) -> Unit,
    onDismiss: () -> Unit,
) {
    var search by remember { mutableStateOf("") }
    val matched = remember(models, search) {
        models.filter { it.id.contains(search, ignoreCase = true) || it.name.contains(search, ignoreCase = true) }
            .take(60)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Escolher modelo") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    label = { Text("Buscar por nome ou ID") },
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(modifier = Modifier.height(320.dp)) {
                    items(matched) { model ->
                        TextButton(
                            onClick = { onSelected(model) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(model.name, fontWeight = FontWeight.SemiBold)
                                Text(model.id, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fechar") } },
    )
}
