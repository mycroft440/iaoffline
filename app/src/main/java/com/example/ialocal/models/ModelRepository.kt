package com.example.ialocal.models

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.example.ialocal.data.AgentEntity
import com.example.ialocal.data.AiModelEntity
import com.example.ialocal.data.ContextCalibrationStatus
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

            val model = persistGguf(destination, preview.suggestedName)
            logger?.info("IMPORT", "Modelo importado: ${model.apiModelId}")
            model
        } catch (t: Throwable) {
            logger?.error("IMPORT", "Falha ao importar ${preview.displayName}", t)
            runCatching { dao.deleteModel(id) }
            modelDir.deleteRecursively()
            throw t
        }
    }

    /**
     * Registers a GGUF already downloaded into app-scoped storage.
     * No second copy is created: for 15-20GB catalog models this avoids temporarily requiring
     * roughly twice the model size just to finish installation.
     */
    suspend fun adoptDownloadedGguf(file: File, suggestedName: String? = null): AiModelEntity = withContext(Dispatchers.IO) {
        require(file.isFile && file.length() > 0L) { "O download do modelo não foi encontrado." }
        require(file.extension.equals("gguf", ignoreCase = true)) { "O arquivo baixado não é um GGUF." }

        val canonicalPath = file.canonicalPath
        dao.getModels().firstOrNull {
            runCatching { File(it.filePath).canonicalPath }.getOrNull() == canonicalPath
        }?.let { return@withContext it }

        val compatibility = compatibilityChecker.check(file.length())
        require(compatibility.supportedAbi) {
            "Este aparelho usa ${compatibility.primaryAbi}; o runtime atual exige arm64-v8a ou x86_64."
        }

        val model = persistGguf(file, suggestedName)
        logger?.info("IMPORT", "Download interno adotado sem cópia: ${model.apiModelId}")
        model
    }

    private suspend fun persistGguf(file: File, suggestedName: String?): AiModelEntity {
        val metadata = inspector.inspect(file)
        require(metadata.tensorCount > 0) { "O GGUF não contém tensors válidos." }

        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val cleanName = metadata.name?.trim().takeUnless { it.isNullOrBlank() }
            ?: suggestedName?.trim().takeUnless { it.isNullOrBlank() }
            ?: file.nameWithoutExtension
        val apiId = buildApiId(cleanName, id)
        val declaredContext = metadata.contextLength
        val initialContext = (declaredContext ?: SAFE_INITIAL_CONTEXT)
            .coerceIn(MIN_CONTEXT, SAFE_INITIAL_CONTEXT)
        val model = AiModelEntity(
            id = id,
            name = cleanName,
            apiModelId = apiId,
            format = "GGUF",
            architecture = metadata.architecture,
            filePath = file.absolutePath,
            sizeBytes = file.length(),
            importedAt = now,
            isActive = false,
            contextLength = initialContext,
            sizeLabel = metadata.sizeLabel,
            ggufVersion = metadata.version,
            tensorCount = metadata.tensorCount,
            declaredContextLength = declaredContext,
            verificationStatus = ModelVerificationStatus.IMPORTED.name,
            contextCalibrationStatus = ContextCalibrationStatus.NOT_CALIBRATED.name,
        )
        dao.insertModel(model)

        dao.insertAgent(
            AgentEntity(
                id = UUID.randomUUID().toString(),
                name = "$cleanName · Agente",
                modelId = id,
                systemPrompt = DEFAULT_SYSTEM_PROMPT,
                temperature = 0.3f,
                maxTokens = 4096,
                isDefault = false,
                createdAt = now,
                updatedAt = now,
            )
        )
        return model
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

    suspend fun startContextCalibration(id: String, calibrationKey: String, safeContext: Int) {
        dao.startContextCalibration(
            id = id,
            safeContext = safeContext.coerceAtLeast(MIN_CONTEXT),
            status = ContextCalibrationStatus.RUNNING.name,
            calibrationKey = calibrationKey,
            updatedAt = System.currentTimeMillis(),
        )
    }

    suspend fun recordContextProbeSuccess(id: String, contextLength: Int) {
        dao.recordContextProbeSuccess(id, contextLength, System.currentTimeMillis())
    }

    suspend fun recordContextProbeFailure(id: String, contextLength: Int, reason: String?) {
        dao.recordContextProbeFailure(id, contextLength, reason, System.currentTimeMillis())
    }

    suspend fun finishContextCalibration(id: String, contextLength: Int) {
        dao.finishContextCalibration(
            id = id,
            contextLength = contextLength,
            status = ContextCalibrationStatus.CALIBRATED.name,
            updatedAt = System.currentTimeMillis(),
        )
    }

    suspend fun failContextCalibration(id: String, error: String) {
        dao.failContextCalibration(
            id = id,
            status = ContextCalibrationStatus.FAILED.name,
            error = error,
            updatedAt = System.currentTimeMillis(),
        )
    }

    suspend fun setDefaultAgent(id: String) {
        requireNotNull(dao.getAgent(id)) { "Agente não encontrado." }
        val now = System.currentTimeMillis()
        dao.clearDefaultAgent()
        dao.markAgentDefault(id, now)
    }

    suspend fun updateAgent(agent: AgentEntity) {
        dao.updateAgent(agent.copy(updatedAt = System.currentTimeMillis()))
    }

    suspend fun deleteModel(id: String) = withContext(Dispatchers.IO) {
        val model = dao.getModel(id) ?: return@withContext
        File(model.filePath).parentFile?.deleteRecursively()
        dao.deleteModel(id)

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
        if (uri.scheme == "file") {
            val file = uri.path?.let(::File)
            if (file != null) return file.name to file.length().takeIf { it > 0L }
        }

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
        const val MIN_CONTEXT = 1024
        /** Safe first load. The runtime can use larger values after per-device calibration. */
        const val SAFE_INITIAL_CONTEXT = 8192
        const val DEFAULT_SYSTEM_PROMPT =
            "Se empenhe ao máximo nas tarefas e use a web quando precisar, nunca pressuponha nada."
    }
}
