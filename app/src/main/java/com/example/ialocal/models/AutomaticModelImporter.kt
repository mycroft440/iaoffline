package com.example.ialocal.models

import android.content.Context
import android.net.Uri
import android.os.Environment
import com.example.ialocal.data.AiModelEntity
import com.example.ialocal.diagnostics.AiEventLogger
import java.io.File
import java.security.MessageDigest
import java.util.ArrayDeque
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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

enum class AutomaticModelImportPhase {
    IDLE,
    DISCOVERING,
    IMPORTING,
    COMPLETED,
    PERMISSION_REQUIRED,
    FAILED,
}

/** What is happening to the file currently being processed. */
enum class AutomaticModelImportStep {
    /** A new AI was found in storage and is being checked. */
    FOUND,
    /** The AI is being copied into the app. */
    IMPORTING,
}

/** One-off notices for the user, emitted as the scan progresses. */
sealed interface AutomaticModelImportEvent {
    val modelName: String
    data class Found(override val modelName: String) : AutomaticModelImportEvent
    data class Importing(override val modelName: String) : AutomaticModelImportEvent
}

enum class AutomaticModelScanMode {
    QUICK,
    FULL,
}

data class AutomaticModelImportProgress(
    val phase: AutomaticModelImportPhase = AutomaticModelImportPhase.IDLE,
    val processed: Int = 0,
    val total: Int = 0,
    val currentFileName: String? = null,
    val summary: AutomaticModelImportSummary? = null,
    val currentStep: AutomaticModelImportStep? = null,
    /** Friendly name of the AI in [currentFileName], when one was found. */
    val currentModelName: String? = null,
) {
    val isRunning: Boolean
        get() = phase == AutomaticModelImportPhase.DISCOVERING ||
            phase == AutomaticModelImportPhase.IMPORTING

    val fraction: Float?
        get() = if (phase == AutomaticModelImportPhase.IMPORTING && total > 0) {
            (processed.toFloat() / total.toFloat()).coerceIn(0f, 1f)
        } else {
            null
        }
}

/**
 * Searches shared Android storage for GGUF files and imports compatible models without making the
 * user pick each file manually. The first app scan can use [AutomaticModelScanMode.QUICK] to avoid
 * walking the entire shared storage; the manual scan uses [AutomaticModelScanMode.FULL].
 * Runtime verification is deliberately deferred until the user actually activates/opens a model.
 */
