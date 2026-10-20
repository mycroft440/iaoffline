package com.example.ialocal.models

import android.content.Context
import android.net.Uri
import android.os.Environment
import com.example.ialocal.data.AiModelEntity
import com.example.ialocal.diagnostics.AiEventLogger
import java.io.File
import java.security.MessageDigest
import java.util.ArrayDeque
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
    /** The user stopped the search; what was imported before that stays installed. */
    CANCELLED,
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
    /** Every GGUF file found so far, in the order they are imported, with what happened to each. */
    val found: List<FoundStorageModel> = emptyList(),
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
 * Newly registered GGUF models are queued for automatic inference verification in a foreground
 * service, even when the user leaves the storage scan screen.
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
    private var scanJob: Job? = null

    fun startScan(mode: AutomaticModelScanMode = AutomaticModelScanMode.FULL) {
        if (_progress.value.isRunning) return

        _progress.value = AutomaticModelImportProgress(
            phase = AutomaticModelImportPhase.DISCOVERING,
        )
        scanJob = scanScope.launch {
            runCatching { scanAndImport(mode) }
                .onFailure { error ->
                    if (error is CancellationException) return@onFailure
                    logger?.error(
                        "AUTO_MODEL_SCAN",
                        "Falha na varredura do armazenamento compartilhado.",
                        error,
                    )
                }
        }.also { job ->
            // Cancelled before the search itself started, so nothing moved the progress on.
            job.invokeOnCompletion { cause ->
                if (cause is CancellationException && _progress.value.isRunning) {
                    _progress.value = AutomaticModelImportProgress(
                        phase = AutomaticModelImportPhase.CANCELLED,
                        found = _progress.value.found.cancelPending(),
                    )
                }
            }
        }
    }

    /**
     * Stops the search started by [startScan]. A copy in progress is stopped and removed; AIs
     * imported before it stay installed.
     */
    fun cancelScan() {
        scanJob?.cancel()
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

        var processed = 0
        var imported = 0
        var alreadyInstalled = 0
        var invalid = 0
        var failures = 0
        try {
            val summary = withContext(Dispatchers.IO) {
                val candidates = discoverGgufFiles(mode) { file ->
                    _progress.value = _progress.value.copy(found = _progress.value.found + foundModel(file))
                }
                var installed = repository.getModels().toMutableList()

                _progress.value = AutomaticModelImportProgress(
                    phase = AutomaticModelImportPhase.IMPORTING,
                    total = candidates.size,
                    found = candidates.map(::foundModel),
                )

                candidates.forEachIndexed { index, file ->
                    _progress.value = _progress.value.copy(
                        processed = index,
                        total = candidates.size,
                        currentFileName = file.name,
                    )
                    fun mark(status: FoundModelStatus, detail: String? = null) {
                        _progress.value = _progress.value.copy(
                            found = _progress.value.found.update(index) {
                                it.copy(status = status, detail = detail, copiedBytes = 0L)
                            },
                        )
                    }
                    mark(FoundModelStatus.CHECKING)

                    try {
                        val fingerprint = fingerprint(file)
                        // A manual search must find a file again after its app-private copy was removed.
                        if (mode == AutomaticModelScanMode.QUICK && preferences.getBoolean(fingerprint, false)) {
                            mark(FoundModelStatus.SEEN_BEFORE)
                            return@forEachIndexed
                        }

                        val catalogModel = catalogModelForFileName(file.name)
                        if (catalogModel != null && installed.any { it.apiModelId.startsWith(catalogModel.apiIdPrefix) }) {
                            markProcessed(fingerprint)
                            alreadyInstalled += 1
                            mark(FoundModelStatus.ALREADY_INSTALLED)
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
                                if (error is CancellationException) throw error
                                failures += 1
                                logger?.error("AUTO_MODEL_SCAN", "Falha ao ler ${file.name}.", error)
                                mark(FoundModelStatus.FAILED, "Não foi possível ler o arquivo.")
                                return@forEachIndexed
                            }
                            if (!checksum.equals(catalogModel.sha256, true)) {
                                markProcessed(fingerprint)
                                invalid += 1
                                logger?.info("AUTO_MODEL_SCAN", "O GGUF ${file.name} não corresponde ao SHA-256 do catálogo.")
                                mark(FoundModelStatus.INVALID, "O arquivo não corresponde ao do catálogo.")
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
                            mark(FoundModelStatus.INVALID)
                            return@forEachIndexed
                        }

                        if (!preview.compatibility.canStore) {
                            // Do not remember this as processed: freeing space later must allow a retry.
                            failures += 1
                            logger?.info(
                                "AUTO_MODEL_SCAN",
                                "Sem espaço para importar ${file.name}; tente novamente em Configurações depois de liberar espaço.",
                            )
                            mark(FoundModelStatus.FAILED, "Sem espaço livre no aparelho.")
                            return@forEachIndexed
                        }

                        if (installed.any { sameInstalledModel(it, preview) }) {
                            markProcessed(fingerprint)
                            alreadyInstalled += 1
                            mark(FoundModelStatus.ALREADY_INSTALLED)
                            return@forEachIndexed
                        }

                        _progress.value = _progress.value.copy(currentStep = AutomaticModelImportStep.IMPORTING)
                        mark(FoundModelStatus.IMPORTING)
                        _events.tryEmit(AutomaticModelImportEvent.Importing(modelName))
                        try {
                            val newlyInstalled = repository.importGguf(preview, catalogModel) { copied ->
                                _progress.value = _progress.value.copy(
                                    found = _progress.value.found.update(index) { it.copy(copiedBytes = copied) },
                                )
                            }
                            // Model is already installed: its real inference test must proceed even
                            // if the scanner UI is closed or the user switches apps.
                            BackgroundModelVerificationService.enqueue(appContext, newlyInstalled.id)
                            // Counted before the next suspension, so a cancel from here on keeps it as imported.
                            imported += 1
                            markProcessed(fingerprint)
                            mark(FoundModelStatus.IMPORTED)
                            installed = repository.getModels().toMutableList()
                            logger?.info(
                                "AUTO_MODEL_SCAN",
                                "Modelo importado; teste de inferência agendado em segundo plano: ${file.absolutePath}",
                            )
                        } catch (e: CancellationException) {
                            throw e
                        } catch (t: Throwable) {
                            failures += 1
                            logger?.error("AUTO_MODEL_SCAN", "Falha ao importar automaticamente ${file.absolutePath}", t)
                            mark(FoundModelStatus.FAILED, t.message)
                        }
                    } finally {
                        processed = index + 1
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
                found = _progress.value.found,
            )
            summary
        } catch (e: CancellationException) {
            val found = _progress.value.found
            logger?.info("AUTO_MODEL_SCAN", "Busca cancelada pelo usuário depois de $imported importação(ões).")
            _progress.value = AutomaticModelImportProgress(
                phase = AutomaticModelImportPhase.CANCELLED,
                processed = processed,
                total = found.size,
                summary = AutomaticModelImportSummary(
                    scanned = processed,
                    imported = imported,
                    alreadyInstalled = alreadyInstalled,
                    invalid = invalid,
                    failures = failures,
                ),
                found = found.cancelPending(),
            )
            throw e
        } catch (t: Throwable) {
            _progress.value = AutomaticModelImportProgress(
                phase = AutomaticModelImportPhase.FAILED,
                found = _progress.value.found,
            )
            throw t
        }
    }

    private fun foundModel(file: File) = FoundStorageModel(
        path = file.absolutePath,
        fileName = file.name,
        name = catalogModelForFileName(file.name)?.displayName ?: file.nameWithoutExtension,
        sizeBytes = file.length(),
    )

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

    /** The GGUF files in storage, in import order; [onFound] hears of each as the walk finds it. */
    private suspend fun discoverGgufFiles(mode: AutomaticModelScanMode, onFound: (File) -> Unit): List<File> {
        val root = Environment.getExternalStorageDirectory()
        if (!root.isDirectory || !root.canRead()) return emptyList()

        val result = mutableListOf<File>()
        if (mode == AutomaticModelScanMode.QUICK) {
            runCatching { root.listFiles() }.getOrNull().orEmpty()
                .filter { file ->
                    file.isFile && file.extension.equals("gguf", ignoreCase = true) && file.length() > 0L
                }
                .forEach { file ->
                    result += file
                    onFound(file)
                }
            val quickRoots = listOf(
                File(root, PublicModelDownloads.FOLDER_NAME),
                File(root, Environment.DIRECTORY_DOWNLOADS),
                File(root, Environment.DIRECTORY_DOCUMENTS),
            ).filter { it.isDirectory && it.canRead() }
            result += discoverRecursively(quickRoots, onFound)
        } else {
            result += discoverRecursively(listOf(root), onFound)
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

    private suspend fun discoverRecursively(roots: List<File>, onFound: (File) -> Unit): List<File> {
        val result = mutableListOf<File>()
        val pending = ArrayDeque<File>()
        val visited = hashSetOf<String>()
        roots.forEach { root -> if (root.isDirectory && root.canRead()) pending.add(root) }

        while (pending.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val directory = pending.removeFirst()
            val canonical = runCatching { directory.canonicalPath }.getOrElse { directory.absolutePath }
            if (!visited.add(canonical)) continue

            val children = runCatching { directory.listFiles() }.getOrNull() ?: continue
            children.forEach { child ->
                when {
                    child.isDirectory && shouldEnter(child) -> pending.addLast(child)
                    child.isFile && child.extension.equals("gguf", ignoreCase = true) && child.length() > 0L -> {
                        result += child
                        onFound(child)
                    }
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

    private suspend fun fileSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
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
