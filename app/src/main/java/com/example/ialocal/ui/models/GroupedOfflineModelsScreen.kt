package com.example.ialocal.ui.models

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ialocal.data.AiModelEntity
import com.example.ialocal.models.CatalogModel
import com.example.ialocal.models.ModelDownloadPhase
import com.example.ialocal.models.ModelDownloadState
import com.example.ialocal.models.ModelProvider
import com.example.ialocal.ui.branding.ProviderLogo

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupedOfflineModelsScreen(
    viewModel: ModelsViewModel,
    onBack: () -> Unit,
) {
    val installedModels by viewModel.models.collectAsStateWithLifecycle()
    val download by viewModel.downloadState.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val groups = remember(viewModel.catalog) {
        CATALOG_PROVIDER_ORDER.mapNotNull { provider ->
            viewModel.catalog
                .filter { it.provider == provider }
                .takeIf { it.isNotEmpty() }
                ?.let { provider to it }
        }
    }

    LaunchedEffect(error) {
        error?.let {
            snackbar.showSnackbar(it)
            viewModel.clearError()
        }
    }

    BackHandler(onBack = onBack)

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Baixar I.As offline") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Voltar")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            groups.forEach { (provider, providerModels) ->
                item(key = "provider-${provider.name}") {
                    ProviderCatalogHeader(provider = provider)
                }

                items(
                    items = providerModels,
                    key = { it.id },
                ) { catalogModel ->
                    val installed = installedModels.firstOrNull {
                        it.apiModelId.startsWith(catalogModel.apiIdPrefix)
                    }
                    GroupedCatalogInstallCard(
                        model = catalogModel,
                        state = download.takeIf { it.catalogId == catalogModel.id },
                        installedModel = installed,
                        anotherOperationRunning = download.isBusy && download.catalogId != catalogModel.id,
                        onInstall = { viewModel.downloadCatalogModel(catalogModel.id) },
                        onPause = viewModel::cancelDownload,
                        onEnd = viewModel::endDownload,
                        onDelete = { installed?.let { viewModel.delete(it.id) } },
                    )
                }
            }

            item { Spacer(Modifier.height(18.dp)) }
        }
    }
}

@Composable
private fun ProviderCatalogHeader(provider: ModelProvider) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ProviderLogo(provider = provider, size = 38.dp)
        Text(
            text = providerCatalogName(provider),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun GroupedCatalogInstallCard(
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
    var confirmDelete by remember(installedModel?.id) { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Text(
                text = model.displayName,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "Parâmetros: ${groupedFormatParameters(model.totalParametersBillions)}" +
                    (model.activeParametersBillions?.let { " · ${groupedFormatParameters(it)} ativos" } ?: ""),
            )
            Text("Tamanho: ${groupedFormatBytes(model.approximateSizeBytes)}")
            Text("Requisitos mínimos: ${groupedFormatBytes(model.recommendedRamBytes)} RAM")
            Text(
                model.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (state != null && state.phase != ModelDownloadPhase.IDLE) {
                Text(
                    groupedDownloadStatus(state, progress),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (failed) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }

            when {
                installed -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { confirmDelete = true },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Desinstalar")
                        }
                        Button(
                            onClick = {},
                            enabled = false,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Instalada")
                        }
                    }
                }
                busy -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = onEnd,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Encerrar")
                        }
                        Button(
                            onClick = onPause,
                            enabled = state?.phase == ModelDownloadPhase.DOWNLOADING,
                            modifier = Modifier.weight(1f),
                        ) {
                            if (state?.phase != ModelDownloadPhase.DOWNLOADING) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                )
                            }
                            Text(if (state?.phase == ModelDownloadPhase.DOWNLOADING) "Pausar" else " Instalando")
                        }
                    }
                }
                paused || failed -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = onEnd,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Encerrar")
                        }
                        Button(
                            onClick = onInstall,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Continuar")
                        }
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

    if (confirmDelete && installedModel != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Desinstalar I.A?") },
            text = {
                Text(
                    "Tem certeza que quer desinstalar essa I.A? O arquivo de ${installedModel.name} será excluído do armazenamento interno do Android.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        onDelete()
                    },
                ) {
                    Text("Sim, desinstalar")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text("Cancelar")
                }
            },
        )
    }
}

private fun providerCatalogName(provider: ModelProvider): String = when (provider) {
    ModelProvider.DEEPSEEK -> "DeepSeek"
    ModelProvider.GOOGLE -> "Google"
    ModelProvider.ALIBABA -> "Qwen"
    ModelProvider.META -> "Meta"
    ModelProvider.MISTRAL -> "Mistral"
    ModelProvider.MICROSOFT -> "Microsoft"
    ModelProvider.IBM -> "IBM / Granite"
    ModelProvider.LIQUID -> "Liquid AI"
    ModelProvider.AI2 -> "Ai2 / OLMo"
    ModelProvider.NVIDIA -> "NVIDIA"
}

private fun groupedDownloadStatus(state: ModelDownloadState, progress: String?): String = when (state.phase) {
    ModelDownloadPhase.IDLE -> "Pronto para instalar"
    ModelDownloadPhase.CHECKING -> "Preparando instalação"
    ModelDownloadPhase.DOWNLOADING -> "Instalando${progress?.let { " · $it" } ?: ""}"
    ModelDownloadPhase.VERIFYING_FILE -> "Verificando arquivo"
    ModelDownloadPhase.IMPORTING -> "Registrando I.A"
    ModelDownloadPhase.VERIFYING_MODEL -> "Testando I.A no aparelho"
    ModelDownloadPhase.COMPLETE -> state.message ?: "Instalação concluída"
    ModelDownloadPhase.ERROR -> state.message ?: "Falha na instalação"
    ModelDownloadPhase.CANCELLED -> "Instalação pausada"
}

private fun groupedFormatParameters(value: Double): String =
    if (value % 1.0 == 0.0) {
        "${value.toInt()}B"
    } else {
        "${"%.2f".format(value).trimEnd('0').trimEnd('.')}B"
    }

private fun groupedFormatBytes(bytes: Long): String {
    val gb = bytes / (1024.0 * 1024.0 * 1024.0)
    return if (gb >= 1.0) {
        "%.1f GB".format(gb)
    } else {
        "%.0f MB".format(bytes / (1024.0 * 1024.0))
    }
}

private val CATALOG_PROVIDER_ORDER = listOf(
    ModelProvider.DEEPSEEK,
    ModelProvider.GOOGLE,
    ModelProvider.ALIBABA,
    ModelProvider.META,
    ModelProvider.MISTRAL,
    ModelProvider.MICROSOFT,
    ModelProvider.IBM,
    ModelProvider.LIQUID,
    ModelProvider.AI2,
    ModelProvider.NVIDIA,
)
