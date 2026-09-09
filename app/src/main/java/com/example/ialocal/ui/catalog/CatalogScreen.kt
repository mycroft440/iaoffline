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
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
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
                title = { Text("Baixar IA") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Voltar")
                    }
                },
                actions = {
                    IconButton(onClick = onOpenModels) {
                        Icon(Icons.Default.SmartToy, contentDescription = "Meus modelos")
                    }
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
                        "Modelos prontos para baixar",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "O download acontece dentro do app. Depois o GGUF é validado por inferência real e o contexto máximo é calibrado neste aparelho.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            items(items, key = { it.model.id }) { item ->
                CatalogModelCard(
                    item = item,
                    onDownload = { viewModel.download(item.model.id) },
                    onCancel = { viewModel.cancel(item.model.id) },
                    onRetryInstall = { viewModel.retryInstall(item.model.id) },
                )
            }

            item {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(
                        "Os downloads do catálogo são GGUFs de terceiros compatíveis com llama.cpp. A licença e a origem aparecem em cada opção.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = onOpenModels) { Text("Abrir meus modelos") }
                }
            }
        }
    }
}

@Composable
private fun CatalogModelCard(
    item: CatalogItemUi,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onRetryInstall: () -> Unit,
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
                        Text("  Instalado")
                    }
                }
                item.installing -> {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Text("Verificando o GGUF e calibrando o contexto…")
                }
                item.installError != null -> {
                    Text(
                        item.installError,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = onRetryInstall) { Text("Tentar instalar novamente") }
                }
                else -> DownloadControls(item.download, model.approximateSizeBytes, onDownload, onCancel)
            }
        }
    }
}

@Composable
private fun DownloadControls(
    state: CatalogDownloadState,
    approximateSize: Long,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
) {
    when (state) {
        CatalogDownloadState.Idle -> {
            Button(onClick = onDownload) {
                Icon(Icons.Default.Download, contentDescription = null)
                Text("  Baixar ${formatBytes(approximateSize)}")
            }
        }
        is CatalogDownloadState.Pending -> {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Preparando download…", modifier = Modifier.weight(1f))
                OutlinedButton(onClick = onCancel) { Text("Cancelar") }
            }
        }
        is CatalogDownloadState.Running -> {
            val total = state.totalBytes.takeIf { it > 0L }
            if (total != null) {
                val progress = (state.downloadedBytes.toDouble() / total.toDouble()).coerceIn(0.0, 1.0)
                LinearProgressIndicator(progress = { progress.toFloat() }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                Text("${(progress * 100).roundToInt()}% · ${formatBytes(state.downloadedBytes)} de ${formatBytes(total)}")
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                Text("Baixando ${formatBytes(state.downloadedBytes)}…")
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onCancel) { Text("Cancelar download") }
        }
        is CatalogDownloadState.Paused -> {
            Text("Download pausado pelo Android (código ${state.reason}).")
            if (state.totalBytes > 0L) {
                val progress = (state.downloadedBytes.toDouble() / state.totalBytes.toDouble()).coerceIn(0.0, 1.0)
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(progress = { progress.toFloat() }, modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onCancel) { Text("Cancelar") }
        }
        is CatalogDownloadState.Successful -> {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Text("Download concluído. Preparando instalação…")
        }
        is CatalogDownloadState.Failed -> {
            Text(
                "Falha no download (código ${state.reason}).",
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = onDownload) { Text("Tentar novamente") }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    val gb = bytes.toDouble() / 1_000_000_000.0
    return if (gb >= 1.0) String.format(java.util.Locale.getDefault(), "%.2f GB", gb)
    else String.format(java.util.Locale.getDefault(), "%.0f MB", bytes / 1_000_000.0)
}
