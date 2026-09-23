package com.example.ialocal.ui.models

import android.app.ActivityManager
import android.content.Context
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ialocal.ads.InlineAdBanner
import com.example.ialocal.data.AiModelEntity
import com.example.ialocal.models.CatalogModel
import com.example.ialocal.models.DownloadEntryStatus
import com.example.ialocal.models.ModelDownloadPhase
import com.example.ialocal.models.ModelDownloadState
import com.example.ialocal.models.ModelProvider
import com.example.ialocal.ui.branding.ProviderLogo
import kotlinx.coroutines.launch

private val CatalogBackground = Color(0xFF0A0D14)
private val CatalogCard = Color(0xFF111726)
private val CatalogCardDark = Color(0xFF080C14)
private val CatalogSurface = Color(0xFF0F172A)
private val CatalogBorder = Color(0xFF1F293D)
private val CatalogBorderLight = Color(0xFF334155)
private val CatalogText = Color(0xFFF8FAFC)
private val CatalogMuted = Color(0xFF94A3B8)
private val CatalogMutedDark = Color(0xFF64748B)
private val CatalogCyan = Color(0xFF06B6D4)
private val CatalogCyanLight = Color(0xFF67E8F9)
private val CatalogEmerald = Color(0xFF34D399)
private val CatalogAmber = Color(0xFFFBBF24)
private val CatalogRose = Color(0xFFFB7185)

private data class CatalogHardwareSnapshot(
    val totalRamBytes: Long,
    val availableRamBytes: Long,
)

private data class ProviderUiMeta(
    val selectorName: String,
    val selectorSubtitle: String,
    val sectionName: String,
    val badge: String,
    val tagline: String,
    val accent: Color,
)

private data class CatalogCompatibility(
    val label: String,
    val accent: Color,
    val canRunComfortably: Boolean,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupedOfflineModelsScreen(
    viewModel: ModelsViewModel,
    adsEnabled: Boolean,
    onEnsureStorageAccess: (() -> Unit) -> Unit,
    onBack: () -> Unit,
) {
    val installedModels by viewModel.models.collectAsStateWithLifecycle()
    val downloads by viewModel.downloads.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val hardware = remember(context) { readCatalogHardwareSnapshot(context) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var selectedProvider by remember { mutableStateOf<ModelProvider?>(null) }

    val groups = remember(viewModel.catalog) {
        CATALOG_PROVIDER_ORDER.mapNotNull { provider ->
            viewModel.catalog
                .filter { it.provider == provider }
                .takeIf { it.isNotEmpty() }
                ?.let { provider to it }
        }
    }
    val visibleGroups = if (selectedProvider == null) {
        groups
    } else {
        groups.filter { it.first == selectedProvider }
    }
    val visibleCount = visibleGroups.sumOf { it.second.size }

    LaunchedEffect(error) {
        error?.let {
            snackbar.showSnackbar(it)
            viewModel.clearError()
        }
    }

    BackHandler(onBack = onBack)

    Scaffold(
        containerColor = CatalogBackground,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Baixar I.As offline") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Voltar")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = CatalogBackground,
                    titleContentColor = CatalogText,
                    navigationIconContentColor = CatalogText,
                ),
            )
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(CatalogBackground),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item(key = "catalog-ad-top") {
                InlineAdBanner(enabled = adsEnabled)
            }

            item(key = "company-selector") {
                CompanySelectorSection(
                    catalog = viewModel.catalog,
                    selectedProvider = selectedProvider,
                    visibleCount = visibleCount,
                    onSelect = { provider ->
                        selectedProvider = if (provider != null && selectedProvider == provider) null else provider
                    },
                    onScrollTop = {
                        scope.launch { listState.animateScrollToItem(0) }
                    },
                )
            }

            visibleGroups.forEach { (provider, providerModels) ->
                item(key = "provider-${provider.name}") {
                    ProviderCatalogHeader(
                        provider = provider,
                        modelCount = providerModels.size,
                    )
                }

                items(
                    items = providerModels,
                    key = { it.id },
                ) { catalogModel ->
                    val installed = installedModels.firstOrNull {
                        it.apiModelId.startsWith(catalogModel.apiIdPrefix)
                    }
                    HtmlCatalogInstallCard(
                        model = catalogModel,
                        hardware = hardware,
                        download = downloads.firstOrNull { it.model.id == catalogModel.id },
                        installedModel = installed,
                        onInstall = {
                            onEnsureStorageAccess { viewModel.downloadCatalogModel(catalogModel.id) }
                        },
                        onPause = viewModel::cancelDownload,
                        onResume = {
                            onEnsureStorageAccess { viewModel.resumeDownload(catalogModel.id) }
                        },
                        onRemove = { viewModel.removeDownload(catalogModel.id) },
                        onDelete = { installed?.let { viewModel.delete(it.id) } },
                    )
                }
            }

            item(key = "catalog-ad-bottom") {
                InlineAdBanner(enabled = adsEnabled)
            }

            item { Spacer(Modifier.height(18.dp)) }
        }
    }
}

