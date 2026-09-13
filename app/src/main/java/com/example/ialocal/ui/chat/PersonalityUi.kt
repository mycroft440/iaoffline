package com.example.ialocal.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Face
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.ialocal.data.AgentEntity
import com.example.ialocal.data.AiModelEntity
import kotlin.math.roundToInt

private val PERSONALITY_ORDER = compareByDescending<AgentEntity> { it.usageCount }
    .thenByDescending { it.lastUsedAt ?: 0L }
    .thenBy { it.createdAt }

internal fun orderedPersonalities(personalities: List<AgentEntity>): List<AgentEntity> =
    personalities.sortedWith(PERSONALITY_ORDER)

@Composable
internal fun PersonalityDrawerSection(
    personalities: List<AgentEntity>,
    selectedAgentId: String?,
    enabled: Boolean,
    onSelect: (String) -> Unit,
    onManage: () -> Unit,
) {
    val ordered = remember(personalities) { orderedPersonalities(personalities) }

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "I.A Offline · Personalidades",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
    }

    if (ordered.isEmpty()) {
        Text(
            "Baixe e verifique um modelo para usar personalidades.",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        val visibleRows = minOf(3, ordered.size)
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .height((visibleRows * 58).dp),
        ) {
            items(ordered, key = { it.id }) { agent ->
                NavigationDrawerItem(
                    label = {
                        Column {
                            Text(agent.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (agent.deepThinking) {
                                Text(
                                    "Deep Thinking",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    },
                    selected = agent.id == selectedAgentId,
                    icon = {
                        Icon(
                            if (agent.id == selectedAgentId) Icons.Default.Check else Icons.Default.Face,
                            contentDescription = null,
                        )
                    },
                    onClick = { onSelect(agent.id) },
                    modifier = Modifier.heightIn(min = 52.dp),
                )
            }
        }
    }

    TextButton(
        onClick = onManage,
        enabled = enabled,
        modifier = Modifier.padding(horizontal = 8.dp),
    ) {
        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text("Adicionar mais")
    }
}

@Composable
internal fun PersonalitiesManagerDialog(
    model: AiModelEntity,
    personalities: List<AgentEntity>,
    selectedAgentId: String?,
    supportsDeepThinking: Boolean,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
    onUpdate: (AgentEntity) -> Unit,
    onCreate: (name: String, prompt: String, temperature: Float, deepThinking: Boolean) -> Unit,
) {
    val ordered = remember(personalities) { orderedPersonalities(personalities) }
    var editing by remember { mutableStateOf<AgentEntity?>(null) }
    var creating by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.88f),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 4.dp,
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 10.dp, top = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Personalidades",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            model.name,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Fechar")
                    }
                }

                HorizontalDivider(Modifier.padding(top = 10.dp))

                if (ordered.isEmpty()) {
                    Text(
                        "Nenhuma personalidade criada para este modelo.",
                        modifier = Modifier.padding(20.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(ordered, key = { it.id }) { agent ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp)
                                    .clickable { onSelect(agent.id) },
                                colors = CardDefaults.cardColors(
                                    containerColor = if (agent.id == selectedAgentId) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surfaceContainer
                                    },
                                ),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                agent.name,
                                                modifier = Modifier.weight(1f, fill = false),
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            if (agent.id == selectedAgentId) {
                                                Spacer(Modifier.width(6.dp))
                                                Icon(
                                                    Icons.Default.Check,
                                                    contentDescription = "Em uso",
                                                    modifier = Modifier.size(18.dp),
                                                )
                                            }
                                        }
                                        Text(
                                            buildString {
                                                append("Temperatura ")
                                                append(String.format("%.1f", agent.temperature))
                                                if (agent.deepThinking) append(" · Deep Thinking")
                                                if (agent.usageCount > 0) append(" · ${agent.usageCount} usos")
                                            },
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    IconButton(onClick = { editing = agent }) {
                                        Icon(Icons.Default.Edit, contentDescription = "Editar ${agent.name}")
                                    }
                                }
                            }
                        }
                        item { Spacer(Modifier.height(4.dp)) }
                    }
                }

                Button(
                    onClick = { creating = true },
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Criar personalidade")
                }
            }
        }
    }

    editing?.let { agent ->
        PersonalityEditorDialog(
            title = "Editar personalidade",
            initialName = agent.name,
            initialPrompt = agent.systemPrompt,
            initialTemperature = agent.temperature,
            initialDeepThinking = agent.deepThinking,
            supportsDeepThinking = supportsDeepThinking,
            onDismiss = { editing = null },
            onSave = { name, prompt, temperature, deepThinking ->
                onUpdate(
                    agent.copy(
                        name = name,
                        systemPrompt = prompt,
                        temperature = temperature,
                        deepThinking = deepThinking,
                    )
                )
                editing = null
            },
        )
    }

    if (creating) {
        PersonalityEditorDialog(
            title = "Criar personalidade",
            initialName = "",
            initialPrompt = "",
            initialTemperature = 0.3f,
            initialDeepThinking = false,
            supportsDeepThinking = supportsDeepThinking,
            onDismiss = { creating = false },
            onSave = { name, prompt, temperature, deepThinking ->
                onCreate(name, prompt, temperature, deepThinking)
                creating = false
            },
        )
    }
}

@Composable
private fun PersonalityEditorDialog(
    title: String,
    initialName: String,
    initialPrompt: String,
    initialTemperature: Float,
    initialDeepThinking: Boolean,
    supportsDeepThinking: Boolean,
    onDismiss: () -> Unit,
    onSave: (name: String, prompt: String, temperature: Float, deepThinking: Boolean) -> Unit,
) {
    var name by remember(initialName, title) { mutableStateOf(initialName) }
    var prompt by remember(initialPrompt, title) { mutableStateOf(initialPrompt) }
    var temperature by remember(initialTemperature, title) { mutableFloatStateOf(initialTemperature) }
    var deepThinking by remember(initialDeepThinking, title) { mutableStateOf(initialDeepThinking) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nome") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    label = { Text("Prompt da personalidade") },
                    minLines = 5,
                    maxLines = 10,
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Deep Thinking", fontWeight = FontWeight.SemiBold)
                        Text(
                            if (supportsDeepThinking) {
                                "Usa o modo de raciocínio profundo quando o modelo oferece suporte."
                            } else {
                                "Este modelo não anuncia suporte compatível ao Deep Thinking."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = deepThinking && supportsDeepThinking,
                        onCheckedChange = { deepThinking = it },
                        enabled = supportsDeepThinking,
                    )
                }

                Text(
                    "Temperatura: ${String.format("%.1f", temperature)}",
                    fontWeight = FontWeight.SemiBold,
                )
                Slider(
                    value = temperature,
                    onValueChange = {
                        temperature = ((it * 10f).roundToInt() / 10f).coerceIn(0f, 2f)
                    },
                    valueRange = 0f..2f,
                    steps = 19,
                )
                Row(Modifier.fillMaxWidth()) {
                    Text(
                        "Precisa",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        "Criativa",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && prompt.isNotBlank(),
                onClick = {
                    onSave(
                        name.trim(),
                        prompt.trim(),
                        temperature,
                        deepThinking && supportsDeepThinking,
                    )
                },
            ) { Text("Salvar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}
