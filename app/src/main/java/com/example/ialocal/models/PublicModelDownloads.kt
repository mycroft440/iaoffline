package com.example.ialocal.models

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Publishes verified model files in the user-visible Downloads collection using scoped storage. */
class PublicModelDownloads(context: Context) {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver

    /**
     * MediaStore only materializes a relative folder when it contains an item. A tiny read-me keeps
     * the requested folder visible even before the first model is downloaded.
     */
    fun ensureFolder() {
        synchronized(folderLock) {
            if (findByDisplayName(README_FILE_NAME) != null) return

            val values = baseValues(README_FILE_NAME, "text/plain").apply {
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Não foi possível criar a pasta pública de modelos.")
            try {
                val text = buildString {
                    appendLine("Modelos de I.A offline")
                    appendLine()
                    appendLine("Os modelos baixados e verificados pelo app são publicados nesta pasta.")
                    appendLine("O app mantém também uma cópia privada necessária para executar o GGUF com segurança.")
                }
                resolver.openOutputStream(uri, "w")?.bufferedWriter(Charsets.UTF_8)?.use { it.write(text) }
                    ?: throw IllegalStateException("Não foi possível inicializar a pasta pública de modelos.")
                markReady(uri)
            } catch (t: Throwable) {
                resolver.delete(uri, null, null)
                throw t
            }
        }
    }

    suspend fun publishVerifiedModel(model: CatalogModel, source: File): Uri = withContext(Dispatchers.IO) {
        require(source.isFile && source.length() > 0L) { "O modelo verificado não está disponível para publicação." }
        ensureFolder()

        synchronized(folderLock) {
            findByDisplayName(model.fileName)?.let { resolver.delete(it, null, null) }

            val values = baseValues(model.fileName, "application/octet-stream").apply {
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Não foi possível salvar o modelo na pasta Downloads/$FOLDER_NAME.")
            try {
                val copied = source.inputStream().buffered(MODEL_COPY_BUFFER).use { input ->
                    resolver.openOutputStream(uri, "w")?.buffered(MODEL_COPY_BUFFER)?.use { output ->
                        input.copyTo(output, MODEL_COPY_BUFFER)
                    } ?: throw IllegalStateException("Não foi possível abrir o destino público do modelo.")
                }
                require(copied == source.length()) {
                    "A cópia para Downloads/$FOLDER_NAME ficou incompleta."
                }
                markReady(uri)
                uri
            } catch (t: Throwable) {
                resolver.delete(uri, null, null)
                throw t
            }
        }
    }

    private fun baseValues(displayName: String, mimeType: String) = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
        put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
        put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_PATH)
    }

    private fun markReady(uri: Uri) {
        val ready = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        resolver.update(uri, ready, null, null)
    }

    private fun findByDisplayName(displayName: String): Uri? {
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        resolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?",
            arrayOf(RELATIVE_PATH, displayName),
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                return ContentUris.withAppendedId(collection, id)
            }
        }
        return null
    }

    companion object {
        const val FOLDER_NAME = "modelos de I.A offline"
        val RELATIVE_PATH: String = "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER_NAME/"
        private const val README_FILE_NAME = "LEIA-ME.txt"
        private const val MODEL_COPY_BUFFER = 1024 * 1024
        private val folderLock = Any()
    }
}
