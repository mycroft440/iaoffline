package com.example.ialocal.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ialocal.data.KeepLoaded
import com.example.ialocal.data.PerformanceSettingsRepository
import com.example.ialocal.runtime.PerformanceLevel
import com.example.ialocal.runtime.RuntimeTuning
import com.example.ialocal.runtime.TuningSwitch

/**
 * Settings › Desempenho da IA: what the AI may trade RAM for speed for. Each choice that takes more
 * RAM turns itself off for a model that made Android close the AI for lack of memory, and the
 * choices that change how a model is loaded apply from its next load.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PerformanceScreen(
    settings: PerformanceSettingsRepository,
    onBack: () -> Unit,
) {
    val tuning by settings.tuning.collectAsStateWithLifecycle()
    val keepLoaded by settings.keepLoaded.collectAsStateWithLifecycle()
    val preload by settings.preload.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Desempenho da IA") },
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
                    "Opções que usam mais memória RAM para a IA responder mais rápido. No modo automático, cada uma " +
                        "liga de acordo com a RAM do celular e o modelo. Se o Android fechar a IA por falta de memória, " +
                        "o app desliga sozinho a opção que pesou para aquele modelo. As mudanças valem a partir do " +
                        "próximo carregamento do modelo.",
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                ChoiceCard(
                    title = "Desempenho da IA",
                    description = "Guarda na RAM uma cópia dos pesos no formato rápido dos processadores ARM. A mensagem " +
                        "é lida até 2x mais rápido em modelos Q4_0 e Q4_K_M; o Android não consegue liberar essa " +
                        "memória sem fechar a IA. Pouco ganho em arquivos Q3_K, IQ3 e Q2. Em modelos MoE (como o " +
                        "LFM2.5 8B-A1B), quando a cópia inteira não cabe, ganham a cópia os pesos lidos a cada palavra " +
                        "e, em celulares de 12 GB ou mais, parte dos especialistas; o resto fica no arquivo e as " +
                        "páginas copiadas são liberadas, então a RAM em uso não aumenta.",
                    options = listOf(
                        PerformanceLevel.AUTO to "Automático (celulares a partir de 12 GB)",
                        PerformanceLevel.ECONOMY to "Economizar memória",
                        PerformanceLevel.BALANCED to "Equilibrado",
                        PerformanceLevel.MAX to "Máximo (usa mais RAM)",
                    ),
                    selected = tuning.performance,
                    onSelect = settings::setPerformance,
                )
            }
            item {
                ChoiceCard(
                    title = "Contexto longo",
                    description = "Mais 512 MB (1 GB em celulares de 16 GB) para a memória da conversa. Conversas longas " +
                        "continuam por mais tempo sem a IA reler tudo desde o começo, o que leva minutos.",
                    options = switchOptions("Automático (12 GB ou mais)"),
                    selected = tuning.longContext,
                    onSelect = settings::setLongContext,
                )
            }
            item {
                ChoiceCard(
                    title = "Refazer e editar sem reler",
                    description = "\"Refazer\" volta ao ponto da sua mensagem em vez de reler a conversa. Nos modelos " +
                        "híbridos (Qwen3.5, Qwen3.6, Bonsai 2, Nemotron) isso guarda uma cópia do estado da IA: " +
                        "de 20 a 60 MB, até 150 MB nos maiores.",
                    options = listOf(
                        TuningSwitch.AUTO to "Automático",
                        TuningSwitch.OFF to "Desligado nos modelos híbridos",
                        TuningSwitch.ON to "Sempre (até 160 MB)",
                    ),
                    selected = tuning.rewind,
                    onSelect = settings::setRewind,
                )
            }
            item {
                ChoiceCard(
                    title = "Leitura rápida de textos longos (MoE)",
                    description = "Nos modelos MoE grandes lidos do armazenamento, lê mensagens longas e anexos em lotes " +
                        "maiores, relendo o arquivo menos vezes: 1,2 a 1,6x mais rápido. Usa de 50 a 200 MB a mais só " +
                        "enquanto lê.",
                    options = listOf(
                        TuningSwitch.AUTO to "Automático",
                        TuningSwitch.OFF to "Desligado",
                        TuningSwitch.ON to "Máximo",
                    ),
                    selected = tuning.longPromptMoe,
                    onSelect = settings::setLongPromptMoe,
                )
            }
            item {
                ChoiceCard(
                    title = "Modo rápido de MoE",
                    description = "Cada palavra passa por cerca de um quarto menos especialistas: 6 em vez de 8 no " +
                        "Gemma 4 26B-A4B e nos Qwen 30B-A3B. Nos MoE lidos do armazenamento, lê do arquivo um quarto a " +
                        "menos por palavra, o que deve deixar a resposta de 15 a 25% mais rápida. Não usa RAM a mais, " +
                        "mas as respostas podem perder um pouco de qualidade. Modelos que usam 2 ou 3 especialistas " +
                        "ficam como estão.",
                    options = listOf(
                        TuningSwitch.AUTO to "Automático (só MoE lidos do armazenamento)",
                        TuningSwitch.OFF to "Desligado",
                        TuningSwitch.ON to "Em todos os modelos MoE",
                    ),
                    selected = tuning.fastMoe,
                    onSelect = settings::setFastMoe,
                )
            }
            item {
                ChoiceCard(
                    title = "Conversas prontas para continuar",
                    description = "Quantas conversas recentes ficam salvas no armazenamento com a memória da IA, para " +
                        "voltar a elas em cerca de um segundo. Usa só armazenamento (até 1 GB), não RAM.",
                    options = RuntimeTuning.READY_CONVERSATION_CHOICES.map { it to it.toString() },
                    selected = tuning.readyConversations,
                    onSelect = settings::setReadyConversations,
                )
            }
            item {
                ChoiceCard(
                    title = "Manter a IA carregada ao sair do app",
                    description = "Evita carregar o modelo de novo (de 3 a 15 segundos) quando você volta logo. Enquanto " +
                        "isso, outros apps podem ser fechados antes pelo Android.",
                    options = listOf(
                        KeepLoaded.AUTO to "Automático (10 min a partir de 12 GB)",
                        KeepLoaded.RELEASE to "Liberar se faltar memória",
                        KeepLoaded.TEN_MINUTES to "Manter por 10 minutos",
                        KeepLoaded.UNTIL_ANDROID_NEEDS to "Até o Android precisar",
                    ),
                    selected = keepLoaded,
                    onSelect = settings::setKeepLoaded,
                )
            }
            item {
                ChoiceCard(
                    title = "Carregar a IA ao abrir o app",
                    description = "Carrega o modelo do perfil padrão enquanto você está na tela inicial, para a primeira " +
                        "resposta começar sem esperar o carregamento.",
                    options = switchOptions("Automático (12 GB ou mais)"),
                    selected = preload,
                    onSelect = settings::setPreload,
                )
            }
        }
    }
}

private fun switchOptions(automatic: String) = listOf(
    TuningSwitch.AUTO to automatic,
    TuningSwitch.OFF to "Desligado",
    TuningSwitch.ON to "Ligado",
)

@Composable
private fun <T> ChoiceCard(
    title: String,
    description: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(Modifier.selectableGroup()) {
                options.forEach { (value, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(selected = value == selected, role = Role.RadioButton, onClick = { onSelect(value) })
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = value == selected, onClick = null)
                        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
    }
}
