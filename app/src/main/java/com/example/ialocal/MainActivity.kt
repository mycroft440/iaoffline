package com.example.ialocal

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.ialocal.data.ThemeMode
import com.example.ialocal.models.CatalogModel
import com.example.ialocal.ui.chat.ChatViewModel
import com.example.ialocal.ui.chat.ChatWithDeepThinkScreen
import com.example.ialocal.ui.codeeditor.CodeEditorScreen
import com.example.ialocal.ui.codeeditor.CodeEditorViewModel
import com.example.ialocal.ui.home.HomeScreen
import com.example.ialocal.ui.home.HomeViewModel
import com.example.ialocal.ui.models.AiHomeScreen
import com.example.ialocal.ui.models.ModelsScreen
import com.example.ialocal.ui.models.ModelsViewModel
import com.example.ialocal.ui.models.OfflineModelsScreen
import com.example.ialocal.ui.settings.SettingsScreen
import com.example.ialocal.ui.settings.SettingsViewModel
import com.example.ialocal.ui.theme.LocalAiTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as LocalAiApplication).container

        setContent {
            val themeMode by container.themeRepository.themeMode.collectAsStateWithLifecycle(
                initialValue = ThemeMode.SYSTEM,
            )
            LocalAiTheme(themeMode = themeMode) {
                LocalAiApp(
                    container = container,
                    onExitApp = { finish() },
                )
            }
        }
    }
}

