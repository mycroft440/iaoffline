package com.example.ialocal.ui.models

import com.example.ialocal.BuildConfig
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Chat
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import android.os.StatFs
import android.os.BatteryManager
import android.content.Context
import android.app.ActivityManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ialocal.R
import com.example.ialocal.data.AiModelEntity
import com.example.ialocal.models.CatalogModel
import com.example.ialocal.models.ModelDownloadPhase
import com.example.ialocal.models.ModelDownloadState
import com.example.ialocal.models.ModelProvider
import com.example.ialocal.ui.branding.ProviderLogo
import com.example.ialocal.ui.branding.brandName
import com.example.ialocal.ui.branding.catalogProvider

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiHomeScreen(
    viewModel: ModelsViewModel,
    onStartChat: () -> Unit,
    onOpenChats: () -> Unit,
    onOpenOfflineModels: () -> Unit,
    onOpenApi: () -> Unit,
    onOpenSettings: () -> Unit,
    onRestoreModels: () -> Unit,
    onOpenCodeEditor: () -> Unit,
    onExitApp: () -> Unit,
) {
    val installedModels by viewModel.models.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var showExitDialog by remember { mutableStateOf(false) }

    LaunchedEffect(error) {
        error?.let {
            snackbar.showSnackbar(it)
            viewModel.clearError()
        }
    }

    BackHandler {
        showExitDialog = true
    }

    Scaffold(
        containerColor = HomeBackground,
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        HomeContent(
            modifier = Modifier.padding(padding),
            installedModels = installedModels,
            onStartChat = onStartChat,
            onOpenChats = onOpenChats,
            onDeleteModel = viewModel::delete,
            onOpenOfflineModels = onOpenOfflineModels,
            onOpenApi = onOpenApi,
            onOpenSettings = onOpenSettings,
            onRestoreModels = onRestoreModels,
            onOpenCodeEditor = onOpenCodeEditor,
        )
    }

    if (showExitDialog) {
        AlertDialog(
            onDismissRequest = onExitApp,
            title = { Text("Deseja sair do app?") },
            confirmButton = {
                TextButton(onClick = onExitApp) {
                    Text("Sim")
                }
            },
            dismissButton = {
                TextButton(onClick = { showExitDialog = false }) {
                    Text("Não")
                }
            },
            properties = DialogProperties(dismissOnClickOutside = false),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfflineModelsScreen(
    viewModel: ModelsViewModel,
    onBack: () -> Unit,
) {
    val installedModels by viewModel.models.collectAsStateWithLifecycle()
    val download by viewModel.downloadState.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var selectedProvider by remember { mutableStateOf<ModelProvider?>(null) }

    LaunchedEffect(error) {
        error?.let {
            snackbar.showSnackbar(it)
            viewModel.clearError()
        }
    }

    BackHandler {
        if (selectedProvider != null) selectedProvider = null else onBack()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    selectedProvider?.let { ProviderSectionTitle(it) }
                        ?: Text("Baixar I.As offline")
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (selectedProvider != null) selectedProvider = null else onBack()
                        },
                    ) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Voltar")
                    }
                },
            )
        },
    ) { padding ->
        if (selectedProvider == null) {
            OfflineProvidersContent(
                modifier = Modifier.padding(padding),
                onSelectProvider = { selectedProvider = it },
            )
        } else {
            ProviderModelsContent(
                modifier = Modifier.padding(padding),
                provider = requireNotNull(selectedProvider),
                catalog = viewModel.catalog,
                installedModels = installedModels,
                download = download,
                onInstall = viewModel::downloadCatalogModel,
                onPause = viewModel::cancelDownload,
                onEnd = viewModel::endDownload,
                onDeleteModel = viewModel::delete,
            )
        }
    }
}

@Composable
private fun ProviderSectionTitle(provider: ModelProvider) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ProviderLogo(provider = provider, size = 30.dp)
        Text(provider.sectionLabel)
    }
}

