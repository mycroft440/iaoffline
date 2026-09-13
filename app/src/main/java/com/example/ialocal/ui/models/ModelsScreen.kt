package com.example.ialocal.ui.models

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.example.ialocal.data.AiModelEntity
import com.example.ialocal.data.ModelVerificationStatus
import com.example.ialocal.models.CatalogModel
import com.example.ialocal.models.ModelDownloadPhase
import com.example.ialocal.models.ModelDownloadState
import com.example.ialocal.models.ModelImportPreview
import com.example.ialocal.models.ModelProvider
import com.example.ialocal.runtime.RuntimeStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsScreen(
    viewModel: ModelsViewModel,
    onOpenChats: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val models by viewModel.models.collectAsStateWithLifecycle()
    val server by viewModel.serverState.collectAsStateWithLifecycle()
    val runtime by viewModel.runtimeState.collectAsStateWithLifecycle()
    val download by viewModel.downloadState.collectAsStateWithLifecycle()
    val importing by viewModel.isImporting.collectAsStateWithLifecycle()
    val operation by viewModel.operationText.collectAsStateWithLifecycle()
    val preview by viewModel.preview.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val apiKey by viewModel.apiKey.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    var selectedProvider by remember { mutableStateOf<ModelProvider?>(null) }
    var showApi by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.inspectModel(uri)
    }

    LaunchedEffect(error) {
        error?.let {
            snackbar.showSnackbar(it)
            viewModel.clearError()
        }
    }

    val isSubPage = selectedProvider != null || showApi
    val title = when {
        selectedProvider != null -> providerTitle(selectedProvider!!)
        showApi -> "Utilizar API"
        else -> "I.A Off-line"
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    if (isSubPage) {
                        IconButton(onClick = {
                            selectedProvider = null
                            showApi = false
                        }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Voltar")
                        }
                    }
                },
                actions = {
                    if (!isSubPage) {
                        IconButton(onClick = onOpenSettings) {
                            Icon(Icons.Default.Settings, contentDescription = "Configurações")
                        }
                    }
                },
            )
        },
    ) { padding ->
        when {
            selectedProvider != null -> {
                ProviderModelsContent(
                    modifier = Modifier.padding(padding),
                    provider = selectedProvider!!,
                    catalog = viewModel.catalog.filter { it.provider == selectedProvider },
                    installedModels = models,
                    download = download,
                    importing = importing,
                    operation = operation,
                    onInstall = viewModel::downloadCatalogModel,
                    onPause = viewModel::pauseDownload,
                    onStopOrUninstall = viewModel::stopOrUninstallCatalogModel,
                )
            }

            showApi -> {
                ApiContent(
                    modifier = Modifier.padding(padding),
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

            else -> {
                ModelsHomeContent(
                    modifier = Modifier.padding(padding),
                    models = models,
                    runtimeModelId = runtime.modelId,
                    runtimeReady = runtime.status == RuntimeStatus.READY,
                    operation = operation,
                    importing = importing,
                    downloadBusy = download.isBusy,
                    onActivate = viewModel::activate,
                    onDelete = viewModel::delete,
                    onOpenChats = onOpenChats,
                    onProvider = { selectedProvider = it },
                    onApi = { showApi = true },
                    onImport = { picker.launch(arrayOf("application/octet-stream", "*/*")) },
                )
            }
        }
    }

    preview?.let {
        ImportPreviewDialog(
            preview = it,
            onDismiss = viewModel::dismissPreview,
            onConfirm = viewModel::confirmImport,
        )
    }
}

@Composable
private fun ModelsHomeContent(
    modifier: Modifier,
    models: List<AiModelEntity>,
    runtimeModelId: String?,
    runtimeReady: Boolean,
    operation: String?,
    importing: Boolean,
    downloadBusy: Boolean,
    onActivate: (String) -> Unit,
    onDelete: (String) -> Unit,
    onOpenChats: () -> Unit,
    onProvider: (ModelProvider) -> Unit,
    onApi: () -> Unit,
    onImport: () -> Unit,
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Spacer(Modifier.height(4.dp))
            Text(
                "Minhas I.As >>",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
        }

        if (models.isEmpty()) {
            item {
                Text(
                    "Nenhuma I.A instalada",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            }
        } else {
            items(models, key = { "installed-${it.id}" }) { model ->
                InstalledAiCard(
                    model = model,
                    loaded = runtimeModelId == model.id && runtimeReady,
                    operationRunning = operation != null,
                    onActivate = { onActivate(model.id) },
                    onOpenChats = onOpenChats,
                    onDelete = { onDelete(model.id) },
                )
            }
        }

        item {
            Text(
                "Instale modelos off-line",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        item { HomeOptionCard("Modelos do Google") { onProvider(ModelProvider.GOOGLE) } }
        item { HomeOptionCard("Modelos da Alibaba") { onProvider(ModelProvider.ALIBABA) } }
        item { HomeOptionCard("Modelos da Meta") { onProvider(ModelProvider.META) } }
        item { HomeOptionCard("Utilizar API", onClick = onApi) }

        item {
            OutlinedButton(
                onClick = onImport,
                enabled = !importing && operation == null && !downloadBusy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (importing) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.FileOpen, contentDescription = null)
                }
                Text("  Importar I.A do armazenamento (.gguf)")
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun HomeOptionCard(title: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Icon(Icons.Default.ArrowForward, contentDescription = null)
        }
    }
}

@Composable
private fun InstalledAiCard(
    model: AiModelEntity,
    loaded: Boolean,
    operationRunning: Boolean,
    onActivate: () -> Unit,
    onOpenChats: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(model.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "Tamanho: ${formatBytes(model.sizeBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (model.isActive) {
                    Icon(Icons.Default.CheckCircle, contentDescription = "I.A ativa", tint = MaterialTheme.colorScheme.primary)
                }
            }

            Text(
                when (model.verificationStatus) {
                    ModelVerificationStatus.VERIFIED.name -> if (loaded) "I.A carregada e pronta" else "I.A instalada e verificada"
                    ModelVerificationStatus.VERIFYING.name -> "Verificando I.A…"
                    ModelVerificationStatus.ERROR.name -> "Falha na verificação: ${model.lastError ?: "erro desconhecido"}"
                    else -> "I.A instalada, aguardando verificação"
                },
                color = if (model.verificationStatus == ModelVerificationStatus.ERROR.name) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onActivate,
                    enabled = !operationRunning,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (loaded) "I.A em uso" else "Usar I.A")
                }
                FilledTonalButton(onClick = onOpenChats, modifier = Modifier.weight(1f)) {
                    Text("Conversas")
                }
            }

            TextButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = null)
                Text(" Desinstalar")
            }
        }
    }
}

