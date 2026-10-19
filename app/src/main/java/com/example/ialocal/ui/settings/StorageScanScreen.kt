package com.example.ialocal.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ialocal.models.AutomaticModelImportPhase
import com.example.ialocal.models.AutomaticModelImportProgress

/** Settings → Achar I.As no armazenamento: imports GGUF files already saved on the phone. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageScanScreen(
    importProgress: AutomaticModelImportProgress,
    onScanStorage: () -> Unit,
    onBack: () -> Unit,
) {
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
                StorageScanCard(
                    importProgress = importProgress,
                    onScanStorage = onScanStorage,
                )
            }
        }
    }
}

@Composable
private fun StorageScanCard(
    importProgress: AutomaticModelImportProgress,
    onScanStorage: () -> Unit,
) {
    val scanning = importProgress.isRunning
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
                "A busca automática acontece na primeira abertura do app. Você também pode buscar novamente aqui ou em Minhas I.As. Se necessário, o Android solicitará acesso aos arquivos.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
                    Text("Buscar agora")
                }
            }
            storageScanResult(importProgress)?.let { result ->
                Text(
                    result,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun storageScanResult(progress: AutomaticModelImportProgress): String? = when (progress.phase) {
    AutomaticModelImportPhase.COMPLETED -> progress.summary?.let { summary ->
        "Busca concluída: ${summary.imported} importada(s), ${summary.alreadyInstalled} já instalada(s), ${summary.invalid} inválida(s), ${summary.failures} falha(s)."
    }
    AutomaticModelImportPhase.PERMISSION_REQUIRED -> "Permita o acesso aos arquivos do aparelho e toque em Buscar agora novamente."
    AutomaticModelImportPhase.FAILED -> "A busca falhou. Tente novamente."
    else -> null
}