private val HomeBackground = Color(0xFF020408)
private val HomeCard = Color(0xFF050811)
private val HomeCardAlt = Color(0xFF090E1F)
private val HomeText = Color(0xFFF8FAFC)
private val HomeMuted = Color(0xFF94A3B8)
private val HomeIndigo = Color(0xFF6366F1)
private val HomeIndigoStrong = Color(0xFF4F46E5)
private val HomeIndigoLight = Color(0xFFA5B4FC)
private val HomeCyan = Color(0xFF22D3EE)
private val HomeEmerald = Color(0xFF34D399)
private val HomeRose = Color(0xFFFB7185)

private data class HomeHardwareSnapshot(
    val freeStorageBytes: Long,
    val totalStorageBytes: Long,
    val availableRamBytes: Long,
    val totalRamBytes: Long,
    val batteryPercent: Int,
) {
    val storageUsedFraction: Float
        get() = if (totalStorageBytes <= 0L) 0f
        else ((totalStorageBytes - freeStorageBytes).toFloat() / totalStorageBytes.toFloat()).coerceIn(0f, 1f)

    val usedRamBytes: Long
        get() = (totalRamBytes - availableRamBytes).coerceAtLeast(0L)
}

@Composable
private fun HomeContent(
    modifier: Modifier,
    installedModels: List<AiModelEntity>,
    onStartChat: () -> Unit,
    onOpenChats: () -> Unit,
    onDeleteModel: (String) -> Unit,
    onOpenOfflineModels: () -> Unit,
    onOpenApi: () -> Unit,
    onOpenSettings: () -> Unit,
    onRestoreModels: () -> Unit,
    onOpenCodeEditor: () -> Unit,
) {
    val context = LocalContext.current
    val hardware = remember(context) { readHomeHardwareSnapshot(context) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(HomeBackground),
    ) {
        HomeHeader(
            batteryPercent = hardware.batteryPercent,
            onOpenSettings = onOpenSettings,
        )

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                NeuralHeroCard(onStartChat = onStartChat)
            }

            item {
                InstalledModelsSection(
                    models = installedModels,
                    onOpenChats = onOpenChats,
                    onOpenChat = onStartChat,
                    onDeleteModel = onDeleteModel,
                )
            }

            item {
                HardwareCard(snapshot = hardware)
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    HomeActionCard(
                        title = "Baixar I.As offline",
                        subtitle = "Modelos GGUF otimizados para uso local",
                        icon = Icons.Default.Download,
                        iconBackground = Color(0xFF4F46E5),
                        borderColor = Color(0xFF818CF8),
                        backgroundColor = Color(0xFF090E1F),
                        onClick = onOpenOfflineModels,
                    )
                    HomeActionCard(
                        title = "Utilizar API",
                        subtitle = "Configure e controle a API local do aparelho",
                        icon = Icons.Default.Memory,
                        iconBackground = Color(0xFF1E293B),
                        borderColor = Color(0xFF64748B),
                        backgroundColor = Color(0xFF0A0D18),
                        onClick = onOpenApi,
                    )
                }
            }

            item { Spacer(Modifier.height(2.dp)) }
        }

        HomeFooter(
            onRestoreModels = onRestoreModels,
            onOpenCodeEditor = onOpenCodeEditor,
        )
    }
}

@Composable
private fun HomeHeader(
    batteryPercent: Int,
    onOpenSettings: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "LOCAL ACTIVE",
                color = HomeEmerald,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.ExtraBold,
                modifier = Modifier
                    .background(Color(0xFF052E2B), RoundedCornerShape(6.dp))
                    .border(1.dp, HomeEmerald, RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "$batteryPercent%",
                color = HomeText,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.ExtraBold,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .background(HomeEmerald, RoundedCornerShape(50)),
                )
                Text(
                    text = "I.A Off-line",
                    color = HomeText,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.ExtraBold,
                )
                Text(
                    text = "v${BuildConfig.VERSION_NAME}",
                    color = Color(0xFFC7D2FE),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Black,
                    modifier = Modifier
                        .background(Color(0xFF312E81), RoundedCornerShape(50))
                        .border(1.dp, Color(0xFF818CF8), RoundedCornerShape(50))
                        .padding(horizontal = 9.dp, vertical = 3.dp),
                )
            }

            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(Color(0xFF0D1222), RoundedCornerShape(16.dp))
                    .border(2.dp, Color(0xFF64748B), RoundedCornerShape(16.dp))
                    .clickable(onClick = onOpenSettings),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Configurações",
                    tint = HomeText,
                    modifier = Modifier.size(21.dp),
                )
            }
        }
    }
}

