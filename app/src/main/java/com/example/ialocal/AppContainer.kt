package com.example.ialocal

import android.content.Context
import com.example.ialocal.agent.AiOrchestrator
import com.example.ialocal.agent.tools.AgentToolRegistry
import com.example.ialocal.ai.AiGateway
import com.example.ialocal.api.ApiSettingsRepository
import com.example.ialocal.api.LocalApiAiGateway
import com.example.ialocal.api.LocalApiServer
import com.example.ialocal.data.AppDatabase
import com.example.ialocal.data.ChatRepository
import com.example.ialocal.data.ThemeRepository
import com.example.ialocal.diagnostics.AiEventLogger
import com.example.ialocal.diagnostics.IntegrationSelfTest
import com.example.ialocal.files.AttachmentContentProcessor
import com.example.ialocal.files.AttachmentContextBuilder
import com.example.ialocal.files.AttachmentImporter
import com.example.ialocal.models.AutomaticModelImporter
import com.example.ialocal.models.DeepThinkStore
import com.example.ialocal.models.ModelDownloader
import com.example.ialocal.models.ModelManager
import com.example.ialocal.models.ModelRepository
import com.example.ialocal.runtime.LlamaCppRuntime
import com.example.ialocal.runtime.ModelRuntime
import com.example.ialocal.ui.codeeditor.CodeEditSessionStore
import com.example.ialocal.ui.codeeditor.CodeLanguagePackRepository

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    private val database = AppDatabase.create(appContext)

    val diagnostics = AiEventLogger(appContext)
    val chatRepository = ChatRepository(database.chatDao())
    val themeRepository = ThemeRepository(appContext)
    val codeLanguagePacks = CodeLanguagePackRepository(appContext)
    val codeEditSessions = CodeEditSessionStore()
    val attachmentImporter = AttachmentImporter(appContext)
    val attachmentProcessor = AttachmentContentProcessor(appContext)
    private val attachmentContextBuilder = AttachmentContextBuilder()

    val modelRepository = ModelRepository(appContext, database.modelDao(), logger = diagnostics)
    val modelRuntime: ModelRuntime = LlamaCppRuntime(appContext, diagnostics)
    val modelDownloader = ModelDownloader(appContext, logger = diagnostics)
    val modelManager = ModelManager(appContext, modelRepository, modelRuntime, modelDownloader, diagnostics)
    val automaticModelImporter = AutomaticModelImporter(appContext, modelRepository, modelManager, diagnostics)
    val agentTools = AgentToolRegistry(chatRepository, codeEditSessions, diagnostics)
    val deepThinkStore = DeepThinkStore(appContext)
    val orchestrator = AiOrchestrator(modelRepository, modelRuntime, agentTools, deepThinkStore)

    val apiSettings = ApiSettingsRepository(appContext)
    val apiServer = LocalApiServer(
        apiSettings,
        modelRepository,
        modelManager,
        orchestrator,
        diagnostics,
    )
    val integrationSelfTest = IntegrationSelfTest(apiServer, apiSettings, diagnostics)
    val aiGateway: AiGateway = LocalApiAiGateway(
        apiServer,
        apiSettings,
        attachmentContextBuilder,
        diagnostics,
    )

    init {
        apiServer.start()
    }
}
