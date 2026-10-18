package com.example.ialocal.ui.models

import android.content.Context
import android.os.BatteryManager
import android.os.StatFs
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Api
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ialocal.BuildConfig
import com.example.ialocal.ads.InlineAdBanner
import com.example.ialocal.api.ApiServerStatus
import com.example.ialocal.data.AiModelEntity
import com.example.ialocal.data.ModelVerificationStatus
import com.example.ialocal.models.provider
import com.example.ialocal.runtime.DeviceMemory
import com.example.ialocal.ui.branding.ProviderLogo
import com.example.ialocal.ui.rememberLiveMemory

/** At most this many installed AIs are listed on the home screen; Minhas I.As has them all. */
private const val HOME_MODEL_LIMIT = 4

private data class HomeHardwareSnapshot(
    val freeStorageBytes: Long,
    val availableRamBytes: Long,
    val totalRamBytes: Long,
    val batteryPercent: Int,
) {
    val usedRamBytes: Long get() = (totalRamBytes - availableRamBytes).coerceAtLeast(0L)
    val ramFraction: Float
        get() = if (totalRamBytes <= 0L) 0f
        else (usedRamBytes.toFloat() / totalRamBytes.toFloat()).coerceIn(0f, 1f)
}

/** The home screen, in the look of the "Baixar I.As offline" catalog. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HtmlAiHomeScreen(
    viewModel: ModelsViewModel,
    adsEnabled: Boolean,
    onStartChat: () -> Unit,
    onOpenChats: () -> Unit,
    onOpenOfflineModels: () -> Unit,
    onOpenApi: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenCodeEditor: () -> Unit,
    onExitApp: () -> Unit,
) {
    val context = LocalContext.current
    val installedModels by viewModel.models.collectAsStateWithLifecycle()
    val server by viewModel.serverState.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val storageAndBattery = remember(context) { readHomeHardwareSnapshot(context) }
    val memory = rememberLiveMemory()
    val hardware = storageAndBattery.copy(availableRamBytes = memory.availableBytes, totalRamBytes = memory.totalBytes)
    var showExitDialog by remember { mutableStateOf(false) }

    LaunchedEffect(error) {
        error?.let {
            snackbar.showSnackbar(it)
            viewModel.clearError()
        }
    }

    BackHandler { showExitDialog = true }

    Scaffold(
        containerColor = CatalogBackground,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("I.A Off-line") },
                actions = {
                    HomeIconButton(Icons.Default.Code, "Canvas de código", CatalogEmerald, onOpenCodeEditor)
                    HomeIconButton(Icons.Default.Settings, "Configurações", CatalogMuted, onOpenSettings)
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = CatalogBackground,
                    titleContentColor = CatalogText,
                ),
            )
        },
    ) { padding ->
        // The banner is pinned to the bottom, outside the content, so it is always visible.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(CatalogBackground),
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                HomeActionsSection(
                    installedCount = installedModels.size,
                    catalogCount = viewModel.catalog.size,
                    apiRunning = server.status == ApiServerStatus.RUNNING,
                    onStartChat = onStartChat,
                    onOpenChats = onOpenChats,
                    onOpenOfflineModels = onOpenOfflineModels,
                    onOpenApi = onOpenApi,
                )
                HomeMyAisSection(
                    models = installedModels,
                    onOpenMyAis = onOpenChats,
                    onOpenOfflineModels = onOpenOfflineModels,
                )
                HomeDeviceSection(hardware)
            }
            InlineAdBanner(
                enabled = adsEnabled,
                anchored = true,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }

    if (showExitDialog) {
        AlertDialog(
            onDismissRequest = { showExitDialog = false },
            containerColor = CatalogCard,
            titleContentColor = CatalogText,
            textContentColor = CatalogMuted,
            title = { Text("Deseja sair do app?") },
            confirmButton = {
                TextButton(onClick = onExitApp) { Text("Sim", color = CatalogCyanLight) }
            },
            dismissButton = {
                TextButton(onClick = { showExitDialog = false }) { Text("Não", color = CatalogMuted) }
            },
        )
    }
}

@Composable
private fun HomeIconButton(icon: ImageVector, description: String, tint: Color, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = Modifier
            .padding(end = 8.dp)
            .size(36.dp)
            .background(Color(0xCC1E293B), shape)
            .border(1.dp, CatalogBorderLight.copy(alpha = 0.75f), shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(18.dp))
    }
}

/** What to do, laid out like the catalog's grid of companies. */
@Composable
private fun HomeActionsSection(
    installedCount: Int,
    catalogCount: Int,
    apiRunning: Boolean,
    onStartChat: () -> Unit,
    onOpenChats: () -> Unit,
    onOpenOfflineModels: () -> Unit,
    onOpenApi: () -> Unit,
) {
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
                        text = "IA NO APARELHO",
                        color = Color(0xFFCBD5E1),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.7.sp,
                    )
                    CatalogPill(
                        text = "100% offline",
                        foreground = CatalogCyanLight,
                        background = Color(0x2622D3EE),
                        border = CatalogCyan.copy(alpha = 0.35f),
                    )
                }
                Text(
                    text = "Execução local, segura e privada: nenhum dado sai do celular",
                    color = CatalogMuted,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }

            Box(
                modifier = Modifier
                    .background(Color(0xCC1E293B), RoundedCornerShape(8.dp))
                    .border(1.dp, CatalogBorderLight.copy(alpha = 0.75f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 9.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "v${BuildConfig.VERSION_NAME}",
                    color = CatalogCyanLight,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HomeActionCard(
                title = "Chat com I.A",
                subtitle = "Nova conversa",
                icon = Icons.Default.SmartToy,
                accent = CatalogCyan,
                badge = "Novo",
                highlighted = true,
                modifier = Modifier.weight(1f),
                onClick = onStartChat,
            )
            HomeActionCard(
                title = "Minhas I.As",
                subtitle = "No aparelho",
                icon = Icons.Default.Memory,
                accent = CatalogEmerald,
                badge = installedCount.toString(),
                modifier = Modifier.weight(1f),
                onClick = onOpenChats,
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HomeActionCard(
                title = "Baixar I.As",
                subtitle = "Catálogo por empresa",
                icon = Icons.Default.Download,
                accent = Color(0xFF60A5FA),
                badge = catalogCount.toString(),
                modifier = Modifier.weight(1f),
                onClick = onOpenOfflineModels,
            )
            HomeActionCard(
                title = "Utilizar API",
                subtitle = "API local do aparelho",
                icon = Icons.Default.Api,
                accent = if (apiRunning) CatalogAmber else CatalogMuted,
                badge = if (apiRunning) "ON" else "OFF",
                modifier = Modifier.weight(1f),
                onClick = onOpenApi,
            )
        }
    }
}

/** A card like the catalog's company filter: icon, title, subtitle and a colored badge. */
@Composable
private fun HomeActionCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    accent: Color,
    badge: String,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    val background = if (highlighted) Color(0x2622D3EE) else Color(0xE60F172A)
    val border = if (highlighted) CatalogCyan.copy(alpha = 0.55f) else CatalogBorder

    Row(
        modifier = modifier
            .background(background, shape)
            .border(1.dp, border, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .background(accent.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
                    .border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(16.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = if (highlighted) CatalogCyanLight else CatalogText,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    color = if (highlighted) CatalogCyanLight.copy(alpha = 0.8f) else CatalogMuted,
                    fontSize = 9.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        CatalogPill(
            text = badge,
            foreground = accent,
            background = accent.copy(alpha = 0.12f),
            border = accent.copy(alpha = 0.35f),
        )
    }
}

/** A section heading like the catalog's company headers. */
@Composable
private fun HomeSectionHeader(
    title: String,
    badge: String,
    tagline: String,
    icon: ImageVector,
    accent: Color,
    trailing: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
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
                        .background(accent.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
                        .border(1.dp, accent.copy(alpha = 0.38f), RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        Text(
                            text = title,
                            color = CatalogText,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.ExtraBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        CatalogPill(
                            text = badge,
                            foreground = Color(0xFFCBD5E1),
                            background = Color(0xFF1E293B),
                            border = CatalogBorderLight.copy(alpha = 0.7f),
                        )
                    }
                    Text(
                        text = tagline,
                        color = CatalogMuted,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            CatalogPill(
                text = trailing,
                foreground = CatalogMuted,
                background = Color(0xFF0F172A),
                border = CatalogBorder,
            )
        }
    }
}

@Composable
private fun HomeMyAisSection(
    models: List<AiModelEntity>,
    onOpenMyAis: () -> Unit,
    onOpenOfflineModels: () -> Unit,
) {
    val visible = remember(models) { models.sortedBy { it.importedAt }.take(HOME_MODEL_LIMIT) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        HomeSectionHeader(
            title = "Minhas I.As",
            badge = "Offline",
            tagline = "Baixadas e prontas para usar sem internet",
            icon = Icons.Default.Memory,
            accent = CatalogEmerald,
            trailing = "${models.size} ${if (models.size == 1) "instalada" else "instaladas"}",
            modifier = Modifier.clickable(onClick = onOpenMyAis),
        )

        if (visible.isEmpty()) {
            val shape = RoundedCornerShape(16.dp)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CatalogCard, shape)
                    .border(1.dp, CatalogBorder.copy(alpha = 0.9f), shape)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Nenhuma IA baixada", color = CatalogText, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text(
                    text = "Baixe uma IA do catálogo para conversar sem internet. Ela aparece aqui assim que for instalada.",
                    color = CatalogMuted,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                )
                CatalogActionButton(
                    text = "↓  Baixar I.As offline",
                    modifier = Modifier.fillMaxWidth(),
                    background = Brush.horizontalGradient(listOf(CatalogCyan, Color(0xFF3B82F6))),
                    foreground = Color(0xFF041014),
                    border = CatalogCyan.copy(alpha = 0.45f),
                    onClick = onOpenOfflineModels,
                )
            }
        } else {
            visible.forEach { model -> HomeModelCard(model, onClick = onOpenMyAis) }
            if (models.size > visible.size) {
                CatalogActionButton(
                    text = "Ver todas as ${models.size} IAs",
                    modifier = Modifier.fillMaxWidth(),
                    background = Brush.horizontalGradient(listOf(Color(0xE61E293B), Color(0xE61E293B))),
                    foreground = Color(0xFFCBD5E1),
                    border = CatalogBorderLight.copy(alpha = 0.55f),
                    onClick = onOpenMyAis,
                )
            }
        }
    }
}

/** An installed AI, like the top of a catalog card. */
@Composable
private fun HomeModelCard(model: AiModelEntity, onClick: () -> Unit) {
    val provider = remember(model.id, model.name) { model.provider() }
    val meta = provider?.let(::providerUiMeta)
    val shape = RoundedCornerShape(16.dp)
    val status = when (model.verificationStatus) {
        ModelVerificationStatus.VERIFIED.name -> "Pronta" to CatalogEmerald
        ModelVerificationStatus.VERIFYING.name -> "Testando" to CatalogAmber
        ModelVerificationStatus.ERROR.name -> "Erro" to CatalogRose
        else -> "Não testada" to CatalogAmber
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(CatalogCard, shape)
            .border(1.dp, CatalogBorder.copy(alpha = 0.9f), shape)
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .background(CatalogSurface.copy(alpha = 0.95f), RoundedCornerShape(12.dp))
                .border(1.dp, CatalogBorderLight.copy(alpha = 0.65f), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (provider != null) {
                ProviderLogo(provider = provider, size = 30.dp)
            } else {
                Icon(Icons.Default.SmartToy, contentDescription = null, tint = CatalogMuted, modifier = Modifier.size(20.dp))
            }
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = model.name,
                color = CatalogText,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                modifier = Modifier.padding(top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Text(
                    text = meta?.sectionName ?: "Importada",
                    color = meta?.accent ?: CatalogMuted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text("•", color = CatalogMutedDark, fontSize = 11.sp)
                Text(
                    text = groupedFormatBytes(model.sizeBytes),
                    color = CatalogMuted,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
            }
        }

        CatalogPill(
            text = status.first,
            foreground = status.second,
            background = status.second.copy(alpha = 0.10f),
            border = status.second.copy(alpha = 0.32f),
            leadingDot = true,
        )
    }
}

/** Free space and memory, in the catalog's grid of specifications. */
@Composable
private fun HomeDeviceSection(snapshot: HomeHardwareSnapshot) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        HomeSectionHeader(
            title = "Este aparelho",
            badge = "Hardware",
            tagline = "Espaço e memória disponíveis para as IAs",
            icon = Icons.Default.PhoneAndroid,
            accent = CatalogCyan,
            trailing = "Bateria ${snapshot.batteryPercent}%",
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(CatalogCardDark.copy(alpha = 0.78f), RoundedCornerShape(12.dp))
                .border(1.dp, CatalogBorder.copy(alpha = 0.9f), RoundedCornerShape(12.dp))
                .padding(vertical = 10.dp, horizontal = 6.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SpecCell(
                    label = "ARMAZENAMENTO",
                    value = "${groupedFormatBytes(snapshot.freeStorageBytes)} livres",
                    valueColor = CatalogCyanLight,
                    modifier = Modifier.weight(1f),
                )
                Box(Modifier.width(1.dp).height(34.dp).background(CatalogBorder))
                SpecCell(
                    label = "RAM EM USO",
                    value = "${formatHomeGb(snapshot.usedRamBytes)} / ${formatHomeGb(snapshot.totalRamBytes)} GB",
                    valueColor = Color(0xFFE2E8F0),
                    modifier = Modifier.weight(1f),
                )
                Box(Modifier.width(1.dp).height(34.dp).background(CatalogBorder))
                SpecCell(
                    label = "RAM LIVRE",
                    value = "${formatHomeGb(snapshot.availableRamBytes)} GB",
                    valueColor = CatalogEmerald,
                    modifier = Modifier.weight(1f),
                )
            }
            Box(
                modifier = Modifier
                    .padding(horizontal = 8.dp)
                    .fillMaxWidth()
                    .height(6.dp)
                    .background(CatalogSurface, RoundedCornerShape(50))
                    .border(1.dp, CatalogBorder, RoundedCornerShape(50)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(snapshot.ramFraction)
                        .height(6.dp)
                        .background(Brush.horizontalGradient(listOf(CatalogCyan, Color(0xFF3B82F6))), RoundedCornerShape(50)),
                )
            }
        }
    }
}

private fun readHomeHardwareSnapshot(context: Context): HomeHardwareSnapshot {
    val memory = DeviceMemory.read(context)
    val storage = StatFs(context.filesDir.absolutePath)
    val battery = context.getSystemService(BatteryManager::class.java)
        .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        .coerceIn(0, 100)
    return HomeHardwareSnapshot(
        freeStorageBytes = storage.availableBytes,
        availableRamBytes = memory.availableBytes,
        totalRamBytes = memory.totalBytes,
        batteryPercent = battery,
    )
}

private fun formatHomeGb(bytes: Long): String =
    "%.1f".format(bytes / (1024.0 * 1024.0 * 1024.0))
