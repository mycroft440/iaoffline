package com.example.ialocal.models

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class PersistedCatalogFile(
    val model: CatalogModel,
    val uri: Uri,
    val sizeBytes: Long?,
)

internal fun catalogModelForFileName(fileName: String?): CatalogModel? {
    if (fileName.isNullOrBlank()) return null
    return ModelCatalog.entries.firstOrNull { it.fileName.equals(fileName, ignoreCase = true) }
}

/**
 * Keeps verified catalog GGUFs in shared storage /IAs Offline so they survive app uninstall.
 * Old Downloads folders remain readable through a one-time SAF grant for legacy restoration.
 */
class PublicModelDownloads(context: Context) {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    /** Creates the visible folder after the user has granted access to shared storage. */
    fun ensureFolder(): File {
        synchronized(folderLock) {
            check(Environment.isExternalStorageManager()) {
                "Permita o acesso aos arquivos do aparelho para salvar as I.As em $FOLDER_NAME."
            }
            val folder = File(Environment.getExternalStorageDirectory(), FOLDER_NAME)
            check((folder.isDirectory || folder.mkdirs()) && folder.isDirectory) {
                "Não foi possível criar a pasta $FOLDER_NAME no armazenamento interno."
            }
            val readme = File(folder, README_FILE_NAME)
            if (!readme.exists()) {
                val text = buildString {
                    appendLine("IAs Offline")
                    appendLine()
                    appendLine("Os modelos GGUF baixados pelo app ficam nesta pasta para sobreviver à desinstalação.")
                    appendLine("Ao reinstalar o app, abra Minhas I.As e toque em Buscar para recuperar seus modelos.")
                    appendLine("Antes de voltar a usar um arquivo, o app confere o SHA-256 e executa uma validação real de inferência.")
                }
                readme.writeText(text)
            }
            return folder
        }
    }

    suspend fun publishVerifiedModel(model: CatalogModel, source: File): Uri = withContext(Dispatchers.IO) {
        require(source.isFile && source.length() > 0L) { "O modelo verificado não está disponível para publicação." }
        synchronized(folderLock) {
            val folder = ensureFolder()
            val destination = File(folder, model.fileName)
            val partial = File(folder, "${model.fileName}.part")
            require(folder.usableSpace > source.length() + RESTORE_HEADROOM) {
                "Espaço insuficiente para salvar ${model.displayName} em $FOLDER_NAME."
            }
            try {
                val copied = source.inputStream().buffered(MODEL_COPY_BUFFER).use { input ->
                    FileOutputStream(partial).use { stream ->
                        val output = stream.buffered(MODEL_COPY_BUFFER)
                        val bytes = input.copyTo(output, MODEL_COPY_BUFFER)
                        output.flush()
                        stream.fd.sync()
                        bytes
                    }
                }
                require(copied == source.length()) {
                    "A cópia para $FOLDER_NAME ficou incompleta."
                }
                try {
                    Files.move(
                        partial.toPath(), destination.toPath(),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING,
                    )
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(partial.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
                Uri.fromFile(destination)
            } catch (t: Throwable) {
                partial.delete()
                throw t
            }
        }
    }

    /** Ensures a verified download has a persistent shared copy before the private install proceeds. */
    suspend fun ensurePersistedVerifiedModel(model: CatalogModel, source: File): Uri = withContext(Dispatchers.IO) {
        val existing = File(ensureFolder(), model.fileName)
        if (existing.isFile && existing.length() == source.length() && sha256(existing).equals(model.sha256, true)) {
            return@withContext Uri.fromFile(existing)
        }
        publishVerifiedModel(model, source)
    }

    /** Discovers models in the new folder and metadata for files in legacy Downloads folders. */
    fun discoverCatalogModels(): List<CatalogModel> {
        val result = linkedMapOf<String, CatalogModel>()
        if (Environment.isExternalStorageManager()) {
            File(Environment.getExternalStorageDirectory(), FOLDER_NAME).listFiles().orEmpty()
                .forEach { file -> catalogModelForFileName(file.name)?.let { result[it.id] = it } }
        }
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        try {
            resolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
                "${MediaStore.MediaColumns.RELATIVE_PATH} IN (?, ?)",
                arrayOf(OLD_RELATIVE_PATH, LEGACY_RELATIVE_PATH),
                null,
            )?.use { cursor ->
                val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    catalogModelForFileName(cursor.getString(nameIndex))?.let { result[it.id] = it }
                }
            }
        } catch (_: SecurityException) {
            // Expected on Android builds that hide orphaned Downloads metadata after reinstall.
        } catch (_: IllegalArgumentException) {
            // Some OEM MediaStore implementations are stricter about Downloads queries.
        }
        return result.values.toList()
    }

