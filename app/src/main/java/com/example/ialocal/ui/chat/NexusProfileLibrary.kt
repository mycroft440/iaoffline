package com.example.ialocal.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ialocal.data.AgentEntity
import com.example.ialocal.data.ModelVerificationStatus
import com.example.ialocal.models.ModelRepository
import com.example.ialocal.ui.theme.NexusColors

private data class NexusAgentTemplate(
    val name: String,
    val description: String,
    val prompt: String,
)

private val NEXUS_AGENT_TEMPLATES = listOf(
    NexusAgentTemplate(
        ModelRepository.SOFTWARE_ENGINEER_NAME,
        "Especialista em programação, arquitetura e depuração.",
        ModelRepository.SOFTWARE_ENGINEER_PROMPT,
    ),
    NexusAgentTemplate(
        ModelRepository.SELF_DRIVEN_NAME,
        "Foco em produtividade, disciplina e execução por etapas.",
        ModelRepository.SELF_DRIVEN_PROMPT,
    ),
    NexusAgentTemplate(
        ModelRepository.UNCENSORED_NAME,
        "Respostas diretas, francas e com menos filtros de estilo.",
        ModelRepository.UNCENSORED_PROMPT,
    ),
    NexusAgentTemplate(
        "Assistente Geral",
        "Equilibrado para tarefas cotidianas.",
        ModelRepository.DEFAULT_SYSTEM_PROMPT,
    ),
    NexusAgentTemplate(
        "Tutor de Estudos",
        "Explicações didáticas, exemplos e revisão de conhecimento.",
        "Você é um tutor paciente e rigoroso. Explique conceitos por etapas, use exemplos concretos, faça perguntas de verificação quando útil e adapte a profundidade ao nível do usuário.",
    ),
    NexusAgentTemplate(
        "Criador de Conteúdo",
        "Ajuda com textos, roteiros, ideias e revisão.",
        "Você é um criador e editor de conteúdo. Produza textos claros, originais e adequados ao público e ao canal. Ofereça alternativas de tom e melhore estrutura, ritmo e precisão.",
    ),
    NexusAgentTemplate(
        "Analista de Dados",
        "Análise, tabelas, métricas e interpretação de resultados.",
        "Você é um analista de dados cuidadoso. Estruture hipóteses, verifique unidades e premissas, diferencie correlação de causalidade e apresente conclusões com limitações e próximos testes.",
    ),
)

