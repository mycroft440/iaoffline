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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ialocal.knowledge.OfflineWikipedia
import com.example.ialocal.knowledge.WikipediaPackState
import com.example.ialocal.knowledge.WikipediaPackStatus

/** Settings → Wikipedia Offline: Kiwix editions of Wikipedia the agents can consult offline. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WikipediaScreen(
    wikipedia: OfflineWikipedia,
    onRequestRewardedAction: (String, () -> Unit) -> Unit,
    onBack: () -> Unit,
) {
    val states by wikipedia.states.collectAsStateWithLifecycle()
    val autoContext by wikipedia.autoContext.collectAsStateWithLifecycle()
    var freeBytes by remember { mutableLongStateOf(wikipedia.freeBytes()) }
    var confirmRemoval by remember { mutableStateOf<WikipediaPackState?>(null) }
    var confirmDownload by remember { mutableStateOf<WikipediaPackState?>(null) }

    LaunchedEffect(Unit) { wikipedia.refresh() }
    LaunchedEffect(states) { freeBytes = wikipedia.freeBytes() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Wikipedia Offline") },
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
                    "Escolha qual edição da Wikipédia baixar para os agentes consultarem sem internet. Com ela instalada, as IAs " +
                        "conferem nomes, datas e fatos nos artigos em vez de inventá-los, sem precisar de um modelo maior. " +
                        "Cada download é liberado após um anúncio recompensado; você confirma o tamanho e o Wi-Fi antes de assistir. " +
                        "Os arquivos são do Kiwix (formato ZIM) e são conferidos por SHA-256 depois do download.",
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Espaço livre: ${OfflineWikipedia.formatSize(freeBytes)}",
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .clickable { wikipedia.setAutoContext(!autoContext) }
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Consultar automaticamente nas conversas",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                "Quando a pergunta cita uma pessoa, lugar ou assunto, o começo do artigo vai junto para a IA. " +
                                    "Os agentes também podem procurar e ler artigos nas edições que você baixou; eles nunca baixam nada.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = autoContext, onCheckedChange = wikipedia::setAutoContext)
                    }
                }
            }

            items(states, key = { it.pack.id }) { state ->
                WikipediaPackCard(
                    state = state,
                    onDownload = { confirmDownload = state },
                    onCancel = { wikipedia.cancel(state.pack.id) },
                    onRemove = { confirmRemoval = state },
                )
            }

            item { Spacer(Modifier.padding(bottom = 12.dp)) }
        }
    }

    confirmDownload?.let { state ->
        var wifiOnly by remember(state.pack.id) { mutableStateOf(true) }
        AlertDialog(
            onDismissRequest = { confirmDownload = null },
            title = { Text("Baixar ${state.pack.title}?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "O app vai baixar a edição mais recente do Kiwix, com cerca de " +
                            "${OfflineWikipedia.formatSize(state.pack.approximateBytes)}. " +
                            "Espaço livre: ${OfflineWikipedia.formatSize(freeBytes)}.",
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { wifiOnly = !wifiOnly },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = wifiOnly, onCheckedChange = { wifiOnly = it })
                        Text("Baixar apenas pelo Wi-Fi")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val packId = state.pack.id
                    val wifiOnlySelected = wifiOnly
                    onRequestRewardedAction("o download de ${state.pack.title}") {
                        wikipedia.install(packId, wifiOnlySelected)
                        confirmDownload = null
                    }
                }) { Text("Assistir anúncio e baixar") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDownload = null }) { Text("Cancelar") }
            },
        )
    }

    confirmRemoval?.let { state ->
        AlertDialog(
            onDismissRequest = { confirmRemoval = null },
            title = { Text("Remover ${state.pack.title}?") },
            text = { Text("O arquivo de ${OfflineWikipedia.formatSize(state.totalBytes)} será apagado. Você pode baixá-lo de novo depois.") },
            confirmButton = {
                TextButton(onClick = {
                    wikipedia.remove(state.pack.id)
                    confirmRemoval = null
                }) { Text("Remover") }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemoval = null }) { Text("Cancelar") }
            },
        )
    }
}

@Composable
private fun WikipediaPackCard(
    state: WikipediaPackState,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onRemove: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(state.pack.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        state.pack.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        statusLine(state),
                        style = MaterialTheme.typography.labelSmall,
                        color = when (state.status) {
                            WikipediaPackStatus.INSTALLED -> MaterialTheme.colorScheme.primary
                            WikipediaPackStatus.FAILED -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                when (state.status) {
                    WikipediaPackStatus.INSTALLED -> OutlinedButton(onClick = onRemove) {
                        Icon(Icons.Default.Delete, contentDescription = null)
                        Text(" Remover")
                    }
                    WikipediaPackStatus.DOWNLOADING, WikipediaPackStatus.WAITING -> OutlinedButton(onClick = onCancel) {
                        Icon(Icons.Default.Close, contentDescription = null)
                        Text(" Cancelar")
                    }
                    WikipediaPackStatus.PREPARING, WikipediaPackStatus.VERIFYING -> Unit
                    WikipediaPackStatus.NOT_INSTALLED, WikipediaPackStatus.FAILED -> Button(onClick = onDownload) {
                        Icon(Icons.Default.Download, contentDescription = null)
                        Text(if (state.status == WikipediaPackStatus.FAILED) " Recompensa e tentar" else " Assistir anúncio")
                    }
                }
            }
            when (state.status) {
                WikipediaPackStatus.DOWNLOADING, WikipediaPackStatus.WAITING -> LinearProgressIndicator(
                    progress = { (state.downloadedBytes.toFloat() / state.totalBytes.coerceAtLeast(1)).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                WikipediaPackStatus.PREPARING, WikipediaPackStatus.VERIFYING ->
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                else -> Unit
            }
        }
    }
}

private fun statusLine(state: WikipediaPackState): String = when (state.status) {
    WikipediaPackStatus.NOT_INSTALLED -> "~${OfflineWikipedia.formatSize(state.pack.approximateBytes)} · não instalado"
    WikipediaPackStatus.PREPARING -> "Procurando a versão mais recente no Kiwix…"
    WikipediaPackStatus.DOWNLOADING ->
        "Baixando · ${OfflineWikipedia.formatSize(state.downloadedBytes)} de ${OfflineWikipedia.formatSize(state.totalBytes)}"
    WikipediaPackStatus.WAITING ->
        "${state.message ?: "Pausado"} · ${OfflineWikipedia.formatSize(state.downloadedBytes)} de ${OfflineWikipedia.formatSize(state.totalBytes)}"
    WikipediaPackStatus.VERIFYING -> "Conferindo a integridade do arquivo…"
    WikipediaPackStatus.INSTALLED -> "Instalado · ${OfflineWikipedia.formatSize(state.totalBytes)} · ${releaseOf(state.fileName)}"
    WikipediaPackStatus.FAILED -> state.message ?: "Falha no download."
}

/** "wikipedia_pt_top_mini_2026-10.zim" -> "edição de 2026-10". */
private fun releaseOf(fileName: String?): String =
    fileName?.let { Regex("_(\\d{4}-\\d{2})\\.zim$").find(it)?.groupValues?.get(1) }?.let { "edição de $it" } ?: "Kiwix"
