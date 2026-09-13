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
        require(compatibility.supportedAbi) {
            "Este aparelho usa ${compatibility.primaryAbi}; o runtime atual exige arm64-v8a ou x86_64."
        }
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
        require(modelDir.isDirectory) { "Não foi possível preparar a pasta privada do modelo." }
        val destination = File(modelDir, "model.gguf")

        try {
            logger?.info("MODEL_COPY", "Copiando ${preview.displayName} para armazenamento privado")
            val storageLimit = (context.filesDir.usableSpace - COPY_FALLBACK_HEADROOM).coerceAtLeast(0L)
            require(storageLimit > 0) { "Não há espaço livre suficiente para importar este modelo." }
            preview.sourceSizeBytes?.takeIf { it > 0 }?.let { declared ->
                require(declared <= storageLimit) {
                    "Não há espaço livre suficiente para importar este modelo com margem de segurança."
                }
            }
            val maxCopyBytes = preview.sourceSizeBytes?.takeIf { it > 0 }?.let { minOf(it, storageLimit) }
                ?: storageLimit
            var copied = 0L
            context.contentResolver.openInputStream(preview.uri)?.use { input ->
                FileOutputStream(destination).buffered(COPY_BUFFER_SIZE).use { output ->
                    val buffer = ByteArray(COPY_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        copied += read
                        require(copied <= maxCopyBytes) {
                            if (preview.sourceSizeBytes?.takeIf { it > 0 } != null) {
                                "O provedor entregou mais dados que o tamanho declarado para este modelo."
                            } else {
                                "O modelo excedeu o espaço disponível com margem de segurança durante a cópia."
                            }
                        }
                        output.write(buffer, 0, read)
                    }
                    output.flush()
                }
            } ?: error("Não foi possível abrir o modelo selecionado.")

            require(copied > 0) { "O modelo selecionado está vazio." }
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
            runCatching { dao.deleteModelAtomically(id) }
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
        require(modelDir.isDirectory) { "Não foi possível preparar a pasta privada do modelo." }
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
            runCatching { dao.deleteModelAtomically(id) }
            val recovered = runCatching {
                if (destination.isFile && !download.exists()) restoreDownload(destination, download)
            }.onFailure {
                logger?.error("MODEL_COPY", "Não foi possível devolver o GGUF verificado à pasta de downloads", it)
            }.isSuccess
            if (recovered || !destination.exists()) modelDir.deleteRecursively()
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
        // Re-read the private copy. This catches truncation/provider issues before native loading.
        val metadata = inspector.inspect(destination)
        require(metadata.tensorCount > 0) { "O arquivo copiado não contém tensors válidos." }
        val compatibility = compatibilityChecker.check(destination.length())
        require(compatibility.supportedAbi) {
            "Este aparelho usa ${compatibility.primaryAbi}; o runtime atual exige arm64-v8a ou x86_64."
        }

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
        dao.insertModelWithAgent(model, agent)
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

    private fun restoreDownload(source: File, destination: File) {
        destination.parentFile?.mkdirs()
        if (source.renameTo(destination)) return
        source.copyTo(destination, overwrite = true)
        require(destination.length() == source.length()) { "A recuperação do download verificado ficou incompleta." }
        require(source.delete()) { "O GGUF foi recuperado, mas a cópia temporária não pôde ser removida." }
    }

    suspend fun activateModel(id: String) {
        val model = requireNotNull(dao.getModel(id)) { "Modelo não encontrado." }
        require(model.verificationStatus == ModelVerificationStatus.VERIFIED.name) {
            "O modelo precisa passar pela verificação real de inferência antes de ser ativado."
        }
        dao.activateModelAtomically(id)
    }

    suspend fun markVerifying(id: String) =
        dao.updateVerification(id, ModelVerificationStatus.VERIFYING.name, null, null)

    suspend fun markVerified(id: String) =
        dao.updateVerification(id, ModelVerificationStatus.VERIFIED.name, null, System.currentTimeMillis())

    suspend fun markVerificationError(id: String, message: String) =
        dao.updateVerification(id, ModelVerificationStatus.ERROR.name, message, null)

    suspend fun restoreVerification(model: AiModelEntity) =
        dao.updateVerification(model.id, model.verificationStatus, model.lastError, model.lastVerifiedAt)

    suspend fun setDefaultAgent(id: String) {
        val agent = requireNotNull(dao.getAgent(id)) { "Agente não encontrado." }
        val model = requireNotNull(dao.getModel(agent.modelId)) { "O modelo deste agente não está disponível." }
        require(model.verificationStatus == ModelVerificationStatus.VERIFIED.name) {
            "Só é possível tornar padrão um agente cujo modelo foi verificado."
        }
        dao.setDefaultAgentAtomically(id, System.currentTimeMillis())
    }

    suspend fun updateAgent(agent: AgentEntity) {
        val persisted = requireNotNull(dao.getAgent(agent.id)) { "Agente não encontrado." }
        require(agent.modelId == persisted.modelId) { "Não é permitido trocar o modelo de um agente por esta operação." }
        dao.updateAgent(agent.copy(updatedAt = System.currentTimeMillis()))
    }

    suspend fun repairSelections() {
        dao.repairSelections(ModelVerificationStatus.VERIFIED.name, System.currentTimeMillis())
    }

    /** Removes restored/stale database entries whose excluded private GGUF no longer exists. */
    suspend fun reconcileStorage() = withContext(Dispatchers.IO) {
        dao.getModels().forEach { model ->
            val file = File(model.filePath)
            if (!file.isFile || file.length() != model.sizeBytes) {
                logger?.error(
                    "MODEL_STORAGE",
                    "Removendo registro inconsistente de ${model.apiModelId}: GGUF ausente ou com tamanho inesperado",
                )
                dao.deleteModelAtomically(model.id)
                if (file.exists()) runCatching { file.parentFile?.deleteRecursively() }
            }
        }
        repairSelections()
    }

    suspend fun deleteModel(id: String) = withContext(Dispatchers.IO) {
        val model = dao.getModel(id) ?: return@withContext
        // Commit database state first. A leftover private file is recoverable; a row pointing to a
        // file already deleted is not.
        dao.deleteModelAtomically(id)
        repairSelections()
        runCatching { File(model.filePath).parentFile?.deleteRecursively() }
            .onFailure { logger?.error("MODEL_STORAGE", "Falha ao remover arquivos de ${model.apiModelId}", it) }
        logger?.info("IMPORT", "Modelo removido: ${model.apiModelId}")
    }

    suspend fun getModels(): List<AiModelEntity> = dao.getModels()
    suspend fun getAgents(): List<AgentEntity> = dao.getAgents()
    suspend fun getModel(id: String): AiModelEntity? = dao.getModel(id)
    suspend fun getModelByApiId(apiId: String): AiModelEntity? = dao.getModelByApiId(apiId)
    suspend fun getActiveModel(): AiModelEntity? = dao.getActiveModel()
    suspend fun getAgent(id: String): AgentEntity? = dao.getAgent(id)
    suspend fun getDefaultAgent(): AgentEntity? = dao.getDefaultAgent()
    suspend fun getAgentForModel(modelId: String): AgentEntity? = dao.getAgentForModel(modelId)

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
        /** v0.4.0 Android binding currently creates an 8192-token native context. */
        const val ANDROID_RUNTIME_CONTEXT = 8192
        const val DEFAULT_SYSTEM_PROMPT =
            "Você é um assistente de IA local. Responda com clareza, utilidade e honestidade. " +
                "Quando não souber algo, diga que não sabe em vez de inventar."
        private const val COPY_BUFFER_SIZE = 1024 * 1024
        private const val COPY_FALLBACK_HEADROOM = 256L * 1024 * 1024
    }
}
