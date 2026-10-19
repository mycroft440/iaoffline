package com.example.ialocal.ui.settings

import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ialocal.models.AiProfile
import com.example.ialocal.models.CustomProfiles

/** Settings → Perfis de I.A: the built-in profiles, the ones the user created and a way to add more. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiProfilesScreen(
    viewModel: AiProfilesViewModel,
    onBack: () -> Unit,
) {
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val selected by viewModel.selectedProfile.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val statusMessage by viewModel.statusMessage.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    // The profile open in the editor; [creating] opens it empty instead.
    var editing by remember { mutableStateOf<AiProfile?>(null) }
    var creating by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<AiProfile?>(null) }

    LaunchedEffect(statusMessage) {
        statusMessage?.let {
            snackbar.showSnackbar(it)
            viewModel.clearStatusMessage()
        }
    }

    val builtIn = profiles.filter { !it.isCustom }
    val custom = profiles.filter { it.isCustom }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Perfis de I.A") },
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
                    "O perfil escolhido define como a IA responde no chat. Todos ficam disponíveis no chat antes mesmo de baixar uma IA.",
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            item { SectionTitle("Pré-perfis") }
            items(builtIn, key = { it.id }) { profile ->
                ProfileCard(
                    profile = profile,
                    selected = profile.id == selected.id,
                    onSelect = { viewModel.select(profile) },
                )
            }

            item {
                SectionTitle("Meus perfis")
                Text(
                    "Escreva as instruções que a IA deve seguir, como um professor paciente ou um revisor de textos.",
                    modifier = Modifier.padding(horizontal = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                Button(
                    onClick = { creating = true },
                    enabled = !busy,
                    modifier = Modifier.padding(horizontal = 8.dp),
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Text(" Criar novo perfil")
                }
            }
            if (custom.isEmpty()) {
                item {
                    Text(
                        "Você ainda não criou nenhum perfil.",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(custom, key = { it.id }) { profile ->
                ProfileCard(
                    profile = profile,
                    selected = profile.id == selected.id,
                    onSelect = { viewModel.select(profile) },
                    actionsEnabled = !busy,
                    onEdit = { editing = profile },
                    onDelete = { confirmDelete = profile },
                )
            }

            item { Spacer(Modifier.padding(bottom = 12.dp)) }
        }
    }

    if (creating || editing != null) {
        val initial = editing
        ProfileEditorDialog(
            initial = initial,
            profiles = profiles,
            onDismiss = {
                creating = false
                editing = null
            },
            onSave = { name, description, prompt ->
                viewModel.save(initial?.id, name, description, prompt)
                creating = false
                editing = null
            },
        )
    }

    confirmDelete?.let { profile ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Excluir ${profile.displayName}?") },
            text = { Text("As conversas que usavam este perfil continuam com o Programador.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(profile)
                    confirmDelete = null
                }) { Text("Excluir") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text("Cancelar") }
            },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 8.dp),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun ProfileCard(
    profile: AiProfile,
    selected: Boolean,
    onSelect: () -> Unit,
    actionsEnabled: Boolean = true,
    onEdit: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
) {
    var showPrompt by rememberSaveable(profile.id) { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onSelect)
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = onSelect)
            Column(Modifier.padding(start = 8.dp).weight(1f)) {
                Text(profile.displayName, fontWeight = FontWeight.SemiBold)
                Text(
                    profile.summary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (selected) {
                    Text(
                        "Em uso no chat",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
        if (showPrompt) {
            Text(
                profile.systemPrompt,
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                    .padding(10.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(
            modifier = Modifier.padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { showPrompt = !showPrompt }) {
                Text(if (showPrompt) "Ocultar instruções" else "Ver instruções")
            }
            Spacer(Modifier.weight(1f))
            if (onEdit != null) {
                IconButton(onClick = onEdit, enabled = actionsEnabled) {
                    Icon(Icons.Default.Edit, contentDescription = "Editar ${profile.displayName}")
                }
            }
            if (onDelete != null) {
                IconButton(onClick = onDelete, enabled = actionsEnabled) {
                    Icon(Icons.Default.Delete, contentDescription = "Excluir ${profile.displayName}")
                }
            }
        }
    }
}

@Composable
private fun ProfileEditorDialog(
    initial: AiProfile?,
    profiles: List<AiProfile>,
    onDismiss: () -> Unit,
    onSave: (name: String, description: String, prompt: String) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initial?.displayName.orEmpty()) }
    var description by rememberSaveable { mutableStateOf(initial?.description.orEmpty()) }
    var prompt by rememberSaveable { mutableStateOf(initial?.systemPrompt.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Novo perfil" else "Editar perfil") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it.take(CustomProfiles.MAX_NAME_LENGTH)
                        error = null
                    },
                    label = { Text("Nome") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = {
                        description = it.take(CustomProfiles.MAX_DESCRIPTION_LENGTH)
                        error = null
                    },
                    label = { Text("Descrição (opcional)") },
                    maxLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = prompt,
                    onValueChange = {
                        prompt = it.take(CustomProfiles.MAX_PROMPT_LENGTH)
                        error = null
                    },
                    label = { Text("Instruções para a IA") },
                    placeholder = { Text("Ex.: Você é um professor de matemática paciente. Explique passo a passo.") },
                    supportingText = { Text("${prompt.length}/${CustomProfiles.MAX_PROMPT_LENGTH}") },
                    minLines = 5,
                    maxLines = 10,
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                error = CustomProfiles.validate(name, description, prompt, profiles, editingId = initial?.id)
                if (error == null) onSave(name, description, prompt)
            }) { Text("Salvar") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        },
    )
}