@Composable
private fun LocalAiApp(
    container: AppContainer,
    onExitApp: () -> Unit,
) {
    val navController = rememberNavController()
    val currentEntry by navController.currentBackStackEntryAsState()
    val installedModels by container.modelRepository.models.collectAsStateWithLifecycle(initialValue = emptyList())
    val appScope = rememberCoroutineScope()
    var discoveredModels by remember { mutableStateOf<List<CatalogModel>>(emptyList()) }
    var showRecoveryPrompt by remember { mutableStateOf(false) }
    var recoveryRunning by remember { mutableStateOf(false) }
    var recoveryProgress by remember { mutableStateOf<String?>(null) }
    var recoveryMessage by remember { mutableStateOf<String?>(null) }

    val recoveryPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            appScope.launch {
                showRecoveryPrompt = false
                recoveryRunning = true
                recoveryProgress = "Localizando IAs salvas…"
                runCatching {
                    container.modelManager.restorePersistedModels(uri) { progress ->
                        recoveryProgress = progress
                    }
                }.onSuccess { summary ->
                    recoveryMessage = summary.toUserMessage()
                    discoveredModels = emptyList()
                }.onFailure { error ->
                    recoveryMessage = error.message ?: "Não foi possível restaurar as IAs salvas."
                }
                recoveryProgress = null
                recoveryRunning = false
            }
        }
    }

    LaunchedEffect(Unit) {
        if (container.modelRepository.getModels().isEmpty()) {
            discoveredModels = container.modelManager.discoverPersistedCatalogModels()
            showRecoveryPrompt = discoveredModels.isNotEmpty()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        NavHost(navController = navController, startDestination = "ai-home") {
            composable("ai-home") {
                val vm: ModelsViewModel = viewModel(
                    key = "ai-home-models",
                    factory = ModelsViewModel.Factory(
                        container.modelRepository,
                        container.modelManager,
                        container.apiServer,
                        container.apiSettings,
                        container.integrationSelfTest,
                    ),
                )
                val scope = rememberCoroutineScope()
                AiHomeScreen(
                    viewModel = vm,
                    onStartChat = {
                        scope.launch {
                            val conversationId = container.chatRepository.createConversation()
                            navController.navigate("chat/$conversationId")
                        }
                    },
                    onOpenChats = { navController.navigate("home") },
                    onOpenOfflineModels = { navController.navigate("offline-models") },
                    onOpenApi = { navController.navigate("models") },
                    onOpenSettings = { navController.navigate("settings") },
                    onExitApp = onExitApp,
                )
            }

            composable("code-editor") {
                val vm: CodeEditorViewModel = viewModel(
                    key = "code-editor",
                    factory = CodeEditorViewModel.Factory(container.aiGateway),
                )
                CodeEditorScreen(
                    viewModel = vm,
                    onBack = { navController.popBackStack() },
                )
            }

            composable("offline-models") {
                val vm: ModelsViewModel = viewModel(
                    key = "offline-models-catalog",
                    factory = ModelsViewModel.Factory(
                        container.modelRepository,
                        container.modelManager,
                        container.apiServer,
                        container.apiSettings,
                        container.integrationSelfTest,
                    ),
                )
                OfflineModelsScreen(
                    viewModel = vm,
                    onBack = { navController.popBackStack() },
                )
            }

            composable("home") {
                val vm: HomeViewModel = viewModel(factory = HomeViewModel.Factory(container.chatRepository))
                HomeScreen(
                    viewModel = vm,
                    onOpenConversation = { id -> navController.navigate("chat/$id") },
                    onOpenSettings = { navController.navigate("settings") },
                    onOpenModels = {
                        if (!navController.popBackStack("ai-home", inclusive = false)) {
                            navController.navigate("ai-home")
                        }
                    },
                )
            }

            composable(
                route = "chat/{conversationId}",
                arguments = listOf(navArgument("conversationId") { type = NavType.StringType }),
            ) { entry ->
                val id = requireNotNull(entry.arguments?.getString("conversationId"))
                val vm: ChatViewModel = viewModel(
                    key = "chat-$id",
                    factory = ChatViewModel.Factory(
                        conversationId = id,
                        repository = container.chatRepository,
                        aiGateway = container.aiGateway,
                        attachmentImporter = container.attachmentImporter,
                        attachmentProcessor = container.attachmentProcessor,
                        modelRepository = container.modelRepository,
                    ),
                )
                ChatWithDeepThinkScreen(
                    viewModel = vm,
                    onOpenConversation = { targetId ->
                        if (targetId != id) {
                            navController.navigate("chat/$targetId") {
                                popUpTo("home") { inclusive = false }
                            }
                        }
                    },
                    onOpenHistory = {
                        if (!navController.popBackStack("home", inclusive = false)) {
                            navController.navigate("home")
                        }
                    },
                    onOpenModels = {
                        if (!navController.popBackStack("ai-home", inclusive = false)) {
                            navController.navigate("ai-home") {
                                launchSingleTop = true
                            }
                        }
                    },
                    onOpenSettings = { navController.navigate("settings") },
                )
            }

            composable("models") {
                val vm: ModelsViewModel = viewModel(
                    key = "models-settings",
                    factory = ModelsViewModel.Factory(
                        container.modelRepository,
                        container.modelManager,
                        container.apiServer,
                        container.apiSettings,
                        container.integrationSelfTest,
                    ),
                )
                ModelsScreen(viewModel = vm, onBack = { navController.popBackStack() })
            }

            composable("settings") {
                val vm: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory(container.themeRepository))
                SettingsScreen(viewModel = vm, onBack = { navController.popBackStack() })
            }
        }

        if (currentEntry?.destination?.route == "ai-home") {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(20.dp),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (installedModels.isEmpty()) {
                    ExtendedFloatingActionButton(
                        onClick = { recoveryPicker.launch(null) },
                        icon = { Icon(Icons.Default.Restore, contentDescription = null) },
                        text = { Text("Restaurar IAs") },
                    )
                }
                FloatingActionButton(onClick = { navController.navigate("code-editor") }) {
                    Icon(Icons.Default.Code, contentDescription = "Abrir editor de código")
                }
            }
        }
    }

    if (showRecoveryPrompt && discoveredModels.isNotEmpty() && !recoveryRunning) {
        AlertDialog(
            onDismissRequest = { showRecoveryPrompt = false },
            title = { Text("IAs salvas encontradas") },
            text = {
                Text(
                    "Encontramos ${discoveredModels.size} IA(s) em Downloads/IAs Offline. " +
                        "Como o aplicativo foi reinstalado, o Android precisa que você autorize essa pasta uma vez. " +
                        "Os modelos serão validados e restaurados sem baixar novamente.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showRecoveryPrompt = false
                    recoveryPicker.launch(null)
                }) {
                    Text("Autorizar e restaurar")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRecoveryPrompt = false }) {
                    Text("Agora não")
                }
            },
        )
    }

    if (recoveryRunning) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Restaurando IAs") },
            text = {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator()
                    Text(recoveryProgress ?: "Verificando arquivos salvos…")
                }
            },
            confirmButton = {},
        )
    }

    recoveryMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { recoveryMessage = null },
            title = { Text("Restauração de IAs") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { recoveryMessage = null }) {
                    Text("OK")
                }
            },
        )
    }
}