    /** Saves a persistable SAF grant and returns catalog GGUFs found in the selected folder/tree. */
    suspend fun catalogFilesFromTree(treeUri: Uri): List<PersistedCatalogFile> = withContext(Dispatchers.IO) {
        rememberTreeAccess(treeUri)
        val files = linkedMapOf<String, PersistedCatalogFile>()
        modelFolders(treeUri).forEach { folder ->
            folder.listFiles().forEach documentLoop@{ document ->
                if (!document.isFile) return@documentLoop
                val model = catalogModelForFileName(document.name) ?: return@documentLoop
                val size = document.length().takeIf { it > 0L }
                files[model.id] = PersistedCatalogFile(model, document.uri, size)
            }
        }
        files.values.toList()
    }

    suspend fun copyVerifiedModelToStaging(candidate: PersistedCatalogFile, destination: File): File =
        withContext(Dispatchers.IO) {
            val expectedSize = candidate.sizeBytes ?: candidate.model.approximateSizeBytes
            require(appContext.filesDir.usableSpace > expectedSize + RESTORE_HEADROOM) {
                "Espaço insuficiente para restaurar ${candidate.model.displayName}. Libere armazenamento e tente novamente."
            }
            destination.parentFile?.mkdirs()
            destination.delete()

            try {
                val digest = MessageDigest.getInstance("SHA-256")
                var copied = 0L
                resolver.openInputStream(candidate.uri)?.buffered(MODEL_COPY_BUFFER)?.use { input ->
                    FileOutputStream(destination).use { fileOutput ->
                        val output = fileOutput.buffered(MODEL_COPY_BUFFER)
                        val buffer = ByteArray(MODEL_COPY_BUFFER)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            digest.update(buffer, 0, read)
                            output.write(buffer, 0, read)
                            copied += read
                        }
                        output.flush()
                        fileOutput.fd.sync()
                    }
                } ?: throw IllegalStateException("O Android não permitiu abrir ${candidate.model.fileName}.")

                candidate.sizeBytes?.takeIf { it > 0L }?.let { expected ->
                    require(copied == expected) {
                        "A restauração de ${candidate.model.displayName} ficou incompleta ($copied de $expected bytes)."
                    }
                }
                val actualSha = digest.digest().joinToString("") { "%02x".format(it) }
                require(actualSha.equals(candidate.model.sha256, ignoreCase = true)) {
                    "${candidate.model.displayName} não passou na verificação SHA-256. O arquivo salvo não será usado."
                }
                require(destination.isFile && destination.length() == copied) {
                    "A cópia privada de ${candidate.model.displayName} ficou incompleta."
                }
                destination
            } catch (t: Throwable) {
                destination.delete()
                throw t
            }
        }

    /** Deletes the persistent catalog copy when the user explicitly removes that IA inside the app. */
    suspend fun deletePersistedModel(model: CatalogModel) = withContext(Dispatchers.IO) {
        val failures = mutableListOf<String>()
        if (Environment.isExternalStorageManager()) {
            val saved = File(File(Environment.getExternalStorageDirectory(), FOLDER_NAME), model.fileName)
            if (saved.exists() && !saved.delete()) failures += saved.absolutePath
        }
        listOf(OLD_RELATIVE_PATH, LEGACY_RELATIVE_PATH).forEach { path ->
            findOwnedByDisplayName(model.fileName, path)?.let { entry ->
                if (resolver.delete(entry.uri, null, null) <= 0) failures += path
            }
        }

        persistedTreeUri()?.let { treeUri ->
            runCatching {
                modelFolders(treeUri).forEach { folder ->
                    folder.listFiles()
                        .filter { it.isFile && it.name?.equals(model.fileName, ignoreCase = true) == true }
                        .forEach { if (!it.delete()) failures += it.uri.toString() }
                }
            }.onFailure { failures += it.message ?: "acesso SAF" }
        }

        check(failures.isEmpty()) {
            "Não foi possível excluir a cópia persistente de ${model.displayName}."
        }
    }

    private fun rememberTreeAccess(treeUri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            resolver.takePersistableUriPermission(treeUri, flags)
        } catch (security: SecurityException) {
            throw IllegalStateException(
                "O Android não concedeu acesso persistente à pasta selecionada. Selecione $FOLDER_NAME e tente novamente.",
                security,
            )
        }
        preferences.edit().putString(PREF_TREE_URI, treeUri.toString()).apply()
    }

    private fun persistedTreeUri(): Uri? = preferences.getString(PREF_TREE_URI, null)
        ?.let(Uri::parse)
        ?.takeIf { stored -> resolver.persistedUriPermissions.any { it.uri == stored && it.isReadPermission } }

    private fun modelFolders(treeUri: Uri): List<DocumentFile> {
        val tree = DocumentFile.fromTreeUri(appContext, treeUri)
            ?: throw IllegalArgumentException("Não foi possível abrir a pasta selecionada.")
        if (!tree.isDirectory) throw IllegalArgumentException("Selecione a pasta $FOLDER_NAME.")

        if (isModelFolderName(tree.name)) return listOf(tree)

        val folders = tree.listFiles().filter { it.isDirectory && isModelFolderName(it.name) }
        require(folders.isNotEmpty()) {
            "Nenhuma pasta '$FOLDER_NAME' foi encontrada. Selecione $FOLDER_NAME ou uma pasta antiga em Downloads."
        }
        return folders
    }

    private fun isModelFolderName(name: String?): Boolean =
        name?.equals(FOLDER_NAME, ignoreCase = true) == true ||
            name?.equals(LEGACY_FOLDER_NAME, ignoreCase = true) == true

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(MODEL_COPY_BUFFER).use { input ->
            val buffer = ByteArray(MODEL_COPY_BUFFER)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun findOwnedByDisplayName(displayName: String, relativePath: String): OwnedEntry? {
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        resolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.SIZE),
            "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND " +
                "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND " +
                "${MediaStore.MediaColumns.OWNER_PACKAGE_NAME}=?",
            arrayOf(relativePath, displayName, appContext.packageName),
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                val sizeIndex = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else null
                return OwnedEntry(ContentUris.withAppendedId(collection, id), size)
            }
        }
        return null
    }

    private data class OwnedEntry(val uri: Uri, val sizeBytes: Long?)

    companion object {
        const val FOLDER_NAME = "IAs Offline"
        const val LEGACY_FOLDER_NAME = "modelos de I.A offline"
        val OLD_RELATIVE_PATH: String = "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER_NAME/"
        val LEGACY_RELATIVE_PATH: String = "${Environment.DIRECTORY_DOWNLOADS}/$LEGACY_FOLDER_NAME/"
        private const val README_FILE_NAME = "LEIA-ME.txt"
        private const val MODEL_COPY_BUFFER = 1024 * 1024
        private const val RESTORE_HEADROOM = 256L * 1024L * 1024L
        private const val PREFERENCES_NAME = "persistent_model_downloads"
        private const val PREF_TREE_URI = "tree_uri"
        private val folderLock = Any()
    }
}
