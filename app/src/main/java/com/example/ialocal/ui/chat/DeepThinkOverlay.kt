package com.example.ialocal.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.ialocal.data.AgentEntity
import com.example.ialocal.data.AiModelEntity
import com.example.ialocal.models.DeepThinkLevel
import com.example.ialocal.models.DeepThinkStore
import com.example.ialocal.models.DeepThinkSupport
import kotlin.math.roundToInt

@Composable
fun DeepThinkOverlay(
    agent: AgentEntity,
    model: AiModelEntity,
) {
    val context = LocalContext.current
    val store = remember(context) { DeepThinkStore(context.applicationContext) }
    var open by remember(agent.id) { mutableStateOf(false) }
    var enabled by remember(agent.id) { mutableStateOf(store.isEnabled(agent.id)) }
    var level by remember(agent.id) { mutableStateOf(store.getLevel(agent.id)) }
    val capability = remember(model.id, model.name, model.apiModelId) { DeepThinkSupport.capability(model) }

    if (!capability.supported) return

    AssistChip(
        onClick = {
            if (enabled) {
                store.setEnabled(agent.id, false)
                enabled = false
            } else {
                open = true
            }
        },
        label = {
            Text(
                if (enabled) "DeepThink: ${level.label}"
                else "DeepThink: Desativado",
            )
        },
    )

    if (open) {
        // Sempre que o Deep Think é reativado, a configuração começa no nível máximo.
        var raw by remember(agent.id, open) { mutableFloatStateOf(DeepThinkLevel.HIGH.storedValue.toFloat()) }
        val selected = DeepThinkLevel.fromStoredValue(raw.roundToInt().coerceIn(1, 3))
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text("Configurar DeepThink") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(model.name, style = MaterialTheme.typography.titleSmall)
                    Text(capability.description, style = MaterialTheme.typography.bodySmall)
                    Text("Nível: ${selected.label}")
                    Slider(
                        value = raw,
                        onValueChange = { raw = it },
                        valueRange = 1f..3f,
                        steps = 1,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("Baixo · Médio · Máximo", style = MaterialTheme.typography.labelSmall)
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        store.setLevel(agent.id, selected)
                        store.setEnabled(agent.id, true)
                        level = selected
                        enabled = true
                        open = false
                    },
                ) { Text("Ativar") }
            },
            dismissButton = {
                TextButton(onClick = { open = false }) { Text("Cancelar") }
            },
        )
    }
}
