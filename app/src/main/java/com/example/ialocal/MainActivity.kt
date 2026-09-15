package com.example.ialocal

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import com.example.ialocal.data.ThemeMode
import com.example.ialocal.ui.chat.ChatViewModel
import com.example.ialocal.ui.chat.ChatWithDeepThinkScreen
import com.example.ialocal.ui.home.HomeScreen
import com.example.ialocal.ui.home.HomeViewModel
import com.example.ialocal.ui.models.AiHomeScreen
import com.example.ialocal.ui.models.ModelsScreen
import com.example.ialocal.ui.models.ModelsViewModel
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
                onOpenApi = { navController.navigate("models") },
                onOpenSettings = { navController.navigate("settings") },
                onExitApp = onExitApp,
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
                onOpenModels = { navController.navigate("models") },
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
}
