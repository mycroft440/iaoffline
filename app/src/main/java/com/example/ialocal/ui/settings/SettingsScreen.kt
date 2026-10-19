package com.example.ialocal.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ialocal.ads.InlineAdBanner
import com.example.ialocal.models.AutomaticModelImportProgress
import com.example.ialocal.models.BuiltInProfile

/** Settings menu: one button per section, each opening its own screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    importProgress: AutomaticModelImportProgress,
    selectedProfile: BuiltInProfile,
    privacyOptionsRequired: Boolean,
    onPrivacyOptions: () -> Unit,
    wikipediaSummary: String,
    onOpenAppearance: () -> Unit,
    onOpenAiProfiles: () -> Unit,
    onOpenStorageScan: () -> Unit,
    onOpenCompilers: () -> Unit,
    onOpenWikipedia: () -> Unit,
    adsEnabled: Boolean,
    onBack: () -> Unit,
) {
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val installedIds by viewModel.installedLanguageIds.collectAsStateWithLifecycle()

    Scaffold(
        bottomBar = {
            InlineAdBanner(
                enabled = adsEnabled,
                anchored = true,
                modifier = Modifier.navigationBarsPadding().padding(horizontal = 12.dp),
            )
        },
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
                SettingsEntry(
                    icon = Icons.Default.Palette,
                    title = "Aparência",
                    summary = "Tema: " + themeLabel(themeMode),
                    onClick = onOpenAppearance,
                )
            }
            item {
                SettingsEntry(
                    icon = Icons.Default.Psychology,
                    title = "Perfis de I.A",
                    summary = "Perfil ativo: " + selectedProfile.displayName,
                    onClick = onOpenAiProfiles,
                )
            }
            item {
                SettingsEntry(
                    icon = Icons.Default.Storage,
                    title = "Achar I.As no armazenamento",
                    summary = if (importProgress.isRunning) {
                        "Buscando I.As no aparelho..."
                    } else {
                        "Importa modelos GGUF já salvos no aparelho"
                    },
                    onClick = onOpenStorageScan,
                )
            }
            item {
                SettingsEntry(
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
                SettingsEntry(
                    icon = Icons.AutoMirrored.Filled.MenuBook,
                    title = "Baixar Wikipedia Offline",
                    summary = wikipediaSummary,
                    onClick = onOpenWikipedia,
                )
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

            item { Spacer(Modifier.padding(bottom = 12.dp)) }
        }
    }
}

@Composable
private fun SettingsEntry(
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
