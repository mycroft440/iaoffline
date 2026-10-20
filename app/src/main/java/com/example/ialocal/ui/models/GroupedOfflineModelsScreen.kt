package com.example.ialocal.ui.models

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ialocal.ads.CatalogAdSlots
import com.example.ialocal.ads.InlineAdBanner
import com.example.ialocal.ads.NativeAdCard
import com.example.ialocal.ads.rememberInlineAdBannerState
import com.example.ialocal.ads.rememberNativeAdSlots
import com.example.ialocal.data.AiModelEntity
import com.example.ialocal.models.CatalogModel
import com.example.ialocal.models.DownloadEntryStatus
import com.example.ialocal.models.ModelDownloadPhase
import com.example.ialocal.models.ModelDownloadState
import com.example.ialocal.models.ModelProvider
import com.example.ialocal.runtime.DeviceMemory
import com.example.ialocal.ui.branding.ProviderLogo
import com.example.ialocal.ui.formatAdvertisedGb
import kotlinx.coroutines.launch

private data class CatalogHardwareSnapshot(
    /** The phone's RAM as sold ("12 GB"), the unit the catalog requirements are written in. */
    val advertisedRamGb: Double,
    val availableRamBytes: Long,
) {
    /** [advertisedRamGb] in bytes, as the catalog's requirements (12_000_000_000 is "12 GB"). */
    val deviceRamBytes: Long get() = (advertisedRamGb * 1_000_000_000L).toLong()
}

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
    onRequestRewardedDownload: (String, () -> Unit) -> Unit,
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
    val nativeAds = rememberNativeAdSlots(enabled = adsEnabled)
    // Kept above the list: a banner scrolled out of view and back keeps its ad.
    val topBanner = rememberInlineAdBannerState(enabled = adsEnabled)
    val bottomBanner = rememberInlineAdBannerState(enabled = adsEnabled)

    val catalog = viewModel.catalog
    val deviceRamBytes = hardware.deviceRamBytes
    val visibleGroups = remember(catalog, selectedProvider, deviceRamBytes) {
        catalogGroups(catalog, onlyProvider = selectedProvider, deviceRamBytes = deviceRamBytes)
    }
    val visibleCount = remember(visibleGroups) { visibleGroups.sumOf { it.models.size } }
    val sectionCounts = remember(visibleGroups) {
        visibleGroups.groupBy { it.section }.mapValues { (_, groups) -> groups.sumOf { it.models.size } }
    }
    val providerCounts = remember(catalog) { catalog.groupingBy { it.provider }.eachCount() }
    // Looked up once per change instead of in every card: during a download the list updates
    // several times a second.
    val installedByCatalogId = remember(installedModels, catalog) {
        catalog.mapNotNull { entry ->
            installedModels.firstOrNull { it.apiModelId.startsWith(entry.apiIdPrefix) }?.let { entry.id to it }
        }.toMap()
    }
    val downloadsByCatalogId = remember(downloads) { downloads.associateBy { it.model.id } }
    val recommendation = remember(catalog, hardware, installedByCatalogId) {
        recommendedModels(
            catalog = catalog,
            freeRamBytes = hardware.availableRamBytes,
            deviceRamBytes = deviceRamBytes,
            installedIds = installedByCatalogId.keys,
        )
    }
    // The banner, then the recommendations with their heading.
    val selectorIndex = 1 + if (recommendation.models.isEmpty()) 0 else 1 + recommendation.models.size
    val selectorOutOfView by remember(selectorIndex) {
        derivedStateOf { listState.firstVisibleItemIndex > selectorIndex }
    }
    // One card per model, in the recommendations and again in its company's section.
    val catalogCard: @Composable (CatalogModel) -> Unit = { catalogModel ->
        val installed = installedByCatalogId[catalogModel.id]
        HtmlCatalogInstallCard(
            model = catalogModel,
            hardware = hardware,
            download = downloadsByCatalogId[catalogModel.id],
            installedModel = installed,
            onInstall = {
                onEnsureStorageAccess {
                    onRequestRewardedDownload(catalogModel.displayName) {
                        viewModel.downloadCatalogModel(catalogModel.id)
                    }
                }
            },
            onPause = viewModel::cancelDownload,
            onResume = {
                onEnsureStorageAccess { viewModel.resumeDownload(catalogModel.id) }
            },
            onRemove = { viewModel.removeDownload(catalogModel.id) },
            onDelete = { installed?.let { viewModel.delete(it.id) } },
        )
    }

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
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = CatalogBackground,
                    titleContentColor = CatalogText,
                    navigationIconContentColor = CatalogText,
                ),
            )
        },
        floatingActionButton = {
            // Back to the companies from anywhere in the list, to filter by another one.
            AnimatedVisibility(
                visible = selectorOutOfView,
                enter = fadeIn() + scaleIn(initialScale = 0.8f),
                exit = fadeOut() + scaleOut(targetScale = 0.8f),
            ) {
                BackToCompaniesButton(
                    onClick = { scope.launch { listState.animateScrollToItem(selectorIndex) } },
                )
            }
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
            // Every item says what it is, so the list reuses a card that scrolled away for the next
            // card instead of building it again.
            item(key = "catalog-ad-top", contentType = CatalogItem.BANNER) {
                InlineAdBanner(state = topBanner)
            }

            if (recommendation.models.isNotEmpty()) {
                item(key = "section-recommended", contentType = CatalogItem.SECTION_HEADER) {
                    RecommendedSectionHeader(
                        freeRamBytes = hardware.availableRamBytes,
                        fitFreeRam = recommendation.fitFreeRam,
                    )
                }
                items(
                    items = recommendation.models,
                    key = { "recommended-${it.id}" },
                    contentType = { CatalogItem.MODEL_CARD },
                ) { catalogCard(it) }
            }

            item(key = "company-selector", contentType = CatalogItem.SELECTOR) {
                CompanySelectorSection(
                    catalog = catalog,
                    providerCounts = providerCounts,
                    selectedProvider = selectedProvider,
                    visibleCount = visibleCount,
                    onSelect = { provider ->
                        selectedProvider = if (provider != null && selectedProvider == provider) null else provider
                    },
                )
            }

            visibleGroups.forEachIndexed { groupIndex, group ->
                val provider = group.provider
                // Each section opens with its heading, before its first company.
                if (groupIndex == 0 || visibleGroups[groupIndex - 1].section != group.section) {
                    item(key = "section-${group.section.name}", contentType = CatalogItem.SECTION_HEADER) {
                        when (group.section) {
                            CatalogSection.MOE -> MoeSectionHeader(modelCount = sectionCounts[group.section] ?: 0)
                            CatalogSection.DENSE -> DenseSectionHeader()
                            CatalogSection.MOE_NEEDS_MORE_RAM -> MoeNeedsMoreRamHeader(hardware.advertisedRamGb)
                        }
                    }
                }

                item(key = "provider-${group.section.name}-${provider.name}", contentType = CatalogItem.PROVIDER_HEADER) {
                    ProviderCatalogHeader(
                        provider = provider,
                        modelCount = group.models.size,
                        moe = group.moe,
                    )
                }

                items(
                    items = group.models,
                    key = { it.id },
                    contentType = { CatalogItem.MODEL_CARD },
                ) { catalogCard(it) }

                if (nativeAds != null && CatalogAdSlots.showsAfterGroup(groupIndex, visibleGroups.size)) {
                    item(key = "catalog-native-ad-$groupIndex", contentType = CatalogItem.NATIVE_AD) {
                        NativeAdCard(slots = nativeAds, slot = groupIndex)
                    }
                }
            }

            item(key = "catalog-ad-bottom", contentType = CatalogItem.BANNER) {
                InlineAdBanner(state = bottomBanner)
            }

            // Room for the button back to the companies over the last card.
            item(key = "catalog-end", contentType = CatalogItem.SPACER) { Spacer(Modifier.height(72.dp)) }
        }
    }
}