@Composable
private fun CompanySelectorSection(
    catalog: List<CatalogModel>,
    selectedProvider: ModelProvider?,
    visibleCount: Int,
    onSelect: (ModelProvider?) -> Unit,
    onScrollTop: () -> Unit,
) {
    val selectorProviders = listOf(
        ModelProvider.DEEPSEEK,
        ModelProvider.ALIBABA,
        ModelProvider.MISTRAL,
        ModelProvider.META,
        ModelProvider.GOOGLE,
    ).filter { provider -> catalog.any { it.provider == provider } }
    val cells: List<ModelProvider?> = selectorProviders + listOf(null)

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Text(
                        text = "ORGANIZADO POR EMPRESA",
                        color = Color(0xFFCBD5E1),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.7.sp,
                    )
                    CatalogPill(
                        text = "$visibleCount IAs",
                        foreground = CatalogCyanLight,
                        background = Color(0x2622D3EE),
                        border = CatalogCyan.copy(alpha = 0.35f),
                    )
                }
                Text(
                    text = "Exibindo catálogo completo de modelos (todos os tamanhos)",
                    color = CatalogMuted,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }

            Box(
                modifier = Modifier
                    .background(Color(0xCC1E293B), RoundedCornerShape(8.dp))
                    .border(1.dp, CatalogBorderLight.copy(alpha = 0.75f), RoundedCornerShape(8.dp))
                    .clickable(onClick = onScrollTop)
                    .padding(horizontal = 9.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "↑  Topo",
                    color = CatalogCyanLight,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            cells.chunked(2).forEach { rowCells ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    rowCells.forEach { provider ->
                        val count = if (provider == null) catalog.size else catalog.count { it.provider == provider }
                        CompanyFilterCard(
                            provider = provider,
                            count = count,
                            selected = selectedProvider == provider,
                            modifier = Modifier.weight(1f),
                            onClick = { onSelect(provider) },
                        )
                    }
                    if (rowCells.size == 1) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun CompanyFilterCard(
    provider: ModelProvider?,
    count: Int,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val meta = provider?.let(::providerUiMeta)
    val accent = if (selected) CatalogCyan else meta?.accent ?: CatalogCyan
    val shape = RoundedCornerShape(12.dp)
    val background = if (selected) Color(0x2622D3EE) else Color(0xE60F172A)
    val border = if (selected) CatalogCyan.copy(alpha = 0.55f) else CatalogBorder

    Row(
        modifier = modifier
            .background(background, shape)
            .border(1.dp, border, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (provider != null) {
                ProviderLogo(provider = provider, size = 24.dp)
            } else {
                Text(text = "🌐", fontSize = 17.sp)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = meta?.selectorName ?: "Todas as IAs",
                    color = if (selected) CatalogCyanLight else CatalogText,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = meta?.selectorSubtitle ?: "Catálogo Geral",
                    color = if (selected) CatalogCyanLight.copy(alpha = 0.8f) else CatalogMuted,
                    fontSize = 9.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        CatalogPill(
            text = count.toString(),
            foreground = accent,
            background = accent.copy(alpha = 0.12f),
            border = accent.copy(alpha = 0.35f),
        )
    }
}

@Composable
private fun ProviderCatalogHeader(
    provider: ModelProvider,
    modelCount: Int,
) {
    val meta = providerUiMeta(provider)

    Column(modifier = Modifier.fillMaxWidth()) {
        HorizontalDivider(color = CatalogBorder.copy(alpha = 0.8f))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .background(meta.accent.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
                        .border(1.dp, meta.accent.copy(alpha = 0.38f), RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    ProviderLogo(provider = provider, size = 24.dp)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        Text(
                            text = meta.sectionName,
                            color = CatalogText,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.ExtraBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        CatalogPill(
                            text = meta.badge,
                            foreground = Color(0xFFCBD5E1),
                            background = Color(0xFF1E293B),
                            border = CatalogBorderLight.copy(alpha = 0.7f),
                        )
                    }
                    Text(
                        text = meta.tagline,
                        color = CatalogMuted,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            CatalogPill(
                text = "$modelCount ${if (modelCount == 1) "modelo" else "modelos"}",
                foreground = CatalogMuted,
                background = Color(0xFF0F172A),
                border = CatalogBorder,
            )
        }
    }
}

@Composable
private fun HtmlCatalogInstallCard(
    model: CatalogModel,
    hardware: CatalogHardwareSnapshot,
    download: DownloadItemUi?,
    installedModel: AiModelEntity?,
    onInstall: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRemove: () -> Unit,
    onDelete: () -> Unit,
) {
    val installed = installedModel != null
    val state = download?.state
    val busy = download?.status == DownloadEntryStatus.ACTIVE
    val queued = download?.status == DownloadEntryStatus.QUEUED
    val paused = download?.status == DownloadEntryStatus.PAUSED
    val failed = download?.status == DownloadEntryStatus.FAILED
    val progressFraction = (state?.progress ?: 0f).coerceIn(0f, 1f)
    val compatibility = catalogCompatibility(model, hardware)
    val providerMeta = providerUiMeta(model.provider)
    var confirmDelete by remember(installedModel?.id) { mutableStateOf(false) }
    var showDetails by remember(model.id) { mutableStateOf(false) }
    val shape = RoundedCornerShape(16.dp)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(CatalogCard, shape)
            .border(1.dp, CatalogBorder.copy(alpha = 0.9f), shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .background(CatalogSurface.copy(alpha = 0.95f), RoundedCornerShape(12.dp))
                    .border(1.dp, CatalogBorderLight.copy(alpha = 0.65f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                ProviderLogo(provider = model.provider, size = 30.dp)
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = model.displayName,
                    color = CatalogText,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    modifier = Modifier.padding(top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Text(
                        text = providerMeta.sectionName,
                        color = providerMeta.accent,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                    )
                    Text("•", color = CatalogMutedDark, fontSize = 11.sp)
                    Text(
                        text = "${model.quantization} GGUF",
                        color = CatalogMuted,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                    )
                }
            }

            CatalogPill(
                text = compatibility.label,
                foreground = compatibility.accent,
                background = compatibility.accent.copy(alpha = 0.10f),
                border = compatibility.accent.copy(alpha = 0.32f),
                leadingDot = true,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CatalogTag(model.quantization)
            CatalogTag("GGUF")
            if (model.activeParametersBillions != null) {
                CatalogTag("MoE")
            }
            if (installed) {
                CatalogTag("Instalada", accent = CatalogEmerald)
            }
        }

        ModelSpecsGrid(
            model = model,
            requirementAccent = compatibility.accent,
        )

        Text(
            text = model.description,
            color = CatalogMuted,
            fontSize = 11.sp,
            lineHeight = 16.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        if (state != null && !installed) {
            DownloadStatePanel(
                state = state,
                progressFraction = progressFraction,
                failed = failed,
            )
        }

        HorizontalDivider(color = CatalogBorder.copy(alpha = 0.8f))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CatalogActionButton(
                text = "ⓘ  Detalhes",
                modifier = Modifier.width(104.dp),
                background = SolidColor(Color(0xE61E293B)),
                foreground = Color(0xFFCBD5E1),
                border = CatalogBorderLight.copy(alpha = 0.55f),
                onClick = { showDetails = true },
            )

            when {
                installed -> {
                    CatalogActionButton(
                        text = "Desinstalar",
                        modifier = Modifier.weight(1f),
                        background = SolidColor(CatalogRose.copy(alpha = 0.12f)),
                        foreground = Color(0xFFFDA4AF),
                        border = CatalogRose.copy(alpha = 0.4f),
                        onClick = { confirmDelete = true },
                    )
                }

                busy -> {
                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        CatalogActionButton(
                            text = "Encerrar",
                            modifier = Modifier.weight(1f),
                            background = SolidColor(Color(0xE61E293B)),
                            foreground = CatalogMuted,
                            border = CatalogBorderLight.copy(alpha = 0.55f),
                            onClick = onRemove,
                        )
                        CatalogActionButton(
                            text = if (state?.phase == ModelDownloadPhase.DOWNLOADING) "Pausar" else "Instalando",
                            modifier = Modifier.weight(1.25f),
                            background = SolidColor(CatalogCyan.copy(alpha = 0.18f)),
                            foreground = CatalogCyanLight,
                            border = CatalogCyan.copy(alpha = 0.42f),
                            enabled = state?.phase == ModelDownloadPhase.DOWNLOADING,
                            onClick = onPause,
                        )
                    }
                }

                queued -> {
                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        CatalogActionButton(
                            text = "Remover",
                            modifier = Modifier.weight(1f),
                            background = SolidColor(Color(0xE61E293B)),
                            foreground = CatalogMuted,
                            border = CatalogBorderLight.copy(alpha = 0.55f),
                            onClick = onRemove,
                        )
                        CatalogActionButton(
                            text = "Na fila",
                            modifier = Modifier.weight(1.15f),
                            background = SolidColor(CatalogCyan.copy(alpha = 0.12f)),
                            foreground = CatalogCyanLight,
                            border = CatalogCyan.copy(alpha = 0.32f),
                            enabled = false,
                            onClick = {},
                        )
                    }
                }

                paused || failed -> {
                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        CatalogActionButton(
                            text = "Encerrar",
                            modifier = Modifier.weight(1f),
                            background = SolidColor(Color(0xE61E293B)),
                            foreground = CatalogMuted,
                            border = CatalogBorderLight.copy(alpha = 0.55f),
                            onClick = onRemove,
                        )
                        CatalogActionButton(
                            text = if (failed) "Tentar novamente" else "Continuar",
                            modifier = Modifier.weight(1.15f),
                            background = Brush.horizontalGradient(listOf(CatalogCyan, Color(0xFF3B82F6))),
                            foreground = Color(0xFF041014),
                            border = CatalogCyan.copy(alpha = 0.45f),
                            onClick = onResume,
                        )
                    }
                }

                else -> {
                    CatalogActionButton(
                        text = if (compatibility.canRunComfortably) "↓  Instalar I.A" else "↓  Instalar (Alta Exigência)",
                        modifier = Modifier.weight(1f),
                        background = if (compatibility.canRunComfortably) {
                            Brush.horizontalGradient(listOf(CatalogCyan, Color(0xFF3B82F6)))
                        } else {
                            SolidColor(Color(0xE61E293B))
                        },
                        foreground = if (compatibility.canRunComfortably) Color(0xFF041014) else CatalogAmber,
                        border = if (compatibility.canRunComfortably) CatalogCyan.copy(alpha = 0.45f) else CatalogAmber.copy(alpha = 0.35f),
                        onClick = onInstall,
                    )
                }
            }
        }
    }

    if (showDetails) {
        ModelDetailsDialog(
            model = model,
            compatibility = compatibility,
            onDismiss = { showDetails = false },
        )
    }

    if (confirmDelete && installedModel != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Desinstalar I.A?") },
            text = {
                Text(
                    "Tem certeza que quer desinstalar essa I.A? O arquivo de ${installedModel.name} será excluído do armazenamento interno do Android.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        onDelete()
                    },
                ) {
                    Text("Sim, desinstalar")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text("Cancelar")
                }
            },
        )
    }
}

@Composable
private fun ModelSpecsGrid(
    model: CatalogModel,
    requirementAccent: Color,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(CatalogCardDark.copy(alpha = 0.78f), RoundedCornerShape(12.dp))
            .border(1.dp, CatalogBorder.copy(alpha = 0.9f), RoundedCornerShape(12.dp))
            .padding(vertical = 10.dp, horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SpecCell(
            label = "PARÂMETROS",
            value = groupedFormatParameters(model.totalParametersBillions),
            valueColor = Color(0xFFE2E8F0),
            modifier = Modifier.weight(1f),
        )
        Box(
            Modifier
                .width(1.dp)
                .height(34.dp)
                .background(CatalogBorder),
        )
        SpecCell(
            label = "TAMANHO",
            value = groupedFormatBytes(model.approximateSizeBytes),
            valueColor = CatalogCyanLight,
            modifier = Modifier.weight(1f),
        )
        Box(
            Modifier
                .width(1.dp)
                .height(34.dp)
                .background(CatalogBorder),
        )
        SpecCell(
            label = "REQUISITOS MÍN.",
            value = "${groupedFormatBytes(model.recommendedRamBytes)} RAM",
            valueColor = requirementAccent,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun SpecCell(
    label: String,
    value: String,
    valueColor: Color,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = label,
            color = CatalogMutedDark,
            fontSize = 8.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.3.sp,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
        Text(
            text = value,
            color = valueColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
private fun DownloadStatePanel(
    state: ModelDownloadState,
    progressFraction: Float,
    failed: Boolean,
) {
    val accent = if (failed) CatalogRose else CatalogCyan
    val progressText = "${(progressFraction * 100).toInt()}%"

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(CatalogCardDark, RoundedCornerShape(12.dp))
            .border(1.dp, accent.copy(alpha = 0.32f), RoundedCornerShape(12.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = groupedDownloadStatus(state, progressText),
                color = accent,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (state.phase == ModelDownloadPhase.DOWNLOADING || progressFraction > 0f) {
                Text(
                    text = progressText,
                    color = CatalogCyanLight,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        if (state.phase == ModelDownloadPhase.DOWNLOADING || progressFraction > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .background(Color(0xFF1E293B), RoundedCornerShape(50)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progressFraction)
                        .height(8.dp)
                        .background(
                            Brush.horizontalGradient(
                                listOf(CatalogCyan, Color(0xFF3B82F6), Color(0xFF8B5CF6)),
                            ),
                            RoundedCornerShape(50),
                        ),
                )
            }
        }
    }
}

@Composable
private fun ModelDetailsDialog(
    model: CatalogModel,
    compatibility: CatalogCompatibility,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(model.displayName) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(model.description)
                Text("Fornecedor: ${providerUiMeta(model.provider).sectionName}")
                Text("Quantização: ${model.quantization} GGUF")
                Text("Parâmetros: ${groupedFormatParameters(model.totalParametersBillions)}")
                Text("Tamanho: ${groupedFormatBytes(model.approximateSizeBytes)}")
                Text("Requisitos: ${groupedFormatBytes(model.recommendedRamBytes)} RAM")
                Text(
                    text = compatibility.label,
                    color = compatibility.accent,
                    fontWeight = FontWeight.Bold,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Fechar") }
        },
    )
}

@Composable
private fun CatalogPill(
    text: String,
    foreground: Color,
    background: Color,
    border: Color,
    leadingDot: Boolean = false,
) {
    Row(
        modifier = Modifier
            .background(background, RoundedCornerShape(7.dp))
            .border(1.dp, border, RoundedCornerShape(7.dp))
            .padding(horizontal = 7.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (leadingDot) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(foreground, RoundedCornerShape(50)),
            )
        }
        Text(
            text = text,
            color = foreground,
            fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

@Composable
private fun CatalogTag(
    text: String,
    accent: Color = Color(0xFFCBD5E1),
) {
    Box(
        modifier = Modifier
            .background(Color(0xCC0F172A), RoundedCornerShape(6.dp))
            .border(1.dp, accent.copy(alpha = 0.22f), RoundedCornerShape(6.dp))
            .padding(horizontal = 7.dp, vertical = 3.dp),
    ) {
        Text(
            text = text,
            color = accent,
            fontSize = 9.sp,
            maxLines = 1,
        )
    }
}

@Composable
private fun CatalogActionButton(
    text: String,
    modifier: Modifier = Modifier,
    background: Brush,
    foreground: Color,
    border: Color,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = modifier
            .height(40.dp)
            .background(background, shape)
            .border(1.dp, border, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = if (enabled) foreground else foreground.copy(alpha = 0.45f),
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun readCatalogHardwareSnapshot(context: Context): CatalogHardwareSnapshot {
    val activityManager = context.getSystemService(ActivityManager::class.java)
    val memory = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
    return CatalogHardwareSnapshot(
        totalRamBytes = memory.totalMem,
        availableRamBytes = memory.availMem,
    )
}

private fun catalogCompatibility(
    model: CatalogModel,
    hardware: CatalogHardwareSnapshot,
): CatalogCompatibility {
    return when {
        hardware.totalRamBytes < model.recommendedRamBytes -> CatalogCompatibility(
            label = "Requer ${groupedFormatBytes(model.recommendedRamBytes)} RAM",
            accent = CatalogRose,
            canRunComfortably = false,
        )

        hardware.availableRamBytes < model.recommendedRamBytes -> CatalogCompatibility(
            label = "Atenção: RAM Justa",
            accent = CatalogAmber,
            canRunComfortably = true,
        )

        else -> CatalogCompatibility(
            label = "100% Compatível",
            accent = CatalogEmerald,
            canRunComfortably = true,
        )
    }
}

private fun providerUiMeta(provider: ModelProvider): ProviderUiMeta = when (provider) {
    ModelProvider.DEEPSEEK -> ProviderUiMeta(
        selectorName = "DeepSeek",
        selectorSubtitle = "Raciocínio R1",
        sectionName = "DeepSeek",
        badge = "Open Weights",
        tagline = "Modelos de raciocínio de alta eficiência e baixo custo",
        accent = Color(0xFF22D3EE),
    )

    ModelProvider.ALIBABA -> ProviderUiMeta(
        selectorName = "Qwen",
        selectorSubtitle = "Alibaba Cloud",
        sectionName = "Alibaba Cloud (Qwen)",
        badge = "Qwen",
        tagline = "Geração de código, matemática e contexto longo",
        accent = Color(0xFFFBBF24),
    )

    ModelProvider.MISTRAL -> ProviderUiMeta(
        selectorName = "Mistral",
        selectorSubtitle = "Mistral AI",
        sectionName = "Mistral AI",
        badge = "Mistral",
        tagline = "Instruções e linguagem em múltiplos idiomas",
        accent = Color(0xFFFB923C),
    )

    ModelProvider.META -> ProviderUiMeta(
        selectorName = "Llama",
        selectorSubtitle = "Meta AI",
        sectionName = "Meta AI",
        badge = "Llama",
        tagline = "Família Llama otimizada para execução local",
        accent = Color(0xFF818CF8),
    )

    ModelProvider.GOOGLE -> ProviderUiMeta(
        selectorName = "Gemma",
        selectorSubtitle = "Google DeepMind",
        sectionName = "Google (Gemma)",
        badge = "Gemma",
        tagline = "Modelos compactos da família Gemma",
        accent = Color(0xFFFB7185),
    )

    ModelProvider.MICROSOFT -> ProviderUiMeta(
        selectorName = "Phi",
        selectorSubtitle = "Microsoft",
        sectionName = "Microsoft (Phi)",
        badge = "Phi",
        tagline = "Modelos compactos de alta densidade lógica",
        accent = Color(0xFF34D399),
    )

    ModelProvider.IBM -> ProviderUiMeta(
        selectorName = "Granite",
        selectorSubtitle = "IBM",
        sectionName = "IBM / Granite",
        badge = "Granite",
        tagline = "Modelos abertos da família Granite",
        accent = Color(0xFF60A5FA),
    )

    ModelProvider.LIQUID -> ProviderUiMeta(
        selectorName = "Liquid",
        selectorSubtitle = "Liquid AI",
        sectionName = "Liquid AI",
        badge = "LFM",
        tagline = "Modelos compactos para execução eficiente",
        accent = Color(0xFFA78BFA),
    )

    ModelProvider.AI2 -> ProviderUiMeta(
        selectorName = "OLMo",
        selectorSubtitle = "Ai2",
        sectionName = "Ai2 / OLMo",
        badge = "OLMo",
        tagline = "Modelos abertos de pesquisa da Ai2",
        accent = Color(0xFF2DD4BF),
    )

    ModelProvider.NVIDIA -> ProviderUiMeta(
        selectorName = "Nemotron",
        selectorSubtitle = "NVIDIA",
        sectionName = "NVIDIA",
        badge = "Nemotron",
        tagline = "Modelos NVIDIA para raciocínio e agentes",
        accent = Color(0xFFA3E635),
    )
}

private fun groupedDownloadStatus(state: ModelDownloadState, progress: String?): String = when (state.phase) {
    ModelDownloadPhase.IDLE -> state.message ?: "Pronto para instalar"
    ModelDownloadPhase.CHECKING -> "Preparando instalação"
    ModelDownloadPhase.DOWNLOADING -> "Baixando${progress?.let { " · $it" } ?: ""}"
    ModelDownloadPhase.VERIFYING_FILE -> "Verificando arquivo"
    ModelDownloadPhase.IMPORTING -> "Registrando I.A"
    ModelDownloadPhase.VERIFYING_MODEL -> "Testando I.A no aparelho"
    ModelDownloadPhase.COMPLETE -> state.message ?: "Instalação concluída"
    ModelDownloadPhase.ERROR -> state.message ?: "Falha na instalação"
    ModelDownloadPhase.CANCELLED -> "Instalação pausada${progress?.let { " · $it" } ?: ""}"
}

private fun groupedFormatParameters(value: Double): String =
    if (value % 1.0 == 0.0) {
        "${value.toInt()}B"
    } else {
        "${"%.2f".format(value).trimEnd('0').trimEnd('.')}B"
    }

private fun groupedFormatBytes(bytes: Long): String {
    val gb = bytes / (1024.0 * 1024.0 * 1024.0)
    return if (gb >= 1.0) {
        "%.1f GB".format(gb)
    } else {
        "%.0f MB".format(bytes / (1024.0 * 1024.0))
    }
}

private val CATALOG_PROVIDER_ORDER = listOf(
    ModelProvider.DEEPSEEK,
    ModelProvider.ALIBABA,
    ModelProvider.MISTRAL,
    ModelProvider.META,
    ModelProvider.GOOGLE,
    ModelProvider.MICROSOFT,
    ModelProvider.IBM,
    ModelProvider.LIQUID,
    ModelProvider.AI2,
    ModelProvider.NVIDIA,
)
