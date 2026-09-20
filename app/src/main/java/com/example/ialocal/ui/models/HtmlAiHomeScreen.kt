package com.example.ialocal.ui.models

import android.app.ActivityManager
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ialocal.BuildConfig
import com.example.ialocal.data.AiModelEntity
import com.example.ialocal.models.CatalogModel
import com.example.ialocal.ui.branding.ProviderLogo
import com.example.ialocal.ui.branding.catalogProvider
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

private val HtmlBg = Color(0xFF0C0D10)
private val HtmlSurface = Color(0xFF15161B)
private val HtmlElevated = Color(0xFF1E1F26)
private val HtmlBorder = Color(0xFF282A33)
private val HtmlSubtle = Color(0xFF383B47)
private val HtmlText = Color(0xFFF1F2F5)
private val HtmlMuted = Color(0xFF8B8E9D)
private val HtmlDim = Color(0xFF5C5F6E)
private val HtmlActive = Color(0xFF10B981)

private data class HtmlHardwareSnapshot(
    val freeStorageBytes: Long,
    val totalStorageBytes: Long,
    val availableRamBytes: Long,
    val totalRamBytes: Long,
    val batteryPercent: Int,
) {
    val usedRamBytes: Long get() = (totalRamBytes - availableRamBytes).coerceAtLeast(0L)
    val ramFraction: Float
        get() = if (totalRamBytes <= 0L) 0f
        else (usedRamBytes.toFloat() / totalRamBytes.toFloat()).coerceIn(0f, 1f)
}

@Composable
fun HtmlAiHomeScreen(
    viewModel: ModelsViewModel,
    onStartChat: () -> Unit,
    onOpenChats: () -> Unit,
    onOpenOfflineModels: () -> Unit,
    onOpenApi: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenCodeEditor: () -> Unit,
    onExitApp: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val installedModels by viewModel.models.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val hardware = remember(context) { readHtmlHardwareSnapshot(context) }
    var showExitDialog by remember { mutableStateOf(false) }
    var clock by remember { mutableStateOf(currentClockText()) }

    LaunchedEffect(Unit) {
        while (true) {
            clock = currentClockText()
            delay(30_000)
        }
    }

    LaunchedEffect(error) {
        error?.let {
            snackbar.showSnackbar(it)
            viewModel.clearError()
        }
    }

    BackHandler { showExitDialog = true }

    Scaffold(
        containerColor = HtmlBg,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            HtmlBottomBar(onOpenCodeEditor = onOpenCodeEditor)
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(HtmlBg),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                HtmlStatusBar(
                    clock = clock,
                    batteryPercent = hardware.batteryPercent,
                )
            }
            item {
                HtmlAppHeader(onOpenSettings = onOpenSettings)
            }
            item {
                HtmlHeroCard(onStartChat = onStartChat)
            }
            item {
                HtmlInstalledModelsSection(
                    models = installedModels,
                    catalog = viewModel.catalog,
                    onOpenChats = onOpenChats,
                )
            }
            item {
                HtmlHardwareCard(hardware)
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    HtmlNavigationCard(
                        title = "Baixar I.As offline",
                        subtitle = "Modelos GGUF otimizados para uso local",
                        icon = Icons.Default.Download,
                        onClick = onOpenOfflineModels,
                    )
                    HtmlNavigationCard(
                        title = "Utilizar API",
                        subtitle = "Configure e controle a API local do aparelho",
                        icon = Icons.Default.Code,
                        onClick = onOpenApi,
                    )
                }
            }
            item { Spacer(Modifier.height(4.dp)) }
        }
    }

    if (showExitDialog) {
        AlertDialog(
            onDismissRequest = { showExitDialog = false },
            containerColor = HtmlSurface,
            titleContentColor = HtmlText,
            textContentColor = HtmlMuted,
            title = { Text("Deseja sair do app?") },
            confirmButton = {
                TextButton(onClick = onExitApp) { Text("Sim", color = HtmlText) }
            },
            dismissButton = {
                TextButton(onClick = { showExitDialog = false }) { Text("Não", color = HtmlMuted) }
            },
        )
    }
}

