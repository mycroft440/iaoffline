package com.example.ialocal

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.ialocal.data.ThemeMode
import com.example.ialocal.ui.catalog.CatalogScreen
import com.example.ialocal.ui.catalog.CatalogViewModel
import com.example.ialocal.ui.chat.ChatScreen
import com.example.ialocal.ui.chat.ChatViewModel
import com.example.ialocal.ui.history.ChatFilesScreen
import com.example.ialocal.ui.history.ChatFilesViewModel
import com.example.ialocal.ui.history.ChatSearchScreen
import com.example.ialocal.ui.history.ChatSearchViewModel
import com.example.ialocal.ui.home.HomeScreen
import com.example.ialocal.ui.home.HomeViewModel
import com.example.ialocal.ui.modelconfig.ModelConfigScreen
import com.example.ialocal.ui.modelconfig.ModelConfigViewModel
import com.example.ialocal.ui.models.ModelsScreen
import com.example.ialocal.ui.models.ModelsViewModel
import com.example.ialocal.ui.settings.SettingsScreen
import com.example.ialocal.ui.settings.SettingsViewModel
import com.example.ialocal.ui.theme.LocalAiTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as LocalAiApplication).container

        setContent {
            val themeMode by container.themeRepository.themeMode.collectAsStateWithLifecycle(
                initialValue = ThemeMode.SYSTEM,
            )
            LocalAiTheme(themeMode = themeMode) { LocalAiApp(container) }
        }
    }
}

@Composable
private fun LocalAiApp(container: AppContainer) {
    val navController = rememberNavController()

    fun openChat(id: String) {
        container.chatSession.setActiveConversation(id)
        navController.navigate("chat/$id") {
            launchSingleTop = true
        }
    }

    NavHost(navController = navController, startDestination = "restore") {
        composable("restore") {
            LaunchedEffect(Unit) {
                val savedId = container.chatSession.activeConversationId
                val validId = savedId?.takeIf { container.chatRepository.getConversation(it) != null }
                if (savedId != null && validId == null) container.chatSession.clearActiveConversation()
                val target = validId?.let { "chat/$it" } ?: "home"
                navController.navigate(target) {
                    popUpTo("restore") { inclusive = true }
                    launchSingleTop = true
                }
            }
        }

        composable("home") {
            val vm: HomeViewModel = viewModel(factory = HomeViewModel.Factory(container.chatRepository))
            HomeScreen(
                viewModel = vm,
                onOpenConversation = ::openChat,
                onOpenSettings = { navController.navigate("settings") },
                onOpenModels = { navController.navigate("models") },
                onOpenCatalog = { navController.navigate("catalog") },
            )
        }

        composable("catalog") {
            val vm: CatalogViewModel = viewModel(
                factory = CatalogViewModel.Factory(
                    container.modelRepository,
                    container.modelManager,
                    container.modelDownloads,
                )
            )
            CatalogScreen(
                viewModel = vm,
                onBack = { navController.popBackStack() },
                onOpenModels = { navController.navigate("models") },
                onOpenModelConfig = { modelId -> navController.navigate("model-config/$modelId") },
            )
        }

        composable(
            route = "model-config/{modelId}",
            arguments = listOf(navArgument("modelId") { type = NavType.StringType }),
        ) { entry ->
            val modelId = requireNotNull(entry.arguments?.getString("modelId"))
            val vm: ModelConfigViewModel = viewModel(
                key = "model-config-$modelId",
                factory = ModelConfigViewModel.Factory(
                    modelId = modelId,
                    models = container.modelRepository,
                    manager = container.modelManager,
                    chats = container.chatRepository,
                ),
            )
            ModelConfigScreen(
                viewModel = vm,
                onBack = { navController.popBackStack() },
                onStartChat = ::openChat,
            )
        }

        composable(
            route = "chat/{conversationId}",
            arguments = listOf(navArgument("conversationId") { type = NavType.StringType }),
        ) { entry ->
            val id = requireNotNull(entry.arguments?.getString("conversationId"))
            LaunchedEffect(id) { container.chatSession.setActiveConversation(id) }
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
            ChatScreen(
                viewModel = vm,
                onOpenCatalog = { navController.navigate("catalog") },
                onConfigureModel = { modelId -> navController.navigate("model-config/$modelId") },
                onOpenFiles = { navController.navigate("chat-files") },
                onSearchChats = { navController.navigate("chat-search") },
                onAllConversations = {
                    container.chatSession.clearActiveConversation()
                    navController.navigate("home") {
                        launchSingleTop = true
                    }
                },
            )
        }

        composable("chat-files") {
            val vm: ChatFilesViewModel = viewModel(factory = ChatFilesViewModel.Factory(container.chatRepository))
            ChatFilesScreen(
                viewModel = vm,
                onBack = { navController.popBackStack() },
                onOpenConversation = ::openChat,
            )
        }

        composable("chat-search") {
            val vm: ChatSearchViewModel = viewModel(factory = ChatSearchViewModel.Factory(container.chatRepository))
            ChatSearchScreen(
                viewModel = vm,
                onBack = { navController.popBackStack() },
                onOpenConversation = ::openChat,
            )
        }

        composable("models") {
            val vm: ModelsViewModel = viewModel(
                factory = ModelsViewModel.Factory(
                    container.modelRepository,
                    container.modelManager,
                    container.apiServer,
                    container.apiSettings,
                    container.integrationSelfTest,
                )
            )
            ModelsScreen(viewModel = vm, onBack = { navController.popBackStack() })
        }

        composable("settings") {
            val vm: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory(container.themeRepository))
            SettingsScreen(viewModel = vm, onBack = { navController.popBackStack() })
        }
    }
}
