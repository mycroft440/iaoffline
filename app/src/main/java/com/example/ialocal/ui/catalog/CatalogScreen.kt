package com.example.ialocal.ui.catalog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ialocal.models.CatalogDownloadState
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogScreen(
    viewModel: CatalogViewModel,
    onBack: () -> Unit,
    onOpenModels: () -> Unit,
    onOpenModelConfig: (String) -> Unit,
) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbar = rememberSnackbarHostState()

    LaunchedEffect(message) {
        val text = message ?: return@LaunchedEffect
        snackbar.showSnackbar(text)
        viewModel.clearMessage()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Modelos") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.SmartToy, contentDescription = "Voltar")
                    }
                },
                actions = {
                    TextButton(onClick = onOpenModels) { Text("Importar GGUF") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(
                        "Escolha uma I.A",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "O app baixa, valida, configura e calibra o contexto automaticamente. Depois toque em Abrir para revisar os parâmetros.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            items(items, key = { it.model.id }) { item ->
                CatalogModelCard(
                    item = item,
                    onDownload = { viewModel.download(item.model.id) },
                    onPause = { viewModel.pause(item.model.id) },
                    onResume = { viewModel.resume(item.model.id) },
                    onCancel = { viewModel.cancel(item.model.id) },
                    onRetryInstall = { viewModel.retryInstall(item.model.id) },
                    onOpen = {
                        item.installedModelId?.let(onOpenModelConfig)
                    },
                )
            }

            item {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(
                        "Os GGUFs do catálogo mostram origem e licença. Você também pode importar manualmente um GGUF compatível.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = onOpenModels) { Text("Importar meu próprio GGUF") }
                }
            }
        }
    }
}

@Composable
private fun CatalogModelCard(
    item: CatalogItemUi,
    onDownload: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onRetryInstall: () -> Unit,
    onOpen: () -> Unit,
) {
    val model = item.model
    Card(modifier = Modifier.padding(horizontal = 12.dp).fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(model.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(model.variant, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                }
                if (model.recommended) {
                    Text("RECOMENDADO", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(model.description, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                "${formatBytes(model.approximateSizeBytes)} · ${model.license} · ${model.source}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            when {
                item.installed -> {
                    FilledTonalButton(onClick = {}, enabled = false) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null)
                        Text(if (item.verified) "  Instalado" else "  Instalado · verificar")
                    }
                    item.installError?.let { error ->
                        Spacer(Modifier.height(6.dp))
                        Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = onOpen, enabled = item.installedModelId != null) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Text("  Abrir ${model.name}")
                    }
                }
                item.installing -> {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Text("Configurando automaticamente: validando GGUF, inferência real e contexto…")
                }
                item.installError != null && item.download is CatalogDownloadState.Successful -> {
                    Text(
                        item.installError,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = onRetryInstall) { Text("Tentar configurar novamente") }
                }
                else -> DownloadControls(
                    state = item.download,
                    approximateSize = model.approximateSizeBytes,
                    onDownload = onDownload,
                    onPause = onPause,
                    onResume = onResume,
                    onCancel = onCancel,
                )
            }
        }
    }
}

@Composable
private fun DownloadControls(
    state: CatalogDownloadState,
    approximateSize: Long,
    onDownload: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
) {
    when (state) {
        CatalogDownloadState.Idle -> {
            Button(onClick = onDownload) {
                Icon(Icons.Default.Download, contentDescription = null)
                Text("  Baixar")
            }
        }
        is CatalogDownloadState.Pending -> {
            Progress(state.downloadedBytes, state.totalBytes)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onPause) {
                    Icon(Icons.Default.Pause, contentDescription = null)
                    Text("  Pausar")
                }
                OutlinedButton(onClick = onCancel) {
                    Icon(Icons.Default.Cancel, contentDescription = null)
                    Text("  Cancelar")
                }
            }
        }
        is CatalogDownloadState.Running -> {
            Progress(state.downloadedBytes, state.totalBytes)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onPause) {
                    Icon(Icons.Default.Pause, contentDescription = null)
                    Text("  Pausar")
                }
                OutlinedButton(onClick = onCancel) {
                    Icon(Icons.Default.Cancel, contentDescription = null)
                    Text("  Cancelar")
                }
            }
        }
        is CatalogDownloadState.Paused -> {
            Progress(state.downloadedBytes, state.totalBytes)
            state.reason?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onResume) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Text("  Continuar download")
                }
                OutlinedButton(onClick = onCancel) { Text("Cancelar") }
            }
        }
        is CatalogDownloadState.Successful -> {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Text("Download concluído. Configurando a I.A automaticamente…")
        }
        is CatalogDownloadState.Failed -> {
            Text(state.message, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onResume) { Text("Continuar download") }
                OutlinedButton(onClick = onCancel) { Text("Cancelar") }
            }
        }
    }

    if (state == CatalogDownloadState.Idle) {
        Spacer(Modifier.height(4.dp))
        Text(
            "Tamanho aproximado: ${formatBytes(approximateSize)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Progress(downloadedBytes: Long, totalBytes: Long) {
    if (totalBytes > 0L) {
        val progress = (downloadedBytes.toDouble() / totalBytes.toDouble()).coerceIn(0.0, 1.0)
        LinearProgressIndicator(progress = { progress.toFloat() }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(6.dp))
        Text("${(progress * 100).roundToInt()}% · ${formatBytes(downloadedBytes)} de ${formatBytes(totalBytes)}")
    } else {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(6.dp))
        Text("${formatBytes(downloadedBytes)} baixados")
    }
}

private fun formatBytes(bytes: Long): String {
    val gb = bytes.toDouble() / 1_000_000_000.0
    return if (gb >= 1.0) String.format(java.util.Locale.getDefault(), "%.2f GB", gb)
    else String.format(java.util.Locale.getDefault(), "%.0f MB", bytes / 1_000_000.0)
}