@Composable
fun NexusProfileLibraryAction(
    viewModel: ChatViewModel,
    modifier: Modifier = Modifier,
) {
    val conversation by viewModel.conversation.collectAsStateWithLifecycle()
    val agents by viewModel.agents.collectAsStateWithLifecycle()
    val models by viewModel.models.collectAsStateWithLifecycle()
    val agentUsageCounts by viewModel.agentUsageCounts.collectAsStateWithLifecycle()

    val readyModels = remember(models) {
        models.filter { it.verificationStatus == ModelVerificationStatus.VERIFIED.name }
    }
    val selectedAgent = agents.firstOrNull { it.id == conversation?.agentId }
        ?: agents.firstOrNull { it.isDefault }
    val selectedModel = readyModels.firstOrNull { it.id == selectedAgent?.modelId }
        ?: readyModels.firstOrNull { it.isActive }
        ?: readyModels.firstOrNull()
    val modelAgents = remember(agents, selectedModel?.id, agentUsageCounts) {
        val modelId = selectedModel?.id
        if (modelId == null) {
            emptyList()
        } else {
            agents.filter { it.modelId == modelId }.sortedWith(
                compareByDescending<AgentEntity> { agentUsageCounts[it.id] ?: 0 }
                    .thenByDescending { it.isDefault }
                    .thenByDescending { it.updatedAt }
            )
        }
    }

    var libraryOpen by remember { mutableStateOf(false) }
    var customOpen by remember { mutableStateOf(false) }
    var pendingDefaultAgent by remember { mutableStateOf<AgentEntity?>(null) }

    if (selectedModel != null) {
        OutlinedButton(
            onClick = { libraryOpen = true },
            modifier = modifier,
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, NexusColors.Border),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = NexusColors.Surface850.copy(alpha = 0.96f),
                contentColor = NexusColors.TextSecondary,
            ),
        ) {
            Icon(Icons.Default.Psychology, contentDescription = null, tint = NexusColors.Brand, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(6.dp))
            Text("Mais perfis", fontSize = 11.sp, fontWeight = FontWeight.Medium)
        }
    }

    if (libraryOpen && selectedModel != null) {
        AlertDialog(
            onDismissRequest = { libraryOpen = false },
            containerColor = NexusColors.Surface850,
            title = {
                Column {
                    Text("Biblioteca de perfis", fontWeight = FontWeight.SemiBold)
                    Text(
                        selectedModel.name,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Normal,
                        color = NexusColors.TextMuted,
                    )
                }
            },
            text = {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 520.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    item {
                        Text(
                            "SEUS PERFIS · MAIS USADOS PRIMEIRO",
                            modifier = Modifier.padding(vertical = 6.dp),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = NexusColors.TextMuted,
                        )
                    }
                    if (modelAgents.isEmpty()) {
                        item {
                            Text(
                                "Nenhum perfil disponível para este modelo.",
                                modifier = Modifier.padding(vertical = 8.dp),
                                fontSize = 12.sp,
                                color = NexusColors.TextMuted,
                            )
                        }
                    } else {
                        items(modelAgents, key = { "profile-${it.id}" }) { agent ->
                            NexusProfileRow(
                                name = agent.name,
                                description = agent.systemPrompt,
                                selected = agent.id == selectedAgent?.id,
                                actionLabel = if (agent.id == selectedAgent?.id) "Atual" else "Usar",
                                onClick = {
                                    viewModel.selectAgent(agent.id)
                                    pendingDefaultAgent = agent
                                    libraryOpen = false
                                },
                            )
                        }
                    }

                    item {
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 10.dp),
                            color = NexusColors.BorderSoft,
                        )
                        Text(
                            "PERFIS PRONTOS",
                            modifier = Modifier.padding(bottom = 6.dp),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = NexusColors.TextMuted,
                        )
                    }

                    items(NEXUS_AGENT_TEMPLATES, key = { "template-${it.name}" }) { template ->
                        val existing = modelAgents.firstOrNull { it.name.equals(template.name, ignoreCase = true) }
                        NexusProfileRow(
                            name = template.name,
                            description = template.description,
                            selected = existing?.id == selectedAgent?.id,
                            actionLabel = when {
                                existing?.id == selectedAgent?.id -> "Atual"
                                existing != null -> "Usar"
                                else -> "Adicionar"
                            },
                            onClick = {
                                if (existing != null) {
                                    viewModel.selectAgent(existing.id)
                                    pendingDefaultAgent = existing
                                    libraryOpen = false
                                } else {
                                    viewModel.createAgentProfile(
                                        modelId = selectedModel.id,
                                        name = template.name,
                                        systemPrompt = template.prompt,
                                    ) { created ->
                                        pendingDefaultAgent = created
                                    }
                                    libraryOpen = false
                                }
                            },
                        )
                    }

                    item {
                        TextButton(
                            onClick = {
                                libraryOpen = false
                                customOpen = true
                            },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Criar perfil personalizado")
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { libraryOpen = false }) { Text("Fechar") }
            },
        )
    }

    if (customOpen && selectedModel != null) {
        NexusCustomProfileDialog(
            onDismiss = { customOpen = false },
            onSave = { name, prompt ->
                viewModel.createAgentProfile(
                    modelId = selectedModel.id,
                    name = name,
                    systemPrompt = prompt,
                ) { created ->
                    pendingDefaultAgent = created
                }
                customOpen = false
            },
        )
    }

    pendingDefaultAgent?.let { agent ->
        AlertDialog(
            onDismissRequest = { pendingDefaultAgent = null },
            containerColor = NexusColors.Surface850,
            title = { Text("Perfil ativo") },
            text = {
                Text("${agent.name} está ativo nesta conversa. Deseja defini-lo também como perfil padrão?")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.setDefaultAgent(agent.id)
                        pendingDefaultAgent = null
                    },
                ) { Text("Definir padrão") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDefaultAgent = null }) { Text("Agora não") }
            },
        )
    }
}

@Composable
private fun NexusProfileRow(
    name: String,
    description: String,
    selected: Boolean,
    actionLabel: String,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = if (selected) NexusColors.Brand.copy(alpha = 0.14f) else Color.Transparent,
        border = if (selected) BorderStroke(1.dp, NexusColors.Brand.copy(alpha = 0.35f)) else null,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (selected) Icons.Default.Check else Icons.Default.Psychology,
                contentDescription = null,
                tint = if (selected) NexusColors.Brand else NexusColors.TextMuted,
                modifier = Modifier.size(17.dp),
            )
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    name,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = NexusColors.TextPrimary,
                )
                Text(
                    description,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 10.sp,
                    lineHeight = 14.sp,
                    color = NexusColors.TextMuted,
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(actionLabel, fontSize = 10.sp, color = NexusColors.Brand)
        }
    }
}

@Composable
private fun NexusCustomProfileDialog(
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = NexusColors.Surface850,
        title = { Text("Criar novo perfil", fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(80) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Nome do perfil") },
                    placeholder = { Text("Ex.: Meu Assistente") },
                )
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 5,
                    maxLines = 9,
                    label = { Text("Prompt do sistema") },
                    placeholder = { Text("Descreva como a I.A deve se comportar...") },
                )
                Text(
                    "O runtime local usa temperatura fixa em 0,3; o perfil altera instruções e comportamento, não o sampler.",
                    style = MaterialTheme.typography.bodySmall,
                    color = NexusColors.TextMuted,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(name.trim(), prompt.trim()) },
                enabled = name.isNotBlank() && prompt.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = NexusColors.BrandStrong),
            ) { Text("Salvar e ativar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}