/** The kinds of items of the catalog list. */
private enum class CatalogItem { BANNER, SECTION_HEADER, SELECTOR, PROVIDER_HEADER, MODEL_CARD, NATIVE_AD, SPACER }

@Composable
private fun BackToCompaniesButton(onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier = Modifier
            .shadow(8.dp, shape)
            .background(Color(0xF21E293B), shape)
            .border(1.dp, CatalogCyan.copy(alpha = 0.45f), shape)
            .clip(shape)
            .clickable(onClickLabel = "Voltar às empresas", role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "↑  Empresas",
            color = CatalogCyanLight,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun CompanySelectorSection(
    catalog: List<CatalogModel>,
    providerCounts: Map<ModelProvider, Int>,
    selectedProvider: ModelProvider?,
    visibleCount: Int,
    onSelect: (ModelProvider?) -> Unit,
) {
    // Every company of the catalog, in its order; before, only the first seven could be chosen.
    val cells: List<ModelProvider?> = remember(providerCounts) {
        listOf(null) + CATALOG_PROVIDER_ORDER.filter { (providerCounts[it] ?: 0) > 0 }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column {
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
                text = selectedProvider?.let { "Exibindo só os modelos de ${providerUiMeta(it).sectionName}" }
                    ?: "Exibindo o catálogo completo: deslize para escolher uma empresa",
                color = CatalogMuted,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }

        // From edge to edge of the screen, past the list's side margins, so the chips are not cut
        // 16 dp before the border while they scroll.
        LazyRow(
            modifier = Modifier.layout { measurable, constraints ->
                val margin = if (constraints.hasBoundedWidth) SELECTOR_BLEED.roundToPx() else 0
                val placeable = measurable.measure(
                    constraints.copy(
                        minWidth = constraints.minWidth + 2 * margin,
                        maxWidth = constraints.maxWidth + 2 * margin,
                    ),
                )
                layout(constraints.maxWidth, placeable.height) { placeable.place(-margin, 0) }
            },
            contentPadding = PaddingValues(horizontal = SELECTOR_BLEED),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(items = cells, key = { it?.name ?: "all" }) { provider ->
                CompanyFilterChip(
                    provider = provider,
                    count = if (provider == null) catalog.size else providerCounts[provider] ?: 0,
                    selected = selectedProvider == provider,
                    onClick = { onSelect(provider) },
                )
            }
        }
    }
}

/** The list's side margin, which the company chips scroll past. */
private val SELECTOR_BLEED = 16.dp

@Composable
private fun CompanyFilterChip(
    provider: ModelProvider?,
    count: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val meta = provider?.let(::providerUiMeta)
    val accent = if (selected) CatalogCyan else meta?.accent ?: CatalogCyan
    val shape = RoundedCornerShape(12.dp)
    val background = if (selected) Color(0x2622D3EE) else Color(0xE60F172A)
    val border = if (selected) CatalogCyan.copy(alpha = 0.55f) else CatalogBorder

    Row(
        modifier = Modifier
            .background(background, shape)
            .border(1.dp, border, shape)
            .clip(shape)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (provider != null) {
            ProviderLogo(provider = provider, size = 24.dp)
        } else {
            Text(text = "🌐", fontSize = 17.sp)
        }
        Column {
            Text(
                text = meta?.selectorName ?: "Todas as IAs",
                color = if (selected) CatalogCyanLight else CatalogText,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
            Text(
                text = meta?.selectorSubtitle ?: "Catálogo Geral",
                color = if (selected) CatalogCyanLight.copy(alpha = 0.8f) else CatalogMuted,
                fontSize = 9.sp,
                maxLines = 1,
            )
        }
        CatalogPill(
            text = count.toString(),
            foreground = accent,
            background = accent.copy(alpha = 0.12f),
            border = accent.copy(alpha = 0.35f),
        )
    }
}

/** Opens the recommendations: the models that fit the RAM free right now. */
@Composable
private fun RecommendedSectionHeader(freeRamBytes: Long, fitFreeRam: Boolean) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(CatalogEmerald.copy(alpha = 0.07f), shape)
            .border(1.dp, CatalogEmerald.copy(alpha = 0.40f), shape)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Recomendadas para o seu telefone",
                color = CatalogText,
                fontSize = 17.sp,
                fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.weight(1f),
            )
            CatalogPill(
                text = "${groupedFormatBytes(freeRamBytes)} livres",
                foreground = CatalogEmerald,
                background = CatalogEmerald.copy(alpha = 0.12f),
                border = CatalogEmerald.copy(alpha = 0.4f),
            )
        }
        Text(
            text = if (fitFreeRam) {
                "As mais capazes que cabem na RAM livre agora."
            } else {
                "Pouca RAM livre agora: feche apps em segundo plano. Estas são as mais leves."
            },
            color = if (fitFreeRam) CatalogMuted else CatalogAmber,
            fontSize = 11.sp,
            lineHeight = 15.sp,
        )
    }
}

