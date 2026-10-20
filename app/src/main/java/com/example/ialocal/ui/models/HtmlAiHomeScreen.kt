package com.example.ialocal.ui.models

import android.content.Context
import android.os.BatteryManager
import android.os.StatFs
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Api
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ialocal.BuildConfig
import com.example.ialocal.ads.InlineAdBanner
import com.example.ialocal.api.ApiServerStatus
import com.example.ialocal.data.AiModelEntity
import com.example.ialocal.data.ModelVerificationStatus
import com.example.ialocal.models.ModelCatalog
import com.example.ialocal.models.ModelProvider
import com.example.ialocal.models.provider
import com.example.ialocal.runtime.DeviceMemory
import com.example.ialocal.ui.branding.brandName
import com.example.ialocal.ui.branding.providerLogoPainter
import com.example.ialocal.ui.rememberLiveMemory

/** Installed AIs listed one per row on the home screen; the others share the box side by side. */
private const val HOME_MODEL_ROWS = 3

private data class HomeDeviceSnapshot(
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
@Composable
fun HtmlAiHomeScreen(
    viewModel: ModelsViewModel,
    adsEnabled: Boolean,
    onStartChat: () -> Unit,
    /** A new conversation with this installed AI. */
    onStartChatWith: (modelId: String) -> Unit,
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
    val storageAndBattery = remember(context) { readHomeDeviceSnapshot(context) }
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
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CatalogBackground)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "IA NO APARELHO",
                        color = CatalogText,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.7.sp,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        HomeIconButton(Icons.Default.Code, "Canvas de código", CatalogEmerald, onOpenCodeEditor)
                        HomeIconButton(Icons.Default.Settings, "Configurações", CatalogMuted, onOpenSettings)
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .background(Color(0xCC1E293B), RoundedCornerShape(8.dp))
                            .border(1.dp, CatalogBorderLight.copy(alpha = 0.75f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 9.dp, vertical = 5.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "v${BuildConfig.VERSION_NAME}",
                            color = CatalogCyanLight,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
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
                )
            }
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
                    apiRunning = server.status == ApiServerStatus.RUNNING,
                    onStartChat = onStartChat,
                    onOpenApi = onOpenApi,
                )
                HomeMyAisSection(
                    models = installedModels,
                    onOpenMyAis = onOpenChats,
                    // An AI whose test failed opens Minhas I.As, where it can be tested again or removed.
                    onOpenModel = { model ->
                        if (model.verificationStatus == ModelVerificationStatus.ERROR.name) onOpenChats()
                        else onStartChatWith(model.id)
                    },
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

/** The app in one line, then its two ways in: the chat, centered, and the local API. */
@Composable
private fun HomeActionsSection(
    apiRunning: Boolean,
    onStartChat: () -> Unit,
    onOpenApi: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        HomeStartChatButton(onClick = onStartChat)
        HomeApiButton(running = apiRunning, onClick = onOpenApi)
    }
}

/**
 * The main action, centered: a new conversation with the selected AI. A deep cyan, blue and violet
 * gradient (dark enough for white text) with a soft glow that breathes behind it, a light sheen
 * crossing it every few seconds, the sparkles of AI in a frosted badge and an arrow that invites
 * the tap; it sinks a little while pressed.
 */
