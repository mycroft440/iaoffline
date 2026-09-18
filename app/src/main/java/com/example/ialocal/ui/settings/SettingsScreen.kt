package com.example.ialocal.ui.settings

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.RadioButton
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
import com.example.ialocal.data.ThemeMode
import com.example.ialocal.ui.codeeditor.CodeLanguagePackDescriptor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
) {
    val selected by viewModel.themeMode.collectAsStateWithLifecycle()
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
                title = { Text("Configurações") },
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
            contentPadding = PaddingValues(
                horizontal = 12.dp,
                vertical = 12.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text(
                    "Aparência",
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            item { ThemeOption("Seguir o sistema", ThemeMode.SYSTEM, selected, viewModel::setTheme) }
            item { ThemeOption("Claro", ThemeMode.LIGHT, selected, viewModel::setTheme) }
            item { ThemeOption("Escuro", ThemeMode.DARK, selected, viewModel::setTheme) }

            item {
                Text(
                    "Linguagens do editor",
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "O APK não inclui compiladores nem parsers. Estes pacotes pequenos são baixados do repositório somente quando você quiser e dão contexto especializado à IA no Canvas de código.",
                    modifier = Modifier.padding(horizontal = 8.dp, bottom = 4.dp),
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
                    onDownload = { viewModel.downloadLanguage(descriptor.id) },
                    onRemove = { viewModel.removeLanguage(descriptor.id) },
                )
            }

            item { Spacer(Modifier.padding(bottom = 12.dp)) }
        }
    }
}

@Composable
private fun ThemeOption(
    label: String,
    mode: ThemeMode,
    selected: ThemeMode,
    onSelect: (ThemeMode) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect(mode) }
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected == mode,
            onClick = { onSelect(mode) },
        )
        Text(label, modifier = Modifier.padding(start = 8.dp))
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
                    Text(" Baixar")
                }
            }
        }
    }
}
