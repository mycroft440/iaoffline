package com.example.ialocal.models

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import java.io.File

sealed interface CatalogDownloadState {
    data object Idle : CatalogDownloadState
    data class Pending(val downloadedBytes: Long = 0L, val totalBytes: Long = 0L) : CatalogDownloadState
    data class Running(val downloadedBytes: Long, val totalBytes: Long) : CatalogDownloadState
    data class Paused(val downloadedBytes: Long, val totalBytes: Long, val reason: Int) : CatalogDownloadState
    data class Successful(val file: File) : CatalogDownloadState
    data class Failed(val reason: Int) : CatalogDownloadState
}

/**
 * Thin persistent wrapper around Android DownloadManager.
 *
 * DownloadManager owns the long-running transfer, so downloads continue if the activity is
 * recreated. The destination is app-scoped external storage: no storage permission is required,
 * the file is removed with the app, and the GGUF can be adopted in-place without a second 15-20GB
 * copy in private storage.
 */
class CatalogDownloadManager(context: Context) {
    private val appContext = context.applicationContext
    private val downloads = appContext.getSystemService(DownloadManager::class.java)
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun start(model: CatalogModel): Long {
        val existing = downloadId(model.id)
        if (existing != null) return existing

        val destination = destinationFile(model)
        destination.parentFile?.mkdirs()
        if (destination.exists()) destination.delete()

        val request = DownloadManager.Request(Uri.parse(model.downloadUrl))
            .setTitle("${model.name} · ${model.variant}")
            .setDescription("Baixando ${model.fileName}")
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationUri(Uri.fromFile(destination))

        val id = downloads.enqueue(request)
        prefs.edit().putLong(key(model.id), id).apply()
        return id
    }

    fun cancel(modelId: String) {
        downloadId(modelId)?.let(downloads::remove)
        ModelCatalog.byId(modelId)?.let { destinationFile(it).delete() }
        forget(modelId)
    }

    fun forget(modelId: String) {
        prefs.edit().remove(key(modelId)).apply()
    }

    fun state(model: CatalogModel): CatalogDownloadState {
        val id = downloadId(model.id) ?: return CatalogDownloadState.Idle
        val cursor = downloads.query(DownloadManager.Query().setFilterById(id))
        cursor.use {
            if (!it.moveToFirst()) {
                forget(model.id)
                return CatalogDownloadState.Idle
            }

            val status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val downloaded = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)).coerceAtLeast(0L)
            val reason = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
            return when (status) {
                DownloadManager.STATUS_PENDING -> CatalogDownloadState.Pending(downloaded, total)
                DownloadManager.STATUS_RUNNING -> CatalogDownloadState.Running(downloaded, total)
                DownloadManager.STATUS_PAUSED -> CatalogDownloadState.Paused(downloaded, total, reason)
                DownloadManager.STATUS_SUCCESSFUL -> {
                    val file = destinationFile(model)
                    if (file.isFile && file.length() > 0L) CatalogDownloadState.Successful(file)
                    else CatalogDownloadState.Failed(ERROR_MISSING_FILE)
                }
                DownloadManager.STATUS_FAILED -> CatalogDownloadState.Failed(reason)
                else -> CatalogDownloadState.Pending(downloaded, total)
            }
        }
    }

    fun destinationFile(model: CatalogModel): File {
        val root = requireNotNull(appContext.getExternalFilesDir("models")) {
            "Armazenamento externo do app indisponível."
        }
        return File(File(root, model.id), model.fileName)
    }

    private fun downloadId(modelId: String): Long? {
        val value = prefs.getLong(key(modelId), NO_DOWNLOAD)
        return value.takeUnless { it == NO_DOWNLOAD }
    }

    private fun key(modelId: String) = "download_$modelId"

    companion object {
        private const val PREFS_NAME = "catalog_downloads"
        private const val NO_DOWNLOAD = -1L
        const val ERROR_MISSING_FILE = -10_001
    }
}