@Composable
private fun NeuralHeroCard(
    onStartChat: () -> Unit,
) {
    val cardShape = RoundedCornerShape(24.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(HomeCard, cardShape)
            .border(2.dp, Color(0x666366F1), cardShape)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                modifier = Modifier.size(66.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .background(Color(0xFF090E1C), RoundedCornerShape(16.dp))
                        .border(2.dp, Color(0xFF818CF8), RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.Memory,
                        contentDescription = null,
                        tint = Color(0xFFC7D2FE),
                        modifier = Modifier.size(34.dp),
                    )
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(24.dp)
                        .background(Color.Black, RoundedCornerShape(50))
                        .border(2.dp, HomeRose, RoundedCornerShape(50)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.WifiOff,
                        contentDescription = null,
                        tint = HomeRose,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }

            Column(Modifier.weight(1f)) {
                Text(
                    text = "IA On-Device",
                    color = HomeText,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Black,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Execução neural local de alta velocidade, segura e 100% privada.",
                    color = Color(0xFFCBD5E1),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                )
            }
        }

        val buttonShape = RoundedCornerShape(16.dp)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    brush = Brush.linearGradient(
                        listOf(HomeIndigoStrong, HomeIndigo, Color(0xFF7C3AED)),
                    ),
                    shape = buttonShape,
                )
                .border(2.dp, HomeIndigoLight, buttonShape)
                .clickable(onClick = onStartChat)
                .padding(horizontal = 16.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Chat,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(21.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = "Iniciar chat com IA",
                color = Color.White,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Black,
            )
        }
    }
}

@Composable
private fun InstalledModelsSection(
    models: List<AiModelEntity>,
    onOpenChats: () -> Unit,
    onOpenChat: () -> Unit,
    onDeleteModel: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenChats)
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Minhas I.As",
                    color = HomeText,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.ExtraBold,
                )
                Icon(
                    imageVector = Icons.Default.KeyboardArrowRight,
                    contentDescription = null,
                    tint = Color(0xFF818CF8),
                    modifier = Modifier.size(20.dp),
                )
            }
            Text(
                text = "${models.size} instalada${if (models.size == 1) "" else "s"}",
                color = Color(0xFFC7D2FE),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.ExtraBold,
                modifier = Modifier
                    .background(Color(0xFF0C1224), RoundedCornerShape(50))
                    .border(1.dp, Color(0x806366F1), RoundedCornerShape(50))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }

        if (models.isEmpty()) {
            EmptyModelsCard()
        } else {
            models.forEach { model ->
                InstalledModelCard(
                    model = model,
                    onOpenChat = onOpenChat,
                    onDelete = { onDeleteModel(model.id) },
                )
            }
        }
    }
}

@Composable
private fun EmptyModelsCard() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF04060C), RoundedCornerShape(16.dp))
            .border(2.dp, Color(0xFF334155), RoundedCornerShape(16.dp))
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .background(Color(0xFF0F172A), RoundedCornerShape(50))
                .border(2.dp, Color(0xFF475569), RoundedCornerShape(50)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Memory,
                contentDescription = null,
                tint = Color(0xFF818CF8),
                modifier = Modifier.size(25.dp),
            )
        }
        Text(
            text = "Nenhum modelo baixado",
            color = HomeText,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "Baixe pesos quantizados em GGUF para uso sem internet.",
            color = HomeMuted,
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun HardwareCard(snapshot: HomeHardwareSnapshot) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF040711), RoundedCornerShape(16.dp))
            .border(2.dp, Color(0xFF1E293B), RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(Color(0xFF083344), RoundedCornerShape(12.dp))
                    .border(2.dp, HomeCyan, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Storage,
                    contentDescription = null,
                    tint = Color(0xFF67E8F9),
                    modifier = Modifier.size(21.dp),
                )
            }
            Column {
                Text(
                    text = "Armazenamento Local",
                    color = HomeMuted,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "${formatHomeGb(snapshot.freeStorageBytes)} GB Livres",
                    color = HomeText,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Black,
                )
            }
        }

        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = "RAM: ${formatHomeGb(snapshot.usedRamBytes)} / ${formatHomeGb(snapshot.totalRamBytes)} GB",
                color = Color(0xFFCBD5E1),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(5.dp))
            Box(
                modifier = Modifier
                    .width(96.dp)
                    .height(8.dp)
                    .background(Color(0xFF1E293B), RoundedCornerShape(50))
                    .border(1.dp, Color(0xFF334155), RoundedCornerShape(50)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(snapshot.storageUsedFraction)
                        .height(8.dp)
                        .background(
                            Brush.horizontalGradient(listOf(HomeIndigo, HomeCyan)),
                            RoundedCornerShape(50),
                        ),
                )
            }
        }
    }
}

