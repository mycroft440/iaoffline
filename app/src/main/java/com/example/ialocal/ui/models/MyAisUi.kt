package com.example.ialocal.ui.models

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ialocal.ads.InlineAdBanner
import com.example.ialocal.data.AiModelEntity
import com.example.ialocal.models.AutomaticModelImportPhase
import com.example.ialocal.models.AutomaticModelImportProgress
import com.example.ialocal.models.CatalogModel
import com.example.ialocal.models.ModelProvider
import com.example.ialocal.ui.branding.brandName
import com.example.ialocal.ui.branding.logoRes

private val MyAiBg = Color(0xFF0C0D10)
private val MyAiSurface = Color(0xFF15161B)
private val MyAiElevated = Color(0xFF1E1F26)
private val MyAiBorder = Color(0xFF282A33)
private val MyAiText = Color(0xFFF1F2F5)
private val MyAiMuted = Color(0xFF8B8E9D)
private val MyAiActive = Color(0xFF10B981)
private val MyAiDanger = Color(0xFFF43F5E)

private data class PreviewMetrics(
    val height: Dp,
    val logoSize: Dp,
    val logoPadding: Dp,
    val horizontalPadding: Dp,
    val nameSize: TextUnit,
    val parameterSize: TextUnit,
    val spacing: Dp,
)

