package com.example.ialocal.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
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
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ialocal.runtime.PerformanceProfile
import java.util.Locale
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PerformanceScreen(viewModel: PerformanceViewModel, onBack: () -> Unit) {
    val models by viewModel.models.collectAsStateWithLifecycle()
    val reports by viewModel.reports.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf(emptySet<String>()) }
    Scaffold(topBar = {
        TopAppBar(title = { Text("Desempenho no aparelho") }, navigationIcon = {
            IconButton(onClick = onBack, enabled = progress == null) { Icon(Icons.Default.ArrowBack, "Voltar") }
        })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("Compare os modelos e quantizações já instalados. Cada perfil lê 128 tokens e calcula mais 64, três vezes, com o mesmo texto e contexto de 2.048 tokens.")
                Text("O teste mede o motor com uma continuação fixa. Não mede a qualidade das respostas. O cache de arquivos do Android pode favorecer testes posteriores; repita a comparação com o aparelho frio.",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            }
            if (models.isEmpty()) item { Text("Importe ou baixe um modelo para comparar.") }
            items(models, key = { it.id }) { model ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Row {
                            Checkbox(checked = model.id in selected, enabled = progress == null,
                                onCheckedChange = { selected = if (it) selected + model.id else selected - model.id })
                            Column {
                                Text(model.name)
                                Text("${decimal(model.sizeBytes / 1e9)} GB · ${profiles[model.id]?.label ?: PerformanceProfile.CACHE.label}",
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        OutlinedButton(enabled = progress == null, onClick = { viewModel.apply(model.id, PerformanceProfile.CACHE) }) {
                            Text("Usar CPU com cache automático")
                        }
                    }
                }
            }
            item {
                if (progress == null) {
                    Button(enabled = selected.any { id -> models.any { it.id == id } }, onClick = { viewModel.compare(selected) }) {
                        Text("Comparar CPU, cache e GPU")
                    }
                } else {
                    Text(progress.orEmpty())
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
                    OutlinedButton(onClick = viewModel::stop) { Text("Cancelar teste") }
                }
                message?.let { Text(it, modifier = Modifier.padding(top = 8.dp)) }
            }
            items(reports) { raw ->
                val report = remember(raw) { JSONObject(raw) }
                val profile = PerformanceProfile.parse(report.optString("profile"))
                val modelId = report.optString("modelId")
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(report.optString("modelName"), style = MaterialTheme.typography.titleSmall)
                        Text(profile.label)
                        if (report.has("error")) {
                            Text(report.optString("error"))
                        } else {
                            val runs = report.getJSONArray("runs")
                            val first = runs.getJSONObject(0)
                            val warm = (1 until runs.length()).map { runs.getJSONObject(it) }
                            val warmTokens = warm.sumOf { it.getLong("decodeTokens") }
                            val warmUs = warm.sumOf { it.getLong("decodeUs") }
                            val actualGpu = report.optString("backend") == "Vulkan"
                            Text("1ª passagem: ${decimal(speed(first))} tokens/s · repetidas: ${decimal(warmTokens * 1e6 / warmUs.coerceAtLeast(1))} tokens/s")
                            Text("Leitura do prompt: ${decimal(first.getLong("promptTokens") * 1e6 / first.getLong("promptUs").coerceAtLeast(1))} tokens/s")
                            val reads = warm.map { it.optLong("readBytes", -1) }
                            val ioText = if (reads.any { it < 0 }) "indisponível" else "${decimal(reads.sum() / 1048576.0 / warmTokens.coerceAtLeast(1))} MiB/token"
                            val requested = warm.sumOf { it.getLong("cacheRequested") }
                            val hits = warm.sumOf { it.getLong("cacheHits") }
                            Text("Armazenamento na geração repetida: $ioText")
                            Text("Cache: ${decimal(first.getLong("cacheCapacity") / 1048576.0)} MiB · acertos: ${if (requested > 0) decimal(hits * 100.0 / requested) + "% dos bytes" else "sem amostras"}")
                            Text("Pico de RAM residente durante o teste: ${decimal(report.getLong("peakRssBytes") / 1073741824.0)} GiB")
                            Text("Temperatura do sistema: ${thermal(report.optInt("thermalBefore", -1))} → ${thermal(report.optInt("thermalAfter", -1))}")
                            Text(if (actualGpu) "Vulkan · ${report.optInt("gpuLayers")} camadas parciais" else if (profile == PerformanceProfile.GPU) "GPU indisponível ou sem margem de RAM; teste executado em CPU" else "CPU")
                            Text("Contexto real: ${report.optInt("contextTokens")} tokens · carregamento: ${decimal(report.optLong("loadMs") / 1000.0)} s", style = MaterialTheme.typography.bodySmall)
                            OutlinedButton(enabled = progress == null && models.any { it.id == modelId } && (profile != PerformanceProfile.GPU || actualGpu),
                                onClick = { viewModel.apply(modelId, profile) }) { Text("Usar este perfil") }
                        }
                    }
                }
            }
        }
    }
}

private fun decimal(value: Double) = String.format(Locale.forLanguageTag("pt-BR"), "%.2f", value)
private fun speed(run: JSONObject) = run.getLong("decodeTokens") * 1e6 / run.getLong("decodeUs").coerceAtLeast(1)
private fun thermal(status: Int) = listOf("normal", "leve", "moderada", "alta", "crítica", "emergência", "desligamento").getOrNull(status) ?: "indisponível"