@Composable
private fun HtmlStatusBar(clock: String, batteryPercent: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = clock,
            color = HtmlText,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "OFFLINE ENGINE",
                color = HtmlDim,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                letterSpacing = 0.6.sp,
            )
            Text(
                text = "$batteryPercent%",
                color = HtmlText,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun HtmlAppHeader(onOpenSettings: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(10.dp)
                    .background(HtmlActive, CircleShape)
            )
            Text(
                text = "I.A Off-line",
                color = HtmlText,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.3).sp,
            )
            Text(
                text = "v${BuildConfig.VERSION_NAME}",
                color = HtmlMuted,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .background(HtmlElevated, RoundedCornerShape(6.dp))
                    .border(1.dp, HtmlBorder, RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .background(Color(0x3310B981), RoundedCornerShape(6.dp))
                    .border(1.dp, Color(0x6659A98C), RoundedCornerShape(6.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(6.dp).background(Color(0xFF34D399), CircleShape))
                Text(
                    text = "LOCAL ACTIVE",
                    color = Color(0xFF6EE7B7),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp,
                )
            }
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(HtmlSurface, RoundedCornerShape(12.dp))
                    .border(1.dp, HtmlBorder, RoundedCornerShape(12.dp))
                    .clickable(onClick = onOpenSettings),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Configurações",
                    tint = HtmlMuted,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun HtmlHeroCard(onStartChat: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(HtmlSurface, RoundedCornerShape(16.dp))
            .border(1.dp, HtmlBorder, RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(Modifier.size(52.dp)) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(HtmlElevated, RoundedCornerShape(12.dp))
                        .border(1.dp, HtmlBorder, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.Memory,
                        contentDescription = null,
                        tint = HtmlText,
                        modifier = Modifier.size(25.dp),
                    )
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(20.dp)
                        .background(HtmlBg, CircleShape)
                        .border(1.dp, HtmlBorder, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.WifiOff,
                        contentDescription = null,
                        tint = Color(0xFFFB7185),
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
            Column(Modifier.weight(1f).padding(top = 2.dp)) {
                Text(
                    text = "IA On-Device",
                    color = HtmlText,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "Execução neural local de alta velocidade, segura e 100% privada. Zero envio de dados externos.",
                    color = HtmlMuted,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(HtmlText, RoundedCornerShape(12.dp))
                .clickable(onClick = onStartChat)
                .padding(horizontal = 16.dp, vertical = 13.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Chat,
                contentDescription = null,
                tint = HtmlBg,
                modifier = Modifier.size(17.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Iniciar chat com IA",
                color = HtmlBg,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun HtmlInstalledModelsSection(
    models: List<AiModelEntity>,
    catalog: List<CatalogModel>,
    onOpenChats: () -> Unit,
) {
    val visibleModels = models.sortedBy { it.importedAt }.take(6)

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier.clickable(onClick = onOpenChats),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Minhas I.As",
                    color = HtmlText,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Icon(
                    imageVector = Icons.Default.KeyboardArrowRight,
                    contentDescription = null,
                    tint = HtmlMuted,
                    modifier = Modifier.size(18.dp),
                )
            }
            Text(
                text = "${models.size} instalada${if (models.size == 1) "" else "s"}",
                color = HtmlMuted,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .background(HtmlElevated, RoundedCornerShape(50))
                    .border(1.dp, HtmlBorder, RoundedCornerShape(50))
                    .padding(horizontal = 10.dp, vertical = 3.dp),
            )
        }

        if (models.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(HtmlSurface, RoundedCornerShape(16.dp))
                    .border(1.dp, HtmlBorder, RoundedCornerShape(16.dp))
                    .padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("Nenhum modelo baixado", color = HtmlText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text("Baixe pesos quantizados em GGUF para uso sem internet.", color = HtmlMuted, fontSize = 11.sp)
            }
        } else {
            visibleModels.chunked(2).forEach { rowModels ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    rowModels.forEach { model ->
                        HtmlInstalledModelCard(
                            model = model,
                            catalogModel = catalog.firstOrNull { model.apiModelId.startsWith(it.apiIdPrefix) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (rowModels.size == 1) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun HtmlInstalledModelCard(
    model: AiModelEntity,
    catalogModel: CatalogModel?,
    modifier: Modifier = Modifier,
) {
    val provider = model.catalogProvider()
    val parameterLabel = htmlModelParameterLabel(model, catalogModel)
    val displayName = compactHtmlModelName(model, catalogModel)

    Row(
        modifier = modifier
            .height(68.dp)
            .background(HtmlSurface, RoundedCornerShape(14.dp))
            .border(1.dp, HtmlBorder, RoundedCornerShape(14.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (provider != null) {
            ProviderLogo(provider = provider, size = 34.dp)
        } else {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .background(HtmlElevated, RoundedCornerShape(10.dp))
                    .border(1.dp, HtmlBorder, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "AI",
                    color = HtmlText,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = displayName,
                color = HtmlText,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 13.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            parameterLabel?.let { params ->
                Text(
                    text = params,
                    color = HtmlMuted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun HtmlHardwareCard(snapshot: HtmlHardwareSnapshot) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(HtmlSurface, RoundedCornerShape(16.dp))
            .border(1.dp, HtmlBorder, RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f).padding(end = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(HtmlElevated, RoundedCornerShape(12.dp))
                    .border(1.dp, HtmlBorder, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Storage, null, tint = HtmlMuted, modifier = Modifier.size(18.dp))
            }
            Column(Modifier.weight(1f)) {
                Text("ARMAZENAMENTO", color = HtmlMuted, fontSize = 9.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.6.sp)
                Text(
                    text = "${formatHtmlGb(snapshot.freeStorageBytes)} GB Livres",
                    color = HtmlText,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Box(Modifier.width(1.dp).height(48.dp).background(HtmlBorder))

        Column(
            modifier = Modifier.weight(1f).padding(start = 12.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("RAM NEURAL", color = HtmlMuted, fontSize = 9.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.6.sp)
                Text(
                    text = "${formatHtmlGb(snapshot.usedRamBytes)} / ${formatHtmlGb(snapshot.totalRamBytes)} GB",
                    color = HtmlText,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
            Spacer(Modifier.height(7.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .background(HtmlElevated, RoundedCornerShape(50))
                    .border(1.dp, HtmlBorder, RoundedCornerShape(50)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(snapshot.ramFraction)
                        .height(8.dp)
                        .background(Color(0xFFE2E4EA), RoundedCornerShape(50)),
                )
            }
        }
    }
}

@Composable
private fun HtmlNavigationCard(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(HtmlSurface, RoundedCornerShape(16.dp))
            .border(1.dp, HtmlBorder, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(HtmlElevated, RoundedCornerShape(12.dp))
                .border(1.dp, HtmlBorder, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = HtmlText, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = HtmlText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(
                text = subtitle,
                color = HtmlMuted,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Box(
            modifier = Modifier
                .size(28.dp)
                .background(HtmlElevated, RoundedCornerShape(8.dp))
                .border(1.dp, HtmlBorder, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.KeyboardArrowRight, null, tint = HtmlMuted, modifier = Modifier.size(17.dp))
        }
    }
}

@Composable
private fun HtmlBottomBar(
    onOpenCodeEditor: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(HtmlBg)
            .border(1.dp, HtmlBorder)
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(width = 48.dp, height = 44.dp)
                    .background(HtmlSurface, RoundedCornerShape(12.dp))
                    .border(1.dp, HtmlBorder, RoundedCornerShape(12.dp))
                    .clickable(onClick = onOpenCodeEditor),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Code, "Console e editor", tint = Color(0xFF34D399), modifier = Modifier.size(18.dp))
            }
        }
        Box(
            modifier = Modifier
                .width(128.dp)
                .height(4.dp)
                .background(HtmlSubtle, RoundedCornerShape(50)),
        )
    }
}

private fun readHtmlHardwareSnapshot(context: Context): HtmlHardwareSnapshot {
    val activityManager = context.getSystemService(ActivityManager::class.java)
    val memory = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
    val storage = StatFs(context.filesDir.absolutePath)
    val battery = context.getSystemService(BatteryManager::class.java)
        .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        .coerceIn(0, 100)
    return HtmlHardwareSnapshot(
        freeStorageBytes = storage.availableBytes,
        totalStorageBytes = storage.totalBytes,
        availableRamBytes = memory.availMem,
        totalRamBytes = memory.totalMem,
        batteryPercent = battery,
    )
}

private fun currentClockText(): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

private fun formatHtmlGb(bytes: Long): String =
    "%.1f".format(bytes / (1024.0 * 1024.0 * 1024.0))

private fun compactHtmlModelName(model: AiModelEntity, catalogModel: CatalogModel?): String {
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

private fun htmlModelParameterLabel(model: AiModelEntity, catalogModel: CatalogModel?): String? {
    val raw = catalogModel?.displayName ?: model.name
    val nominal = Regex(
        "\\s+(\\d+(?:[.,]\\d+)?B(?:-A\\d+(?:[.,]\\d+)?B)?)(?:\\s*·\\s*texto)?$",
        RegexOption.IGNORE_CASE,
    ).find(raw)?.groupValues?.getOrNull(1)
    return nominal?.uppercase(Locale.ROOT)
        ?: catalogModel?.let { formatHtmlParameters(it.totalParametersBillions) }
}

private fun formatHtmlParameters(value: Double): String =
    if (value % 1.0 == 0.0) "${value.toInt()}B" else "${"%.2f".format(value).trimEnd('0').trimEnd('.')}B"
