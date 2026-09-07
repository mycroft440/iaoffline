package com.example.ialocal.ui.models

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ialocal.api.ApiServerStatus
import com.example.ialocal.data.AgentEntity
import com.example.ialocal.data.AiModelEntity
import com.example.ialocal.data.ModelVerificationStatus
import com.example.ialocal.diagnostics.IntegrationCheckStatus
import com.example.ialocal.diagnostics.IntegrationTestState
import com.example.ialocal.models.ModelImportPreview
import com.example.ialocal.runtime.RuntimeStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsScreen(viewModel: ModelsViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val models by viewModel.models.collectAsStateWithLifecycle()
    val agents by viewModel.agents.collectAsStateWithLifecycle()
    val server by viewModel.serverState.collectAsStateWithLifecycle()
    val runtime by viewModel.runtimeState.collectAsStateWithLifecycle()
    val importing by viewModel.isImporting.collectAsStateWithLifecycle()
    val operation by viewModel.operationText.collectAsStateWithLifecycle()
    val preview by viewModel.preview.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val apiKey by viewModel.apiKey.collectAsStateWithLifecycle()
    val integration by viewModel.integrationTest.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var editAgent by remember { mutableStateOf<AgentEntity?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.inspectModel(uri)
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
                title = { Text("Modelos e API") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Voltar") } },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                ApiCard(
                    baseUrl = viewModel.baseUrl,
                    apiKey = apiKey,
                    running = server.status == ApiServerStatus.RUNNING,
                    statusText = when (server.status) {
                        ApiServerStatus.RUNNING -> "API ativa"
                        ApiServerStatus.STARTING -> "Iniciando API…"
                        ApiServerStatus.ERROR -> server.error ?: "Erro na API"
                        ApiServerStatus.STOPPED -> "API parada"
                    },
                    runtimeText = buildString {
                        append("Runtime: ").append(runtime.status.name)
                        runtime.modelName?.let { append(" · ").append(it) }
                        runtime.error?.let { append(" · ").append(it) }
                    },
                    onToggle = {
                        if (server.status == ApiServerStatus.RUNNING) viewModel.stopServer() else viewModel.startServer()
                    },
                    onUnload = viewModel::unload,
                    canUnload = runtime.status in setOf(RuntimeStatus.READY, RuntimeStatus.ERROR),
                    onCopyUrl = { copy(context, "API local", viewModel.baseUrl) },
                    onCopyKey = { copy(context, "Chave API", apiKey) },
                    onRegenerateKey = viewModel::regenerateKey,
                )
            }

            item {
                IntegrationTestCard(
                    state = integration,
                    onRun = viewModel::runIntegrationTest,
                )
            }

            item {
                Button(
                    onClick = { picker.launch(arrayOf("application/octet-stream", "*/*")) },
                    enabled = !importing && operation == null,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (importing) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Default.FileOpen, contentDescription = null)
                    Text("  ${operation ?: "Importar IA (.gguf)"}")
                }
            }

            if (models.isEmpty()) {
                item {
                    Text(
                        "Nenhum modelo importado. O app valida o GGUF antes de copiar e só marca como funcional depois de uma inferência real.",
                        modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            items(models, key = { it.id }) { model ->
                val agent = agents.firstOrNull { it.modelId == model.id }
                ModelCard(
                    model = model,
                    agent = agent,
                    loaded = runtime.modelId == model.id && runtime.status == RuntimeStatus.READY,
                    onActivate = { viewModel.activate(model.id) },
                    onDelete = { viewModel.delete(model.id) },
                    onEditAgent = { if (agent != null) editAgent = agent },
                    onDefaultAgent = { if (agent != null) viewModel.setDefaultAgent(agent.id) },
                )
            }
            item { Spacer(Modifier.height(20.dp)) }
        }
    }

    preview?.let {
        ImportPreviewDialog(
            preview = it,
            onDismiss = viewModel::dismissPreview,
            onConfirm = viewModel::confirmImport,
        )
    }

    editAgent?.let { agent ->
        AgentDialog(
            agent = agent,
            onDismiss = { editAgent = null },
            onSave = { viewModel.saveAgent(it); editAgent = null },
        )
    }
}