@Composable
private fun HomeActionCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    iconBackground: Color,
    borderColor: Color,
    backgroundColor: Color,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(backgroundColor, shape)
            .border(2.dp, borderColor, shape)
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(iconBackground, RoundedCornerShape(12.dp))
                    .border(1.dp, borderColor, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(21.dp))
            }
            Column {
                Text(
                    text = title,
                    color = HomeText,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.ExtraBold,
                )
                Text(
                    text = subtitle,
                    color = if (borderColor == Color(0xFF818CF8)) Color(0xFFC7D2FE) else HomeMuted,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
        Box(
            modifier = Modifier
                .size(32.dp)
                .background(Color(0xFF111827), RoundedCornerShape(9.dp))
                .border(1.dp, borderColor.copy(alpha = 0.55f), RoundedCornerShape(9.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowRight,
                contentDescription = null,
                tint = borderColor,
                modifier = Modifier.size(19.dp),
            )
        }
    }
}

@Composable
private fun HomeFooter(
    onRestoreModels: () -> Unit,
    onOpenCodeEditor: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(Color.Transparent, HomeBackground, Color.Black),
                ),
            )
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .background(Color(0xFF0F172E), RoundedCornerShape(16.dp))
                .border(2.dp, Color(0xFF818CF8), RoundedCornerShape(16.dp))
                .clickable(onClick = onRestoreModels)
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Restore,
                contentDescription = null,
                tint = Color(0xFFA5B4FC),
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(9.dp))
            Text(
                text = "Restaurar IAs",
                color = Color(0xFFE0E7FF),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Black,
            )
        }

        Spacer(Modifier.width(12.dp))

        Box(
            modifier = Modifier
                .size(50.dp)
                .background(Color(0xFF0A1424), RoundedCornerShape(16.dp))
                .border(2.dp, HomeCyan, RoundedCornerShape(16.dp))
                .clickable(onClick = onOpenCodeEditor),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Code,
                contentDescription = "Abrir editor de código",
                tint = Color(0xFF67E8F9),
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

private fun readHomeHardwareSnapshot(context: Context): HomeHardwareSnapshot {
    val activityManager = context.getSystemService(ActivityManager::class.java)
    val memory = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
    val storage = StatFs(context.filesDir.absolutePath)
    val battery = context.getSystemService(BatteryManager::class.java)
        .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        .coerceIn(0, 100)

    return HomeHardwareSnapshot(
        freeStorageBytes = storage.availableBytes,
        totalStorageBytes = storage.totalBytes,
        availableRamBytes = memory.availMem,
        totalRamBytes = memory.totalMem,
        batteryPercent = battery,
    )
}

private fun formatHomeGb(bytes: Long): String =
    "%.1f".format(bytes / (1024.0 * 1024.0 * 1024.0))

@Composable
private fun OfflineProvidersContent(
    modifier: Modifier,
    onSelectProvider: (ModelProvider) -> Unit,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text(
                text = "Empresas com modelos offline",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 12.dp, bottom = 8.dp),
            )
        }
        item { NavigationCard(ModelProvider.GOOGLE.sectionLabel, ModelProvider.GOOGLE) { onSelectProvider(ModelProvider.GOOGLE) } }
        item { NavigationCard(ModelProvider.ALIBABA.sectionLabel, ModelProvider.ALIBABA) { onSelectProvider(ModelProvider.ALIBABA) } }
        item { NavigationCard(ModelProvider.META.sectionLabel, ModelProvider.META) { onSelectProvider(ModelProvider.META) } }
        item { NavigationCard(ModelProvider.MISTRAL.sectionLabel, ModelProvider.MISTRAL) { onSelectProvider(ModelProvider.MISTRAL) } }
        item { NavigationCard(ModelProvider.DEEPSEEK.sectionLabel, ModelProvider.DEEPSEEK) { onSelectProvider(ModelProvider.DEEPSEEK) } }
        item { NavigationCard(ModelProvider.MICROSOFT.sectionLabel, ModelProvider.MICROSOFT) { onSelectProvider(ModelProvider.MICROSOFT) } }
        item { Spacer(Modifier.height(18.dp)) }
    }
}

