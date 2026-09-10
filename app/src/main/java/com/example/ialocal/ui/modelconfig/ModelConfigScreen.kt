package com.example.ialocal.ui.modelconfig

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
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
import com.example.ialocal.data.ContextCalibrationStatus
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelConfigScreen(
    viewModel: ModelConfigViewModel,
    onBack: () -> Unit,
    onStartChat: (String) -> Unit,
) {
    val model by viewModel.model.collectAsStateWithLifecycle()
    val agent by viewModel.agent.collectAsStateWithLifecycle()
    val prompt by viewModel.systemPrompt.collectAsStateWithLifecycle()
    val temperature by viewModel.temperature.collectAsStateWithLifecycle()
    val maxTokens by viewModel.maxTokens.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbar = rememberSnackbarHostState()

    LaunchedEffect(message) {
        val text = message ?: return@LaunchedEffect
        snackbar.showSnackbar(text)
        viewModel.clearMessage()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Configurar modelo de I.A") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Voltar")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                "Revise as configurações da I.A",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                model?.name ?: "Carregando modelo…",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.titleMedium,
            )

            ContextCard(
                contextTokens = model?.contextLength,
                calibratedTokens = model?.calibratedContextLength,
                calibrationStatus = model?.contextCalibrationStatus,
                busy = busy,
                onRecalibrate = viewModel::recalibrateContext,
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Nível de inteligência", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Controla o orçamento máximo de geração. Modelos com modo de raciocínio podem usar esse espaço para respostas mais elaboradas.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        IntelligenceLevel.entries.forEach { level ->
                            FilterChip(
                                selected = IntelligenceLevel.fromMaxTokens(maxTokens) == level,
                                onClick = { viewModel.setIntelligence(level) },
                                label = { Text(level.label) },
                                enabled = !busy,
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("Máximo de saída: $maxTokens tokens", style = MaterialTheme.typography.bodySmall)
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Temperatura", style = MaterialTheme.typography.titleMedium)
                    Text(
                        String.format(Locale.getDefault(), "%.2f", temperature),
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Slider(
                        value = temperature,
                        onValueChange = viewModel::setTemperature,
                        valueRange = 0f..2f,
                        steps = 39,
                        enabled = !busy,
                    )
                    Text(
                        "Mais baixa = respostas mais previsíveis. Mais alta = maior variedade.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            OutlinedTextField(
                value = prompt,
                onValueChange = viewModel::setSystemPrompt,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Prompt do sistema") },
                minLines = 5,
                maxLines = 12,
                enabled = !busy,
                supportingText = {
                    Text("O texto fica salvo para este modelo e pode ser alterado a qualquer momento.")
                },
            )

            Text(
                "Observação: escrever no prompt para usar a web não cria acesso à internet por si só. O modelo só poderá consultar a web quando uma ferramenta de web estiver realmente habilitada no app.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = viewModel::saveSettings,
                    modifier = Modifier.weight(1f),
                    enabled = !busy && agent != null,
                ) {
                    Text("Salvar")
                }
                Button(
                    onClick = { viewModel.startChat(onStartChat) },
                    modifier = Modifier.weight(1f),
                    enabled = !busy && model != null && agent != null,
                ) {
                    if (busy) {
                        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.height(18.dp))
                    } else {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                    }
                    Text("  Iniciar chat")
                }
            }

            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun ContextCard(
    contextTokens: Int?,
    calibratedTokens: Int?,
    calibrationStatus: String?,
    busy: Boolean,
    onRecalibrate: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.AutoAwesome, contentDescription = null)
                Text(
                    "  Contexto: ajuste automático",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "${calibratedTokens ?: contextTokens ?: 0} tokens",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                "O app testa o modelo neste aparelho e mantém o maior contexto considerado estável.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (calibrationStatus == ContextCalibrationStatus.CALIBRATED.name) {
                Spacer(Modifier.height(4.dp))
                Text("Calibrado neste aparelho", style = MaterialTheme.typography.labelMedium)
            }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = onRecalibrate, enabled = !busy) {
                Text("Recalibrar automaticamente")
            }
        }
    }
}
