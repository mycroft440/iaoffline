package com.example.ialocal.knowledge

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.StatFs
import com.example.ialocal.diagnostics.AiEventLogger
import com.github.luben.zstd.ZstdInputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** A Wikipedia edition published by Kiwix as a ZIM file, refreshed every few months. */
data class WikipediaPack(
    val id: String,
    /** Kiwix file name without the "_YYYY-MM.zim" suffix. */
    val filePrefix: String,
    val title: String,
    val description: String,
    val approximateBytes: Long,
)

enum class WikipediaPackStatus { NOT_INSTALLED, PREPARING, DOWNLOADING, WAITING, VERIFYING, INSTALLED, FAILED }

data class WikipediaPackState(
    val pack: WikipediaPack,
    val status: WikipediaPackStatus = WikipediaPackStatus.NOT_INSTALLED,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = pack.approximateBytes,
    /** The dated Kiwix file installed or being downloaded, e.g. wikipedia_pt_top_mini_2026-10.zim. */
    val fileName: String? = null,
    val message: String? = null,
) {
    val busy: Boolean
        get() = status == WikipediaPackStatus.PREPARING || status == WikipediaPackStatus.DOWNLOADING ||
            status == WikipediaPackStatus.WAITING || status == WikipediaPackStatus.VERIFYING
}

/**
 * Offline Wikipedia for the agents: downloads Kiwix ZIM files with the system DownloadManager (it
 * keeps going in the background and resumes after network drops), checks them against Kiwix's
 * SHA-256 and answers lookups from the installed archives.
 *
 * The user decides what is downloaded: [install] is the only way a download starts, and only the
 * Wikipedia screen calls it, after the user picks an edition and confirms. Nothing downloads or
 * updates on its own, and the agents can only read the editions already installed.
 */
