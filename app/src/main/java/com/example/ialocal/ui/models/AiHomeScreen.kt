package com.example.ialocal.ui.models

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ialocal.R
import com.example.ialocal.data.AiModelEntity
import com.example.ialocal.models.CatalogModel
import com.example.ialocal.models.ModelDownloadPhase
import com.example.ialocal.models.ModelDownloadState
import com.example.ialocal.models.ModelProvider
import com.example.ialocal.ui.branding.ProviderLogo
import com.example.ialocal.ui.branding.brandName
import com.example.ialocal.ui.branding.catalogProvider

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiHomeScreen(
    viewModel: ModelsViewModel,
    onOpenChats: () -> Unit,
    onOpenApi: () -> Unit,
    onOpenSettings: () -> Unit,
    onExitApp: () -> Unit,
) {
    val installedModels by viewModel.models.collectAsStateWithLifecycle()
    val download by viewModel.downloadState.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var selectedProvider by remember { mutableStateOf<ModelProvider?>(null) }
    var showExitDialog by remember { mutableStateOf(false) }

    LaunchedEffect(error) {
        error?.let {
            snackbar.showSnackbar(it)
            viewModel.clearError()
        }
    }

    BackHandler {
        if (selectedProvider != null) {
            selectedProvider = null
        } else {
            showExitDialog = true
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    selectedProvider?.let { ProviderSectionTitle(it) }
                        ?: Text("I.A Off-line")
                },
                navigationIcon = {
                    if (selectedProvider != null) {
                        IconButton(onClick = { selectedProvider = null }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Voltar")
                        }
                    }
                },
                actions = {
                    if (selectedProvider == null) {
                        IconButton(onClick = onOpenSettings) {
                            Icon(Icons.Default.Settings, contentDescription = "Configurações")
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (selectedProvider == null) {
            HomeContent(
                modifier = Modifier.padding(padding),
                installedModels = installedModels,
                onOpenChats = onOpenChats,
                onDeleteModel = viewModel::delete,
                onSelectProvider = { selectedProvider = it },
                onOpenApi = onOpenApi,
            )
        } else {
            ProviderModelsContent(
                modifier = Modifier.padding(padding),
                provider = requireNotNull(selectedProvider),
                catalog = viewModel.catalog,
                installedModels = installedModels,
                download = download,
                onInstall = viewModel::downloadCatalogModel,
                onPause = viewModel::cancelDownload,
                onEnd = viewModel::endDownload,
                onDeleteModel = viewModel::delete,
            )
        }
    }

    if (showExitDialog) {
        AlertDialog(
            onDismissRequest = onExitApp,
            title = { Text("Deseja sair do app?") },
            confirmButton = {
                TextButton(onClick = onExitApp) {
                    Text("Sim")
                }
            },
            dismissButton = {
                TextButton(onClick = { showExitDialog = false }) {
                    Text("Não")
                }
            },
            properties = DialogProperties(dismissOnClickOutside = false),
        )
    }
}

@Composable
private fun ProviderSectionTitle(provider: ModelProvider) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ProviderLogo(provider = provider, size = 30.dp)
        Text(provider.sectionLabel)
    }
}

@Composable
private fun AppBrandHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 14.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = painterResource(R.drawable.app_launcher),
            contentDescription = "Logo IA Offline",
            modifier = Modifier.size(72.dp),
            contentScale = ContentScale.Fit,
        )
        Spacer(Modifier.width(14.dp))
        Column {
            Text(
                text = "IA Offline",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Inteligência artificial no aparelho",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun HomeContent(
    modifier: Modifier,
    installedModels: List<AiModelEntity>,
    onOpenChats: () -> Unit,
    onDeleteModel: (String) -> Unit,
    onSelectProvider: (ModelProvider) -> Unit,
    onOpenApi: () -> Unit,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { AppBrandHeader() }

        item {
            Text(
                text = "Minhas I.As >>",
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = installedModels.isNotEmpty(), onClick = onOpenChats)
                    .padding(top = 4.dp, bottom = 4.dp),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
        }

        if (installedModels.isEmpty()) {
            item {
                Text(
                    "Nenhuma I.A instalada",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }
        } else {
            items(installedModels, key = { "installed-${it.id}" }) { model ->
                InstalledModelCard(
                    model = model,
                    onOpenChats = onOpenChats,
                    onDelete = { onDeleteModel(model.id) },
                )
            }
        }

        item {
            Spacer(Modifier.height(6.dp))
            Text(
                "Instale modelos off-line",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(vertical = 6.dp),
            )
        }

        item { NavigationCard(ModelProvider.GOOGLE.sectionLabel, ModelProvider.GOOGLE) { onSelectProvider(ModelProvider.GOOGLE) } }
        item { NavigationCard(ModelProvider.ALIBABA.sectionLabel, ModelProvider.ALIBABA) { onSelectProvider(ModelProvider.ALIBABA) } }
        item { NavigationCard(ModelProvider.META.sectionLabel, ModelProvider.META) { onSelectProvider(ModelProvider.META) } }
        item { NavigationCard(ModelProvider.MISTRAL.sectionLabel, ModelProvider.MISTRAL) { onSelectProvider(ModelProvider.MISTRAL) } }
        item { NavigationCard(ModelProvider.DEEPSEEK.sectionLabel, ModelProvider.DEEPSEEK) { onSelectProvider(ModelProvider.DEEPSEEK) } }
        item { NavigationCard(ModelProvider.MICROSOFT.sectionLabel, ModelProvider.MICROSOFT) { onSelectProvider(ModelProvider.MICROSOFT) } }
        item { NavigationCard("Utilizar API", null, onOpenApi) }
        item { Spacer(Modifier.height(18.dp)) }
    }
}

@Composable
private fun NavigationCard(
    label: String,
    provider: ModelProvider?,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            provider?.let { ProviderLogo(provider = it, size = 42.dp) }
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun InstalledModelCard(
    model: AiModelEntity,
    onOpenChats: () -> Unit,
    onDelete: () -> Unit,
) {
    val provider = model.catalogProvider()

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                provider?.let {
                    ProviderLogo(provider = it, size = 42.dp)
                    Spacer(Modifier.width(12.dp))
                }
                Column(Modifier.weight(1f)) {
                    provider?.let {
                        Text(
                            it.brandName(),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(model.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "${formatBytes(model.sizeBytes)} · ${if (model.isActive) "I.A ativa" else "I.A instalada"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (model.isActive) {
                    Icon(Icons.Default.CheckCircle, contentDescription = "I.A ativa", tint = MaterialTheme.colorScheme.primary)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onOpenChats, modifier = Modifier.weight(1f)) { Text("Abrir chat") }
                OutlinedButton(onClick = onDelete, modifier = Modifier.weight(1f)) { Text("Desinstalar") }
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
    onInstall: (String) -> Unit,
    onPause: () -> Unit,
    onEnd: () -> Unit,
    onDeleteModel: (String) -> Unit,
) {
    val providerModels = catalog.filter { it.provider == provider }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (providerModels.isEmpty()) {
            item {
                Text(
                    "Nenhum modelo verificado disponível nesta categoria por enquanto.",
                    modifier = Modifier.padding(top = 24.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            items(providerModels, key = { it.id }) { catalogModel ->
                val installed = installedModels.firstOrNull { it.apiModelId.startsWith(catalogModel.apiIdPrefix) }
                CatalogInstallCard(
                    model = catalogModel,
                    state = download.takeIf { it.catalogId == catalogModel.id },
                    installedModel = installed,
                    anotherOperationRunning = download.isBusy && download.catalogId != catalogModel.id,
                    onInstall = { onInstall(catalogModel.id) },
                    onPause = onPause,
                    onEnd = onEnd,
                    onDelete = { installed?.let { onDeleteModel(it.id) } },
                )
            }
        }
        item { Spacer(Modifier.height(18.dp)) }
    }
}

@Composable
private fun CatalogInstallCard(
    model: CatalogModel,
    state: ModelDownloadState?,
    installedModel: AiModelEntity?,
    anotherOperationRunning: Boolean,
    onInstall: () -> Unit,
    onPause: () -> Unit,
    onEnd: () -> Unit,
    onDelete: () -> Unit,
) {
    val installed = installedModel != null
    val busy = state?.isBusy == true
    val paused = state?.phase == ModelDownloadPhase.CANCELLED
    val failed = state?.phase == ModelDownloadPhase.ERROR
    val progress = state?.progress?.let { "${(it * 100).toInt()}%" }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProviderLogo(provider = model.provider, size = 46.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        model.provider.brandName(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(model.displayName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
            }
            Text("Parâmetros: ${formatParameters(model.totalParametersBillions)}${model.activeParametersBillions?.let { " · ${formatParameters(it)} ativos" } ?: ""}")
            Text("Tamanho: ${formatBytes(model.approximateSizeBytes)}")
            Text("Requisitos mínimos: ${formatBytes(model.recommendedRamBytes)} RAM")
            Text(
                model.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (state != null && state.phase != ModelDownloadPhase.IDLE) {
                Text(
                    downloadStatus(state, progress),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            when {
                installed -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onDelete, modifier = Modifier.weight(1f)) { Text("Desinstalar") }
                        Button(onClick = {}, enabled = false, modifier = Modifier.weight(1f)) { Text("Instalada") }
                    }
                }
                busy -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onEnd, modifier = Modifier.weight(1f)) { Text("Encerrar") }
                        Button(
                            onClick = onPause,
                            enabled = state?.phase == ModelDownloadPhase.DOWNLOADING,
                            modifier = Modifier.weight(1f),
                        ) {
                            if (state?.phase != ModelDownloadPhase.DOWNLOADING) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            }
                            Text(if (state?.phase == ModelDownloadPhase.DOWNLOADING) "Pausar" else " Instalando")
                        }
                    }
                }
                paused || failed -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onEnd, modifier = Modifier.weight(1f)) { Text("Encerrar") }
                        Button(onClick = onInstall, modifier = Modifier.weight(1f)) { Text("Continuar") }
                    }
                }
                else -> {
                    Button(
                        onClick = onInstall,
                        enabled = !anotherOperationRunning,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Instalar I.A")
                    }
                }
            }
        }
    }
}

