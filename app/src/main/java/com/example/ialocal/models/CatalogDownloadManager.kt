package com.example.ialocal.models

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.io.File

sealed interface CatalogDownloadState {
    data object Idle : CatalogDownloadState
    data class Pending(val downloadedBytes: Long = 0L, val totalBytes: Long = 0L) : CatalogDownloadState
    data class Running(val downloadedBytes: Long, val totalBytes: Long) : CatalogDownloadState
    data class Paused(val downloadedBytes: Long, val totalBytes: Long, val reason: String? = null) : CatalogDownloadState
    data class Successful(val file: File) : CatalogDownloadState
    data class Failed(val message: String) : CatalogDownloadState
}

/**
 * Persistent, resumable catalog downloads backed by WorkManager.
 *
 * The worker writes to <model>.gguf.part and resumes with HTTP Range after a user pause,
 * network interruption or process recreation. The final GGUF remains in app-scoped external
 * storage and is adopted in place, so installation never needs a second multi-gigabyte copy.
 */
class CatalogDownloadManager(context: Context) {
    private val appContext = context.applicationContext
    private val workManager = WorkManager.getInstance(appContext)
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun start(model: CatalogModel) {
        val current = state(model)
        if (current is CatalogDownloadState.Running || current is CatalogDownloadState.Pending) return
        if (current is CatalogDownloadState.Successful) return

        setUserPaused(model.id, false)
        markPending(model.id, partialFile(model).length(), savedTotal(model.id))
        enqueue(model, ExistingWorkPolicy.REPLACE)
    }

    fun pause(modelId: String) {
        val model = ModelCatalog.byId(modelId) ?: return
        setUserPaused(modelId, true)
        workManager.cancelUniqueWork(workName(modelId))
        markPaused(
            modelId = modelId,
            downloadedBytes = partialFile(model).length(),
            totalBytes = savedTotal(modelId),
            reason = "Pausado por você",
        )
    }

    fun resume(modelId: String) {
        val model = ModelCatalog.byId(modelId) ?: return
        setUserPaused(modelId, false)
        markPending(modelId, partialFile(model).length(), savedTotal(modelId))
        enqueue(model, ExistingWorkPolicy.REPLACE)
    }

    fun cancel(modelId: String) {
        workManager.cancelUniqueWork(workName(modelId))
        ModelCatalog.byId(modelId)?.let { model ->
            destinationFile(model).delete()
            partialFile(model).delete()
        }
        clear(modelId)
    }

    /** Keeps the downloaded GGUF but removes transfer bookkeeping after successful installation. */
    fun forget(modelId: String) {
        clear(modelId)
    }

    fun state(model: CatalogModel): CatalogDownloadState {
        val destination = destinationFile(model)
        if (destination.isFile && destination.length() > 0L) {
            return CatalogDownloadState.Successful(destination)
        }

        val downloaded = maxOf(savedDownloaded(model.id), partialFile(model).length())
        val total = savedTotal(model.id)
        return when (prefs.getString(keyState(model.id), STATE_IDLE)) {
            STATE_PENDING -> CatalogDownloadState.Pending(downloaded, total)
            STATE_RUNNING -> CatalogDownloadState.Running(downloaded, total)
            STATE_PAUSED -> CatalogDownloadState.Paused(
                downloaded,
                total,
                prefs.getString(keyMessage(model.id), null),
            )
            STATE_FAILED -> CatalogDownloadState.Failed(
                prefs.getString(keyMessage(model.id), "Falha no download.") ?: "Falha no download."
            )
            else -> if (downloaded > 0L) {
                CatalogDownloadState.Paused(downloaded, total, "Download parcial disponível")
            } else {
                CatalogDownloadState.Idle
            }
        }
    }

    fun destinationFile(model: CatalogModel): File {
        val root = requireNotNull(appContext.getExternalFilesDir("models")) {
            "Armazenamento externo do app indisponível."
        }
        return File(File(root, model.id), model.fileName)
    }

    internal fun partialFile(model: CatalogModel): File {
        val destination = destinationFile(model)
        destination.parentFile?.mkdirs()
        return File(destination.parentFile, destination.name + ".part")
    }

    internal fun isUserPaused(modelId: String): Boolean =
        prefs.getBoolean(keyPaused(modelId), false)

    internal fun markPending(modelId: String, downloadedBytes: Long, totalBytes: Long) {
        writeState(modelId, STATE_PENDING, downloadedBytes, totalBytes, null)
    }

    internal fun markRunning(modelId: String, downloadedBytes: Long, totalBytes: Long) {
        writeState(modelId, STATE_RUNNING, downloadedBytes, totalBytes, null)
    }

    internal fun markPaused(modelId: String, downloadedBytes: Long, totalBytes: Long, reason: String?) {
        writeState(modelId, STATE_PAUSED, downloadedBytes, totalBytes, reason)
    }

    internal fun markSuccessful(modelId: String, downloadedBytes: Long) {
        writeState(modelId, STATE_SUCCESS, downloadedBytes, downloadedBytes, null)
    }

    internal fun markFailed(modelId: String, downloadedBytes: Long, totalBytes: Long, message: String) {
        writeState(modelId, STATE_FAILED, downloadedBytes, totalBytes, message)
    }

    private fun enqueue(model: CatalogModel, policy: ExistingWorkPolicy) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<CatalogDownloadWorker>()
            .setInputData(workDataOf(CatalogDownloadWorker.KEY_MODEL_ID to model.id))
            .setConstraints(constraints)
            .addTag(WORK_TAG)
            .build()
        workManager.enqueueUniqueWork(workName(model.id), policy, request)
    }

    private fun setUserPaused(modelId: String, paused: Boolean) {
        prefs.edit().putBoolean(keyPaused(modelId), paused).apply()
    }

    private fun savedDownloaded(modelId: String): Long = prefs.getLong(keyDownloaded(modelId), 0L)
    private fun savedTotal(modelId: String): Long = prefs.getLong(keyTotal(modelId), 0L)

    private fun writeState(
        modelId: String,
        state: String,
        downloadedBytes: Long,
        totalBytes: Long,
        message: String?,
    ) {
        prefs.edit()
            .putString(keyState(modelId), state)
            .putLong(keyDownloaded(modelId), downloadedBytes.coerceAtLeast(0L))
            .putLong(keyTotal(modelId), totalBytes.coerceAtLeast(0L))
            .apply {
                if (message == null) remove(keyMessage(modelId)) else putString(keyMessage(modelId), message)
            }
            .apply()
    }

    private fun clear(modelId: String) {
        prefs.edit()
            .remove(keyState(modelId))
            .remove(keyDownloaded(modelId))
            .remove(keyTotal(modelId))
            .remove(keyMessage(modelId))
            .remove(keyPaused(modelId))
            .apply()
    }

    private fun workName(modelId: String) = "catalog-download-$modelId"
    private fun keyState(modelId: String) = "state_$modelId"
    private fun keyDownloaded(modelId: String) = "downloaded_$modelId"
    private fun keyTotal(modelId: String) = "total_$modelId"
    private fun keyMessage(modelId: String) = "message_$modelId"
    private fun keyPaused(modelId: String) = "paused_$modelId"

    companion object {
        private const val PREFS_NAME = "catalog_downloads_v2"
        private const val WORK_TAG = "catalog-model-download"
        private const val STATE_IDLE = "IDLE"
        private const val STATE_PENDING = "PENDING"
        private const val STATE_RUNNING = "RUNNING"
        private const val STATE_PAUSED = "PAUSED"
        private const val STATE_SUCCESS = "SUCCESS"
        private const val STATE_FAILED = "FAILED"
    }
}