/** Opens the MoE section with one line on what a mixture-of-experts model is. */
@Composable
private fun MoeSectionHeader(modelCount: Int) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(CatalogViolet.copy(alpha = 0.08f), shape)
            .border(1.dp, CatalogViolet.copy(alpha = 0.45f), shape)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Modelos MoE que podem rodar no seu telefone",
                color = CatalogText,
                fontSize = 17.sp,
                fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.weight(1f),
            )
            CatalogPill(
                text = "$modelCount ${if (modelCount == 1) "modelo" else "modelos"}",
                foreground = CatalogViolet,
                background = CatalogViolet.copy(alpha = 0.12f),
                border = CatalogViolet.copy(alpha = 0.4f),
            )
        }
        Text(
            text = "Só alguns especialistas calculam cada palavra, mas os pesos de todos podem ocupar RAM. " +
                "Confira a estimativa abaixo; usar o armazenamento economiza RAM e pode reduzir a velocidade.",
            color = Color(0xFFCBD5E1),
            fontSize = 11.sp,
            lineHeight = 15.sp,
        )
    }
}

/** Opens the MoE models that need more RAM than the phone has, after the dense ones. */
@Composable
private fun MoeNeedsMoreRamHeader(deviceRamGb: Double) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Text(
            text = "Outros modelos MoE",
            color = CatalogText,
            fontSize = 18.sp,
            fontWeight = FontWeight.ExtraBold,
        )
        Text(
            text = "A RAM recomendada supera os ${formatAdvertisedGb(deviceRamGb)} deste telefone. " +
                "Alguns podem funcionar lentamente com especialistas lidos do armazenamento.",
            color = CatalogMuted,
            fontSize = 11.sp,
            lineHeight = 16.sp,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/** Separates the dense models, listed by company after the MoE section. */
@Composable
private fun DenseSectionHeader() {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Text(
            text = "Modelos densos",
            color = CatalogText,
            fontSize = 18.sp,
            fontWeight = FontWeight.ExtraBold,
        )
        Text(
            text = "Usam todos os parâmetros a cada palavra: quanto maior o modelo, mais devagar ele escreve.",
            color = CatalogMuted,
            fontSize = 11.sp,
            lineHeight = 16.sp,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
private fun ProviderCatalogHeader(
    provider: ModelProvider,
    modelCount: Int,
    moe: Boolean,
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
                        if (moe) {
                            CatalogPill(
                                text = "MoE",
                                foreground = CatalogViolet,
                                background = CatalogViolet.copy(alpha = 0.12f),
                                border = CatalogViolet.copy(alpha = 0.4f),
                            )
                        } else {
                            CatalogPill(
                                text = meta.badge,
                                foreground = Color(0xFFCBD5E1),
                                background = Color(0xFF1E293B),
                                border = CatalogBorderLight.copy(alpha = 0.7f),
                            )
                        }
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
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
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

        // Wrapped: five tags do not fit in one line on a narrow phone or with a large font.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            CatalogTag(model.quantization)
            CatalogTag("GGUF")
            if (model.isMoe) {
                CatalogTag("MoE", accent = CatalogViolet)
            }
            if (model.isExperimental) {
                CatalogTag("MoE grande", accent = CatalogAmber)
            }
            if (installed) {
                CatalogTag("Instalada", accent = CatalogEmerald)
            }
        }

        ModelSpecsGrid(
            model = model,
            requirementAccent = compatibility.accent,
        )

        if (model.isExperimental) {
            Text(
                text = "MoE grande: se os pesos não couberem na RAM livre, o runtime pode ler " +
                    "especialistas pelo armazenamento (mmap). Isso pode ser muito lento ou falhar, " +
                    "dependendo do GGUF, do aparelho e do contexto.",
                color = CatalogAmber,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
        }

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
                        text = if (compatibility.canRunComfortably) "▶  Anúncio e instalar" else "▶  Anúncio e instalar (RAM alta)",
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
            label = if (model.isMoe) "RAM (EST.)" else if (model.isExperimental) "RAM IDEAL" else "REQUISITOS MÍN.",
            value = if (model.isMoe) {
                "~${formatMoeRamEstimate(estimatedFullMoeRamBytes(model))}"
            } else {
                "${formatRamRequirement(model.recommendedRamBytes)} RAM"
            },
            valueColor = requirementAccent,
            modifier = Modifier.weight(1f),
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
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(model.description)
                model.strengths?.let { strengths ->
                    Text("Pontos fortes", fontWeight = FontWeight.Bold)
                    Text(strengths)
                }
                Text("Fornecedor: ${providerUiMeta(model.provider).sectionName}")
                Text("Quantização: ${model.quantization} GGUF")
                Text("Parâmetros: ${groupedFormatParameters(model.totalParametersBillions)}")
                Text(
                    model.activeParametersBillions?.let {
                        "Tipo: MoE, ${groupedFormatParameters(it)} ativos por palavra"
                    } ?: "Tipo: denso, todos os parâmetros usados em cada palavra",
                )
                Text("Tamanho: ${groupedFormatBytes(model.approximateSizeBytes)}")
                if (model.isMoe) {
                    Text(
                        "RAM estimada com os pesos residentes: ~${formatMoeRamEstimate(estimatedFullMoeRamBytes(model))} " +
                            "(arquivo GGUF + 256 MiB de contexto mínimo + 512 MiB de buffers). " +
                            "É uma referência, não consumo medido nem pico garantido."
                    )
                    Text(
                        "RAM recomendada do aparelho: ${formatRamRequirement(model.recommendedRamBytes)}. " +
                            "Os parâmetros ativos não reduzem automaticamente a RAM dos pesos; com mmap, " +
                            "parte dos especialistas pode ficar no armazenamento, consumindo menos RAM " +
                            "e levando mais tempo. Contextos maiores ou repacking podem elevar o consumo."
                    )
                } else {
                    Text("Requisitos: ${formatRamRequirement(model.recommendedRamBytes)} RAM")
                }
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

private fun readCatalogHardwareSnapshot(context: Context): CatalogHardwareSnapshot {
    val memory = DeviceMemory.read(context)
    return CatalogHardwareSnapshot(
        advertisedRamGb = memory.advertisedGigabytes,
        availableRamBytes = memory.availableBytes,
    )
}

private fun catalogCompatibility(
    model: CatalogModel,
    hardware: CatalogHardwareSnapshot,
): CatalogCompatibility {
    // Phone RAM is a recommendation; the estimate below is the GGUF plus the smallest KV cache
    // and compute buffers. MoE weights may remain file-backed with mmap, but no catalog estimate
    // proves that the model will load or predicts its measured resident memory.
    val deviceRamBytes = hardware.deviceRamBytes
    return when {
        model.isMoe && deviceRamBytes < model.recommendedRamBytes -> CatalogCompatibility(
            label = "RAM abaixo da ideal",
            accent = CatalogAmber,
            canRunComfortably = false,
        )

        model.isMoe && hardware.availableRamBytes < estimatedFullMoeRamBytes(model) -> CatalogCompatibility(
            label = "Pode usar armazenamento",
            accent = CatalogAmber,
            canRunComfortably = false,
        )

        model.isMoe -> CatalogCompatibility(
            label = "RAM estimada suficiente",
            accent = CatalogEmerald,
            canRunComfortably = true,
        )

        deviceRamBytes < model.recommendedRamBytes -> CatalogCompatibility(
            label = "Requer ${formatRamRequirement(model.recommendedRamBytes)} RAM",
            accent = CatalogRose,
            canRunComfortably = false,
        )

        hardware.availableRamBytes < model.approximateSizeBytes + CATALOG_WORKING_MEMORY_BYTES -> CatalogCompatibility(
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

/** Decimal GB for MoE RAM estimates, consistent with phone RAM capacities. */
private fun formatMoeRamEstimate(bytes: Long): String = "%.1f GB".format(bytes / 1_000_000_000.0)

/** Catalog requirements are written in the gigabytes phones are sold with: 12_000_000_000 is "12 GB". */
private fun formatRamRequirement(bytes: Long): String = formatAdvertisedGb(bytes / 1_000_000_000.0)

internal fun groupedFormatBytes(bytes: Long): String {
    val gb = bytes / (1024.0 * 1024.0 * 1024.0)
    return if (gb >= 1.0) {
        "%.1f GB".format(gb)
    } else {
        "%.0f MB".format(bytes / (1024.0 * 1024.0))
    }
}
