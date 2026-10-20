package com.example.ialocal

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.withResumed
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.ialocal.ads.AdsManager
import com.example.ialocal.data.ThemeMode
import com.example.ialocal.diagnostics.LastExitReport
import com.example.ialocal.diagnostics.LastExitReporter
import com.example.ialocal.knowledge.WikipediaPackState
import com.example.ialocal.knowledge.WikipediaPackStatus
import com.example.ialocal.models.AutomaticModelImportEvent
import com.example.ialocal.models.AutomaticModelScanMode
import com.example.ialocal.models.PublicModelDownloads
import com.example.ialocal.runtime.RuntimeStatus
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
import com.example.ialocal.ui.settings.AiProfilesScreen
import com.example.ialocal.ui.settings.AiProfilesViewModel
import com.example.ialocal.ui.settings.AppearanceScreen
import com.example.ialocal.ui.settings.CompilersScreen
import com.example.ialocal.ui.settings.SettingsScreen
import com.example.ialocal.ui.settings.SettingsViewModel
import com.example.ialocal.ui.settings.StorageScanScreen
import com.example.ialocal.ui.settings.WikipediaScreen
import com.example.ialocal.ui.theme.LocalAiTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private lateinit var container: AppContainer
    private lateinit var adsManager: AdsManager
    private var pendingStorageAction: (() -> Unit)? = null
    private var lastExitReport by mutableStateOf<LastExitReport?>(null)

    // Coming back from a screen the app opened itself (a file picker, Android settings, an ad's
    // page) is not a return to the app, so it never shows the app open ad.
    private var returningFromOwnActivity = false
    private var skipReplayedForeground = false
    private val appForegroundObserver = LifecycleEventObserver { _, event ->
        if (event != Lifecycle.Event.ON_START) return@LifecycleEventObserver
        when {
            skipReplayedForeground -> skipReplayedForeground = false
            !returningFromOwnActivity -> {
                val status = container.modelManager.runtimeState.value.status
                adsManager.onAppForeground(this, aiIdle = status == RuntimeStatus.IDLE || status == RuntimeStatus.ERROR)
            }
        }
    }

    // ComponentActivity owns the Activity Result registry; this activity does not use Fragment.
    @SuppressLint("InvalidFragmentVersionForActivityResult")
    private val storageAccessLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        val action = pendingStorageAction
        pendingStorageAction = null
        if (Environment.isExternalStorageManager()) {
            Thread({ runCatching { PublicModelDownloads(this).ensureFolder() } }, "ia-offline-shared-folder").start()
            action?.invoke()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        container = (application as LocalAiApplication).container
        adsManager = container.adsManager
        val processLifecycle = ProcessLifecycleOwner.get().lifecycle
        // A recreated activity registers while the app is in the foreground; that replays ON_START.
        skipReplayedForeground = processLifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        processLifecycle.addObserver(appForegroundObserver)
        lifecycleScope.launch(Dispatchers.IO) {
            container.modelRepository.getModels().forEach { model ->
                runCatching { container.modelRepository.ensureStarterProfiles(model.id) }
            }
        }

        setContent {
            val themeMode by container.themeRepository.themeMode.collectAsStateWithLifecycle(
                initialValue = ThemeMode.SYSTEM,
            )
            LocalAiTheme(themeMode = themeMode) {
                LocalAiApp(
                    container = container,
                    adsManager = adsManager,
                    onScanStorage = { requestStorageScan(AutomaticModelScanMode.FULL) },
                    onEnsureStorageAccess = ::requestStorageAccess,
                    onPrivacyOptions = { adsManager.showPrivacyOptions(this) },
                    onDownloadStarted = ::showDownloadAd,
                    onExitApp = { finish() },
                )
                lastExitReport?.let { report ->
                    AlertDialog(
                        onDismissRequest = { lastExitReport = null },
                        title = { Text("O app foi fechado inesperadamente") },
                        text = {
                            Text(
                                report.summary + "\n\nSe isso aconteceu ao usar uma IA, copie os detalhes e envie para análise.",
                            )
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    val clipboard = getSystemService(ClipboardManager::class.java)
                                    clipboard?.setPrimaryClip(ClipData.newPlainText("Detalhes do fechamento", report.details))
                                    Toast.makeText(this@MainActivity, "Detalhes copiados.", Toast.LENGTH_SHORT).show()
                                    lastExitReport = null
                                },
                            ) { Text("Copiar detalhes") }
                        },
                        dismissButton = {
                            TextButton(onClick = { lastExitReport = null }) { Text("Fechar") }
                        },
                    )
                }
            }
        }

        if (savedInstanceState == null) {
            lifecycleScope.launch(Dispatchers.IO) {
                val report = LastExitReporter(applicationContext, container.diagnostics, container.modelCrashGuard)
                    .takeUnreportedExit()
                if (report != null) withContext(Dispatchers.Main) { lastExitReport = report }
            }
        }

        showStorageImportNotices()
        // Downloads interrupted by the app being killed pick up from their partial files.
        container.modelManager.resumeQueueAfterRestart()
        runInitialStorageScanOnce()
        adsManager.start(this)
    }

    override fun onResume() {
        super.onResume()
        returningFromOwnActivity = false
    }

    override fun onDestroy() {
        ProcessLifecycleOwner.get().lifecycle.removeObserver(appForegroundObserver)
        super.onDestroy()
    }

    // Every activity the app opens goes through here, including the Activity Result API's launches.
    @Deprecated("Deprecated in ComponentActivity")
    override fun startActivityForResult(intent: Intent, requestCode: Int, options: Bundle?) {
        returningFromOwnActivity = true
        @Suppress("DEPRECATION")
        super.startActivityForResult(intent, requestCode, options)
    }

    /** Tells the user, on any screen, when an AI is found in storage and when it is imported. */
    private fun showStorageImportNotices() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                container.automaticModelImporter.events.collect { event ->
                    val message = when (event) {
                        is AutomaticModelImportEvent.Found -> "IA encontrada no armazenamento: ${event.modelName}"
                        is AutomaticModelImportEvent.Importing -> "Importando ${event.modelName} para o app..."
                    }
                    Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * Full-screen ad after a download starts. Skipped while an answer is being generated: the ad's
     * video player would take RAM from the model and could get its process stopped mid-answer.
     */
    private fun showDownloadAd() {
        if (container.chatGenerationManager.activeGenerations.value > 0) return
        adsManager.showDownloadAd(this)
    }

    private fun runInitialStorageScanOnce() {
        val preferences = getSharedPreferences(STORAGE_SCAN_PREFS, MODE_PRIVATE)
        if (preferences.getBoolean(KEY_INITIAL_STORAGE_SCAN_HANDLED, false)) return

        preferences.edit().putBoolean(KEY_INITIAL_STORAGE_SCAN_HANDLED, true).apply()
        requestStorageScan(AutomaticModelScanMode.QUICK)
    }

    private fun requestStorageScan(mode: AutomaticModelScanMode) {
        if (!::container.isInitialized || container.automaticModelImporter.progress.value.isRunning) return
        requestStorageAccess { container.automaticModelImporter.startScan(mode) }
    }

    private fun requestStorageAccess(onGranted: () -> Unit) {
        if (Environment.isExternalStorageManager()) {
            onGranted()
            return
        }

        pendingStorageAction = onGranted
        val appAccessIntent = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:$packageName"),
        )
        runCatching { storageAccessLauncher.launch(appAccessIntent) }
            .onFailure {
                runCatching {
                    storageAccessLauncher.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }.onFailure { pendingStorageAction = null }
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
    adsManager: AdsManager,
    onScanStorage: () -> Unit,
    onEnsureStorageAccess: (() -> Unit) -> Unit,
    onPrivacyOptions: () -> Unit,
    onDownloadStarted: () -> Unit,
    onExitApp: () -> Unit,
) {
    val navController = rememberNavController()

    // Short fades: the default 700 ms cross-fade kept both screens drawing for most of a second,
    // so opening a screen felt slow and stuttered on heavy screens like the catalog.
    NavHost(
        navController = navController,
        startDestination = "ai-home",
        enterTransition = { fadeIn(tween(SCREEN_FADE_MS)) },
        exitTransition = { fadeOut(tween(SCREEN_FADE_MS)) },
    ) {
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
                adsEnabled = adsManager.adsReady,
                onStartChat = {
                    scope.launch {
                        val conversationId = container.chatRepository.createConversation()
                        navController.navigate("chat/$conversationId")
                    }
                },
                onStartChatWith = { modelId ->
                    scope.launch {
                        val conversationId = container.chatRepository.createConversation()
                        navController.navigate("chat/$conversationId?modelId=$modelId")
                    }
                },
                onOpenChats = { navController.navigate("my-ais") },
                onOpenOfflineModels = { navController.navigate("offline-models") },
                onOpenApi = { navController.navigate("models") },
                onOpenSettings = { navController.navigate("settings") },
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
                adsEnabled = adsManager.adsReady,
                onScanStorage = onScanStorage,
                onEnsureStorageAccess = onEnsureStorageAccess,
                importProgress = importProgress,
                onBack = { navController.popBackStack() },
                onOpenChat = { modelId ->
                    scope.launch {
                        val conversationId = container.chatRepository.createConversation()
                        navController.navigate("chat/$conversationId?modelId=$modelId")
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
                adsEnabled = adsManager.adsReady,
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
            PreloadDownloadAd(adsManager)
            GroupedOfflineModelsScreen(
                viewModel = vm,
                adsEnabled = adsManager.adsReady,
                onEnsureStorageAccess = onEnsureStorageAccess,
                onDownloadStarted = onDownloadStarted,
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
                adsEnabled = adsManager.adsReady,
            )
        }

        composable(
            route = "chat/{conversationId}?modelId={modelId}",
            arguments = listOf(
                navArgument("conversationId") { type = NavType.StringType },
                navArgument("modelId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { entry ->
            val id = requireNotNull(entry.arguments?.getString("conversationId"))
            val initialModelId = entry.arguments?.getString("modelId")
            val vm: ChatViewModel = viewModel(
                key = "chat-$id",
                factory = ChatViewModel.Factory(
                    conversationId = id,
                    initialModelId = initialModelId,
                    repository = container.chatRepository,
                    generationManager = container.chatGenerationManager,
                    attachmentImporter = container.attachmentImporter,
                    attachmentProcessor = container.attachmentProcessor,
                    modelRepository = container.modelRepository,
                ),
            )
            ChatWithDeepThinkScreen(
                viewModel = vm,
                adsEnabled = adsManager.adsReady,
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
            PreloadDownloadAd(adsManager)
            ModelsScreen(
                viewModel = vm,
                onEnsureStorageAccess = onEnsureStorageAccess,
                onDownloadStarted = onDownloadStarted,
                onBack = { navController.popBackStack() },
            )
        }

        composable("settings") {
            val vm: SettingsViewModel = viewModel(
                factory = SettingsViewModel.Factory(
                    container.themeRepository,
                    container.codeLanguagePacks,
                ),
            )
            val importProgress by container.automaticModelImporter.progress.collectAsStateWithLifecycle()
            val selectedProfile by container.modelRepository.selectedProfile.collectAsStateWithLifecycle()
            val profiles by container.modelRepository.profiles.collectAsStateWithLifecycle()
            val wikipediaStates by container.offlineWikipedia.states.collectAsStateWithLifecycle()
            SettingsScreen(
                viewModel = vm,
                importProgress = importProgress,
                selectedProfile = selectedProfile,
                customProfileCount = profiles.count { it.isCustom },
                privacyOptionsRequired = adsManager.privacyOptionsRequired,
                onPrivacyOptions = onPrivacyOptions,
                wikipediaSummary = wikipediaSummary(wikipediaStates),
                onOpenAppearance = { navController.navigate("appearance") },
                onOpenAiProfiles = { navController.navigate("ai-profiles") },
                onOpenStorageScan = { navController.navigate("storage-scan") },
                onOpenCompilers = { navController.navigate("compilers") },
                onOpenWikipedia = { navController.navigate("wikipedia") },
                adsEnabled = adsManager.adsReady,
                onBack = { navController.popBackStack() },
            )
        }

        composable("appearance") {
            val vm: SettingsViewModel = viewModel(
                factory = SettingsViewModel.Factory(
                    container.themeRepository,
                    container.codeLanguagePacks,
                ),
            )
            AppearanceScreen(
                viewModel = vm,
                onBack = { navController.popBackStack() },
            )
        }

        composable("ai-profiles") {
            val vm: AiProfilesViewModel = viewModel(
                factory = AiProfilesViewModel.Factory(container.modelRepository),
            )
            AiProfilesScreen(
                viewModel = vm,
                onBack = { navController.popBackStack() },
            )
        }

        composable("storage-scan") {
            val importProgress by container.automaticModelImporter.progress.collectAsStateWithLifecycle()
            StorageScanScreen(
                importProgress = importProgress,
                onScanStorage = onScanStorage,
                onCancelScan = container.automaticModelImporter::cancelScan,
                onBack = { navController.popBackStack() },
            )
        }

        composable("compilers") {
            val vm: SettingsViewModel = viewModel(
                factory = SettingsViewModel.Factory(
                    container.themeRepository,
                    container.codeLanguagePacks,
                ),
            )
            CompilersScreen(
                viewModel = vm,
                onBack = { navController.popBackStack() },
            )
        }

        composable("wikipedia") {
            PreloadDownloadAd(adsManager)
            WikipediaScreen(
                wikipedia = container.offlineWikipedia,
                onDownloadStarted = onDownloadStarted,
                onBack = { navController.popBackStack() },
            )
        }
    }
}

private const val SCREEN_FADE_MS = 220

/**
 * Loads the download ad ahead of time, once the screen has finished opening: loading it during the
 * transition made the transition stutter. A screen is resumed only when its transition ends.
 */
@Composable
private fun PreloadDownloadAd(adsManager: AdsManager) {
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(adsManager.adsReady, lifecycleOwner) {
        lifecycleOwner.withResumed { adsManager.preloadDownloadAd() }
    }
}

private fun wikipediaSummary(states: List<WikipediaPackState>): String {
    val installed = states.filter { it.status == WikipediaPackStatus.INSTALLED }
    return when {
        states.any { it.busy } -> "Baixando a Wikipédia…"
        installed.size == 1 -> "Instalada: ${installed.single().pack.title}"
        installed.size > 1 -> "${installed.size} edições instaladas"
        else -> "Enciclopédia para os agentes consultarem sem internet"
    }
}