private fun downloadStatus(state: ModelDownloadState, progress: String?): String = when (state.phase) {
    ModelDownloadPhase.IDLE -> "Pronto para instalar"
    ModelDownloadPhase.CHECKING -> "Preparando instalação"
    ModelDownloadPhase.DOWNLOADING -> "Instalando${progress?.let { " · $it" } ?: ""}"
    ModelDownloadPhase.VERIFYING_FILE -> "Verificando arquivo"
    ModelDownloadPhase.IMPORTING -> "Registrando I.A"
    ModelDownloadPhase.VERIFYING_MODEL -> "Testando I.A no aparelho"
    ModelDownloadPhase.COMPLETE -> "Instalação concluída"
    ModelDownloadPhase.ERROR -> state.message ?: "Falha na instalação"
    ModelDownloadPhase.CANCELLED -> "Instalação pausada"
}

private fun formatParameters(value: Double): String =
    if (value % 1.0 == 0.0) "${value.toInt()}B" else "${"%.2f".format(value).trimEnd('0').trimEnd('.')}B"

private fun formatBytes(bytes: Long): String {
    val gb = bytes / (1024.0 * 1024.0 * 1024.0)
    return if (gb >= 1.0) "%.1f GB".format(gb) else "%.0f MB".format(bytes / (1024.0 * 1024.0))
}
