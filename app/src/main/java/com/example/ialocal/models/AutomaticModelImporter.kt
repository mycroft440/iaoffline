package com.example.ialocal.models

import android.content.Context
import android.net.Uri
import android.os.Environment
import com.example.ialocal.data.AiModelEntity
import com.example.ialocal.diagnostics.AiEventLogger
import java.io.File
import java.security.MessageDigest
import java.util.ArrayDeque
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class AutomaticModelImportSummary(
    val scanned: Int = 0,
    val imported: Int = 0,
    val alreadyInstalled: Int = 0,
    val invalid: Int = 0,
    val verificationWarnings: Int = 0,
    val failures: Int = 0,
    val permissionRequired: Boolean = false,
)

/**
 * Searches the primary shared Android storage for GGUF files and imports compatible models without
 * making the user pick each file manually. Android requires MANAGE_EXTERNAL_STORAGE for this broad
 * scan; callers must request the special access before invoking [scanAndImport].
 */
class AutomaticModelImporter(
    context: Context,
    private val repository: ModelRepository,
    private val manager: ModelManager,
    private val logger: AiEventLogger? = null,
) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val scanMutex = Mutex()
    private val inspector = GgufInspector()
    private val compatibilityChecker = DeviceCompatibilityChecker(appContext)

    suspend fun scanAndImport(): AutomaticModelImportSummary = scanMutex.withLock {
        if (!Environment.isExternalStorageManager()) {
            return@withLock AutomaticModelImportSummary(permissionRequired = true)
        }

        withContext(Dispatchers.IO) {
            val candidates = discoverGgufFiles()
            var installed = repository.getModels().toMutableList()
            var imported = 0
            var alreadyInstalled = 0
            var invalid = 0
            var verificationWarnings = 0
            var failures = 0

            candidates.forEach { file ->
                val fingerprint = fingerprint(file)
                if (preferences.getBoolean(fingerprint, false)) return@forEach

                val catalogModel = catalogModelForFileName(file.name)
                if (catalogModel != null && installed.any { it.apiModelId.startsWith(catalogModel.apiIdPrefix) }) {
                    markProcessed(fingerprint)
                    alreadyInstalled += 1
                    return@forEach
                }

                val preview = try {
                    inspectFile(file)
                } catch (t: Throwable) {
                    // Structurally invalid GGUFs are ignored until the file itself changes.
                    markProcessed(fingerprint)
                    invalid += 1
                    logger?.error("AUTO_MODEL_SCAN", "GGUF inválido ignorado: ${file.absolutePath}", t)
                    return@forEach
                }

                if (!preview.compatibility.canStore) {
                    // Do not remember this as processed: freeing space later must allow a retry.
                    failures += 1
                    logger?.info(
                        "AUTO_MODEL_SCAN",
                        "Sem espaço para importar ${file.name}; o app tentará novamente em outra varredura.",
                    )
                    return@forEach
                }

                if (installed.any { sameInstalledModel(it, preview) }) {
                    markProcessed(fingerprint)
                    alreadyInstalled += 1
                    return@forEach
                }

                val beforeIds = installed.mapTo(hashSetOf()) { it.id }
                try {
                    manager.importAndVerify(preview)
                    installed = repository.getModels().toMutableList()
                    markProcessed(fingerprint)
                    imported += 1
                    logger?.info("AUTO_MODEL_SCAN", "Modelo importado automaticamente: ${file.absolutePath}")
                } catch (t: Throwable) {
                    val after = repository.getModels().toMutableList()
                    val importedDespiteVerification = after.firstOrNull { it.id !in beforeIds }
                    installed = after
                    if (importedDespiteVerification != null) {
                        // ModelManager intentionally keeps an imported GGUF when runtime verification fails.
                        markProcessed(fingerprint)
                        imported += 1
                        verificationWarnings += 1
                        logger?.error(
                            "AUTO_MODEL_SCAN",
                            "${file.name} foi importado automaticamente, mas a verificação de execução falhou.",
                            t,
                        )
                    } else {
                        failures += 1
                        logger?.error("AUTO_MODEL_SCAN", "Falha ao importar automaticamente ${file.absolutePath}", t)
                    }
                }
            }

            AutomaticModelImportSummary(
                scanned = candidates.size,
                imported = imported,
                alreadyInstalled = alreadyInstalled,
                invalid = invalid,
                verificationWarnings = verificationWarnings,
                failures = failures,
            )
        }
    }

    private fun inspectFile(file: File): ModelImportPreview {
        require(file.isFile && file.length() > 0L) { "Arquivo GGUF vazio ou indisponível." }
        require(file.extension.equals("gguf", ignoreCase = true)) { "Formato não suportado: ${file.name}" }

        val metadata = inspector.inspect(file)
        require(metadata.tensorCount > 0) { "O GGUF não contém tensors de modelo." }
        val compatibility = compatibilityChecker.check(file.length())

        return ModelImportPreview(
            uri = Uri.fromFile(file),
            displayName = file.name,
            sourceSizeBytes = file.length(),
            metadata = metadata,
            compatibility = compatibility,
        )
    }

    private fun sameInstalledModel(model: AiModelEntity, preview: ModelImportPreview): Boolean {
        val sourceSize = preview.sourceSizeBytes ?: return false
        return model.sizeBytes == sourceSize &&
            model.name.equals(preview.suggestedName, ignoreCase = true)
    }

    private fun discoverGgufFiles(): List<File> {
        val root = Environment.getExternalStorageDirectory()
        if (!root.isDirectory || !root.canRead()) return emptyList()

        val result = mutableListOf<File>()
        val pending = ArrayDeque<File>()
        val visited = hashSetOf<String>()
        pending.add(root)

        while (pending.isNotEmpty()) {
            val directory = pending.removeFirst()
            val canonical = runCatching { directory.canonicalPath }.getOrElse { directory.absolutePath }
            if (!visited.add(canonical)) continue

            val children = runCatching { directory.listFiles() }.getOrNull() ?: continue
            children.forEach { child ->
                when {
                    child.isDirectory && shouldEnter(child) -> pending.addLast(child)
                    child.isFile && child.extension.equals("gguf", ignoreCase = true) && child.length() > 0L -> result += child
                }
            }
        }

        return result
            .distinctBy { runCatching { it.canonicalPath }.getOrElse { it.absolutePath } }
            .sortedByDescending { it.lastModified() }
    }

    private fun shouldEnter(directory: File): Boolean {
        if (!directory.canRead()) return false
        // Even with all-files access Android keeps most /Android app-private trees restricted, and
        // model files downloaded by the user should live in normal shared-storage folders.
        if (directory.parentFile == Environment.getExternalStorageDirectory() && directory.name.equals("Android", ignoreCase = true)) {
            return false
        }
        return true
    }

    private fun fingerprint(file: File): String {
        val canonical = runCatching { file.canonicalPath }.getOrElse { file.absolutePath }
        val raw = "$canonical|${file.length()}|${file.lastModified()}"
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return KEY_PREFIX + digest.joinToString("") { "%02x".format(it) }
    }

    private fun markProcessed(key: String) {
        preferences.edit().putBoolean(key, true).apply()
    }

    companion object {
        private const val PREFERENCES_NAME = "automatic_model_importer"
        private const val KEY_PREFIX = "processed_"
    }
}