@Composable
private fun HomeStartChatButton(onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    val gradient = Brush.linearGradient(listOf(ChatCtaCyan, ChatCtaBlue, ChatCtaViolet))
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, label = "chat-cta-press")
    val motion = rememberInfiniteTransition(label = "chat-cta")
    // Read only while drawing, so the animations redraw the button without recomposing it.
    val glow = motion.animateFloat(
        initialValue = 0.45f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(tween(1_800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "chat-cta-glow",
    )
    // Off the button (below 0 and above 1) most of the time, so the sheen passes now and then.
    val sheen = motion.animateFloat(
        initialValue = -0.5f,
        targetValue = 2.5f,
        animationSpec = infiniteRepeatable(tween(4_200, easing = LinearEasing)),
        label = "chat-cta-sheen",
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .padding(horizontal = 14.dp, vertical = 4.dp)
                .offset(y = 6.dp)
                .graphicsLayer { alpha = if (pressed) 0.9f else glow.value }
                .blur(20.dp, BlurredEdgeTreatment.Unbounded)
                .background(gradient, shape),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(gradient)
                .drawWithContent {
                    drawContent()
                    // Light from the top left, then the passing sheen.
                    drawRect(
                        Brush.radialGradient(
                            listOf(Color.White.copy(alpha = 0.22f), Color.Transparent),
                            center = Offset(size.width * 0.15f, 0f),
                            radius = size.width * 0.6f,
                        ),
                    )
                    val x = size.width * sheen.value
                    drawRect(
                        Brush.linearGradient(
                            listOf(Color.Transparent, Color.White.copy(alpha = 0.20f), Color.Transparent),
                            start = Offset(x - size.height, 0f),
                            end = Offset(x, size.height),
                        ),
                    )
                }
                .border(1.dp, Color.White.copy(alpha = 0.22f), shape)
                .clickable(
                    interactionSource = interaction,
                    indication = ripple(color = Color.White),
                    role = Role.Button,
                    onClickLabel = "Iniciar chat com I.A",
                    onClick = onClick,
                )
                .padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(Color.White.copy(alpha = 0.18f), CircleShape)
                    .border(1.dp, Color.White.copy(alpha = 0.35f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "Iniciar chat com I.A",
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 0.2.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "Nova conversa · sem internet",
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .background(Color.White, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    tint = ChatCtaBlue,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

// The chat button's gradient: Tailwind's cyan-700, blue-600 and violet-600, deep enough for white text.
private val ChatCtaCyan = Color(0xFF0E7490)
private val ChatCtaBlue = Color(0xFF2563EB)
private val ChatCtaViolet = Color(0xFF7C3AED)

/** The local API, under the chat: shows whether it is running. */
@Composable
private fun HomeApiButton(running: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    val accent = if (running) CatalogAmber else CatalogMuted
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xE60F172A), shape)
            .border(1.dp, CatalogBorder, shape)
            .clickable(onClickLabel = "Utilizar API", onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .background(accent.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
                .border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.Api, contentDescription = null, tint = accent, modifier = Modifier.size(16.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Utilizar API",
                color = CatalogText,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
            Text(
                text = if (running) "API local ligada neste aparelho" else "API local do aparelho, para outros apps",
                color = CatalogMuted,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        CatalogPill(
            text = if (running) "ON" else "OFF",
            foreground = accent,
            background = accent.copy(alpha = 0.12f),
            border = accent.copy(alpha = 0.35f),
        )
    }
}

/** A section heading like the catalog's company headers, with [trailing] on the right. */
@Composable
private fun HomeSectionHeader(
    title: String,
    badge: String,
    tagline: String,
    icon: ImageVector,
    accent: Color,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        HorizontalDivider(color = CatalogBorder.copy(alpha = 0.8f))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
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
                            modifier = Modifier.weight(1f, fill = false),
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

            trailing()
        }
    }
}

/** A small text button for a section header, like the catalog's "Topo". */
@Composable
private fun HomeHeaderButton(text: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = Modifier
            .background(Color(0xCC1E293B), shape)
            .border(1.dp, CatalogEmerald.copy(alpha = 0.45f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = CatalogEmerald,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

@Composable
private fun HomeMyAisSection(
    models: List<AiModelEntity>,
    onOpenMyAis: () -> Unit,
    onOpenModel: (AiModelEntity) -> Unit,
    onOpenOfflineModels: () -> Unit,
) {
    // The AI in use first, then the newest; past the first rows, the rest share the box side by side.
    val ordered = remember(models) {
        models.sortedWith(compareByDescending<AiModelEntity> { it.isActive }.thenByDescending { it.importedAt })
    }
    val rows = ordered.take(HOME_MODEL_ROWS)
    val compact = ordered.drop(HOME_MODEL_ROWS)

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        HomeSectionHeader(
            title = "Minhas I.As",
            badge = "Offline",
            tagline = when (models.size) {
                0 -> "Baixe uma IA para usar sem internet"
                1 -> "1 IA no aparelho"
                else -> "${models.size} IAs no aparelho"
            },
            icon = Icons.Default.Memory,
            accent = CatalogEmerald,
        ) {
            if (models.isNotEmpty()) HomeHeaderButton("Ver todas baixadas", onClick = onOpenMyAis)
        }

        val shape = RoundedCornerShape(12.dp)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(CatalogCard, shape)
                .border(1.dp, CatalogBorder.copy(alpha = 0.9f), shape)
                .padding(horizontal = 4.dp, vertical = 4.dp),
        ) {
            if (ordered.isEmpty()) {
                Text(
                    text = "Nenhuma IA baixada ainda. Ela aparece aqui assim que for instalada.",
                    color = CatalogMuted,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                )
            }
            rows.forEachIndexed { index, model ->
                if (index > 0) HorizontalDivider(color = CatalogBorder.copy(alpha = 0.7f), modifier = Modifier.padding(horizontal = 8.dp))
                HomeModelRow(model, onClick = { onOpenModel(model) })
            }
            if (compact.isNotEmpty()) {
                HorizontalDivider(color = CatalogBorder.copy(alpha = 0.7f), modifier = Modifier.padding(horizontal = 8.dp))
                HomeCompactModels(compact, onOpenModel)
            }
        }

        CatalogActionButton(
            text = "↓  Baixar modelos offline",
            modifier = Modifier.fillMaxWidth(),
            background = Brush.horizontalGradient(listOf(Color(0xFF60A5FA), Color(0xFF3B82F6))),
            foreground = Color(0xFF041014),
            border = Color(0xFF60A5FA).copy(alpha = 0.45f),
            onClick = onOpenOfflineModels,
        )
    }
}

/** An installed AI in one slim row: logo, name, size and, once tested, whether it is ready. */
@Composable
private fun HomeModelRow(model: AiModelEntity, onClick: () -> Unit) {
    val provider = remember(model.id, model.name) { model.provider() }
    // An AI not tested yet is tested on its first message; it gets no label until then.
    val status = when (model.verificationStatus) {
        ModelVerificationStatus.VERIFIED.name -> "Pronta" to CatalogEmerald
        ModelVerificationStatus.VERIFYING.name -> "Testando" to CatalogAmber
        ModelVerificationStatus.ERROR.name -> "Erro" to CatalogRose
        else -> null
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        HomeModelLogo(provider, size = 20.dp)
        Text(
            text = compactModelName(model),
            color = CatalogText,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = groupedFormatBytes(model.sizeBytes),
            color = CatalogMuted,
            fontSize = 10.sp,
            maxLines = 1,
        )
        if (status != null) {
            CatalogPill(
                text = status.first,
                foreground = status.second,
                background = status.second.copy(alpha = 0.10f),
                border = status.second.copy(alpha = 0.32f),
                leadingDot = true,
            )
        }
    }
}

/** A company logo without a frame, small enough for a row or a chip; the robot when unknown. */
@Composable
private fun HomeModelLogo(provider: ModelProvider?, size: Dp) {
    if (provider != null) {
        Image(
            painter = providerLogoPainter(provider),
            contentDescription = provider.brandName(),
            modifier = Modifier.size(size).clip(RoundedCornerShape(5.dp)),
            contentScale = ContentScale.Fit,
        )
    } else {
        Icon(Icons.Default.SmartToy, contentDescription = null, tint = CatalogMuted, modifier = Modifier.size(size))
    }
}

/** The AIs past the first rows, side by side: logo and name with its size in parameters. */
@Composable
private fun HomeCompactModels(models: List<AiModelEntity>, onOpenModel: (AiModelEntity) -> Unit) {
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        models.forEach { model ->
            val provider = remember(model.id, model.name) { model.provider() }
            val chipShape = RoundedCornerShape(8.dp)
            val failed = model.verificationStatus == ModelVerificationStatus.ERROR.name
            Row(
                modifier = Modifier
                    .background(CatalogSurface.copy(alpha = 0.95f), chipShape)
                    .border(1.dp, if (failed) CatalogRose.copy(alpha = 0.45f) else CatalogBorderLight.copy(alpha = 0.6f), chipShape)
                    .clip(chipShape)
                    .clickable { onOpenModel(model) }
                    .padding(horizontal = 7.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                HomeModelLogo(provider, size = 13.dp)
                Text(
                    text = compactModelName(model),
                    color = if (failed) CatalogRose else CatalogText,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 170.dp),
                )
            }
        }
    }
}

/** Free space and memory, in the catalog's grid of specifications. */
@Composable
private fun HomeDeviceSection(snapshot: HomeDeviceSnapshot) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        HomeSectionHeader(
            title = "Este aparelho",
            badge = "Hardware",
            tagline = "Espaço e memória disponíveis para as IAs",
            icon = Icons.Default.PhoneAndroid,
            accent = CatalogCyan,
        ) {
            CatalogPill(
                text = "Bateria ${snapshot.batteryPercent}%",
                foreground = CatalogMuted,
                background = Color(0xFF0F172A),
                border = CatalogBorder,
            )
        }

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
                    value = "${formatDeviceGb(snapshot.usedRamBytes)} / ${formatDeviceGb(snapshot.totalRamBytes)} GB",
                    valueColor = Color(0xFFE2E8F0),
                    modifier = Modifier.weight(1f),
                )
                Box(Modifier.width(1.dp).height(34.dp).background(CatalogBorder))
                SpecCell(
                    label = "RAM LIVRE",
                    value = "${formatDeviceGb(snapshot.availableRamBytes)} GB",
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

private fun readHomeDeviceSnapshot(context: Context): HomeDeviceSnapshot {
    val memory = DeviceMemory.read(context)
    val storage = StatFs(context.filesDir.absolutePath)
    val battery = context.getSystemService(BatteryManager::class.java)
        .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        .coerceIn(0, 100)
    return HomeDeviceSnapshot(
        freeStorageBytes = storage.availableBytes,
        availableRamBytes = memory.availableBytes,
        totalRamBytes = memory.totalBytes,
        batteryPercent = battery,
    )
}

/**
 * The name of an installed AI without its file details: a catalog AI by its catalog name
 * ("Gemma 4 26B-A4B"), an imported file without ".gguf" and its quantization ("Qwen3-4B").
 */
internal fun compactModelName(model: AiModelEntity): String {
    ModelCatalog.entries.firstOrNull { model.apiModelId.startsWith(it.apiIdPrefix) }?.let { return it.displayName }
    val name = model.name.trim().removeSuffix(".gguf").removeSuffix(".GGUF")
    return name.replace(QUANTIZATION_SUFFIX, "").ifBlank { name }
}

/** "-Q4_K_M", ".IQ2_XXS", "_F16", "-UD-Q2_K_XL", "-BF16" at the end of a GGUF file name. */
private val QUANTIZATION_SUFFIX = Regex("""[-_. ](UD[-_])?(I?Q\d\w*|BF16|F16|F32)$""", RegexOption.IGNORE_CASE)

private fun formatDeviceGb(bytes: Long): String =
    "%.1f".format(bytes / (1024.0 * 1024.0 * 1024.0))
