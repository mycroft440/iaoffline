package com.example.ialocal.ui.models

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ialocal.data.AiModelEntity
import com.example.ialocal.models.CatalogModel
import com.example.ialocal.ui.branding.ProviderLogo
import com.example.ialocal.ui.branding.catalogProvider
import java.util.Locale

private val InstalledBg = Color(0xFF0C0D10)
private val InstalledSurface = Color(0xFF15161B)
private val InstalledElevated = Color(0xFF1E1F26)
private val InstalledBorder = Color(0xFF282A33)
private val InstalledText = Color(0xFFF1F2F5)
private val InstalledMuted = Color(0xFF8B8E9D)
private val InstalledAction = Color(0xFF059669)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstalledModelsScreen(
    viewModel: ModelsViewModel,
    onBack: () -> Unit,
    onStartChat: () -> Unit,
) {
    val models by viewModel.models.collectAsStateWithLifecycle()
    val operation by viewModel.operationText.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(error) {
        error?.let {
            snackbar.showSnackbar(it)
            viewModel.clearError()
        }
    }

    Scaffold(
        containerColor = InstalledBg,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Minhas I.As") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Voltar")
                    }
                },
            )
        },
    ) { padding ->
        if (models.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("Nenhuma I.A instalada", color = InstalledText, fontWeight = FontWeight.SemiBold)
                Text(
                    "Baixe uma I.A offline para ela aparecer aqui.",
                    color = InstalledMuted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    Text(
                        text = "${models.size} instalada${if (models.size == 1) "" else "s"}",
                        color = InstalledMuted,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }

                items(models.sortedBy { it.importedAt }, key = { it.id }) { model ->
                    val catalogModel = viewModel.catalog.firstOrNull { model.apiModelId.startsWith(it.apiIdPrefix) }
                    InstalledModelChatRow(
                        model = model,
                        catalogModel = catalogModel,
                        busy = operation != null,
                        onChat = {
                            viewModel.prepareForChat(model.id, onStartChat)
                        },
                    )
                }

                item { Spacer(Modifier.size(12.dp)) }
            }
        }
    }
}

@Composable
private fun InstalledModelChatRow(
    model: AiModelEntity,
    catalogModel: CatalogModel?,
    busy: Boolean,
    onChat: () -> Unit,
) {
    val provider = model.catalogProvider()
    val name = installedCompactName(model, catalogModel)
    val params = installedParameterLabel(model, catalogModel)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(InstalledSurface, RoundedCornerShape(14.dp))
            .border(1.dp, InstalledBorder, RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (provider != null) {
            ProviderLogo(provider = provider, size = 40.dp)
        } else {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(InstalledElevated, RoundedCornerShape(10.dp))
                    .border(1.dp, InstalledBorder, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text("AI", color = InstalledText, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            }
        }

        Column(Modifier.weight(1f)) {
            Text(
                text = name,
                color = InstalledText,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            params?.let {
                Text(
                    text = it,
                    color = InstalledMuted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }

        Spacer(Modifier.width(2.dp))
        Button(
            onClick = onChat,
            enabled = !busy,
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(containerColor = InstalledAction),
        ) {
            Text("Chat", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

private fun installedCompactName(model: AiModelEntity, catalogModel: CatalogModel?): String {
    val raw = catalogModel?.displayName ?: model.name
    val withoutParameter = raw.replace(
        Regex("\\s+\\d+(?:[.,]\\d+)?B(?:-A\\d+(?:[.,]\\d+)?B)?(?:\\s*·\\s*texto)?$", RegexOption.IGNORE_CASE),
        "",
    )
    return withoutParameter
        .replace(Regex("^Qwen(?=\\d)", RegexOption.IGNORE_CASE), "Qwen ")
        .trim()
        .ifBlank { raw }
}

private fun installedParameterLabel(model: AiModelEntity, catalogModel: CatalogModel?): String? {
    val raw = catalogModel?.displayName ?: model.name
    val nominal = Regex(
        "\\s+(\\d+(?:[.,]\\d+)?B(?:-A\\d+(?:[.,]\\d+)?B)?)(?:\\s*·\\s*texto)?$",
        RegexOption.IGNORE_CASE,
    ).find(raw)?.groupValues?.getOrNull(1)
    return nominal?.uppercase(Locale.ROOT)
        ?: catalogModel?.let { formatInstalledParameters(it.totalParametersBillions) }
}

private fun formatInstalledParameters(value: Double): String =
    if (value % 1.0 == 0.0) "${value.toInt()}B" else "${"%.2f".format(value).trimEnd('0').trimEnd('.')}B"