@Composable
fun MyAisHomePreview(
    models: List<AiModelEntity>,
    catalog: List<CatalogModel>,
    onOpenMyAis: () -> Unit,
) {
    val visibleModels = remember(models) {
        models.sortedBy { it.importedAt }.take(MAX_HOME_MODELS)
    }
    val count = visibleModels.size
    val metrics = previewMetrics(count)

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier.clickable(onClick = onOpenMyAis),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Minhas I.As",
                    color = MyAiText,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Icon(
                    imageVector = Icons.Default.KeyboardArrowRight,
                    contentDescription = "Abrir Minhas I.As",
                    tint = MyAiMuted,
                    modifier = Modifier.size(18.dp),
                )
            }
            Text(
                text = "${models.size} instalada${if (models.size == 1) "" else "s"}",
                color = MyAiMuted,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .background(MyAiElevated, RoundedCornerShape(50))
                    .border(1.dp, MyAiBorder, RoundedCornerShape(50))
                    .padding(horizontal = 10.dp, vertical = 3.dp),
            )
        }

        if (visibleModels.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MyAiSurface, RoundedCornerShape(16.dp))
                    .border(1.dp, MyAiBorder, RoundedCornerShape(16.dp))
                    .padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("Nenhuma IA baixada", color = MyAiText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text("As IAs instaladas aparecerão aqui automaticamente.", color = MyAiMuted, fontSize = 11.sp)
            }
        } else if (count <= 3) {
            Column(verticalArrangement = Arrangement.spacedBy(metrics.spacing)) {
                visibleModels.forEach { model ->
                    val catalogModel = catalogModelFor(model, catalog)
                    MyAiPreviewCard(
                        model = model,
                        catalogModel = catalogModel,
                        metrics = metrics,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(metrics.spacing)) {
                visibleModels.chunked(2).forEach { rowModels ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(metrics.spacing),
                    ) {
                        rowModels.forEach { model ->
                            val catalogModel = catalogModelFor(model, catalog)
                            MyAiPreviewCard(
                                model = model,
                                catalogModel = catalogModel,
                                metrics = metrics,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (rowModels.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun MyAiPreviewCard(
    model: AiModelEntity,
    catalogModel: CatalogModel?,
    metrics: PreviewMetrics,
    modifier: Modifier,
) {
    val provider = resolveProvider(model, catalogModel)
    val parameter = resolveParameter(model, catalogModel)
    val displayName = compactModelName(model.name, parameter)

    Row(
        modifier = modifier
            .height(metrics.height)
            .background(MyAiSurface, RoundedCornerShape(14.dp))
            .border(1.dp, MyAiBorder, RoundedCornerShape(14.dp))
            .padding(horizontal = metrics.horizontalPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(metrics.spacing),
    ) {
        CircularProviderLogo(
            provider = provider,
            size = metrics.logoSize,
            imagePadding = metrics.logoPadding,
        )
        Text(
            text = displayName,
            modifier = Modifier.weight(1f),
            color = MyAiText,
            fontSize = metrics.nameSize,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        parameter?.let {
            Text(
                text = it,
                color = MyAiText,
                fontFamily = FontFamily.Monospace,
                fontSize = metrics.parameterSize,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
        }
    }
}

@Composable
fun MyAisScreen(
    viewModel: ModelsViewModel,
    adsEnabled: Boolean,
    importProgress: AutomaticModelImportProgress,
    onScanStorage: () -> Unit,
    onBack: () -> Unit,
    onOpenChat: (String) -> Unit,
) {
    val models by viewModel.models.collectAsStateWithLifecycle()
    val orderedModels = remember(models) { models.sortedBy { it.importedAt } }

    Scaffold(containerColor = MyAiBg) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MyAiBg),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .background(MyAiSurface, CircleShape)
                        .border(1.dp, MyAiBorder, CircleShape)
                        .clickable(onClick = onBack),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.ArrowBack, "Voltar", tint = MyAiText, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Minhas I.As", color = MyAiText, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "${models.size} modelo${if (models.size == 1) "" else "s"} instalado${if (models.size == 1) "" else "s"}",
                        color = MyAiMuted,
                        fontSize = 11.sp,
                    )
                }
            }

            if (importProgress.isRunning) {
                ModelStorageImportProgress(importProgress)
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item(key = "my-ais-ad-top") {
                    InlineAdBanner(enabled = adsEnabled)
                }
                item(key = "my-ais-storage-search") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MyAiSurface, RoundedCornerShape(14.dp))
                            .border(1.dp, MyAiBorder, RoundedCornerShape(14.dp))
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            "Você pode ter IAs baixadas anteriormente. Clique em buscar para buscar IAs baixadas no seu telefone.",
                            color = MyAiText,
                            fontSize = 12.sp,
                        )
                        Button(onClick = onScanStorage, enabled = !importProgress.isRunning) {
                            if (importProgress.isRunning) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Text(" Buscando...")
                            } else {
                                Text("Buscar")
                            }
                        }
                        if (importProgress.phase == AutomaticModelImportPhase.COMPLETED) {
                            importProgress.summary?.let { summary ->
                                Text(
                                    "Busca concluída: ${summary.imported} importada(s), ${summary.alreadyInstalled} já instalada(s), ${summary.invalid} inválida(s), ${summary.failures} falha(s).",
                                    color = MyAiMuted,
                                    fontSize = 11.sp,
                                )
                            }
                        }
                        if (importProgress.phase == AutomaticModelImportPhase.PERMISSION_REQUIRED) {
                            Text("Permita o acesso aos arquivos do aparelho e toque em Buscar novamente.", color = MyAiMuted, fontSize = 11.sp)
                        }
                        if (importProgress.phase == AutomaticModelImportPhase.FAILED) {
                            Text("A busca falhou. Tente novamente.", color = MyAiMuted, fontSize = 11.sp)
                        }
                    }
                }

                if (orderedModels.isEmpty()) {
                    item(key = "my-ais-empty") {
                        Box(
                            Modifier.fillMaxWidth().height(240.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                if (importProgress.isRunning) "Buscando I.As no aparelho..." else "Nenhuma IA instalada.",
                                color = MyAiMuted,
                                fontSize = 13.sp,
                            )
                        }
                    }
                } else {
                    orderedModels.forEach { model ->
                        item(key = model.id) {
                            val catalogModel = catalogModelFor(model, viewModel.catalog)
                            MyAiDetailedCard(
                                model = model,
                                catalogModel = catalogModel,
                                onOpenChat = { onOpenChat(model.id) },
                                onDelete = { viewModel.delete(model.id) },
                            )
                        }
                    }
                }

                item(key = "my-ais-ad-bottom") {
                    InlineAdBanner(enabled = adsEnabled)
                }
                item { Spacer(Modifier.height(20.dp)) }
            }
        }
    }
}