class AutomaticModelImporter(
    context: Context,
    private val repository: ModelRepository,
    private val logger: AiEventLogger? = null,
) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val scanMutex = Mutex()
    private val scanScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inspector = GgufInspector()
    private val compatibilityChecker = DeviceCompatibilityChecker(appContext)
    private val _progress = MutableStateFlow(AutomaticModelImportProgress())

    val progress: StateFlow<AutomaticModelImportProgress> = _progress.asStateFlow()
    private val _events = MutableSharedFlow<AutomaticModelImportEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<AutomaticModelImportEvent> = _events.asSharedFlow()

    fun startScan(mode: AutomaticModelScanMode = AutomaticModelScanMode.FULL) {
        if (_progress.value.isRunning) return

        _progress.value = AutomaticModelImportProgress(
            phase = AutomaticModelImportPhase.DISCOVERING,
        )
        scanScope.launch {
            runCatching { scanAndImport(mode) }
                .onFailure { error ->
                    logger?.error(
                        "AUTO_MODEL_SCAN",
                        "Falha na varredura do armazenamento compartilhado.",
                        error,
                    )
                }
        }
    }

    suspend fun scanAndImport(
        mode: AutomaticModelScanMode = AutomaticModelScanMode.FULL,
    ): AutomaticModelImportSummary = scanMutex.withLock {
        if (!Environment.isExternalStorageManager()) {
            val summary = AutomaticModelImportSummary(permissionRequired = true)
            _progress.value = AutomaticModelImportProgress(
                phase = AutomaticModelImportPhase.PERMISSION_REQUIRED,
                summary = summary,
            )
            return@withLock summary
        }

        _progress.value = AutomaticModelImportProgress(
            phase = AutomaticModelImportPhase.DISCOVERING,
        )

        try {
            val summary = withContext(Dispatchers.IO) {
                val candidates = discoverGgufFiles(mode)
                var installed = repository.getModels().toMutableList()
                var imported = 0
                var alreadyInstalled = 0
                var invalid = 0
                var failures = 0

                _progress.value = AutomaticModelImportProgress(
                    phase = AutomaticModelImportPhase.IMPORTING,
                    total = candidates.size,
                )

                candidates.forEachIndexed { index, file ->
                    _progress.value = _progress.value.copy(
                        processed = index,
                        total = candidates.size,
                        currentFileName = file.name,
                    )

                    try {
                        val fingerprint = fingerprint(file)
                        // A manual search must find a file again after its app-private copy was removed.
                        if (mode == AutomaticModelScanMode.QUICK && preferences.getBoolean(fingerprint, false)) {
                            return@forEachIndexed
                        }

                        val catalogModel = catalogModelForFileName(file.name)
                        if (catalogModel != null && installed.any { it.apiModelId.startsWith(catalogModel.apiIdPrefix) }) {
                            markProcessed(fingerprint)
                            alreadyInstalled += 1
                            return@forEachIndexed
                        }

                        val modelName = catalogModel?.displayName ?: file.nameWithoutExtension
                        _progress.value = _progress.value.copy(
                            currentStep = AutomaticModelImportStep.FOUND,
                            currentModelName = modelName,
                        )
                        _events.tryEmit(AutomaticModelImportEvent.Found(modelName))

                        if (catalogModel != null) {
                            val checksum = runCatching { fileSha256(file) }.getOrElse { error ->
                                failures += 1
                                logger?.error("AUTO_MODEL_SCAN", "Falha ao ler ${file.name}.", error)
                                return@forEachIndexed
                            }
                            if (!checksum.equals(catalogModel.sha256, true)) {
                                markProcessed(fingerprint)
                                invalid += 1
                                logger?.info("AUTO_MODEL_SCAN", "O GGUF ${file.name} não corresponde ao SHA-256 do catálogo.")
                                return@forEachIndexed
                            }
                        }

                        val preview = try {
                            inspectFile(file)
                        } catch (t: Throwable) {
                            // Structurally invalid GGUFs are ignored until the file itself changes.
                            markProcessed(fingerprint)
                            invalid += 1
                            logger?.error("AUTO_MODEL_SCAN", "GGUF inválido ignorado: ${file.absolutePath}", t)
                            return@forEachIndexed
                        }

                        if (!preview.compatibility.canStore) {
                            // Do not remember this as processed: freeing space later must allow a retry.
                            failures += 1
                            logger?.info(
                                "AUTO_MODEL_SCAN",
                                "Sem espaço para importar ${file.name}; tente novamente em Configurações depois de liberar espaço.",
                            )
                            return@forEachIndexed
                        }

                        if (installed.any { sameInstalledModel(it, preview) }) {
                            markProcessed(fingerprint)
                            alreadyInstalled += 1
                            return@forEachIndexed
                        }

                        _progress.value = _progress.value.copy(currentStep = AutomaticModelImportStep.IMPORTING)
                        _events.tryEmit(AutomaticModelImportEvent.Importing(modelName))
                        try {
                            repository.importGguf(preview, catalogModel)
                            installed = repository.getModels().toMutableList()
                            markProcessed(fingerprint)
                            imported += 1
                            logger?.info(
                                "AUTO_MODEL_SCAN",
                                "Modelo importado automaticamente sem carregar o runtime: ${file.absolutePath}",
                            )
                        } catch (t: Throwable) {
                            failures += 1
                            logger?.error("AUTO_MODEL_SCAN", "Falha ao importar automaticamente ${file.absolutePath}", t)
                        }
                    } finally {
                        _progress.value = _progress.value.copy(
                            processed = index + 1,
                            total = candidates.size,
                            currentFileName = null,
                            currentStep = null,
                            currentModelName = null,
                        )
                    }
                }

                AutomaticModelImportSummary(
                    scanned = candidates.size,
                    imported = imported,
                    alreadyInstalled = alreadyInstalled,
                    invalid = invalid,
                    failures = failures,
                )
            }

            _progress.value = AutomaticModelImportProgress(
                phase = AutomaticModelImportPhase.COMPLETED,
                processed = summary.scanned,
                total = summary.scanned,
                summary = summary,
            )
            summary
        } catch (t: Throwable) {
            _progress.value = AutomaticModelImportProgress(
                phase = AutomaticModelImportPhase.FAILED,
            )
            throw t
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

    private fun discoverGgufFiles(mode: AutomaticModelScanMode): List<File> {
        val root = Environment.getExternalStorageDirectory()
        if (!root.isDirectory || !root.canRead()) return emptyList()

        val result = mutableListOf<File>()
        if (mode == AutomaticModelScanMode.QUICK) {
            runCatching { root.listFiles() }.getOrNull().orEmpty()
                .filterTo(result) { file ->
                    file.isFile && file.extension.equals("gguf", ignoreCase = true) && file.length() > 0L
                }
            val quickRoots = listOf(
                File(root, PublicModelDownloads.FOLDER_NAME),
                File(root, Environment.DIRECTORY_DOWNLOADS),
                File(root, Environment.DIRECTORY_DOCUMENTS),
            ).filter { it.isDirectory && it.canRead() }
            result += discoverRecursively(quickRoots)
        } else {
            result += discoverRecursively(listOf(root))
        }

        return result
            .distinctBy { file ->
                runCatching { file.canonicalPath }
                    .getOrElse { file.absolutePath }
            }
            .sortedWith(
                compareByDescending<File> { it.parentFile?.name == PublicModelDownloads.FOLDER_NAME }
                    .thenByDescending { it.lastModified() }
            )
    }

    private fun discoverRecursively(roots: List<File>): List<File> {
        val result = mutableListOf<File>()
        val pending = ArrayDeque<File>()
        val visited = hashSetOf<String>()
        roots.forEach { root -> if (root.isDirectory && root.canRead()) pending.add(root) }

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

    private fun fileSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun markProcessed(key: String) {
        preferences.edit().putBoolean(key, true).apply()
    }

    companion object {
        private const val PREFERENCES_NAME = "automatic_model_importer"
        private const val KEY_PREFIX = "processed_"
    }
}
