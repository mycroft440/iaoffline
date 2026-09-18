package com.example.ialocal.ui.codeeditor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodeEditorScreen(
    viewModel: CodeEditorViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var editorValue by remember { mutableStateOf(TextFieldValue(state.code)) }

    val targetRange = remember(editorValue.text, editorValue.selection) {
        CodeLineEditEngine.lineRangeForSelection(
            code = editorValue.text,
            selectionStart = editorValue.selection.start,
            selectionEnd = editorValue.selection.end,
        )
    }
    val targetText = remember(editorValue.text, targetRange) {
        CodeLineEditEngine.extractLines(editorValue.text, targetRange).orEmpty()
    }

    LaunchedEffect(state.code) {
        if (editorValue.text != state.code) {
            val cursor = editorValue.selection.start.coerceIn(0, state.code.length)
            editorValue = TextFieldValue(
                text = state.code,
                selection = TextRange(cursor),
            )
        }
    }

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbar.showSnackbar(it)
            viewModel.clearError()
        }
    }

    BackHandler(onBack = onBack)

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Canvas de código") },
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
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    text = "Editor preciso: toque em uma linha ou selecione um intervalo. A IA só pode substituir essa faixa; não há compilador, linter nem validação automática.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            item {
                OutlinedTextField(
                    value = state.language,
                    onValueChange = viewModel::updateLanguage,
                    label = { Text("Linguagem ou extensão") },
                    supportingText = {
                        Text(
                            if (state.languagePackInstalled) {
                                "Pacote de linguagem instalado e usado como contexto."
                            } else {
                                "Sem pacote baixado: a IA usa apenas o contexto base."
                            },
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            targetRange.label() + " selecionada",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            targetRange.lineCount.toString() + " linha(s)",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    OutlinedTextField(
                        value = editorValue,
                        onValueChange = {
                            editorValue = it
                            viewModel.updateCode(it.text)
                        },
                        label = { Text("Código") },
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(420.dp),
                    )
                }
            }

            item {
                TargetPreview(
                    label = "Trecho que a IA pode editar",
                    code = targetText,
                )
            }

            item {
                OutlinedTextField(
                    value = state.instruction,
                    onValueChange = viewModel::updateInstruction,
                    label = { Text("O que alterar somente nessa faixa?") },
                    placeholder = { Text("Ex.: troque a chamada síncrona por await sem mudar o restante.") },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            item {
                Button(
                    onClick = { viewModel.requestEdit(targetRange) },
                    enabled = !state.editing &&
                        state.code.isNotBlank() &&
                        state.instruction.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (state.editing) {
                        CircularProgressIndicator(
                            modifier = Modifier.width(18.dp).height(18.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Preparando edição")
                    } else {
                        Icon(Icons.Default.Edit, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Editar " + targetRange.label().lowercase())
                    }
                }
            }

            state.pendingEdit?.let { pending ->
                item {
                    PendingEditCard(
                        edit = pending,
                        onApply = viewModel::applyPendingEdit,
                        onDiscard = viewModel::discardPendingEdit,
                    )
                }
            }

            state.lastAppliedSummary?.let { summary ->
                item {
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun PendingEditCard(
    edit: PendingCodeLineEdit,
    onApply: () -> Unit,
    onDiscard: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "Edição preparada · " + edit.range.label(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = edit.summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider()
            TargetPreview("Antes", edit.original)
            TargetPreview("Depois", edit.replacement)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = onDiscard,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Close, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Descartar")
                }
                Button(
                    onClick = onApply,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Check, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Aplicar")
                }
            }
        }
    }
}

@Composable
private fun TargetPreview(
    label: String,
    code: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Surface(
            tonalElevation = 2.dp,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = code.ifEmpty { "∅" },
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(10.dp),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
