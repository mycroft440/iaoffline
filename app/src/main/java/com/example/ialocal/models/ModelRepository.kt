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
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val lastChatModelPrefs = context.getSharedPreferences("last_selected_chat_model", Context.MODE_PRIVATE)

    /** Set only when a user explicitly chooses a model, not during automatic fallback. */
    fun rememberChatModel(modelId: String) {
        lastChatModelPrefs.edit().putString("model_id", modelId).apply()
    }

    fun lastChatModelId(): String? = lastChatModelPrefs.getString("model_id", null)

    private val profilePreferences = context.getSharedPreferences(PROFILE_PREFS, Context.MODE_PRIVATE)
    private val _profiles = MutableStateFlow(
        AiProfile.BUILT_IN + CustomProfiles.decode(profilePreferences.getString(CUSTOM_PROFILES_KEY, null))
    )
    /** The built-in profiles followed by the ones the user created. */
    val profiles: StateFlow<List<AiProfile>> = _profiles.asStateFlow()
    private val _selectedProfile = MutableStateFlow(
        _profiles.value.firstOrNull { it.id == profilePreferences.getString(PROFILE_KEY, null) } ?: AiProfile.DEFAULT
    )
    val selectedProfile: StateFlow<AiProfile> = _selectedProfile.asStateFlow()
    private val _agentUsageCounts = MutableStateFlow(loadAgentUsageCounts())
    val agentUsageCounts: StateFlow<Map<String, Int>> = _agentUsageCounts.asStateFlow()
    private val profileMigrationMutex = Mutex()

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

    /**
     * Copies the GGUF into the app and registers it. [onCopied] hears how many bytes are copied as
     * the copy goes; cancelling the coroutine stops the copy and removes what was copied.
     */
    suspend fun importGguf(
        preview: ModelImportPreview,
        catalog: CatalogModel? = null,
        onCopied: (Long) -> Unit = {},
    ): AiModelEntity = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val modelDir = File(context.filesDir, "models/$id").apply { mkdirs() }
        val destination = File(modelDir, "model.gguf")

        try {
            logger?.info("MODEL_COPY", "Copiando ${preview.displayName} para armazenamento privado")
            context.contentResolver.openInputStream(preview.uri)?.use { input ->
                FileOutputStream(destination).use { output -> copyCancellable(input, output, onCopied) }
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
                preferredName = catalog?.displayName,
                apiIdPrefix = catalog?.apiIdPrefix,
            )
        } catch (t: Throwable) {
            // A cancelled import must still remove its row and its partial copy.
            withContext(NonCancellable) {
                if (t is CancellationException) {
                    logger?.info("IMPORT", "Importação de ${preview.displayName} cancelada")
                } else {
                    logger?.error("IMPORT", "Falha ao importar ${preview.displayName}", t)
                }
                runCatching { dao.deleteModel(id) }
                modelDir.deleteRecursively()
            }
            throw t
        }
    }

    private suspend fun copyCancellable(input: InputStream, output: OutputStream, onCopied: (Long) -> Unit) {
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        var copied = 0L
        var reported = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            output.write(buffer, 0, count)
            copied += count
            if (copied - reported >= COPY_PROGRESS_STEP_BYTES) {
                reported = copied
                onCopied(copied)
            }
        }
        onCopied(copied)
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
        val starterAgents = starterAgentsForModel(id, now)
        dao.insertModelWithAgents(model, starterAgents)
        logger?.info("IMPORT", "Modelo importado com ${starterAgents.size} perfis iniciais: ${model.apiModelId}")
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

    /** An interrupted first-use test must not turn an installed model into a broken model. */
    suspend fun restoreVerification(model: AiModelEntity) =
        dao.updateVerification(model.id, model.verificationStatus, model.lastError, model.lastVerifiedAt)

    suspend fun markVerified(id: String) {
        ensureStarterProfiles(id)
        dao.updateVerification(id, ModelVerificationStatus.VERIFIED.name, null, System.currentTimeMillis())
        ensureDefaultAgentForVerifiedModels(preferredModelId = id)
    }

    suspend fun markVerificationError(id: String, message: String) {
        dao.updateVerification(id, ModelVerificationStatus.ERROR.name, message, null)
        val active = ensureActiveVerifiedModel()
        ensureDefaultAgentForVerifiedModels(preferredModelId = active?.id)
    }

    suspend fun setDefaultAgent(id: String) {
        val agent = requireNotNull(dao.getAgent(id)) { "Agente não encontrado." }
        val profile = requireNotNull(profileNamed(agent.name)) { PROFILE_GONE_MESSAGE }
        val model = requireNotNull(dao.getModel(agent.modelId)) { "O modelo deste agente não está mais disponível." }
        require(model.verificationStatus == ModelVerificationStatus.VERIFIED.name) {
            "Este perfil só pode ser definido como padrão depois que o modelo passar pelo teste real de inferência."
        }
        val now = System.currentTimeMillis()
        dao.clearDefaultAgent()
        dao.markAgentDefault(id, now)
        setSelectedProfile(profile)
    }

    fun setSelectedProfile(profile: AiProfile) {
        val current = requireNotNull(_profiles.value.firstOrNull { it.id == profile.id }) { PROFILE_GONE_MESSAGE }
        profilePreferences.edit().putString(PROFILE_KEY, current.id).apply()
        _selectedProfile.value = current
    }

    /** The profile whose agents are named [name], if it still exists. */
    fun profileNamed(name: String): AiProfile? = _profiles.value.firstOrNull { it.displayName == name }

    /** Saves a new profile and gives every installed model an agent for it. */
    suspend fun createCustomProfile(name: String, description: String, systemPrompt: String): AiProfile {
        val profile = profileMigrationMutex.withLock {
            CustomProfiles.validate(name, description, systemPrompt, _profiles.value)?.let { throw IllegalArgumentException(it) }
            CustomProfiles.create(name, description, systemPrompt).also { saveProfiles(_profiles.value + it) }
        }
        syncProfilesForAllModels()
        logger?.info("PROFILES", "Perfil criado: ${profile.displayName}")
        return profile
    }

    /** Changes a profile the user created; its agents keep their conversations. */
    suspend fun updateCustomProfile(id: String, name: String, description: String, systemPrompt: String): AiProfile {
        val updated = profileMigrationMutex.withLock {
            val current = requireNotNull(_profiles.value.firstOrNull { it.id == id && it.isCustom }) { PROFILE_GONE_MESSAGE }
            CustomProfiles.validate(name, description, systemPrompt, _profiles.value, editingId = id)
                ?.let { throw IllegalArgumentException(it) }
            val updated = current.copy(
                displayName = name.trim(),
                description = description.trim(),
                systemPrompt = systemPrompt.trim(),
            )
            // Agents are found by name, so they are renamed before the profile list changes.
            val now = System.currentTimeMillis()
            dao.getAgents().filter { it.name == current.displayName }.forEach { agent ->
                dao.updateAgent(
                    agent.copy(
                        name = updated.displayName,
                        systemPrompt = updated.systemPrompt,
                        maxTokens = updated.maxTokens,
                        updatedAt = now,
                    )
                )
            }
            saveProfiles(_profiles.value.map { if (it.id == id) updated else it })
            if (_selectedProfile.value.id == id) _selectedProfile.value = updated
            updated
        }
        syncProfilesForAllModels()
        return updated
    }

    /**
     * Removes a profile the user created and its agents. Conversations that used it continue with
     * Programador, which also becomes the selected profile if this one was.
     */
    suspend fun deleteCustomProfile(id: String) {
        profileMigrationMutex.withLock {
            val profile = _profiles.value.firstOrNull { it.id == id && it.isCustom } ?: return
            saveProfiles(_profiles.value - profile)
            if (_selectedProfile.value.id == id) setSelectedProfile(AiProfile.DEFAULT)
            logger?.info("PROFILES", "Perfil removido: ${profile.displayName}")
        }
        syncProfilesForAllModels()
        ensureDefaultAgentForVerifiedModels()
    }

    private fun saveProfiles(profiles: List<AiProfile>) {
        profilePreferences.edit().putString(CUSTOM_PROFILES_KEY, CustomProfiles.encode(profiles)).apply()
        _profiles.value = profiles
    }

    private suspend fun syncProfilesForAllModels() {
        dao.getModels().forEach { model -> ensureStarterProfiles(model.id) }
    }

    suspend fun updateAgent(agent: AgentEntity) {
        val profile = requireNotNull(profileNamed(agent.name)) { PROFILE_GONE_MESSAGE }
        dao.updateAgent(
            agent.copy(
                systemPrompt = profile.systemPrompt,
                maxTokens = profile.maxTokens,
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    suspend fun createAgentProfile(
        modelId: String,
        name: String,
        systemPrompt: String,
        temperature: Float = 0.3f,
    ): AgentEntity {
        requireNotNull(dao.getModel(modelId)) { "Modelo não encontrado." }
        val profile = requireNotNull(_profiles.value.firstOrNull { it.displayName.equals(name.trim(), true) }) {
            "Este perfil não existe. Crie-o em Configurações → Perfis de I.A."
        }
        val cleanName = profile.displayName
        val cleanPrompt = profile.systemPrompt
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
            temperature = temperature.coerceIn(0f, 2f),
            maxTokens = profile.maxTokens,
            isDefault = false,
            createdAt = now,
            updatedAt = now,
        )
        dao.insertAgent(agent)
        return agent
    }

    suspend fun ensureStarterProfiles(modelId: String) = profileMigrationMutex.withLock {
        requireNotNull(dao.getModel(modelId)) { "Modelo não encontrado." }
        var modelAgents = dao.getAgents().filter { it.modelId == modelId }
        val currentProfiles = _profiles.value

        currentProfiles.forEach { profile ->
            val existing = modelAgents.firstOrNull { it.name.equals(profile.displayName, true) }
                ?: modelAgents.firstOrNull { agent ->
                    when (profile.builtIn) {
                        BuiltInProfile.PROGRAMMER -> agent.name.equals(LEGACY_ENGINEER_NAME, true) ||
                            (agent.name.endsWith(" · Agente") && agent.systemPrompt == DEFAULT_SYSTEM_PROMPT)
                        BuiltInProfile.UNCENSORED -> agent.name.equals(LEGACY_UNCENSORED_NAME, true)
                        null -> false
                    }
                }
            if (existing != null && (
                    existing.name != profile.displayName ||
                        existing.systemPrompt != profile.systemPrompt ||
                        existing.maxTokens != profile.maxTokens
                    )
            ) {
                dao.updateAgent(existing.copy(
                    name = profile.displayName,
                    systemPrompt = profile.systemPrompt,
                    maxTokens = profile.maxTokens,
                    updatedAt = System.currentTimeMillis(),
                ))
            }
        }

        modelAgents = dao.getAgents().filter { it.modelId == modelId }
        currentProfiles.forEach { profile ->
            if (modelAgents.none { it.name.equals(profile.displayName, ignoreCase = true) }) {
                createAgentProfile(
                    modelId = modelId,
                    name = profile.displayName,
                    systemPrompt = profile.systemPrompt,
                )
                modelAgents = dao.getAgents().filter { it.modelId == modelId }
            }
        }
        fun agentOf(profile: AiProfile) = requireNotNull(modelAgents.firstOrNull { it.name == profile.displayName }).id
        val keepIds = currentProfiles.map(::agentOf)
        val removed = modelAgents.filter { it.id !in keepIds }
        if (removed.isNotEmpty()) {
            dao.keepOnlyProfiles(
                modelId,
                keepIds,
                agentOf(AiProfile.of(BuiltInProfile.PROGRAMMER)),
                agentOf(AiProfile.of(BuiltInProfile.UNCENSORED)),
                LEGACY_UNCENSORED_NAME,
            )
            val editor = agentUsagePreferences.edit()
            removed.forEach { editor.remove("count_${it.id}") }
            editor.apply()
            _agentUsageCounts.value = _agentUsageCounts.value - removed.map { it.id }.toSet()
        }
    }

    fun recordAgentUse(id: String) {
        val next = (_agentUsageCounts.value[id] ?: 0) + 1
        agentUsagePreferences.edit().putInt("count_$id", next).apply()
        _agentUsageCounts.value = _agentUsageCounts.value.toMutableMap().apply { put(id, next) }
    }

    suspend fun deleteAgent(id: String) = withContext(Dispatchers.IO) {
        val agent = dao.getAgent(id) ?: return@withContext
        require(profileNamed(agent.name) == null) {
            "Os perfis são gerenciados em Configurações → Perfis de I.A."
        }
        dao.deleteAgent(id)
        agentUsagePreferences.edit().remove("count_$id").apply()
        _agentUsageCounts.value = _agentUsageCounts.value - id

        if (agent.isDefault) {
            dao.clearDefaultAgent()
            ensureDefaultAgentForVerifiedModels(preferredModelId = agent.modelId)
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

        val active = ensureActiveVerifiedModel()
        val preferredModelId = active?.id ?: dao.getModels()
            .firstOrNull { it.verificationStatus == ModelVerificationStatus.VERIFIED.name }
            ?.id
        ensureDefaultAgentForVerifiedModels(preferredModelId)
        logger?.info("IMPORT", "Modelo removido e arquivo interno excluído: ${model.apiModelId}")
    }

    suspend fun getModels(): List<AiModelEntity> = dao.getModels()
    suspend fun getAgents(): List<AgentEntity> = dao.getAgents()
    suspend fun getModel(id: String): AiModelEntity? = dao.getModel(id)
    suspend fun getModelByApiId(apiId: String): AiModelEntity? = dao.getModelByApiId(apiId)
    suspend fun getActiveModel(): AiModelEntity? = ensureActiveVerifiedModel()
    suspend fun getAgent(id: String): AgentEntity? = dao.getAgent(id)
    suspend fun getDefaultAgent(): AgentEntity? = ensureDefaultAgentForVerifiedModels()
    suspend fun getAgentForModel(modelId: String): AgentEntity? = dao.getAgentForModel(modelId)

    suspend fun resolveAgentForUse(preferredId: String?): AgentEntity? {
        if (!preferredId.isNullOrBlank()) {
            val preferred = dao.getAgent(preferredId)
            if (preferred != null && isAgentVerified(preferred)) return preferred
        }
        return ensureDefaultAgentForVerifiedModels()
    }

    private suspend fun ensureActiveVerifiedModel(preferredModelId: String? = null): AiModelEntity? {
        val current = dao.getActiveModel()
        if (current != null && current.verificationStatus == ModelVerificationStatus.VERIFIED.name) return current

        val verified = dao.getModels().filter { it.verificationStatus == ModelVerificationStatus.VERIFIED.name }
        val replacement = preferredModelId
            ?.let { preferred -> verified.firstOrNull { it.id == preferred } }
            ?: verified.firstOrNull()

        if (current != null || replacement != null) dao.clearActiveModel()
        if (replacement == null) return null

        dao.markModelActive(replacement.id)
        return dao.getModel(replacement.id)
    }

    private suspend fun ensureDefaultAgentForVerifiedModels(preferredModelId: String? = null): AgentEntity? {
        repairVerifiedModelsWithoutAgents()

        val current = dao.getDefaultAgent()
        if (current != null && current.name == _selectedProfile.value.displayName && isAgentVerified(current)) return current

        val verifiedModelIds = dao.getModels()
            .filter { it.verificationStatus == ModelVerificationStatus.VERIFIED.name }
            .mapTo(linkedSetOf()) { it.id }
        val targetModelId = preferredModelId
            ?.takeIf { it in verifiedModelIds }
            ?: ensureActiveVerifiedModel()?.id?.takeIf { it in verifiedModelIds }
        val eligibleAgents = dao.getAgents().filter { it.modelId in verifiedModelIds }
        val targetAgents = targetModelId?.let { target -> eligibleAgents.filter { it.modelId == target } }.orEmpty()
        val selectedName = _selectedProfile.value.displayName
        val replacement = targetAgents.firstOrNull { it.name == selectedName }
            ?: eligibleAgents.firstOrNull { it.name == selectedName }
            ?: targetAgents.firstOrNull { it.name.equals(SOFTWARE_ENGINEER_NAME, ignoreCase = true) }
            ?: targetAgents.firstOrNull()
            ?: eligibleAgents.firstOrNull { it.name.equals(SOFTWARE_ENGINEER_NAME, ignoreCase = true) }
            ?: eligibleAgents.firstOrNull()

        if (current != null || replacement != null) dao.clearDefaultAgent()
        if (replacement == null) return null

        dao.markAgentDefault(replacement.id, System.currentTimeMillis())
        return dao.getAgent(replacement.id)
    }

    private suspend fun repairVerifiedModelsWithoutAgents() {
        dao.getModels()
            .filter { it.verificationStatus == ModelVerificationStatus.VERIFIED.name }
            .forEach { ensureStarterProfiles(it.id) }
    }

    private fun starterAgentsForModel(modelId: String, now: Long): List<AgentEntity> =
        _profiles.value.map { profile ->
            AgentEntity(
                id = UUID.randomUUID().toString(),
                name = profile.displayName,
                modelId = modelId,
                systemPrompt = profile.systemPrompt,
                temperature = 0.3f,
                maxTokens = profile.maxTokens,
                isDefault = false,
                createdAt = now,
                updatedAt = now,
            )
        }

    private suspend fun isAgentVerified(agent: AgentEntity): Boolean =
        dao.getModel(agent.modelId)?.verificationStatus == ModelVerificationStatus.VERIFIED.name

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

    companion object {
        const val ANDROID_RUNTIME_CONTEXT = 8192
        const val DEFAULT_SYSTEM_PROMPT =
            "Você é um assistente de IA local. Responda com clareza, utilidade e honestidade. " +
                "Quando não souber algo, diga que não sabe em vez de inventar."

        const val SOFTWARE_ENGINEER_NAME = "Programador"
        const val SOFTWARE_ENGINEER_PROMPT =
            "Você é um engenheiro de software sênior. Priorize soluções corretas, simples, testáveis e seguras. " +
                "Ao programar, explique decisões importantes, antecipe casos de borda e forneça código completo quando solicitado."

        const val SELF_DRIVEN_NAME = "Agente de auto empenho"
        const val SELF_DRIVEN_PROMPT =
            "Você é um agente de auto empenho, focado em transformar objetivos em execução. " +
                "Divida tarefas complexas em etapas concretas, mantenha o foco, verifique o próprio trabalho e proponha o próximo passo útil sem enrolação."

        const val UNCENSORED_NAME = "Sem censura"
        const val UNCENSORED_PROMPT =
            "Responda de forma direta, franca e sem moralizações desnecessárias. Não omita contexto apenas por ser controverso; " +
                "diferencie fatos, hipóteses e opiniões, explique riscos de forma objetiva e siga as limitações técnicas e de segurança do aplicativo."

        internal const val LEGACY_ENGINEER_NAME = "Engenheiro de Software"
        internal const val LEGACY_UNCENSORED_NAME = "I.A sem sensura (jailbreak)"
        private const val PROFILE_PREFS = "selected_builtin_profile"
        private const val PROFILE_KEY = "profile"
        private const val CUSTOM_PROFILES_KEY = "custom_profiles"
        private const val PROFILE_GONE_MESSAGE = "Este perfil não existe mais."
        private const val AGENT_USAGE_PREFS = "agent_profile_usage"
        private const val COPY_FALLBACK_HEADROOM = 256L * 1024 * 1024
        private const val COPY_BUFFER_BYTES = 1024 * 1024
        private const val COPY_PROGRESS_STEP_BYTES = 16L * 1024 * 1024
    }
}
