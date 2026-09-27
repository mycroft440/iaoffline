package com.example.ialocal.files

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import com.example.ialocal.data.ChatRepository
import com.example.ialocal.data.MessageRole
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * The PDFs the agent tools work with, per conversation: the ones the user attached and the ones
 * the tools created. Created PDFs live in the app's files (where the tools and the chat open them)
 * with a copy in the shared Documents/IA Offline folder, so they can also be found and shared with
 * any file manager.
 */
class PdfLibrary(
    context: Context,
    private val chats: ChatRepository,
    val engine: PdfEngine = PdfEngine(),
) {
    enum class Origin(val label: String) { ATTACHED("anexado"), CREATED("criado") }

    data class Document(
        val id: String,
        val name: String,
        val file: File,
        val origin: Origin,
        val conversationId: String?,
        val createdAt: Long,
        /** Where the shared copy is, for the user; null when it could not be written. */
        val sharedPath: String? = null,
    )

    private val appContext = context.applicationContext
    private val directory = File(appContext.filesDir, DIRECTORY)
    private val indexFile = File(directory, "index.json")
    private val lock = Mutex()

    init {
        PDFBoxResourceLoader.init(appContext)
    }

    /** The conversation's PDFs, attached first, in the order they appeared. */
    suspend fun list(conversationId: String?): List<Document> = withContext(Dispatchers.IO) {
        val attached = conversationId?.let { chats.getMessages(it) }.orEmpty()
            .filter { it.message.role == MessageRole.USER.name }
            .flatMap { it.attachments }
            .filter { isPdf(it.fileName, it.mimeType) && File(it.localPath).isFile }
            .map { Document(it.id, it.fileName, File(it.localPath), Origin.ATTACHED, conversationId, it.createdAt) }
        val created = lock.withLock { readIndex() }
            .filter { it.conversationId == conversationId && it.file.isFile }
        (attached + created).distinctBy { it.id }
    }

    suspend fun find(conversationId: String?, id: String): Document =
        list(conversationId).firstOrNull { it.id == id.trim() }
            ?: throw IllegalArgumentException("Não há PDF com id \"$id\" nesta conversa. Use pdf_list para ver os ids.")

    /** Writes a new PDF with [write] and registers it for [conversationId]. */
    suspend fun create(conversationId: String?, requestedName: String, write: (File) -> PdfResult): Pair<Document, PdfResult> =
        withContext(Dispatchers.IO) {
            val id = UUID.randomUUID().toString()
            val file = File(directory, "$id.pdf")
            val result = try {
                write(file)
            } catch (t: Throwable) {
                file.delete()
                throw t
            }
            val name = fileName(requestedName)
            val document = Document(
                id = id,
                name = name,
                file = file,
                origin = Origin.CREATED,
                conversationId = conversationId,
                createdAt = System.currentTimeMillis(),
                sharedPath = runCatching { shareCopy(file, name) }.getOrNull(),
            )
            lock.withLock { writeIndex(readIndex() + document) }
            document to result
        }

    /** PDFs the tools created in [conversationId] from [since] on, to attach to that answer. */
    suspend fun createdSince(conversationId: String, since: Long): List<Document> = withContext(Dispatchers.IO) {
        lock.withLock { readIndex() }.filter { it.conversationId == conversationId && it.createdAt >= since && it.file.isFile }
    }

    /** Copies [file] to Documents/IA Offline and returns the path the user sees. */
    private fun shareCopy(file: File, name: String): String? {
        val resolver = appContext.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, PDF_MIME)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOCUMENTS}/$SHARED_FOLDER")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values) ?: return null
        try {
            resolver.openOutputStream(uri)?.use { output -> file.inputStream().use { it.copyTo(output) } }
                ?: error("Não foi possível gravar a cópia em Documentos.")
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (t: Throwable) {
            resolver.delete(uri, null, null)
            throw t
        }
        // Android renames on a clash, so read back the name it used.
        val shownName = resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: name
        return "Documentos/$SHARED_FOLDER/$shownName"
    }

    private fun readIndex(): List<Document> {
        if (!indexFile.isFile) return emptyList()
        return runCatching {
            val array = JSONArray(indexFile.readText())
            (0 until array.length()).map { i ->
                val item = array.getJSONObject(i)
                Document(
                    id = item.getString("id"),
                    name = item.getString("name"),
                    file = File(directory, item.getString("id") + ".pdf"),
                    origin = Origin.CREATED,
                    conversationId = item.optString("conversation_id").takeIf { it.isNotEmpty() },
                    createdAt = item.getLong("created_at"),
                    sharedPath = item.optString("shared_path").takeIf { it.isNotEmpty() },
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun writeIndex(documents: List<Document>) {
        directory.mkdirs()
        val array = JSONArray()
        // Entries whose file is gone (deleted by the system or the user) are dropped.
        documents.filter { it.file.isFile }.forEach { document ->
            array.put(
                JSONObject()
                    .put("id", document.id)
                    .put("name", document.name)
                    .put("conversation_id", document.conversationId ?: "")
                    .put("created_at", document.createdAt)
                    .put("shared_path", document.sharedPath ?: "")
            )
        }
        val temporary = File(directory, "index.json.tmp")
        temporary.writeText(array.toString())
        if (!temporary.renameTo(indexFile)) {
            indexFile.writeText(array.toString())
            temporary.delete()
        }
    }

    companion object {
        const val DIRECTORY = "documents"
        const val PDF_MIME = "application/pdf"
        private const val SHARED_FOLDER = "IA Offline"

        fun isPdf(fileName: String, mimeType: String?): Boolean =
            mimeType.equals(PDF_MIME, ignoreCase = true) || fileName.endsWith(".pdf", ignoreCase = true)

        /** A safe file name ending in .pdf. */
        fun fileName(requested: String): String {
            val base = requested.trim().removeSuffix(".pdf").removeSuffix(".PDF")
                .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
                .take(80)
                .ifEmpty { "documento" }
            return "$base.pdf"
        }
    }
}