@Composable
private fun ModelStorageImportProgress(progress: AutomaticModelImportProgress) {
    val detail = when (progress.phase) {
        AutomaticModelImportPhase.DISCOVERING -> "Procurando arquivos GGUF no armazenamento interno..."
        AutomaticModelImportPhase.IMPORTING -> if (progress.total > 0) {
            val current = (progress.processed + 1).coerceAtMost(progress.total)
            "Processando IA $current de ${progress.total}"
        } else {
            "Nenhum arquivo GGUF encontrado."
        }
        else -> ""
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp)
            .background(MyAiSurface, RoundedCornerShape(14.dp))
            .border(1.dp, MyAiBorder, RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Text(
            "Buscando I.As no armazenamento interno",
            color = MyAiText,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(detail, color = MyAiMuted, fontSize = 11.sp)

        val fraction = progress.fraction
        if (fraction == null) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = MyAiActive,
                trackColor = MyAiElevated,
            )
        } else {
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth(),
                color = MyAiActive,
                trackColor = MyAiElevated,
            )
        }

        progress.currentFileName?.let { fileName ->
            Text(
                fileName,
                color = MyAiMuted,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun MyAiDetailedCard(
    model: AiModelEntity,
    catalogModel: CatalogModel?,
    onOpenChat: () -> Unit,
    onDelete: () -> Unit,
) {
    var confirmDelete by remember(model.id) { mutableStateOf(false) }
    val provider = resolveProvider(model, catalogModel)
    val parameter = resolveParameter(model, catalogModel)
    val providerName = provider?.brandName() ?: model.architecture?.takeIf { it.isNotBlank() } ?: "Modelo local"
    val displayName = compactModelName(model.name, parameter)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MyAiSurface, RoundedCornerShape(16.dp))
            .border(1.dp, MyAiBorder, RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProviderLogo(provider = provider, size = 42.dp, imagePadding = 7.dp)

        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = displayName,
                    modifier = Modifier.weight(1f),
                    color = MyAiText,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                parameter?.let {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = it,
                        color = MyAiText,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .background(MyAiElevated, RoundedCornerShape(6.dp))
                            .border(1.dp, MyAiBorder, RoundedCornerShape(6.dp))
                            .padding(horizontal = 7.dp, vertical = 3.dp),
                    )
                }
            }
            Row(
                modifier = Modifier.padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(providerName, color = MyAiMuted, fontSize = 10.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (model.isActive) {
                    Text("  •  ativa", color = MyAiActive, fontSize = 10.5.sp, fontWeight = FontWeight.Medium)
                }
            }
        }

        Box(
            modifier = Modifier
                .size(36.dp)
                .background(MyAiActive, RoundedCornerShape(10.dp))
                .clickable(onClick = onOpenChat),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.Chat, "Abrir chat com esta IA", tint = Color.White, modifier = Modifier.size(18.dp))
        }
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(MyAiElevated, RoundedCornerShape(10.dp))
                .border(1.dp, MyAiBorder, RoundedCornerShape(10.dp))
                .clickable { confirmDelete = true },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.Delete, "Desinstalar IA", tint = MyAiMuted, modifier = Modifier.size(17.dp))
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = MyAiSurface,
            titleContentColor = MyAiText,
            textContentColor = MyAiMuted,
            title = { Text("Desinstalar I.A?") },
            text = { Text("Tem certeza que quer desinstalar ${model.name}?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        onDelete()
                    }
                ) { Text("Desinstalar", color = MyAiDanger) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancelar", color = MyAiMuted) }
            },
        )
    }
}