@Composable
private fun ImportPreviewDialog(preview: ModelImportPreview, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Verificar e importar") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(preview.suggestedName, fontWeight = FontWeight.Bold)
                Text("Arquivo: ${preview.displayName}")
                Text("Formato: GGUF v${preview.metadata.version} ✓")
                Text("Arquitetura: ${preview.metadata.architecture ?: "não informada"}")
                Text("Tensors: ${preview.metadata.tensorCount}")
                Text("Contexto declarado: ${preview.metadata.contextLength ?: "não informado"}")
                Text("Chat template: ${if (preview.metadata.hasChatTemplate) "encontrado ✓" else "não declarado; llama.cpp tentará o template disponível"}")
                Text("ABI do aparelho: ${preview.compatibility.primaryAbi} ${if (preview.compatibility.supportedAbi) "✓" else "✗"}")
                preview.sourceSizeBytes?.let { Text("Tamanho: ${formatBytes(it)}") }
                Text("RAM total: ${formatBytes(preview.compatibility.totalRamBytes)}")
                preview.compatibility.estimatedModelRamBytes?.let { Text("Estimativa conservadora de RAM: ${formatBytes(it)}") }
                preview.compatibility.warnings.forEach { Text("⚠ $it", color = MaterialTheme.colorScheme.error) }
                Text(
                    "Ao importar, o app copiará o GGUF, carregará o modelo no llama.cpp e executará um teste real antes de ativá-lo.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onConfirm, enabled = preview.compatibility.canImport) { Text("Importar e testar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

@Composable
private fun ApiCard(
    baseUrl: String,
    apiKey: String,
    running: Boolean,
    statusText: String,
    runtimeText: String,
    onToggle: () -> Unit,
    onUnload: () -> Unit,
    canUnload: Boolean,
    onCopyUrl: () -> Unit,
    onCopyKey: () -> Unit,
    onRegenerateKey: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("API local", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(statusText, color = if (running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Memory, null)
                Text("  $runtimeText", style = MaterialTheme.typography.bodySmall)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(baseUrl, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                IconButton(onClick = onCopyUrl) { Icon(Icons.Default.ContentCopy, "Copiar URL") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${apiKey.take(14)}••••••••", modifier = Modifier.weight(1f), maxLines = 1)
                IconButton(onClick = onCopyKey) { Icon(Icons.Default.Key, "Copiar chave") }
            }
            Text("Endpoints: /v1/health · /v1/models · /v1/chat/completions", style = MaterialTheme.typography.bodySmall)
            Text("A API escuta somente em 127.0.0.1.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onToggle) {
                    Icon(if (running) Icons.Default.Stop else Icons.Default.PlayArrow, null)
                    Text(if (running) " Parar" else " Iniciar")
                }
                OutlinedButton(onClick = onUnload, enabled = canUnload) { Text("Liberar RAM") }
                OutlinedButton(onClick = onRegenerateKey) { Text("Nova chave") }
            }
        }
    }
}

@Composable
private fun IntegrationTestCard(
    state: IntegrationTestState,
    onRun: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Teste completo da integração", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "Valida o caminho real: localhost → autenticação → modelo VERIFIED → inferência HTTP → SSE.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.checks.forEach { check ->
                val marker = when (check.status) {
                    IntegrationCheckStatus.PENDING -> "○"
                    IntegrationCheckStatus.RUNNING -> "◌"
                    IntegrationCheckStatus.PASSED -> "✓"
                    IntegrationCheckStatus.FAILED -> "✗"
                    IntegrationCheckStatus.SKIPPED -> "–"
                }
                Column {
                    Text("$marker ${check.title}", fontWeight = if (check.status == IntegrationCheckStatus.FAILED) FontWeight.Bold else FontWeight.Normal)
                    check.detail?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (check.status == IntegrationCheckStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            state.summary?.let {
                Text(
                    it,
                    fontWeight = FontWeight.SemiBold,
                    color = if (state.checks.any { check -> check.status == IntegrationCheckStatus.FAILED }) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
            }
            state.lastModelOutput?.let {
                Text("Resposta do modelo: ${it.take(180)}", style = MaterialTheme.typography.bodySmall)
            }
            Button(onClick = onRun, enabled = !state.running, modifier = Modifier.fillMaxWidth()) {
                if (state.running) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                else Icon(Icons.Default.Refresh, contentDescription = null)
                Text(if (state.running) "  Testando…" else "  Executar teste completo")
            }
        }
    }
}

@Composable
private fun ModelCard(
    model: AiModelEntity,
    agent: AgentEntity?,
    loaded: Boolean,
    onActivate: () -> Unit,
    onDelete: () -> Unit,
    onEditAgent: () -> Unit,
    onDefaultAgent: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(model.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        listOfNotNull(model.architecture, model.sizeLabel, formatBytes(model.sizeBytes)).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(model.apiModelId, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (model.isActive) Icon(Icons.Default.CheckCircle, "Modelo ativo", tint = MaterialTheme.colorScheme.primary)
            }
            Text(
                when (model.verificationStatus) {
                    ModelVerificationStatus.VERIFIED.name -> "✓ Inferência verificada${if (loaded) " · carregado na RAM" else ""}"
                    ModelVerificationStatus.VERIFYING.name -> "Verificando inferência…"
                    ModelVerificationStatus.ERROR.name -> "Falha na verificação: ${model.lastError ?: "erro desconhecido"}"
                    else -> "Importado, ainda não verificado"
                },
                color = if (model.verificationStatus == ModelVerificationStatus.ERROR.name) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("GGUF v${model.ggufVersion} · ${model.tensorCount} tensors · contexto efetivo ${model.contextLength}", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onActivate) {
                Icon(if (model.verificationStatus == ModelVerificationStatus.ERROR.name) Icons.Default.Refresh else Icons.Default.PlayArrow, null)
                Text(
                    when {
                        model.verificationStatus != ModelVerificationStatus.VERIFIED.name -> " Testar novamente"
                        model.isActive && loaded -> " Modelo em uso"
                        else -> " Carregar e usar"
                    }
                )
            }
            if (agent != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(agent.name, fontWeight = FontWeight.SemiBold)
                        Text(if (agent.isDefault) "Agente padrão" else "Agente disponível", style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = onEditAgent) { Icon(Icons.Default.Edit, "Editar agente") }
                    Switch(checked = agent.isDefault, onCheckedChange = { if (it) onDefaultAgent() })
                }
            }
            TextButton(onClick = onDelete) { Icon(Icons.Default.Delete, null); Text(" Excluir modelo") }
        }
    }
}

@Composable
private fun AgentDialog(agent: AgentEntity, onDismiss: () -> Unit, onSave: (AgentEntity) -> Unit) {
    var name by remember(agent.id) { mutableStateOf(agent.name) }
    var prompt by remember(agent.id) { mutableStateOf(agent.systemPrompt) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Configurar agente") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                TextField(value = name, onValueChange = { name = it }, label = { Text("Nome") })
                TextField(value = prompt, onValueChange = { prompt = it }, label = { Text("Instruções do agente") }, minLines = 5, maxLines = 10)
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(agent.copy(name = name.trim().ifBlank { agent.name }, systemPrompt = prompt.trim())) }) { Text("Salvar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

private fun copy(context: Context, label: String, value: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
}

private fun formatBytes(bytes: Long): String {
    val gb = bytes / (1024.0 * 1024.0 * 1024.0)
    return if (gb >= 1.0) "%.1f GB".format(gb) else "%.0f MB".format(bytes / (1024.0 * 1024.0))
}
