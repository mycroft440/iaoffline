package com.example.ialocal.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
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
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ialocal.ui.codeeditor.CodeLanguagePackDescriptor

/** Settings → Baixar compiladores: the language packs of the code editor. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompilersScreen(
    viewModel: SettingsViewModel,
    onRequestRewardedAction: (String, () -> Unit) -> Unit,
    onBack: () -> Unit,
) {
    val installedIds by viewModel.installedLanguageIds.collectAsStateWithLifecycle()
    val busyLanguageId by viewModel.busyLanguageId.collectAsStateWithLifecycle()
    val statusMessage by viewModel.statusMessage.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(statusMessage) {
        statusMessage?.let {
            snackbar.showSnackbar(it)
            viewModel.clearStatusMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Baixar compiladores") },
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
                Text(
                    "Os compiladores não vêm no APK. Para liberar o download de cada compilador, assista a um anúncio recompensado. Com o pacote instalado, ele pode ser usado sem anúncios.",
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            items(
                items = viewModel.availableLanguagePacks,
                key = { it.id },
            ) { descriptor ->
                LanguagePackCard(
                    descriptor = descriptor,
                    installed = descriptor.id in installedIds,
                    busy = busyLanguageId == descriptor.id,
                    anotherBusy = busyLanguageId != null && busyLanguageId != descriptor.id,
                    onDownload = {
                        onRequestRewardedAction("o compilador ${descriptor.displayName}") {
                            viewModel.downloadLanguage(descriptor.id)
                        }
                    },
                    onRemove = { viewModel.removeLanguage(descriptor.id) },
                )
            }

            item { Spacer(Modifier.padding(bottom = 12.dp)) }
        }
    }
}

@Composable
private fun LanguagePackCard(
    descriptor: CodeLanguagePackDescriptor,
    installed: Boolean,
    busy: Boolean,
    anotherBusy: Boolean,
    onDownload: () -> Unit,
    onRemove: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    descriptor.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    descriptor.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    if (installed) "Instalada" else "Opcional · não ocupa o APK base",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (installed) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }

            if (busy) {
                CircularProgressIndicator()
            } else if (installed) {
                OutlinedButton(
                    onClick = onRemove,
                    enabled = !anotherBusy,
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null)
                    Text(" Remover")
                }
            } else {
                Button(
                    onClick = onDownload,
                    enabled = !anotherBusy,
                ) {
                    Icon(Icons.Default.Download, contentDescription = null)
                    Text(" Assistir anúncio e baixar")
                }
            }
        }
    }
}