@Composable
private fun CircularProviderLogo(
    provider: ModelProvider?,
    size: Dp,
    imagePadding: Dp,
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(MyAiElevated)
            .border(1.dp, MyAiBorder, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (provider != null) {
            Image(
                painter = painterResource(provider.logoRes()),
                contentDescription = provider.brandName(),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(imagePadding),
                contentScale = ContentScale.Fit,
            )
        } else {
            Text(
                "AI",
                color = MyAiText,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = (size.value * 0.28f).sp,
            )
        }
    }
}

private fun previewMetrics(count: Int): PreviewMetrics = when (count) {
    0, 1 -> PreviewMetrics(68.dp, 40.dp, 7.dp, 14.dp, 14.sp, 12.sp, 12.dp)
    2 -> PreviewMetrics(62.dp, 36.dp, 6.dp, 13.dp, 13.5.sp, 11.5.sp, 10.dp)
    3 -> PreviewMetrics(56.dp, 32.dp, 5.dp, 12.dp, 13.sp, 11.sp, 9.dp)
    else -> PreviewMetrics(52.dp, 28.dp, 4.dp, 9.dp, 11.5.sp, 10.sp, 7.dp)
}

private fun catalogModelFor(model: AiModelEntity, catalog: List<CatalogModel>): CatalogModel? =
    catalog.firstOrNull { model.apiModelId.startsWith(it.apiIdPrefix) }
        ?: catalog.firstOrNull { it.displayName.equals(model.name, ignoreCase = true) }

private fun resolveProvider(model: AiModelEntity, catalogModel: CatalogModel?): ModelProvider? {
    catalogModel?.provider?.let { return it }
    val text = "${model.name} ${model.architecture.orEmpty()}".lowercase()
    return when {
        "deepseek" in text -> ModelProvider.DEEPSEEK
        "qwen" in text || "alibaba" in text -> ModelProvider.ALIBABA
        "llama" in text || "meta" in text -> ModelProvider.META
        "gemma" in text || "google" in text -> ModelProvider.GOOGLE
        "mistral" in text || "ministral" in text || "mixtral" in text -> ModelProvider.MISTRAL
        "phi" in text || "microsoft" in text -> ModelProvider.MICROSOFT
        "granite" in text || "ibm" in text -> ModelProvider.IBM
        "nemotron" in text || "nvidia" in text -> ModelProvider.NVIDIA
        "olmo" in text || "ai2" in text -> ModelProvider.AI2
        "liquid" in text || Regex("(^|[^a-z])lfm([^a-z]|$)").containsMatchIn(text) -> ModelProvider.LIQUID
        else -> null
    }
}

private fun resolveParameter(model: AiModelEntity, catalogModel: CatalogModel?): String? {
    catalogModel?.let { return formatParameter(it.totalParametersBillions) }
    model.sizeLabel
        ?.trim()
        ?.takeIf { PARAMETER_REGEX.containsMatchIn(it) }
        ?.let { return PARAMETER_REGEX.find(it)?.value?.uppercase() }
    val text = "${model.name} ${model.architecture.orEmpty()}"
    return PARAMETER_REGEX.find(text)?.value?.replace(" ", "")?.uppercase()
}

private fun compactModelName(rawName: String, parameter: String?): String {
    val cleaned = rawName
        .replace('_', ' ')
        .replace(Regex("\\s+"), " ")
        .trim()
    if (parameter.isNullOrBlank()) return cleaned
    return cleaned.replace(
        Regex("(?i)[\\s_-]+${Regex.escape(parameter)}$"),
        "",
    ).trim().ifBlank { cleaned }
}

private fun formatParameter(value: Double): String =
    if (value % 1.0 == 0.0) "${value.toInt()}B"
    else "${"%.2f".format(value).trimEnd('0').trimEnd('.')}B"

private val PARAMETER_REGEX = Regex("(?i)\\b\\d+(?:\\.\\d+)?\\s*B\\b")
private const val MAX_HOME_MODELS = 6
