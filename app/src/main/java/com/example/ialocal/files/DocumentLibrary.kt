package com.example.ialocal.files

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import com.example.ialocal.data.ChatRepository
import com.example.ialocal.data.MessageRole
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * The documents (PDFs and text files) the agent tools work with, per conversation: the ones the
 * user attached and the ones the tools created. Created documents live in the app's files (where
 * the tools and the chat open them) with a copy in the shared Documents/IA Offline folder, so they
 * can also be found and shared with any file manager.
 */
class DocumentLibrary(
    context: Context,
    private val chats: ChatRepository,
) {
    enum class Kind(val defaultName: String, val defaultExtension: String, val described: String, val listTool: String) {
        PDF("documento", "pdf", "PDF", "pdf_list"),
        TEXT("texto", "txt", "arquivo de texto", "txt_list"),
    }

    enum class Origin(val label: String) { ATTACHED("anexado"), CREATED("criado") }

    data class Document(
        val id: String,
        val name: String,
        val file: File,
        val kind: Kind,
        val origin: Origin,
        val conversationId: String?,
        val createdAt: Long,
        /** Where the shared copy is, for the user; null when it could not be written. */
        val sharedPath: String? = null,
    ) {
        val mimeType: String get() = mimeTypeOf(name, kind)
    }

    private val appContext = context.applicationContext
    private val directory = File(appContext.filesDir, DIRECTORY)
    private val indexFile = File(directory, "index.json")
    private val lock = Mutex()

    /** The conversation's documents of [kind], attached first, in the order they appeared. */
    suspend fun list(conversationId: String?, kind: Kind): List<Document> = withContext(Dispatchers.IO) {
        val attached = conversationId?.let { chats.getMessages(it) }.orEmpty()
            .filter { it.message.role == MessageRole.USER.name }
            .flatMap { it.attachments }
            .filter { kindOf(it.fileName, it.mimeType) == kind && File(it.localPath).isFile }
            .map { Document(it.id, it.fileName, File(it.localPath), kind, Origin.ATTACHED, conversationId, it.createdAt) }
        val created = lock.withLock { readIndex() }
            .filter { it.kind == kind && it.conversationId == conversationId && it.file.isFile }
        (attached + created).distinctBy { it.id }
    }

    /** The document with [id]; small models sometimes give the file name instead, so that works too. */
    suspend fun find(conversationId: String?, kind: Kind, id: String): Document {
        val documents = list(conversationId, kind)
        val wanted = id.trim()
        return documents.firstOrNull { it.id == wanted }
            ?: documents.lastOrNull { it.name.equals(wanted, ignoreCase = true) }
            ?: documents.lastOrNull { it.name.substringBeforeLast('.').equals(wanted, ignoreCase = true) }
            ?: throw IllegalArgumentException(
                "Não há ${kind.described} com id \"$id\" nesta conversa. Use ${kind.listTool} para ver os ids.",
            )
    }

    /** Writes a new document with [write] and registers it for [conversationId]. */
    suspend fun <T> create(conversationId: String?, kind: Kind, requestedName: String, write: (File) -> T): Pair<Document, T> =
        withContext(Dispatchers.IO) {
            val id = UUID.randomUUID().toString()
            val name = fileName(requestedName, kind)
            directory.mkdirs()
            val file = File(directory, "$id.${name.substringAfterLast('.')}")
            val result = try {
                write(file)
            } catch (t: Throwable) {
                file.delete()
                throw t
            }
            val document = Document(
                id = id,
                name = name,
                file = file,
                kind = kind,
                origin = Origin.CREATED,
                conversationId = conversationId,
                createdAt = System.currentTimeMillis(),
                sharedPath = runCatching { shareCopy(file, name) }.getOrNull(),
            )
            lock.withLock { writeIndex(readIndex() + document) }
            document to result
        }

    /** Documents the tools created in [conversationId] from [since] on, to attach to that answer. */
    suspend fun createdSince(conversationId: String, since: Long): List<Document> = withContext(Dispatchers.IO) {
        lock.withLock { readIndex() }.filter { it.conversationId == conversationId && it.createdAt >= since && it.file.isFile }
    }

    /** Copies [file] to Documents/IA Offline and returns the path the user sees. */
    private fun shareCopy(file: File, name: String): String? {
        val resolver = appContext.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            // The type Android itself gives the extension, or it would add another extension to the name.
            put(MediaStore.MediaColumns.MIME_TYPE, systemMimeType(name) ?: "application/octet-stream")
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
                val id = item.getString("id")
                Document(
                    id = id,
                    name = item.getString("name"),
                    file = File(directory, item.optString("file").ifEmpty { "$id.pdf" }),
                    kind = runCatching { Kind.valueOf(item.optString("kind")) }.getOrDefault(Kind.PDF),
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
                    .put("file", document.file.name)
                    .put("kind", document.kind.name)
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

        /** Extensions of the text files the tools read, create and edit. */
        val TEXT_EXTENSIONS = setOf("txt", "md", "markdown", "csv", "tsv", "log", "json", "xml", "yaml", "yml", "ini", "srt")

        fun kindOf(fileName: String, mimeType: String?): Kind? {
            val mime = mimeType.orEmpty().lowercase()
            val extension = fileName.substringAfterLast('.', "").lowercase()
            return when {
                mime == PDF_MIME || extension == "pdf" -> Kind.PDF
                extension in TEXT_EXTENSIONS || mime.startsWith("text/") || mime == "application/json" -> Kind.TEXT
                else -> null
            }
        }

        fun mimeTypeOf(fileName: String, kind: Kind): String = when (kind) {
            Kind.PDF -> PDF_MIME
            Kind.TEXT -> systemMimeType(fileName)?.takeIf { it.startsWith("text/") || it == "application/json" } ?: "text/plain"
        }

        private fun systemMimeType(fileName: String): String? =
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(fileName.substringAfterLast('.', "").lowercase())

        /**
         * A safe file name for [kind]: PDFs end in .pdf; text files keep a text extension they
         * were given (.md, .csv…) and otherwise get [extension] or .txt.
         */
        fun fileName(requested: String, kind: Kind, extension: String? = null): String {
            val trimmed = requested.trim()
            val given = trimmed.substringAfterLast('.', "").lowercase()
            val keepsGiven = when (kind) {
                Kind.PDF -> given == "pdf"
                Kind.TEXT -> given in TEXT_EXTENSIONS
            }
            val finalExtension = if (keepsGiven) given else extension?.lowercase()?.takeIf { kind == Kind.TEXT && it in TEXT_EXTENSIONS } ?: kind.defaultExtension
            val base = (if (keepsGiven) trimmed.dropLast(given.length + 1) else trimmed)
                .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ")
                .replace(Regex("\\s+"), " ")
                .trim(' ', '.')
                .take(80)
                .ifEmpty { kind.defaultName }
            return "$base.$finalExtension"
        }

        /** The default name of an edited copy: "notas.md" becomes "notas editado.md", never "editado editado". */
        fun editedName(sourceName: String, kind: Kind): String {
            val extension = sourceName.substringAfterLast('.', "")
            val base = if (extension.isEmpty()) sourceName else sourceName.dropLast(extension.length + 1)
            return fileName(base.replace(Regex("(\\s+editado)+$", RegexOption.IGNORE_CASE), "") + " editado", kind, extension)
        }
    }
}
