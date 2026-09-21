package com.example.ialocal

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.ialocal.data.ModelVerificationStatus
import com.example.ialocal.data.ThemeMode
import com.example.ialocal.models.AutomaticModelScanMode
import com.example.ialocal.ui.chat.ChatViewModel
import com.example.ialocal.ui.chat.ChatWithDeepThinkScreen
import com.example.ialocal.ui.codeeditor.CodeEditorScreen
import com.example.ialocal.ui.codeeditor.CodeEditorViewModel
import com.example.ialocal.ui.home.HomeScreen
import com.example.ialocal.ui.home.HomeViewModel
import com.example.ialocal.ui.models.GroupedOfflineModelsScreen
import com.example.ialocal.ui.models.HtmlAiHomeScreen
import com.example.ialocal.ui.models.ModelsScreen
import com.example.ialocal.ui.models.ModelsViewModel
import com.example.ialocal.ui.models.MyAisScreen
import com.example.ialocal.ui.settings.SettingsScreen
import com.example.ialocal.ui.settings.SettingsViewModel
import com.example.ialocal.ui.theme.LocalAiTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var container: AppContainer
    private var pendingStorageScanMode = AutomaticModelScanMode.FULL

    private val storageAccessLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        if (::container.isInitialized && Environment.isExternalStorageManager()) {
            container.automaticModelImporter.startScan(pendingStorageScanMode)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        container = (application as LocalAiApplication).container

        setContent {
            val themeMode by container.themeRepository.themeMode.collectAsStateWithLifecycle(
                initialValue = ThemeMode.SYSTEM,
            )
            LocalAiTheme(themeMode = themeMode) {
                LocalAiApp(
                    container = container,
                    onScanStorage = { requestStorageScan(AutomaticModelScanMode.FULL) },
                    onExitApp = { finish() },
                )
            }
        }

        runInitialStorageScanOnce()
    }

    private fun runInitialStorageScanOnce() {
        val preferences = getSharedPreferences(STORAGE_SCAN_PREFS, MODE_PRIVATE)
        if (preferences.getBoolean(KEY_INITIAL_STORAGE_SCAN_HANDLED, false)) return

        preferences.edit().putBoolean(KEY_INITIAL_STORAGE_SCAN_HANDLED, true).apply()
        requestStorageScan(AutomaticModelScanMode.QUICK)
    }

    private fun requestStorageScan(mode: AutomaticModelScanMode) {
        if (!::container.isInitialized || container.automaticModelImporter.progress.value.isRunning) return
        pendingStorageScanMode = mode

        if (Environment.isExternalStorageManager()) {
            container.automaticModelImporter.startScan(mode)
            return
        }

        val appAccessIntent = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:$packageName"),
        )
        runCatching { storageAccessLauncher.launch(appAccessIntent) }
            .onFailure {
                runCatching {
                    storageAccessLauncher.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }
            }
    }

    companion object {
        private const val STORAGE_SCAN_PREFS = "automatic_model_storage_access"
        private const val KEY_INITIAL_STORAGE_SCAN_HANDLED = "initial_scan_handled"
    }
}

@Composable
private fun LocalAiApp(
    container: AppContainer,
    onScanStorage: () -> Unit,
    onExitApp: () -> Unit,
) {
    val navController = rememberNavController()

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
            HtmlAiHomeScreen(
                viewModel = vm,
                onStartChat = {
                    scope.launch {
                        val conversationId = container.chatRepository.createConversation()
                        navController.navigate("chat/$conversationId")
                    }
                },
                onOpenChats = { navController.navigate("my-ais") },
                onOpenOfflineModels = { navController.navigate("offline-models") },
                onOpenApi = { navController.navigate("models") },
                onOpenSettings = { navController.navigate("settings") },
                onRestoreModels = {},
                onOpenCodeEditor = { navController.navigate("code-editor") },
                onExitApp = onExitApp,
            )
        }

        composable("my-ais") {
            val vm: ModelsViewModel = viewModel(
                key = "my-ais-models",
                factory = ModelsViewModel.Factory(
                    container.modelRepository,
                    container.modelManager,
                    container.apiServer,
                    container.apiSettings,
                    container.integrationSelfTest,
                ),
            )
            val importProgress by container.automaticModelImporter.progress.collectAsStateWithLifecycle()
            val scope = rememberCoroutineScope()
            MyAisScreen(
                viewModel = vm,
                importProgress = importProgress,
                onBack = { navController.popBackStack() },
                onOpenChat = { modelId ->
                    scope.launch {
                        runCatching {
                            val model = requireNotNull(container.modelRepository.getModel(modelId)) {
                                "IA não encontrada."
                            }
                            if (model.verificationStatus == ModelVerificationStatus.VERIFIED.name) {
                                container.modelManager.load(modelId)
                            } else {
                                container.modelManager.retryVerification(modelId)
                                container.modelManager.load(modelId)
                            }
                            container.modelRepository.getAgentForModel(modelId)?.let { agent ->
                                container.modelRepository.setDefaultAgent(agent.id)
                            }
                            container.chatRepository.createConversation()
                        }.onSuccess { conversationId ->
                            navController.navigate("chat/$conversationId")
                        }.onFailure { error ->
                            container.diagnostics.error(
                                "MY_AI_CHAT",
                                "Falha ao abrir o chat com a IA selecionada.",
                                error,
                            )
                        }
                    }
                },
            )
        }

        composable("code-editor") {
            val vm: CodeEditorViewModel = viewModel(
                key = "code-editor",
                factory = CodeEditorViewModel.Factory(
                    container.aiGateway,
                    container.codeLanguagePacks,
                    container.modelRepository,
                    container.codeEditSessions,
                ),
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
            GroupedOfflineModelsScreen(
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
            val vm: SettingsViewModel = viewModel(
                factory = SettingsViewModel.Factory(
                    container.themeRepository,
                    container.codeLanguagePacks,
                ),
            )
            val importProgress by container.automaticModelImporter.progress.collectAsStateWithLifecycle()
            SettingsScreen(
                viewModel = vm,
                importProgress = importProgress,
                onScanStorage = onScanStorage,
                onBack = { navController.popBackStack() },
            )
        }
    }
}
