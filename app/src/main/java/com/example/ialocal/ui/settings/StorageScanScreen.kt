package com.example.ialocal.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.ialocal.models.AutomaticModelImportPhase
import com.example.ialocal.models.AutomaticModelImportProgress
import com.example.ialocal.models.FoundModelStatus
import com.example.ialocal.models.FoundStorageModel
import java.util.Locale

/**
 * Settings → Achar I.As no armazenamento: finds GGUF files already saved on the phone, lists them
 * before importing, shows which one is being copied and lets the user cancel.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageScanScreen(
    importProgress: AutomaticModelImportProgress,
    onScanStorage: () -> Unit,
    onCancelScan: () -> Unit,
    onBack: () -> Unit,
) {
    val found = importProgress.found

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Achar I.As no armazenamento") },
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
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                ScanControlsCard(
                    progress = importProgress,
                    onScanStorage = onScanStorage,
                    onCancelScan = onCancelScan,
                )
            }

            if (found.isNotEmpty()) {
                item {
                    Text(
                        "I.As encontradas (${found.size})",
                        modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 8.dp),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
                itemsIndexed(found, key = { index, model -> "$index:${model.path}" }) { _, model ->
                    FoundModelCard(model)
                }
            } else if (importProgress.phase == AutomaticModelImportPhase.COMPLETED) {
                item {
                    Text(
                        "Nenhum arquivo GGUF foi encontrado no armazenamento do aparelho.",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item { Spacer(Modifier.padding(bottom = 12.dp)) }
        }
    }
}

@Composable
private fun ScanControlsCard(
    progress: AutomaticModelImportProgress,
    onScanStorage: () -> Unit,
    onCancelScan: () -> Unit,
) {
    val scanning = progress.isRunning
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "Buscar I.As no armazenamento interno",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "O app procura arquivos GGUF no aparelho, mostra as I.As encontradas e importa uma por vez. " +
                    "A busca automática acontece na primeira abertura do app. Se necessário, o Android solicitará acesso aos arquivos.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = onScanStorage,
                    enabled = !scanning,
                ) {
                    if (scanning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                        Text(" Buscando...")
                    } else {
                        Icon(Icons.Default.Search, contentDescription = null)
                        Text(" Buscar agora")
                    }
                }
                if (scanning) {
                    OutlinedButton(
                        onClick = onCancelScan,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) {
                        Icon(Icons.Default.Close, contentDescription = null)
                        Text(" Cancelar")
                    }
                }
            }
            scanStatusLine(progress)?.let { line ->
                Text(
                    line,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            when (progress.phase) {
                AutomaticModelImportPhase.DISCOVERING -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                AutomaticModelImportPhase.IMPORTING -> progress.fraction?.let { fraction ->
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                }
                else -> Unit
            }
        }
    }
}

@Composable
private fun FoundModelCard(model: FoundStorageModel) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    model.name,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (model.status == FoundModelStatus.CHECKING || model.status == FoundModelStatus.IMPORTING) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                }
                Text(
                    statusLabel(model),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = statusColor(model.status),
                )
            }
            Text(
                "${model.fileName} · ${formatSize(model.sizeBytes)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            model.copyFraction?.let { fraction ->
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                Text(
                    "${formatSize(model.copiedBytes)} de ${formatSize(model.sizeBytes)} copiados",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            model.detail?.let { detail ->
                Text(
                    detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun scanStatusLine(progress: AutomaticModelImportProgress): String? = when (progress.phase) {
    AutomaticModelImportPhase.IDLE -> null
    AutomaticModelImportPhase.DISCOVERING -> when (val count = progress.found.size) {
        0 -> "Procurando arquivos GGUF..."
        1 -> "Procurando arquivos GGUF... 1 I.A encontrada até agora."
        else -> "Procurando arquivos GGUF... $count I.As encontradas até agora."
    }
    AutomaticModelImportPhase.IMPORTING -> if (progress.total > 0) {
        val current = (progress.processed + 1).coerceAtMost(progress.total)
        "Verificando e importando $current de ${progress.total}."
    } else {
        "Nenhum arquivo GGUF encontrado."
    }
    AutomaticModelImportPhase.COMPLETED -> progress.summary?.let { summary ->
        "Busca concluída: ${summary.imported} importada(s), ${summary.alreadyInstalled} já instalada(s), ${summary.invalid} inválida(s), ${summary.failures} falha(s)."
    }
    AutomaticModelImportPhase.CANCELLED -> {
        val imported = progress.summary?.imported ?: 0
        "Busca cancelada. " + when (imported) {
            0 -> "Nenhuma I.A foi importada."
            1 -> "1 I.A importada antes do cancelamento continua instalada."
            else -> "$imported I.As importadas antes do cancelamento continuam instaladas."
        }
    }
    AutomaticModelImportPhase.PERMISSION_REQUIRED -> "Permita o acesso aos arquivos do aparelho e toque em Buscar agora novamente."
    AutomaticModelImportPhase.FAILED -> "A busca falhou. Tente novamente."
}

private fun statusLabel(model: FoundStorageModel): String = when (model.status) {
    FoundModelStatus.WAITING -> "Na fila"
    FoundModelStatus.CHECKING -> "Verificando"
    FoundModelStatus.IMPORTING -> model.copyFraction?.let { "Importando ${(it * 100).toInt()}%" } ?: "Importando"
    FoundModelStatus.IMPORTED -> "Importada"
    FoundModelStatus.ALREADY_INSTALLED -> "Já instalada"
    FoundModelStatus.SEEN_BEFORE -> "Já verificada"
    FoundModelStatus.INVALID -> "Arquivo inválido"
    FoundModelStatus.FAILED -> "Falhou"
    FoundModelStatus.CANCELLED -> "Cancelada"
}

@Composable
private fun statusColor(status: FoundModelStatus): Color = when (status) {
    FoundModelStatus.IMPORTING, FoundModelStatus.IMPORTED -> MaterialTheme.colorScheme.primary
    FoundModelStatus.INVALID, FoundModelStatus.FAILED -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun formatSize(bytes: Long): String {
    val gb = bytes / (1024.0 * 1024.0 * 1024.0)
    return if (gb >= 1.0) String.format(Locale.getDefault(), "%.2f GB", gb)
    else String.format(Locale.getDefault(), "%.0f MB", bytes / (1024.0 * 1024.0))
}
