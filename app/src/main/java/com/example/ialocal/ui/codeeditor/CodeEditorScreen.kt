package com.example.ialocal.ui.codeeditor

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
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
    val editorFocus = remember { FocusRequester() }
    var editorValue by remember { mutableStateOf(TextFieldValue(state.code)) }
    val errorHighlight = MaterialTheme.colorScheme.errorContainer
    val warningHighlight = MaterialTheme.colorScheme.tertiaryContainer
    val infoHighlight = MaterialTheme.colorScheme.secondaryContainer
    val profile = CodeLanguageRegistry.find(state.language)

    val issueTransformation = remember(
        state.issues,
        state.code,
        errorHighlight,
        warningHighlight,
        infoHighlight,
    ) {
        VisualTransformation { source ->
            val highlighted = buildAnnotatedString {
                append(source)
                state.issues.forEach { issue ->
                    CodePatchEngine.selectionRange(source.text, issue)?.let { range ->
                        val start = range.start.coerceIn(0, source.length)
                        val end = range.end.coerceIn(start, source.length)
                        if (end > start) {
                            addStyle(
                                SpanStyle(
                                    background = when (issue.severity) {
                                        CodeIssueSeverity.ERROR -> errorHighlight
                                        CodeIssueSeverity.WARNING -> warningHighlight
                                        CodeIssueSeverity.INFO -> infoHighlight
                                    },
                                ),
                                start = start,
                                end = end,
                            )
                        }
                    }
                }
            }
            TransformedText(highlighted, OffsetMapping.Identity)
        }
    }

    LaunchedEffect(state.code) {
        if (editorValue.text != state.code) {
            editorValue = TextFieldValue(state.code)
        }
    }

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbar.showSnackbar(it)
            viewModel.clearError()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Diagnóstico de código") },
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "A sintaxe é decidida por parser formal local, não pela IA. A IA offline só complementa com erros de tipo, referência, lógica, segurança e compatibilidade depois que a sintaxe é aceita.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = state.language,
                onValueChange = viewModel::updateLanguage,
                label = { Text("Linguagem") },
                supportingText = {
                    Text(
                        "Perfil: ${profile.displayName} · parser: ${backendLabel(profile.syntaxBackend)} · " +
                            "${CodeLanguageRegistry.profiles.size} linguagens catalogadas",
                    )
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            SyntaxStatusCard(
                state = state.syntaxState,
                parserName = state.syntaxParserName,
                message = state.syntaxMessage,
            )

            OutlinedTextField(
                value = editorValue,
                onValueChange = {
                    editorValue = it
                    viewModel.updateCode(it.text)
                },
                label = { Text("Código") },
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                visualTransformation = issueTransformation,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(360.dp)
                    .focusRequester(editorFocus),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(
                    onClick = viewModel::analyze,
                    enabled = !state.analyzing && state.code.isNotBlank(),
                    modifier = Modifier.weight(1f),
                ) {
                    if (state.analyzing) {
                        CircularProgressIndicator(
                            modifier = Modifier.width(18.dp).height(18.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (state.analyzing) "Validando" else "Validar código")
                }

                if (state.issues.any { it.canAutoFix }) {
                    OutlinedButton(
                        onClick = viewModel::applyAll,
                        enabled = !state.analyzing,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Aplicar correções")
                    }
                }
            }

            if (state.lastAppliedCount > 0) {
                Text(
                    "${state.lastAppliedCount} correção(ões) aplicada(s). Execute a validação novamente para confirmar.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            if (state.issues.isNotEmpty()) {
                HorizontalDivider()
                Text(
                    "Erros e alertas encontrados (${state.issues.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }

            state.issues.forEach { issue ->
                IssueCard(
                    issue = issue,
                    onLocate = {
                        CodePatchEngine.selectionRange(editorValue.text, issue)?.let { range ->
                            editorValue = editorValue.copy(selection = range)
                            editorFocus.requestFocus()
                        }
                    },
                    onApply = { viewModel.applyIssue(issue) },
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SyntaxStatusCard(
    state: SyntaxValidationState,
    parserName: String?,
    message: String?,
) {
    if (state == SyntaxValidationState.IDLE && message == null) return

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = when (state) {
                    SyntaxValidationState.IDLE -> "Sintaxe precisa ser validada novamente"
                    SyntaxValidationState.CHECKING -> "Validando sintaxe…"
                    SyntaxValidationState.VALID -> "Sintaxe válida"
                    SyntaxValidationState.INVALID -> "Sintaxe inválida"
                    SyntaxValidationState.PARSER_UNAVAILABLE -> "Parser formal indisponível"
                },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = when (state) {
                    SyntaxValidationState.INVALID,
                    SyntaxValidationState.PARSER_UNAVAILABLE -> MaterialTheme.colorScheme.error
                    SyntaxValidationState.VALID -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurface
                },
            )
            parserName?.let {
                Text(
                    "Parser: $it",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            message?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun IssueCard(
    issue: CodeIssue,
    onLocate: () -> Unit,
    onApply: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                issue.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )

            val position = buildString {
                append("linha ${issue.startLine}")
                issue.column?.let { append(":$it") }
                if (issue.endLine > issue.startLine) append("-${issue.endLine}")
            }
            Text(
                "${severityLabel(issue.severity)} · ${categoryLabel(issue.category)} · $position · ${sourceLabel(issue.source)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (issue.explanation.isNotBlank()) {
                Text(issue.explanation, style = MaterialTheme.typography.bodyMedium)
            }

            if (issue.original.isNotEmpty()) {
                PatchPreview(label = "Trecho atual", code = issue.original)
            }
            if (issue.canAutoFix) {
                PatchPreview(label = "Correção proposta", code = issue.replacement)
            } else {
                Text(
                    "Revisão manual necessária: não há alteração automática considerada segura para este diagnóstico.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onLocate, modifier = Modifier.weight(1f)) {
                    Text("Localizar")
                }
                if (issue.canAutoFix) {
                    Button(onClick = onApply, modifier = Modifier.weight(1f)) {
                        Text("Corrigir")
                    }
                }
            }
        }
    }
}

@Composable
private fun PatchPreview(
    label: String,
    code: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Surface(
            tonalElevation = 2.dp,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = if (code.isEmpty()) "∅" else code,
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(10.dp),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private fun backendLabel(backend: SyntaxBackend): String = when (backend) {
    SyntaxBackend.TREE_SITTER -> "Tree-sitter"
    SyntaxBackend.SQL_JSQLPARSER -> "JSqlParser"
    SyntaxBackend.NONE -> "não disponível"
}

private fun severityLabel(severity: CodeIssueSeverity): String = when (severity) {
    CodeIssueSeverity.ERROR -> "Erro"
    CodeIssueSeverity.WARNING -> "Alerta"
    CodeIssueSeverity.INFO -> "Informação"
}

private fun categoryLabel(category: CodeIssueCategory): String = when (category) {
    CodeIssueCategory.SYNTAX -> "Sintaxe"
    CodeIssueCategory.TYPE -> "Tipo"
    CodeIssueCategory.REFERENCE -> "Referência"
    CodeIssueCategory.LOGIC -> "Lógica"
    CodeIssueCategory.SECURITY -> "Segurança"
    CodeIssueCategory.COMPATIBILITY -> "Compatibilidade"
}

private fun sourceLabel(source: CodeIssueSource): String = when (source) {
    CodeIssueSource.LOCAL -> "parser local"
    CodeIssueSource.AI -> "IA local"
}
