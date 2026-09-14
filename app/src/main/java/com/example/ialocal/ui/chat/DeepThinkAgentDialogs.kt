package com.example.ialocal.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.ialocal.data.AgentEntity
import com.example.ialocal.data.AiModelEntity
import com.example.ialocal.models.DeepThinkLevel
import com.example.ialocal.models.DeepThinkStore
import com.example.ialocal.models.DeepThinkSupport

@Composable
fun DeepThinkAgentSettingsDialog(
    agent: AgentEntity,
    model: AiModelEntity?,
    store: DeepThinkStore,
    onDismiss: () -> Unit,
    onSave: (AgentEntity, DeepThinkLevel) -> Unit,
) {
    var name by remember(agent.id) { mutableStateOf(agent.name) }
    var prompt by remember(agent.id) { mutableStateOf(agent.systemPrompt) }
    var deepThinkValue by remember(agent.id) {
        mutableFloatStateOf(store.getLevel(agent.id).storedValue.toFloat())
    }
    val capability = remember(model?.id, model?.name, model?.apiModelId) {
        model?.let(DeepThinkSupport::capability)
    }
    val level = DeepThinkLevel.fromStoredValue(deepThinkValue.toInt())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Configurações do agente") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(80) },
                    label = { Text("Nome do agente") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    label = { Text("Instruções do agente") },
                    minLines = 5,
                    maxLines = 10,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Temperatura: 0,3 · fixa no runtime atual",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (model != null && capability?.supported == true) {
                    DeepThinkLevelControl(
                        level = level,
                        description = capability.description,
                        onLevelChange = { deepThinkValue = it.storedValue.toFloat() },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        agent.copy(
                            name = name.trim().ifBlank { agent.name },
                            systemPrompt = prompt.trim(),
                        ),
                        level,
                    )
                },
            ) { Text("Salvar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

@Composable
fun DeepThinkCreateAgentDialog(
    model: AiModelEntity?,
    onDismiss: () -> Unit,
    onSave: (name: String, prompt: String, temperature: Float, deepThinkLevel: DeepThinkLevel) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }
    var deepThinkValue by remember { mutableFloatStateOf(DeepThinkLevel.AUTO.storedValue.toFloat()) }
    val temperature = 0.3f
    val capability = remember(model?.id, model?.name, model?.apiModelId) {
        model?.let(DeepThinkSupport::capability)
    }
    val level = DeepThinkLevel.fromStoredValue(deepThinkValue.toInt())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Criar novo agente") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(80) },
                    label = { Text("Nome do agente") },
                    placeholder = { Text("Ex.: Meu Assistente") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    label = { Text("Prompt do sistema") },
                    placeholder = { Text("Descreva como a I.A deve se comportar...") },
                    minLines = 6,
                    maxLines = 10,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Temperatura: 0,3",
                    style = MaterialTheme.typography.labelLarge,
                )
                Slider(
                    value = temperature,
                    onValueChange = {},
                    valueRange = 0f..2f,
                    enabled = false,
                )
                Text(
                    "O runtime llama.cpp Android atual usa temperatura fixa em 0,3.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (model != null && capability?.supported == true) {
                    DeepThinkLevelControl(
                        level = level,
                        description = capability.description,
                        onLevelChange = { deepThinkValue = it.storedValue.toFloat() },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(name.trim(), prompt.trim(), temperature, level) },
                enabled = name.isNotBlank() && prompt.isNotBlank(),
            ) { Text("Salvar agente") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

@Composable
private fun DeepThinkLevelControl(
    level: DeepThinkLevel,
    description: String,
    onLevelChange: (DeepThinkLevel) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("DeepThink", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            Text(level.label, style = MaterialTheme.typography.labelLarge)
        }
        Slider(
            value = level.storedValue.toFloat(),
            onValueChange = { raw ->
                onLevelChange(DeepThinkLevel.fromStoredValue(raw.toInt().coerceIn(0, 3)))
            },
            valueRange = 0f..3f,
            steps = 2,
        )
        Text(
            "Automático · Baixo · Médio · Alto",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (level != DeepThinkLevel.AUTO) {
            Text(
                "Níveis maiores reservam mais tokens para raciocínio e resposta. Máximo atual: 4096 tokens.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