@Composable
private fun ProviderModelsContent(
    modifier: Modifier,
    provider: ModelProvider,
    catalog: List<CatalogModel>,
    installedModels: List<AiModelEntity>,
    download: ModelDownloadState,
    importing: Boolean,
    operation: String?,
    onInstall: (String) -> Unit,
    onPause: () -> Unit,
    onStopOrUninstall: (String) -> Unit,
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                "Escolha uma I.A ${provider.displayName}",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        if (catalog.isEmpty()) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "Nenhuma I.A verificada desse fabricante está disponível nesta versão.",
                        modifier = Modifier.padding(18.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            items(catalog, key = { it.id }) { catalogModel ->
                val installed = installedModels.any { it.apiModelId.startsWith(catalogModel.apiIdPrefix) }
                CatalogInstallCard(
                    model = catalogModel,
                    state = download.takeIf { it.catalogId == catalogModel.id },
                    installed = installed,
                    anotherOperationRunning = (download.isBusy && download.catalogId != catalogModel.id) || importing || operation != null,
                    onInstall = { onInstall(catalogModel.id) },
                    onPause = onPause,
                    onStopOrUninstall = { onStopOrUninstall(catalogModel.id) },
                )
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun CatalogInstallCard(
    model: CatalogModel,
    state: ModelDownloadState?,
    installed: Boolean,
    anotherOperationRunning: Boolean,
    onInstall: () -> Unit,
    onPause: () -> Unit,
    onStopOrUninstall: () -> Unit,
) {
    val currentBusy = state?.isBusy == true
    val pausable = currentBusy && state?.phase in setOf(
        ModelDownloadPhase.CHECKING,
        ModelDownloadPhase.DOWNLOADING,
        ModelDownloadPhase.VERIFYING_FILE,
    )
    val resumable = state?.phase == ModelDownloadPhase.CANCELLED
    val retryable = state?.phase == ModelDownloadPhase.ERROR
    val hasOperationState = state != null && state.phase != ModelDownloadPhase.IDLE
    val progressText = state?.progress?.let { "${(it * 100).toInt()}%" }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(model.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("Tamanho: ${formatBytes(model.approximateSizeBytes)}")
                    Text("Requisitos mínimos: ${formatBytes(model.recommendedRamBytes)} RAM.")
                }
                if (installed) {
                    Icon(Icons.Default.CheckCircle, contentDescription = "I.A instalada", tint = MaterialTheme.colorScheme.primary)
                }
            }

            Text(
                model.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (state != null && state.phase != ModelDownloadPhase.IDLE) {
                Text(
                    buildString {
                        append(downloadPhaseLabel(state.phase))
                        if (state.phase == ModelDownloadPhase.DOWNLOADING && progressText != null) {
                            append(" · ").append(progressText)
                        }
                    },
                    fontWeight = FontWeight.SemiBold,
                    color = when (state.phase) {
                        ModelDownloadPhase.ERROR -> MaterialTheme.colorScheme.error
                        ModelDownloadPhase.COMPLETE -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                if (state.phase == ModelDownloadPhase.DOWNLOADING) {
                    val total = state.totalBytes ?: model.approximateSizeBytes
                    Text(
                        "${formatBytes(state.downloadedBytes)} de ${formatBytes(total)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                state.message?.takeIf { state.phase != ModelDownloadPhase.DOWNLOADING }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
            }

            if (!installed && !hasOperationState) {
                Button(
                    onClick = onInstall,
                    enabled = !anotherOperationRunning,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Instalar I.A")
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onStopOrUninstall,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(if (installed) "Desinstalar" else "Encerrar")
                    }

                    Button(
                        onClick = if (pausable) onPause else onInstall,
                        enabled = when {
                            installed -> false
                            pausable -> true
                            resumable || retryable -> !anotherOperationRunning
                            currentBusy -> false
                            else -> !anotherOperationRunning
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        if (currentBusy && !pausable) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text("  Instalando…")
                        } else {
                            Text(
                                when {
                                    installed -> "Instalada"
                                    pausable -> "Pausar"
                                    resumable -> "Continuar"
                                    retryable -> "Tentar novamente"
                                    else -> "Instalar I.A"
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ApiContent(
    modifier: Modifier,
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
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                "API",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                "A integração disponível nesta versão é a API local do próprio aparelho. Ela permite que outros clientes compatíveis usem a I.A instalada pelo app.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            ApiCard(
                baseUrl = baseUrl,
                apiKey = apiKey,
                running = running,
                statusText = statusText,
                runtimeText = runtimeText,
                onToggle = onToggle,
                onUnload = onUnload,
                canUnload = canUnload,
                onCopyUrl = onCopyUrl,
                onCopyKey = onCopyKey,
                onRegenerateKey = onRegenerateKey,
            )
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
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
            Text(
                statusText,
                color = if (running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Memory, contentDescription = null)
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
            Text(
                "Endpoints: /v1/health · /v1/models · /v1/chat/completions",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "A API escuta somente em 127.0.0.1.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onToggle) {
                    Icon(if (running) Icons.Default.Stop else Icons.Default.PlayArrow, contentDescription = null)
                    Text(if (running) " Parar" else " Iniciar")
                }
                OutlinedButton(onClick = onUnload, enabled = canUnload) { Text("Liberar RAM") }
                OutlinedButton(onClick = onRegenerateKey) { Text("Nova chave") }
            }
        }
    }
}

@Composable
private fun ImportPreviewDialog(
    preview: ModelImportPreview,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
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
                Text("Chat template: ${if (preview.metadata.hasChatTemplate) "encontrado ✓" else "não declarado"}")
                Text("ABI do aparelho: ${preview.compatibility.primaryAbi} ${if (preview.compatibility.supportedAbi) "✓" else "✗"}")
                preview.sourceSizeBytes?.let { Text("Tamanho: ${formatBytes(it)}") }
                Text("RAM total: ${formatBytes(preview.compatibility.totalRamBytes)}")
                preview.compatibility.estimatedModelRamBytes?.let {
                    Text("Estimativa conservadora de RAM: ${formatBytes(it)}")
                }
                preview.compatibility.warnings.forEach {
                    Text("⚠ $it", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = preview.compatibility.canImport) {
                Text("Importar e testar")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

private fun providerTitle(provider: ModelProvider): String = when (provider) {
    ModelProvider.GOOGLE -> "Modelos do Google"
    ModelProvider.ALIBABA -> "Modelos da Alibaba"
    ModelProvider.META -> "Modelos da Meta"
}

private fun downloadPhaseLabel(phase: ModelDownloadPhase): String = when (phase) {
    ModelDownloadPhase.IDLE -> "Pronto"
    ModelDownloadPhase.CHECKING -> "Preparando download"
    ModelDownloadPhase.DOWNLOADING -> "Baixando"
    ModelDownloadPhase.VERIFYING_FILE -> "Verificando arquivo"
    ModelDownloadPhase.IMPORTING -> "Instalando I.A"
    ModelDownloadPhase.VERIFYING_MODEL -> "Testando I.A"
    ModelDownloadPhase.COMPLETE -> "Instalada e verificada"
    ModelDownloadPhase.ERROR -> "Falha"
    ModelDownloadPhase.CANCELLED -> "Download pausado"
}

private fun copy(context: Context, label: String, value: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
}

private fun formatBytes(bytes: Long): String {
    val gb = bytes / (1024.0 * 1024.0 * 1024.0)
    return if (gb >= 1.0) "%.1f GB".format(gb) else "%.0f MB".format(bytes / (1024.0 * 1024.0))
}
