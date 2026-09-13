package com.example.ialocal.models

import android.content.Context
import android.os.SystemClock
import com.example.ialocal.diagnostics.AiEventLogger
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

enum class ModelDownloadPhase {
    IDLE,
    CHECKING,
    DOWNLOADING,
    VERIFYING_FILE,
    IMPORTING,
    VERIFYING_MODEL,
    COMPLETE,
    ERROR,
    CANCELLED,
}

data class ModelDownloadState(
    val catalogId: String? = null,
    val phase: ModelDownloadPhase = ModelDownloadPhase.IDLE,
    val downloadedBytes: Long = 0,
    val totalBytes: Long? = null,
    val message: String? = null,
) {
    val isBusy: Boolean
        get() = phase in setOf(
            ModelDownloadPhase.CHECKING,
            ModelDownloadPhase.DOWNLOADING,
            ModelDownloadPhase.VERIFYING_FILE,
            ModelDownloadPhase.IMPORTING,
            ModelDownloadPhase.VERIFYING_MODEL,
        )

    val progress: Float?
        get() = totalBytes?.takeIf { it > 0 }?.let {
            (downloadedBytes.toDouble() / it.toDouble()).coerceIn(0.0, 1.0).toFloat()
        }
}

/** Downloads catalog GGUFs into app-private storage and verifies their pinned SHA-256. */
class ModelDownloader(
    context: Context,
    private val compatibilityChecker: DeviceCompatibilityChecker = DeviceCompatibilityChecker(context),
    private val logger: AiEventLogger? = null,
) {
    private val appContext = context.applicationContext
    private val downloadDir = File(appContext.filesDir, "model-downloads")

    suspend fun download(
        model: CatalogModel,
        onState: (ModelDownloadState) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        downloadDir.mkdirs()
        require(downloadDir.isDirectory) { "Não foi possível preparar a pasta privada de downloads." }

        val complete = File(downloadDir, "${model.id}.gguf")
        val partial = File(downloadDir, "${model.id}.part")
        val initial = ModelDownloadState(
            catalogId = model.id,
            phase = ModelDownloadPhase.CHECKING,
            downloadedBytes = partial.length(),
            totalBytes = model.approximateSizeBytes,
            message = "Verificando aparelho e armazenamento…",
        )
        onState(initial)

        if (complete.isFile) {
            onState(initial.copy(phase = ModelDownloadPhase.VERIFYING_FILE, message = "Verificando download existente…"))
            if (sha256(complete).equals(model.sha256, ignoreCase = true)) {
                return@withContext complete
            }
            complete.delete()
        }

        val compatibility = compatibilityChecker.check(model.approximateSizeBytes)
        require(compatibility.supportedAbi) {
            "Este aparelho usa ${compatibility.primaryAbi}; o runtime exige arm64-v8a ou x86_64."
        }
        require(compatibility.likelyFitsRam != false) {
            "${model.displayName} é grande demais para a RAM estimada deste aparelho. Escolha um modelo menor para evitar travamentos durante a carga."
        }

        if (partial.length() > model.approximateSizeBytes + PARTIAL_SIZE_TOLERANCE) {
            partial.delete()
        }

        // If the process was cancelled after the network transfer but before/during hashing, avoid
        // throwing away a complete multi-GB file. Only pay for this hash when the partial is very
        // close to the catalog size; ordinary resumptions continue directly with HTTP Range.
        if (partial.isFile && partial.length() >= nearCompleteThreshold(model.approximateSizeBytes)) {
            onState(
                initial.copy(
                    phase = ModelDownloadPhase.VERIFYING_FILE,
                    downloadedBytes = partial.length(),
                    totalBytes = partial.length(),
                    message = "Verificando download interrompido…",
                )
            )
            if (sha256(partial).equals(model.sha256, ignoreCase = true)) {
                finalizeVerifiedDownload(partial, complete)
                logger?.info("MODEL_DOWNLOAD", "Download interrompido já estava completo: ${model.displayName}")
                return@withContext complete
            }
        }

        val estimatedRemaining = (model.approximateSizeBytes - partial.length()).coerceAtLeast(0L)
        require(compatibility.availableStorageBytes > estimatedRemaining + STORAGE_HEADROOM) {
            "Espaço insuficiente. Libere armazenamento antes de baixar ${model.displayName}."
        }

        logger?.info("MODEL_DOWNLOAD", "Iniciando ${model.displayName}; parcial=${partial.length()} bytes")
        downloadBody(model, partial, onState)

        onState(
            ModelDownloadState(
                catalogId = model.id,
                phase = ModelDownloadPhase.VERIFYING_FILE,
                downloadedBytes = partial.length(),
                totalBytes = partial.length(),
                message = "Verificando integridade SHA-256…",
            )
        )
        val actualSha = sha256(partial)
        if (!actualSha.equals(model.sha256, ignoreCase = true)) {
            partial.delete()
            throw IllegalStateException(
                "O arquivo baixado falhou na verificação de integridade. O download foi descartado para sua segurança."
            )
        }

        finalizeVerifiedDownload(partial, complete)
        logger?.info("MODEL_DOWNLOAD", "Download verificado: ${model.displayName} (${complete.length()} bytes)")
        complete
    }

    private suspend fun downloadBody(
        model: CatalogModel,
        partial: File,
        onState: (ModelDownloadState) -> Unit,
    ) {
        var offset = partial.length()
        var connection = openFollowingRedirects(model.downloadUrl, offset)
        var code = connection.responseCode

        // Some CDNs ignore Range, and a stale partial can also produce 416. Restart safely in both cases.
        if (offset > 0 && (code == HttpURLConnection.HTTP_OK || code == HTTP_RANGE_NOT_SATISFIABLE)) {
            connection.disconnect()
            partial.delete()
            offset = 0L
            connection = openFollowingRedirects(model.downloadUrl, 0L)
            code = connection.responseCode
        }

        require(code == HttpURLConnection.HTTP_OK || code == HttpURLConnection.HTTP_PARTIAL) {
            "Falha ao baixar ${model.displayName}: HTTP $code."
        }
        if (offset > 0) {
            require(code == HttpURLConnection.HTTP_PARTIAL) {
                "O servidor não permitiu continuar o download parcial. Tente novamente."
            }
        }

        val totalBytes = responseTotalBytes(connection, offset)
        if (totalBytes != null && offset > totalBytes) {
            connection.disconnect()
            partial.delete()
            return downloadBody(model, partial, onState)
        }

        onState(
            ModelDownloadState(
                catalogId = model.id,
                phase = ModelDownloadPhase.DOWNLOADING,
                downloadedBytes = offset,
                totalBytes = totalBytes ?: model.approximateSizeBytes,
                message = if (offset > 0) "Continuando download…" else "Baixando modelo…",
            )
        )

        try {
            BufferedInputStream(connection.inputStream, BUFFER_SIZE).use { input ->
                FileOutputStream(partial, offset > 0).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var downloaded = offset
                    var lastReport = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastReport >= PROGRESS_INTERVAL_MS) {
                            lastReport = now
                            onState(
                                ModelDownloadState(
                                    catalogId = model.id,
                                    phase = ModelDownloadPhase.DOWNLOADING,
                                    downloadedBytes = downloaded,
                                    totalBytes = totalBytes ?: model.approximateSizeBytes,
                                    message = "Baixando modelo…",
                                )
                            )
                        }
                    }
                    output.fd.sync()
                }
            }
        } finally {
            connection.disconnect()
        }

        if (totalBytes != null) {
            require(partial.length() == totalBytes) {
                "O download terminou incompleto (${partial.length()} de $totalBytes bytes). Tente novamente para continuar."
            }
        }
    }

    private fun openFollowingRedirects(url: String, offset: Long): HttpURLConnection {
        var current = URL(url)
        repeat(MAX_REDIRECTS + 1) { hop ->
            require(current.protocol.equals("https", ignoreCase = true)) {
                "Download bloqueado porque o servidor tentou usar uma conexão não HTTPS."
            }
            val connection = (current.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                requestMethod = "GET"
                setRequestProperty("Accept-Encoding", "identity")
                setRequestProperty("User-Agent", "IA-Offline-Android/0.2")
                if (offset > 0) setRequestProperty("Range", "bytes=$offset-")
            }
            val code = connection.responseCode
            if (code !in 300..399) return connection

            val location = connection.getHeaderField("Location")
                ?: throw IllegalStateException("Redirecionamento de download sem destino.")
            connection.disconnect()
            if (hop == MAX_REDIRECTS) throw IllegalStateException("O download excedeu o limite de redirecionamentos.")
            current = URL(current, location)
        }
        error("Redirecionamento de download inválido.")
    }

    private fun responseTotalBytes(connection: HttpURLConnection, offset: Long): Long? {
        if (connection.responseCode == HttpURLConnection.HTTP_PARTIAL) {
            val range = connection.getHeaderField("Content-Range")
            val total = range?.substringAfterLast('/')?.toLongOrNull()
            if (total != null) return total
        }
        return connection.contentLengthLong.takeIf { it > 0 }?.let { length ->
            if (connection.responseCode == HttpURLConnection.HTTP_PARTIAL) length + offset else length
        }
    }

    private fun finalizeVerifiedDownload(partial: File, complete: File) {
        if (complete.exists() && !complete.delete()) {
            throw IllegalStateException("Não foi possível substituir um download antigo.")
        }
        if (!partial.renameTo(complete)) {
            partial.copyTo(complete, overwrite = true)
            require(complete.length() == partial.length()) { "A cópia final do download ficou incompleta." }
            if (!partial.delete()) logger?.info("MODEL_DOWNLOAD", "Não foi possível apagar o arquivo parcial após a cópia")
        }
    }

    private fun nearCompleteThreshold(approximateSizeBytes: Long): Long =
        (approximateSizeBytes * NEAR_COMPLETE_RATIO).toLong()

    private suspend fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    companion object {
        private const val BUFFER_SIZE = 1024 * 1024
        private const val CONNECT_TIMEOUT_MS = 20_000
        private const val READ_TIMEOUT_MS = 60_000
        private const val PROGRESS_INTERVAL_MS = 300L
        private const val MAX_REDIRECTS = 8
        private const val HTTP_RANGE_NOT_SATISFIABLE = 416
        private const val STORAGE_HEADROOM = 256L * 1024 * 1024
        private const val PARTIAL_SIZE_TOLERANCE = 512L * 1024 * 1024
        private const val NEAR_COMPLETE_RATIO = 0.97
    }
}
