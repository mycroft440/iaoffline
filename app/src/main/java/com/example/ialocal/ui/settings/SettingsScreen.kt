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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.automirrored.filled.MenuBook
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ialocal.data.ThemeMode
import com.example.ialocal.models.AutomaticModelImportProgress
import com.example.ialocal.models.BuiltInProfile

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    importProgress: AutomaticModelImportProgress,
    onScanStorage: () -> Unit,
    selectedProfile: BuiltInProfile,
    onSelectProfile: (BuiltInProfile) -> Unit,
    privacyOptionsRequired: Boolean,
    onPrivacyOptions: () -> Unit,
    wikipediaSummary: String,
    onOpenCompilers: () -> Unit,
    onOpenWikipedia: () -> Unit,
    onBack: () -> Unit,
) {
    val selected by viewModel.themeMode.collectAsStateWithLifecycle()
    val installedIds by viewModel.installedLanguageIds.collectAsStateWithLifecycle()
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
                    "Perfis de IA",
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "Escolha um dos dois perfis. Eles ficam disponíveis no chat antes mesmo de baixar uma IA.",
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(BuiltInProfile.entries, key = { it.name }) { profile ->
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { onSelectProfile(profile) }.padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = profile == selectedProfile, onClick = { onSelectProfile(profile) })
                    Column(Modifier.padding(start = 8.dp)) {
                        Text(profile.displayName, fontWeight = FontWeight.SemiBold)
                        Text(profile.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            if (privacyOptionsRequired) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text("Privacidade dos anúncios", style = MaterialTheme.typography.titleSmall)
                            OutlinedButton(onClick = onPrivacyOptions) {
                                Text("Gerenciar opções de privacidade")
                            }
                        }
                    }
                }
            }

            item {
                Text(
                    "Modelos locais",
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            item {
                StorageScanCard(
                    scanning = importProgress.isRunning,
                    onScanStorage = onScanStorage,
                )
            }

            item {
                Text(
                    "Ferramentas offline",
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "Downloads opcionais que os agentes usam sem internet.",
                    modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                ToolDownloadsEntry(
                    icon = Icons.Default.Code,
                    title = "Baixar compiladores",
                    summary = "Pacotes de linguagem para o editor de código · " + when (installedIds.size) {
                        0 -> "nenhum instalado"
                        1 -> "1 instalado"
                        else -> "${installedIds.size} instalados"
                    },
                    onClick = onOpenCompilers,
                )
            }
            item {
                ToolDownloadsEntry(
                    icon = Icons.AutoMirrored.Filled.MenuBook,
                    title = "Wikipedia Offline",
                    summary = wikipediaSummary,
                    onClick = onOpenWikipedia,
                )
            }

            item { Spacer(Modifier.padding(bottom = 12.dp)) }
        }
    }
}

@Composable
private fun StorageScanCard(
    scanning: Boolean,
    onScanStorage: () -> Unit,
) {
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
private fun ToolDownloadsEntry(
    icon: ImageVector,
    title: String,
    summary: String,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.Default.ChevronRight, contentDescription = null)
        }
    }
}
