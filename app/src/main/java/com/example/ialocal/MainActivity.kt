package com.example.ialocal

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.ialocal.data.ModelVerificationStatus
import com.example.ialocal.data.ThemeMode
import com.example.ialocal.ui.chat.ChatViewModel
import com.example.ialocal.ui.chat.ChatWithDeepThinkScreen
import com.example.ialocal.ui.codeeditor.CodeEditorScreen
import com.example.ialocal.ui.codeeditor.CodeEditorViewModel
import com.example.ialocal.ui.home.HomeScreen
import com.example.ialocal.ui.home.HomeViewModel
import com.example.ialocal.ui.models.HtmlAiHomeScreen
import com.example.ialocal.ui.models.ModelsScreen
import com.example.ialocal.ui.models.ModelsViewModel
import com.example.ialocal.ui.models.MyAisScreen
import com.example.ialocal.ui.models.OfflineModelsScreen
import com.example.ialocal.ui.settings.SettingsScreen
import com.example.ialocal.ui.settings.SettingsViewModel
import com.example.ialocal.ui.theme.LocalAiTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var container: AppContainer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        container = (application as LocalAiApplication).container
        requestAutomaticStorageScanAccessIfNeeded()

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

    override fun onResume() {
        super.onResume()
        if (::container.isInitialized && Environment.isExternalStorageManager()) {
            lifecycleScope.launch {
                runCatching { container.automaticModelImporter.scanAndImport() }
                    .onFailure { error ->
                        container.diagnostics.error(
                            "AUTO_MODEL_SCAN",
                            "Falha na varredura automática do armazenamento compartilhado.",
                            error,
                        )
                    }
            }
        }
    }

    private fun requestAutomaticStorageScanAccessIfNeeded() {
        if (Environment.isExternalStorageManager()) return

        val preferences = getSharedPreferences(STORAGE_SCAN_PREFS, MODE_PRIVATE)
        if (preferences.getBoolean(KEY_STORAGE_ACCESS_REQUESTED, false)) return
        preferences.edit().putBoolean(KEY_STORAGE_ACCESS_REQUESTED, true).apply()

        val appAccessIntent = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:$packageName"),
        )
        runCatching { startActivity(appAccessIntent) }
            .onFailure {
                runCatching { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
            }
    }

    companion object {
        private const val STORAGE_SCAN_PREFS = "automatic_model_storage_access"
        private const val KEY_STORAGE_ACCESS_REQUESTED = "requested"
    }
}

@Composable
private fun LocalAiApp(
    container: AppContainer,
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
            val scope = rememberCoroutineScope()
            MyAisScreen(
                viewModel = vm,
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
            val vm: SettingsViewModel = viewModel(
                factory = SettingsViewModel.Factory(
                    container.themeRepository,
                    container.codeLanguagePacks,
                ),
            )
            SettingsScreen(viewModel = vm, onBack = { navController.popBackStack() })
        }
    }
}
