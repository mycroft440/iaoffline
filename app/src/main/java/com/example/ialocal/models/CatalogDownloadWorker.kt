package com.example.ialocal.models

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** Downloads one catalog GGUF with true pause/resume semantics using HTTP Range. */
class CatalogDownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    private val manager = CatalogDownloadManager(appContext)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val modelId = inputData.getString(KEY_MODEL_ID) ?: return@withContext Result.failure()
        val model = ModelCatalog.byId(modelId) ?: return@withContext Result.failure()
        val partial = manager.partialFile(model)
        val destination = manager.destinationFile(model)

        if (destination.isFile && destination.length() > 0L) {
            manager.markSuccessful(modelId, destination.length())
            return@withContext Result.success()
        }
        if (manager.isUserPaused(modelId)) {
            return@withContext Result.success()
        }

        partial.parentFile?.mkdirs()
        var connection: HttpURLConnection? = null
        try {
            var offset = partial.length().coerceAtLeast(0L)
            connection = openConnection(model.downloadUrl, offset)
            var responseCode = connection.responseCode

            // Some hosts ignore Range. Restart safely rather than appending a second file to the first.
            if (offset > 0L && responseCode == HttpURLConnection.HTTP_OK) {
                RandomAccessFile(partial, "rw").use { it.setLength(0L) }
                offset = 0L
                connection.disconnect()
                connection = openConnection(model.downloadUrl, 0L)
                responseCode = connection.responseCode
            }

            if (responseCode != HttpURLConnection.HTTP_OK && responseCode != HttpURLConnection.HTTP_PARTIAL) {
                val message = "Servidor recusou o download (HTTP $responseCode)."
                manager.markFailed(modelId, partial.length(), 0L, message)
                return@withContext if (responseCode >= 500) Result.retry() else Result.failure()
            }

            val activeConnection = requireNotNull(connection)
            val totalBytes = resolveTotalBytes(activeConnection, offset)
            manager.markRunning(modelId, offset, totalBytes)
            setForeground(createForegroundInfo(model, offset, totalBytes))

            RandomAccessFile(partial, "rw").use { output ->
                output.seek(offset)
                activeConnection.inputStream.buffered(BUFFER_SIZE).use { input ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var downloaded = offset
                    var lastPublishedBytes = downloaded
                    var lastPublishedAt = System.currentTimeMillis()
                    while (true) {
                        coroutineContext.ensureActive()
                        if (manager.isUserPaused(modelId)) {
                            manager.markPaused(modelId, downloaded, totalBytes, "Pausado por você")
                            return@withContext Result.success()
                        }

                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        downloaded += count

                        val now = System.currentTimeMillis()
                        if (
                            downloaded - lastPublishedBytes >= PROGRESS_BYTES ||
                            now - lastPublishedAt >= PROGRESS_INTERVAL_MS
                        ) {
                            manager.markRunning(modelId, downloaded, totalBytes)
                            setForeground(createForegroundInfo(model, downloaded, totalBytes))
                            lastPublishedBytes = downloaded
                            lastPublishedAt = now
                        }
                    }
                }
            }

            val completedSize = partial.length()
            if (totalBytes > 0L && completedSize < totalBytes) {
                manager.markPaused(
                    modelId,
                    completedSize,
                    totalBytes,
                    "A conexão terminou antes do arquivo completo. O app continuará do ponto salvo.",
                )
                return@withContext Result.retry()
            }

            if (destination.exists() && !destination.delete()) {
                throw IOException("Não foi possível substituir um download antigo.")
            }
            if (!partial.renameTo(destination)) {
                throw IOException("Não foi possível finalizar o arquivo baixado sem criar uma segunda cópia.")
            }
            manager.markSuccessful(modelId, destination.length())
            Result.success()
        } catch (cancelled: CancellationException) {
            if (!manager.isUserPaused(modelId)) {
                val downloaded = partial.length()
                manager.markPaused(
                    modelId,
                    downloaded,
                    currentTotal(model),
                    "Download interrompido pelo Android; os dados já baixados foram preservados.",
                )
            }
            throw cancelled
        } catch (t: Throwable) {
            val downloaded = partial.length()
            val message = t.message ?: "Falha de rede durante o download."
            manager.markPaused(
                modelId,
                downloaded,
                currentTotal(model),
                "$message O app tentará continuar do ponto salvo.",
            )
            Result.retry()
        } finally {
            connection?.disconnect()
        }
    }

    private fun currentTotal(model: CatalogModel): Long = when (val state = manager.state(model)) {
        is CatalogDownloadState.Pending -> state.totalBytes
        is CatalogDownloadState.Running -> state.totalBytes
        is CatalogDownloadState.Paused -> state.totalBytes
        else -> 0L
    }

    private fun openConnection(url: String, offset: Long): HttpURLConnection {
        var current = URL(url)
        repeat(MAX_REDIRECTS + 1) { redirect ->
            val connection = current.openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.setRequestProperty("User-Agent", "IAOffline-Android")
            if (offset > 0L) connection.setRequestProperty("Range", "bytes=$offset-")
            connection.connect()

            if (connection.responseCode !in 300..399) return connection
            val location = connection.getHeaderField("Location")
                ?: throw IOException("Redirecionamento de download sem destino.")
            connection.disconnect()
            if (redirect == MAX_REDIRECTS) throw IOException("Muitos redirecionamentos no download.")
            current = URL(current, location)
        }
        throw IOException("Não foi possível abrir o download.")
    }

    private fun resolveTotalBytes(connection: HttpURLConnection, offset: Long): Long {
        val contentRange = connection.getHeaderField("Content-Range")
        val totalFromRange = contentRange
            ?.substringAfterLast('/', "")
            ?.takeUnless { it == "*" }
            ?.toLongOrNull()
        if (totalFromRange != null && totalFromRange > 0L) return totalFromRange
        val contentLength = connection.contentLengthLong.coerceAtLeast(0L)
        return if (connection.responseCode == HttpURLConnection.HTTP_PARTIAL) {
            offset + contentLength
        } else {
            contentLength
        }
    }

    private fun createForegroundInfo(
        model: CatalogModel,
        downloadedBytes: Long,
        totalBytes: Long,
    ): ForegroundInfo {
        val notifications = applicationContext.getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Downloads de modelos de IA",
                NotificationManager.IMPORTANCE_LOW,
            )
        )

        val progress = if (totalBytes > 0L) {
            ((downloadedBytes * 100L) / totalBytes).coerceIn(0L, 100L).toInt()
        } else {
            0
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Baixando ${model.name}")
            .setContentText(
                if (totalBytes > 0L) "$progress% concluído" else "Download em andamento"
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, progress, totalBytes <= 0L)
            .build()

        return ForegroundInfo(
            notificationId(model.id),
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    private fun notificationId(modelId: String): Int =
        (modelId.hashCode() and 0x3fffffff) + NOTIFICATION_ID_BASE

    companion object {
        const val KEY_MODEL_ID = "catalog_model_id"
        private const val CHANNEL_ID = "model_downloads"
        private const val NOTIFICATION_ID_BASE = 10_000
        private const val BUFFER_SIZE = 1024 * 1024
        private const val PROGRESS_BYTES = 4L * 1024L * 1024L
        private const val PROGRESS_INTERVAL_MS = 1_000L
        private const val CONNECT_TIMEOUT_MS = 30_000
        private const val READ_TIMEOUT_MS = 60_000
        private const val MAX_REDIRECTS = 6
    }
}
