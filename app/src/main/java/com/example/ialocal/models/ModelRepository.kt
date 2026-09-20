package com.example.ialocal.models

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.example.ialocal.data.AgentEntity
import com.example.ialocal.data.AiModelEntity
import com.example.ialocal.data.ModelDao
import com.example.ialocal.data.ModelVerificationStatus
import com.example.ialocal.diagnostics.AiEventLogger
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

class ModelRepository(
    private val context: Context,
    private val dao: ModelDao,
    private val inspector: GgufInspector = GgufInspector(),
    private val compatibilityChecker: DeviceCompatibilityChecker = DeviceCompatibilityChecker(context),
    private val logger: AiEventLogger? = null,
) {
    val models: Flow<List<AiModelEntity>> = dao.observeModels()
    val agents: Flow<List<AgentEntity>> = dao.observeAgents()

    private val agentUsagePreferences = context.getSharedPreferences(AGENT_USAGE_PREFS, Context.MODE_PRIVATE)
    private val profilePreferences = context.getSharedPreferences(PROFILE_PREFS, Context.MODE_PRIVATE)
    private val _agentUsageCounts = MutableStateFlow(loadAgentUsageCounts())
    val agentUsageCounts: StateFlow<Map<String, Int>> = _agentUsageCounts.asStateFlow()

    suspend fun inspectForImport(uri: Uri): ModelImportPreview = withContext(Dispatchers.IO) {
        logger?.info("IMPORT", "Validando arquivo selecionado antes da cópia")
        val (displayName, sourceSize) = sourceInfo(uri)
        require(displayName.lowercase().endsWith(".gguf")) {
            "Nesta primeira versão, importe um modelo no formato .gguf."
        }

        val metadata = context.contentResolver.openInputStream(uri)?.use(inspector::inspect)
            ?: throw IllegalArgumentException("Não foi possível abrir o modelo selecionado.")
        require(metadata.tensorCount > 0) { "O GGUF não contém tensors de modelo." }
        val compatibility = compatibilityChecker.check(sourceSize)
        compatibility.warnings.forEach { logger?.info("MODEL_COMPATIBILITY", it) }
        require(compatibility.canStore) { "Não há espaço livre suficiente para importar este modelo." }

        logger?.info(
            "GGUF_METADATA",
            "GGUF v${metadata.version}; arch=${metadata.architecture ?: "?"}; tensors=${metadata.tensorCount}; " +
                "ctx=${metadata.contextLength ?: "?"}; chatTemplate=${metadata.hasChatTemplate}"
        )
        ModelImportPreview(uri, displayName, sourceSize, metadata, compatibility)
    }

    suspend fun importGguf(preview: ModelImportPreview): AiModelEntity = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val modelDir = File(context.filesDir, "models/$id").apply { mkdirs() }
        val destination = File(modelDir, "model.gguf")

        try {
            logger?.info("MODEL_COPY", "Copiando ${preview.displayName} para armazenamento privado")
            context.contentResolver.openInputStream(preview.uri)?.use { input ->
                FileOutputStream(destination).use { output -> input.copyTo(output, 1024 * 1024) }
            } ?: error("Não foi possível abrir o modelo selecionado.")

            if (preview.sourceSizeBytes != null && preview.sourceSizeBytes > 0 && destination.length() != preview.sourceSizeBytes) {
                throw IllegalStateException(
                    "A cópia do modelo ficou incompleta (${destination.length()} de ${preview.sourceSizeBytes} bytes)."
                )
            }

            registerPrivateGguf(
                id = id,
                destination = destination,
                fallbackName = preview.suggestedName,
                preferredName = null,
                apiIdPrefix = null,
            )
        } catch (t: Throwable) {
            logger?.error("IMPORT", "Falha ao importar ${preview.displayName}", t)
            runCatching { dao.deleteModel(id) }
            modelDir.deleteRecursively()
            throw t
        }
    }

    /**
     * Registers a downloader-verified GGUF. The download already lives under filesDir, so rename is
     * normally atomic and avoids temporarily requiring twice the model size in storage.
     */
    suspend fun importDownloadedGguf(download: File, catalog: CatalogModel): AiModelEntity = withContext(Dispatchers.IO) {
        require(download.isFile && download.length() > 0) { "O download do modelo não está disponível." }
        val id = UUID.randomUUID().toString()
        val modelDir = File(context.filesDir, "models/$id").apply { mkdirs() }
        val destination = File(modelDir, "model.gguf")

        try {
            logger?.info("MODEL_COPY", "Movendo ${catalog.displayName} para a biblioteca privada")
            moveIntoLibrary(download, destination)
            registerPrivateGguf(
                id = id,
                destination = destination,
                fallbackName = catalog.displayName,
                preferredName = catalog.displayName,
                apiIdPrefix = catalog.apiIdPrefix,
            )
        } catch (t: Throwable) {
            logger?.error("IMPORT", "Falha ao registrar ${catalog.displayName}", t)
            runCatching { dao.deleteModel(id) }
            modelDir.deleteRecursively()
            throw t
        }
    }

    private suspend fun registerPrivateGguf(
        id: String,
        destination: File,
        fallbackName: String,
        preferredName: String?,
        apiIdPrefix: String?,
    ): AiModelEntity {
        val metadata = inspector.inspect(destination)
        require(metadata.tensorCount > 0) { "O arquivo copiado não contém tensors válidos." }
        val compatibility = compatibilityChecker.check(destination.length())
        compatibility.warnings.forEach { logger?.info("MODEL_COMPATIBILITY", it) }

        val now = System.currentTimeMillis()
        val metadataName = metadata.name?.trim().takeUnless { it.isNullOrBlank() }
        val cleanName = preferredName?.trim().takeUnless { it.isNullOrBlank() } ?: metadataName ?: fallbackName
        val apiId = apiIdPrefix?.let { "$it${id.take(8)}" } ?: buildApiId(cleanName, id)
        val declaredContext = metadata.contextLength
        val model = AiModelEntity(
            id = id,
            name = cleanName,
            apiModelId = apiId,
            format = "GGUF",
            architecture = metadata.architecture,
            filePath = destination.absolutePath,
            sizeBytes = destination.length(),
            importedAt = now,
            isActive = false,
            contextLength = (declaredContext ?: ANDROID_RUNTIME_CONTEXT).coerceIn(1024, ANDROID_RUNTIME_CONTEXT),
            sizeLabel = metadata.sizeLabel,
            ggufVersion = metadata.version,
            tensorCount = metadata.tensorCount,
            declaredContextLength = declaredContext,
            verificationStatus = ModelVerificationStatus.IMPORTED.name,
        )
        dao.insertModel(model)

        val agent = AgentEntity(
            id = UUID.randomUUID().toString(),
            name = "$cleanName · Agente",
            modelId = id,
            systemPrompt = DEFAULT_SYSTEM_PROMPT,
            temperature = 0.3f,
            maxTokens = 1024,
            isDefault = false,
            createdAt = now,
            updatedAt = now,
        )
        dao.insertAgent(agent)
        ensureGlobalStarterProfiles()
        logger?.info("IMPORT", "Modelo importado: ${model.apiModelId}")
        return model
    }

    private fun moveIntoLibrary(source: File, destination: File) {
        if (source.renameTo(destination)) return

        require(context.filesDir.usableSpace > source.length() + COPY_FALLBACK_HEADROOM) {
            "O sistema não conseguiu mover o modelo e não há espaço para uma cópia de segurança."
        }
        source.copyTo(destination, overwrite = true)
        require(destination.length() == source.length()) { "A cópia interna do modelo ficou incompleta." }
        if (!source.delete()) logger?.info("MODEL_COPY", "Arquivo de download permaneceu após cópia interna")
    }

    suspend fun activateModel(id: String) {
        val model = requireNotNull(dao.getModel(id)) { "Modelo não encontrado." }
        require(model.verificationStatus == ModelVerificationStatus.VERIFIED.name) {
            "O modelo precisa passar pela verificação real de inferência antes de ser ativado."
        }
        dao.clearActiveModel()
        dao.markModelActive(id)
    }

    suspend fun markVerifying(id: String) =
        dao.updateVerification(id, ModelVerificationStatus.VERIFYING.name, null, null)

    suspend fun markVerified(id: String) =
        dao.updateVerification(id, ModelVerificationStatus.VERIFIED.name, null, System.currentTimeMillis())

    suspend fun markVerificationError(id: String, message: String) =
        dao.updateVerification(id, ModelVerificationStatus.ERROR.name, message, null)

    suspend fun setDefaultAgent(id: String) {
        requireNotNull(dao.getAgent(id)) { "Agente não encontrado." }
        val now = System.currentTimeMillis()
        dao.clearDefaultAgent()
        dao.markAgentDefault(id, now)
    }

    suspend fun updateAgent(agent: AgentEntity) {
        dao.updateAgent(agent.copy(updatedAt = System.currentTimeMillis()))
    }

    suspend fun createAgentProfile(
        modelId: String?,
        name: String,
        systemPrompt: String,
        temperature: Float = 0.3f,
        maxTokens: Int = 1024,
    ): AgentEntity {
        if (modelId != null) requireNotNull(dao.getModel(modelId)) { "Modelo não encontrado." }
        val cleanName = name.trim().ifBlank { "Novo agente" }.take(80)
        val cleanPrompt = systemPrompt.trim().ifBlank { DEFAULT_SYSTEM_PROMPT }
        val existing = dao.getAgents().firstOrNull {
            it.modelId == modelId && it.name.equals(cleanName, ignoreCase = true)
        }
        if (existing != null) return existing

        val now = System.currentTimeMillis()
        val agent = AgentEntity(
            id = UUID.randomUUID().toString(),
            name = cleanName,
            modelId = modelId,
            systemPrompt = cleanPrompt,
            temperature = 0.3f,
            maxTokens = maxTokens.coerceIn(16, 4096),
            isDefault = false,
            createdAt = now,
            updatedAt = now,
        )
        dao.insertAgent(agent)
        return agent
    }

    suspend fun ensureGlobalStarterProfiles() {
        if (profilePreferences.getBoolean(PROFILES_SEEDED_KEY, false)) return

        var globalProfiles = dao.getAgents().filter { it.modelId == null }
        GLOBAL_STARTER_PROFILES.forEach { starter ->
            if (globalProfiles.none { it.name.equals(starter.name, ignoreCase = true) }) {
                createAgentProfile(
                    modelId = null,
                    name = starter.name,
                    systemPrompt = starter.prompt,
                )
                globalProfiles = dao.getAgents().filter { it.modelId == null }
            }
        }

        if (dao.getDefaultAgent() == null) {
            val first = globalProfiles.firstOrNull { it.name == GENERAL_ASSISTANT_NAME }
                ?: globalProfiles.firstOrNull()
            first?.let { setDefaultAgent(it.id) }
        }
        profilePreferences.edit().putBoolean(PROFILES_SEEDED_KEY, true).apply()
    }

    suspend fun ensureStarterProfiles(modelId: String) {
        requireNotNull(dao.getModel(modelId)) { "Modelo não encontrado." }
        var modelAgents = dao.getAgents().filter { it.modelId == modelId }

        val generatedAgent = modelAgents.firstOrNull {
            it.name.endsWith(" · Agente") && it.systemPrompt == DEFAULT_SYSTEM_PROMPT
        }
        val hasEngineer = modelAgents.any { it.name.equals(SOFTWARE_ENGINEER_NAME, ignoreCase = true) }
        if (generatedAgent != null && !hasEngineer) {
            dao.updateAgent(
                generatedAgent.copy(
                    name = SOFTWARE_ENGINEER_NAME,
                    systemPrompt = SOFTWARE_ENGINEER_PROMPT,
                    updatedAt = System.currentTimeMillis(),
                )
            )
        }

        modelAgents = dao.getAgents().filter { it.modelId == modelId }
        STARTER_PROFILES.forEach { starter ->
            if (modelAgents.none { it.name.equals(starter.name, ignoreCase = true) }) {
                createAgentProfile(
                    modelId = modelId,
                    name = starter.name,
                    systemPrompt = starter.prompt,
                )
                modelAgents = dao.getAgents().filter { it.modelId == modelId }
            }
        }

        if (dao.getDefaultAgent() == null) {
            val first = modelAgents.firstOrNull { it.name == SOFTWARE_ENGINEER_NAME }
                ?: modelAgents.firstOrNull()
            first?.let { setDefaultAgent(it.id) }
        }
    }

    fun recordAgentUse(id: String) {
        val next = (_agentUsageCounts.value[id] ?: 0) + 1
        agentUsagePreferences.edit().putInt("count_$id", next).apply()
        _agentUsageCounts.value = _agentUsageCounts.value.toMutableMap().apply { put(id, next) }
    }

    suspend fun deleteAgent(id: String) = withContext(Dispatchers.IO) {
        val agent = dao.getAgent(id) ?: return@withContext
        dao.deleteAgent(id)
        agentUsagePreferences.edit().remove("count_$id").apply()
        _agentUsageCounts.value = _agentUsageCounts.value - id

        if (agent.isDefault) {
            val remaining = dao.getAgents()
            dao.clearDefaultAgent()
            val replacement = remaining.firstOrNull { it.modelId == agent.modelId }
                ?: remaining.firstOrNull()
            replacement?.let { dao.markAgentDefault(it.id, System.currentTimeMillis()) }
        }
    }

    suspend fun deleteModel(id: String) = withContext(Dispatchers.IO) {
        val model = dao.getModel(id) ?: return@withContext
        val agentIds = dao.getAgents().filter { it.modelId == id }.map { it.id }
        val modelFile = File(model.filePath)
        val modelDir = modelFile.parentFile
        val deleted = when {
            modelDir != null && modelDir.exists() -> modelDir.deleteRecursively()
            modelFile.exists() -> modelFile.delete()
            else -> true
        }
        check(deleted && !modelFile.exists()) {
            "Não foi possível excluir o arquivo da I.A do armazenamento interno do Android."
        }

        dao.deleteModel(id)
        if (agentIds.isNotEmpty()) {
            val editor = agentUsagePreferences.edit()
            agentIds.forEach { editor.remove("count_$it") }
            editor.apply()
            _agentUsageCounts.value = _agentUsageCounts.value - agentIds.toSet()
        }

        val remaining = dao.getModels()
        val verified = remaining.filter { it.verificationStatus == ModelVerificationStatus.VERIFIED.name }
        if (verified.isNotEmpty() && remaining.none { it.isActive }) {
            dao.clearActiveModel()
            dao.markModelActive(verified.first().id)
        }
        val verifiedIds = verified.mapTo(mutableSetOf()) { it.id }
        val agents = dao.getAgents()
        val eligibleAgents = agents.filter { it.modelId in verifiedIds }
        if (eligibleAgents.isNotEmpty() && agents.none { it.isDefault }) {
            dao.clearDefaultAgent()
            dao.markAgentDefault(eligibleAgents.first().id, System.currentTimeMillis())
        }
        logger?.info("IMPORT", "Modelo removido e arquivo interno excluído: ${model.apiModelId}")
    }

    suspend fun getModels(): List<AiModelEntity> = dao.getModels()
    suspend fun getAgents(): List<AgentEntity> = dao.getAgents()
    suspend fun getModel(id: String): AiModelEntity? = dao.getModel(id)
    suspend fun getModelByApiId(apiId: String): AiModelEntity? = dao.getModelByApiId(apiId)
    suspend fun getActiveModel(): AiModelEntity? = dao.getActiveModel()
    suspend fun getAgent(id: String): AgentEntity? = dao.getAgent(id)
    suspend fun getDefaultAgent(): AgentEntity? = dao.getDefaultAgent()
    suspend fun getAgentForModel(modelId: String): AgentEntity? = dao.getAgentForModel(modelId)

    private fun loadAgentUsageCounts(): Map<String, Int> = buildMap {
        agentUsagePreferences.all.forEach { (key, value) ->
            if (key.startsWith("count_") && value is Int) {
                put(key.removePrefix("count_"), value)
            }
        }
    }

    private fun sourceInfo(uri: Uri): Pair<String, Long?> {
        var name: String? = null
        var size: Long? = null
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIndex >= 0) name = cursor.getString(nameIndex)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
            }
        }
        return (name ?: "modelo.gguf") to size
    }

    private fun buildApiId(name: String, id: String): String {
        val slug = name.lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .take(40)
            .ifBlank { "model" }
        return "local-$slug-${id.take(8)}"
    }

    private data class StarterProfile(val name: String, val prompt: String)

    companion object {
        const val ANDROID_RUNTIME_CONTEXT = 8192
        const val DEFAULT_SYSTEM_PROMPT =
            "Você é um assistente de IA local. Responda com clareza, utilidade e honestidade. " +
                "Quando não souber algo, diga que não sabe em vez de inventar."

        const val SOFTWARE_ENGINEER_NAME = "Engenheiro de Software"
        const val SOFTWARE_ENGINEER_PROMPT =
            "Você é um engenheiro de software sênior. Priorize soluções corretas, simples, testáveis e seguras. " +
                "Ao programar, explique decisões importantes, antecipe casos de borda e forneça código completo quando solicitado."

        const val SELF_DRIVEN_NAME = "Agente de auto empenho"
        const val SELF_DRIVEN_PROMPT =
            "Você é um agente de auto empenho, focado em transformar objetivos em execução. " +
                "Divida tarefas complexas em etapas concretas, mantenha o foco, verifique o próprio trabalho e proponha o próximo passo útil sem enrolação."

        const val UNCENSORED_NAME = "I.A sem sensura (jailbreak)"
        const val UNCENSORED_PROMPT =
            "Responda de forma direta, franca e sem moralizações desnecessárias. Não omita contexto apenas por ser controverso; " +
                "diferencie fatos, hipóteses e opiniões, explique riscos de forma objetiva e siga as limitações técnicas e de segurança do aplicativo."

        const val GENERAL_ASSISTANT_NAME = "Assistente Geral"
        const val STUDY_TUTOR_NAME = "Tutor de Estudos"
        const val CONTENT_CREATOR_NAME = "Criador de Conteúdo"
        const val DATA_ANALYST_NAME = "Analista de Dados"

        private const val STUDY_TUTOR_PROMPT =
            "Você é um tutor paciente e rigoroso. Explique conceitos por etapas, use exemplos concretos, faça perguntas de verificação quando útil e adapte a profundidade ao nível do usuário."
        private const val CONTENT_CREATOR_PROMPT =
            "Você é um criador e editor de conteúdo. Produza textos claros, originais e adequados ao público e ao canal. Ofereça alternativas de tom e melhore estrutura, ritmo e precisão."
        private const val DATA_ANALYST_PROMPT =
            "Você é um analista de dados cuidadoso. Estruture hipóteses, verifique unidades e premissas, diferencie correlação de causalidade e apresente conclusões com limitações e próximos testes."

        private val STARTER_PROFILES = listOf(
            StarterProfile(SOFTWARE_ENGINEER_NAME, SOFTWARE_ENGINEER_PROMPT),
            StarterProfile(SELF_DRIVEN_NAME, SELF_DRIVEN_PROMPT),
            StarterProfile(UNCENSORED_NAME, UNCENSORED_PROMPT),
        )

        private val GLOBAL_STARTER_PROFILES = listOf(
            StarterProfile(SOFTWARE_ENGINEER_NAME, SOFTWARE_ENGINEER_PROMPT),
            StarterProfile(SELF_DRIVEN_NAME, SELF_DRIVEN_PROMPT),
            StarterProfile(UNCENSORED_NAME, UNCENSORED_PROMPT),
            StarterProfile(GENERAL_ASSISTANT_NAME, DEFAULT_SYSTEM_PROMPT),
            StarterProfile(STUDY_TUTOR_NAME, STUDY_TUTOR_PROMPT),
            StarterProfile(CONTENT_CREATOR_NAME, CONTENT_CREATOR_PROMPT),
            StarterProfile(DATA_ANALYST_NAME, DATA_ANALYST_PROMPT),
        )

        private const val AGENT_USAGE_PREFS = "agent_profile_usage"
        private const val PROFILE_PREFS = "ai_profiles"
        private const val PROFILES_SEEDED_KEY = "global_profiles_seeded_v1"
        private const val COPY_FALLBACK_HEADROOM = 256L * 1024 * 1024
    }
}