class OfflineWikipedia(
    context: Context,
    private val logger: AiEventLogger? = null,
) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val downloads = appContext.getSystemService(DownloadManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshLock = Mutex()
    private var pollJob: Job? = null
    private val verifying = mutableSetOf<String>()
    private val archiveLock = Any()
    private val openArchives = mutableMapOf<String, ZimArchive>()

    val directory: File
        get() = (appContext.getExternalFilesDir(DIRECTORY) ?: File(appContext.filesDir, DIRECTORY)).apply { mkdirs() }

    private val _states = MutableStateFlow(PACKS.map { WikipediaPackState(it) })
    val states: StateFlow<List<WikipediaPackState>> = _states.asStateFlow()

    private val _autoContext = MutableStateFlow(preferences.getBoolean(KEY_AUTO_CONTEXT, true))
    /** Whether chats automatically get excerpts of the articles a question names. */
    val autoContext: StateFlow<Boolean> = _autoContext.asStateFlow()

    private val lookup = WikipediaLookup(::installedArchives)

    init {
        scope.launch { refresh() }
    }

    fun setAutoContext(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_AUTO_CONTEXT, enabled).apply()
        _autoContext.value = enabled
    }

    fun freeBytes(): Long = runCatching { StatFs(directory.path).availableBytes }.getOrDefault(0L)

    fun isInstalled(): Boolean = _states.value.any { it.status == WikipediaPackStatus.INSTALLED }

    /**
     * Downloads the newest Kiwix release of the edition the user chose and confirmed. With
     * [wifiOnly] the DownloadManager waits for a network that is not metered.
     */
    fun install(packId: String, wifiOnly: Boolean) {
        val pack = PACKS.firstOrNull { it.id == packId } ?: return
        if (_states.value.first { it.pack.id == packId }.busy) return
        update(packId) { it.copy(status = WikipediaPackStatus.PREPARING, message = null) }
        scope.launch {
            try {
                val release = latestRelease(pack)
                val free = freeBytes()
                if (free in 1 until release.sizeBytes + MIN_FREE_MARGIN_BYTES) {
                    error("Espaço insuficiente: são necessários ${formatSize(release.sizeBytes)} e há ${formatSize(free)} livres.")
                }
                val partial = File(directory, release.fileName + PARTIAL_SUFFIX)
                partial.delete()
                val request = DownloadManager.Request(Uri.parse(KIWIX_BASE_URL + release.fileName))
                    .setTitle("Wikipedia Offline: ${pack.title}")
                    .setDescription("Baixando ${release.fileName}")
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalFilesDir(appContext, DIRECTORY, release.fileName + PARTIAL_SUFFIX)
                    .setAllowedOverMetered(!wifiOnly)
                    .setAllowedOverRoaming(false)
                val downloadId = downloads.enqueue(request)
                savePending(pack.id, Pending(downloadId, release.fileName, release.sha256))
                logger?.info("WIKIPEDIA", "Download de ${release.fileName} iniciado")
                refresh()
                startPolling()
            } catch (t: Throwable) {
                logger?.error("WIKIPEDIA", "Falha ao preparar ${pack.id}", t)
                update(packId) { it.copy(status = WikipediaPackStatus.FAILED, message = t.message ?: "Falha ao preparar o download.") }
            }
        }
    }

    fun cancel(packId: String) {
        scope.launch {
            pending(packId)?.let { pending ->
                runCatching { downloads.remove(pending.downloadId) }
                File(directory, pending.fileName + PARTIAL_SUFFIX).delete()
            }
            clearPending(packId)
            refresh()
        }
    }

    fun remove(packId: String) {
        scope.launch {
            preferences.getString(installedKey(packId), null)?.let { fileName ->
                closeArchive(fileName)
                File(directory, fileName).delete()
            }
            preferences.edit().remove(installedKey(packId)).apply()
            refresh()
        }
    }

    /** Re-reads download progress and finishes downloads that completed while the app was away. */
    suspend fun refresh() = refreshLock.withLock {
        val states = PACKS.map { pack -> stateOf(pack) }
        _states.value = states
        if (states.any { it.status == WikipediaPackStatus.DOWNLOADING || it.status == WikipediaPackStatus.WAITING }) {
            startPolling()
        }
    }

    // --- Lookups used by the agents ---

    suspend fun contextFor(question: String): String? = withContext(Dispatchers.IO) {
        if (!_autoContext.value || !isInstalled()) return@withContext null
        runCatching { lookup.contextFor(question) }
            .onFailure { logger?.error("WIKIPEDIA", "Falha ao consultar a Wikipedia offline", it) }
            .getOrNull()
    }

    suspend fun search(query: String, limit: Int): List<WikipediaHit> = withContext(Dispatchers.IO) {
        lookup.search(query, limit).map { (archive, entry) ->
            runCatching { lookup.read(archive, entry, SNIPPET_CHARS, maxFacts = 0) }
                .getOrDefault(WikipediaHit(entry.title, emptyList(), ""))
        }
    }

    suspend fun read(title: String, maxChars: Int): WikipediaHit? = withContext(Dispatchers.IO) {
        val (archive, entry) = lookup.findArticle(title)
            ?: lookup.search(title, 1).firstOrNull()
            ?: return@withContext null
        lookup.read(archive, entry, maxChars)
    }

    private fun installedArchives(): List<ZimArchive> {
        // Full text before introductions and Portuguese before English, so the richest article wins.
        val installed = LOOKUP_ORDER.mapNotNull { id -> PACKS.firstOrNull { it.id == id } }.mapNotNull { pack ->
            preferences.getString(installedKey(pack.id), null)?.let { File(directory, it) }?.takeIf { it.isFile }
        }
        return synchronized(archiveLock) {
            installed.mapNotNull { file ->
                openArchives[file.name] ?: runCatching { ZimArchive.open(file, ZstdDecompressor) }
                    .onFailure { logger?.error("WIKIPEDIA", "Não foi possível abrir ${file.name}", it) }
                    .getOrNull()
                    ?.also { openArchives[file.name] = it }
            }
        }
    }

    private fun closeArchive(fileName: String) = synchronized(archiveLock) {
        openArchives.remove(fileName)?.let { runCatching { it.close() } }
    }

    // --- Download bookkeeping ---

    private fun stateOf(pack: WikipediaPack): WikipediaPackState {
        val current = _states.value.first { it.pack.id == pack.id }
        val pending = pending(pack.id)
        if (pending != null) {
            if (pending.fileName in verifying) {
                return current.copy(status = WikipediaPackStatus.VERIFYING, fileName = pending.fileName, message = null)
            }
            val query = runCatching { downloads.query(DownloadManager.Query().setFilterById(pending.downloadId)) }.getOrNull()
                ?: return current
            query.use { cursor ->
                if (!cursor.moveToFirst()) return@use
                val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val downloaded = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                    .takeIf { it > 0 } ?: pack.approximateBytes
                val reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                return when (status) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        verify(pack, pending)
                        WikipediaPackState(pack, WikipediaPackStatus.VERIFYING, total, total, pending.fileName)
                    }
                    DownloadManager.STATUS_FAILED -> {
                        runCatching { downloads.remove(pending.downloadId) }
                        clearPending(pack.id)
                        WikipediaPackState(pack, WikipediaPackStatus.FAILED, message = failureMessage(reason))
                    }
                    DownloadManager.STATUS_PAUSED -> WikipediaPackState(
                        pack, WikipediaPackStatus.WAITING, downloaded, total, pending.fileName, pausedMessage(reason),
                    )
                    else -> WikipediaPackState(pack, WikipediaPackStatus.DOWNLOADING, downloaded, total, pending.fileName)
                }
            }
            // The system no longer knows this download (data cleared, or removed from its list).
            clearPending(pack.id)
        }
        val installed = preferences.getString(installedKey(pack.id), null)
            ?.let { File(directory, it) }
            ?.takeIf { it.isFile }
        return when {
            installed != null -> WikipediaPackState(
                pack, WikipediaPackStatus.INSTALLED, installed.length(), installed.length(), installed.name,
            )
            current.status == WikipediaPackStatus.PREPARING || current.status == WikipediaPackStatus.FAILED -> current
            else -> WikipediaPackState(pack)
        }
    }

    private fun verify(pack: WikipediaPack, pending: Pending) {
        synchronized(verifying) { if (!verifying.add(pending.fileName)) return }
        scope.launch {
            val partial = File(directory, pending.fileName + PARTIAL_SUFFIX)
            val result = runCatching {
                if (!partial.isFile) throw IOException("O arquivo baixado não foi encontrado.")
                val actual = sha256(partial)
                if (!actual.equals(pending.sha256, ignoreCase = true)) {
                    throw IOException("O arquivo baixado está corrompido (SHA-256 diferente do publicado pelo Kiwix).")
                }
                // Opening it checks that the ZIM is readable before it is offered to the agents.
                ZimArchive.open(partial, ZstdDecompressor).close()
                val target = File(directory, pending.fileName)
                target.delete()
                if (!partial.renameTo(target)) throw IOException("Não foi possível concluir a instalação.")
                val previous = preferences.getString(installedKey(pack.id), null)
                preferences.edit().putString(installedKey(pack.id), pending.fileName).apply()
                if (previous != null && previous != pending.fileName) {
                    closeArchive(previous)
                    File(directory, previous).delete()
                }
            }
            runCatching { downloads.remove(pending.downloadId) }
            partial.delete()
            clearPending(pack.id)
            synchronized(verifying) { verifying.remove(pending.fileName) }
            result.onSuccess { logger?.info("WIKIPEDIA", "${pending.fileName} instalado") }
            refresh()
            result.onFailure { error ->
                logger?.error("WIKIPEDIA", "Falha ao verificar ${pending.fileName}", error)
                update(pack.id) { it.copy(status = WikipediaPackStatus.FAILED, message = error.message) }
            }
        }
    }

    private fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                refresh()
                if (_states.value.none { it.busy }) break
            }
        }
    }

    private data class Release(val fileName: String, val sha256: String, val sizeBytes: Long)

    private fun latestRelease(pack: WikipediaPack): Release {
        val listing = httpText(KIWIX_BASE_URL)
        val fileName = Regex("href=\"(${Regex.escape(pack.filePrefix)}_(\\d{4}-\\d{2})\\.zim)\"")
            .findAll(listing)
            .maxByOrNull { it.groupValues[2] }
            ?.groupValues?.get(1)
            ?: error("O Kiwix não publica mais ${pack.filePrefix}.")
        val sha256 = httpText(KIWIX_BASE_URL + fileName + ".sha256").trim().substringBefore(' ').lowercase()
        require(sha256.matches(Regex("[0-9a-f]{64}"))) { "O Kiwix não publicou o SHA-256 de $fileName." }
        val size = Regex("href=\"${Regex.escape(fileName)}\".*?([0-9.]+)([KMGT])\\s", RegexOption.DOT_MATCHES_ALL)
            .find(listing)
            ?.let { parseSize(it.groupValues[1], it.groupValues[2]) }
            ?: pack.approximateBytes
        return Release(fileName, sha256, size)
    }

    private fun httpText(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "IA-Offline-Android")
        return try {
            val status = connection.responseCode
            if (status !in 200..299) throw IOException("O Kiwix respondeu HTTP $status.")
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private data class Pending(val downloadId: Long, val fileName: String, val sha256: String)

    private fun pending(packId: String): Pending? = preferences.getString(pendingKey(packId), null)?.let { raw ->
        runCatching {
            val json = JSONObject(raw)
            Pending(json.getLong("id"), json.getString("file"), json.getString("sha256"))
        }.getOrNull()
    }

    private fun savePending(packId: String, pending: Pending) {
        val json = JSONObject().put("id", pending.downloadId).put("file", pending.fileName).put("sha256", pending.sha256)
        preferences.edit().putString(pendingKey(packId), json.toString()).apply()
    }

    private fun clearPending(packId: String) {
        preferences.edit().remove(pendingKey(packId)).apply()
    }

    private fun update(packId: String, transform: (WikipediaPackState) -> WikipediaPackState) {
        _states.value = _states.value.map { if (it.pack.id == packId) transform(it) else it }
    }

    private fun failureMessage(reason: Int): String = when (reason) {
        DownloadManager.ERROR_INSUFFICIENT_SPACE -> "Espaço insuficiente para concluir o download."
        DownloadManager.ERROR_DEVICE_NOT_FOUND -> "Armazenamento indisponível."
        DownloadManager.ERROR_HTTP_DATA_ERROR, DownloadManager.ERROR_TOO_MANY_REDIRECTS ->
            "O servidor do Kiwix interrompeu o download. Tente novamente."
        else -> "O download falhou (código $reason). Tente novamente."
    }

    private fun pausedMessage(reason: Int): String = when (reason) {
        DownloadManager.PAUSED_WAITING_FOR_NETWORK -> "Aguardando conexão"
        DownloadManager.PAUSED_QUEUED_FOR_WIFI -> "Aguardando Wi-Fi"
        DownloadManager.PAUSED_WAITING_TO_RETRY -> "Tentando novamente em instantes"
        else -> "Pausado pelo sistema"
    }

    companion object {
        val PACKS = listOf(
            WikipediaPack(
                id = "pt-top-mini",
                filePrefix = "wikipedia_pt_top_mini",
                title = "Português · 50 mil artigos essenciais (resumos)",
                description = "A introdução dos 50 mil artigos mais importantes da Wikipédia em português. Leve e rápido: ideal para começar.",
                approximateBytes = 153_000_000L,
            ),
            WikipediaPack(
                id = "pt-all-mini",
                filePrefix = "wikipedia_pt_all_mini",
                title = "Português · todos os artigos (resumos)",
                description = "A introdução de todos os artigos da Wikipédia em português (mais de 1 milhão). A melhor relação entre tamanho e conhecimento.",
                approximateBytes = 1_700_000_000L,
            ),
            WikipediaPack(
                id = "pt-top-nopic",
                filePrefix = "wikipedia_pt_top_nopic",
                title = "Português · 50 mil artigos essenciais (completos)",
                description = "Os 50 mil artigos mais importantes com o texto completo, sem imagens.",
                approximateBytes = 1_100_000_000L,
            ),
            WikipediaPack(
                id = "pt-all-nopic",
                filePrefix = "wikipedia_pt_all_nopic",
                title = "Português · Wikipédia completa (sem imagens)",
                description = "Todos os artigos da Wikipédia em português com o texto completo, sem imagens.",
                approximateBytes = 7_100_000_000L,
            ),
            WikipediaPack(
                id = "en-top-mini",
                filePrefix = "wikipedia_en_top_mini",
                title = "English · essential articles (intros)",
                description = "A introdução dos artigos mais importantes da Wikipédia em inglês.",
                approximateBytes = 300_000_000L,
            ),
            WikipediaPack(
                id = "en-all-mini",
                filePrefix = "wikipedia_en_all_mini",
                title = "English · all articles (intros)",
                description = "A introdução de todos os artigos da Wikipédia em inglês (mais de 6 milhões). Arquivo muito grande.",
                approximateBytes = 13_900_000_000L,
            ),
        )

        private val LOOKUP_ORDER = listOf("pt-all-nopic", "pt-top-nopic", "pt-all-mini", "pt-top-mini", "en-all-mini", "en-top-mini")

        private const val KIWIX_BASE_URL = "https://download.kiwix.org/zim/wikipedia/"
        private const val DIRECTORY = "wikipedia"
        private const val PARTIAL_SUFFIX = ".part"
        private const val PREFS_NAME = "offline_wikipedia"
        private const val KEY_AUTO_CONTEXT = "auto_context"
        private const val POLL_INTERVAL_MS = 1_000L
        private const val SNIPPET_CHARS = 280
        private const val MIN_FREE_MARGIN_BYTES = 200L * 1024 * 1024

        private fun installedKey(packId: String) = "installed_$packId"
        private fun pendingKey(packId: String) = "pending_$packId"

        private fun parseSize(value: String, unit: String): Long? {
            val number = value.toDoubleOrNull() ?: return null
            val multiplier = when (unit) {
                "K" -> 1024.0
                "M" -> 1024.0 * 1024
                "G" -> 1024.0 * 1024 * 1024
                "T" -> 1024.0 * 1024 * 1024 * 1024
                else -> return null
            }
            return (number * multiplier).toLong()
        }

        fun formatSize(bytes: Long): String {
            val gb = bytes / (1024.0 * 1024 * 1024)
            return if (gb >= 1) "%.1f GB".format(gb) else "%.0f MB".format(bytes / (1024.0 * 1024))
        }

        private fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered(1024 * 1024).use { input ->
                val buffer = ByteArray(1024 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

/** zstd, which every current Kiwix ZIM uses; xz-compressed files from before 2021 are refused. */
object ZstdDecompressor : ZimDecompressor {
    override fun open(compression: Int, compressed: java.io.InputStream): java.io.InputStream = when (compression) {
        5 -> ZstdInputStream(compressed)
        4 -> throw IOException("Este arquivo ZIM usa compressão XZ, que não é suportada. Baixe uma versão atual.")
        else -> throw IOException("Compressão $compression não suportada no arquivo ZIM.")
    }
}