@Composable
private fun NavigationCard(
    label: String,
    provider: ModelProvider?,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            provider?.let { ProviderLogo(provider = it, size = 42.dp) }
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun InstalledModelCard(
    model: AiModelEntity,
    onOpenChat: () -> Unit,
    onDelete: () -> Unit,
) {
    val provider = model.catalogProvider()
    var confirmDelete by remember(model.id) { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(HomeCardAlt, RoundedCornerShape(16.dp))
            .border(2.dp, Color(0x806366F1), RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .background(HomeIndigoStrong, RoundedCornerShape(12.dp))
                .border(1.dp, HomeIndigoLight, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "AI",
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Black,
            )
        }

        Column(Modifier.weight(1f)) {
            Text(
                text = model.name,
                color = HomeText,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Black,
            )
            Text(
                text = buildString {
                    provider?.let { append(it.brandName()).append(" · ") }
                    append(formatBytes(model.sizeBytes))
                    if (model.isActive) append(" · ativa")
                },
                color = Color(0xFFC7D2FE),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }

        Box(
            modifier = Modifier
                .background(Color(0xFF059669), RoundedCornerShape(12.dp))
                .border(1.dp, Color(0xFF6EE7B7), RoundedCornerShape(12.dp))
                .clickable(onClick = onOpenChat)
                .padding(horizontal = 13.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "Usar",
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Black,
            )
        }

        Box(
            modifier = Modifier
                .size(34.dp)
                .background(Color(0x663F0B1A), RoundedCornerShape(12.dp))
                .border(1.dp, HomeRose.copy(alpha = 0.65f), RoundedCornerShape(12.dp))
                .clickable { confirmDelete = true },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = "Remover ${model.name}",
                tint = Color(0xFFFDA4AF),
                modifier = Modifier.size(17.dp),
            )
        }
    }

    if (confirmDelete) {
        ModelUninstallConfirmationDialog(
            modelName = model.name,
            onConfirm = {
                confirmDelete = false
                onDelete()
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun ProviderModelsContent(
    modifier: Modifier,
    provider: ModelProvider,
    catalog: List<CatalogModel>,
    installedModels: List<AiModelEntity>,
    download: ModelDownloadState,
    onInstall: (String) -> Unit,
    onPause: () -> Unit,
    onEnd: () -> Unit,
    onDeleteModel: (String) -> Unit,
) {
    val providerModels = catalog.filter { it.provider == provider }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (providerModels.isEmpty()) {
            item {
                Text(
                    "Nenhum modelo verificado disponível nesta categoria por enquanto.",
                    modifier = Modifier.padding(top = 24.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            items(providerModels, key = { it.id }) { catalogModel ->
                val installed = installedModels.firstOrNull { it.apiModelId.startsWith(catalogModel.apiIdPrefix) }
                CatalogInstallCard(
                    model = catalogModel,
                    state = download.takeIf { it.catalogId == catalogModel.id },
                    installedModel = installed,
                    anotherOperationRunning = download.isBusy && download.catalogId != catalogModel.id,
                    onInstall = { onInstall(catalogModel.id) },
                    onPause = onPause,
                    onEnd = onEnd,
                    onDelete = { installed?.let { onDeleteModel(it.id) } },
                )
            }
        }
        item { Spacer(Modifier.height(18.dp)) }
    }
}

@Composable
private fun CatalogInstallCard(
    model: CatalogModel,
    state: ModelDownloadState?,
    installedModel: AiModelEntity?,
    anotherOperationRunning: Boolean,
    onInstall: () -> Unit,
    onPause: () -> Unit,
    onEnd: () -> Unit,
    onDelete: () -> Unit,
) {
    val installed = installedModel != null
    val busy = state?.isBusy == true
    val paused = state?.phase == ModelDownloadPhase.CANCELLED
    val failed = state?.phase == ModelDownloadPhase.ERROR
    val progress = state?.progress?.let { "${(it * 100).toInt()}%" }
    var confirmDelete by remember(installedModel?.id) { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProviderLogo(provider = model.provider, size = 46.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        model.provider.brandName(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(model.displayName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
            }
            Text("Parâmetros: ${formatParameters(model.totalParametersBillions)}${model.activeParametersBillions?.let { " · ${formatParameters(it)} ativos" } ?: ""}")
            Text("Tamanho: ${formatBytes(model.approximateSizeBytes)}")
            Text("Requisitos mínimos: ${formatBytes(model.recommendedRamBytes)} RAM")
            Text(
                model.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (state != null && state.phase != ModelDownloadPhase.IDLE) {
                Text(
                    downloadStatus(state, progress),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            when {
                installed -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { confirmDelete = true }, modifier = Modifier.weight(1f)) { Text("Desinstalar") }
                        Button(onClick = {}, enabled = false, modifier = Modifier.weight(1f)) { Text("Instalada") }
                    }
                }
                busy -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onEnd, modifier = Modifier.weight(1f)) { Text("Encerrar") }
                        Button(
                            onClick = onPause,
                            enabled = state?.phase == ModelDownloadPhase.DOWNLOADING,
                            modifier = Modifier.weight(1f),
                        ) {
                            if (state?.phase != ModelDownloadPhase.DOWNLOADING) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            }
                            Text(if (state?.phase == ModelDownloadPhase.DOWNLOADING) "Pausar" else " Instalando")
                        }
                    }
                }
                paused || failed -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onEnd, modifier = Modifier.weight(1f)) { Text("Encerrar") }
                        Button(onClick = onInstall, modifier = Modifier.weight(1f)) { Text("Continuar") }
                    }
                }
                else -> {
                    Button(
                        onClick = onInstall,
                        enabled = !anotherOperationRunning,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Instalar I.A")
                    }
                }
            }
        }
    }

    if (confirmDelete && installedModel != null) {
        ModelUninstallConfirmationDialog(
            modelName = installedModel.name,
            onConfirm = {
                confirmDelete = false
                onDelete()
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun ModelUninstallConfirmationDialog(
    modelName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Desinstalar I.A?") },
        text = {
            Text("Tem certeza que quer desinstalar essa I.A? O arquivo de $modelName será excluído do armazenamento interno do Android.")
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Sim, desinstalar") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        },
    )
}

private fun downloadStatus(state: ModelDownloadState, progress: String?): String = when (state.phase) {
    ModelDownloadPhase.IDLE -> "Pronto para instalar"
    ModelDownloadPhase.CHECKING -> "Preparando instalação"
    ModelDownloadPhase.DOWNLOADING -> "Instalando${progress?.let { " · $it" } ?: ""}"
    ModelDownloadPhase.VERIFYING_FILE -> "Verificando arquivo"
    ModelDownloadPhase.IMPORTING -> "Registrando I.A"
    ModelDownloadPhase.VERIFYING_MODEL -> "Testando I.A no aparelho"
    ModelDownloadPhase.COMPLETE -> state.message ?: "Instalação concluída"
    ModelDownloadPhase.ERROR -> state.message ?: "Falha na instalação"
    ModelDownloadPhase.CANCELLED -> "Instalação pausada"
}

private fun formatParameters(value: Double): String =
    if (value % 1.0 == 0.0) "${value.toInt()}B" else "${"%.2f".format(value).trimEnd('0').trimEnd('.')}B"

private fun formatBytes(bytes: Long): String {
    val gb = bytes / (1024.0 * 1024.0 * 1024.0)
    return if (gb >= 1.0) "%.1f GB".format(gb) else "%.0f MB".format(bytes / (1024.0 * 1024.0))
}
