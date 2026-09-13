package com.example.ialocal.files

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.example.ialocal.data.AttachmentType
import com.example.ialocal.data.PendingAttachment
import java.io.File
import java.util.UUID

class AttachmentImporter(
    private val context: Context,
) {
    fun import(uri: Uri): PendingAttachment {
        val resolver = context.contentResolver
        val mime = resolver.getType(uri)
        var displayName = "arquivo"
        var declaredSize: Long? = null

        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIndex >= 0) {
                        displayName = sanitizeDisplayName(cursor.getString(nameIndex) ?: displayName)
                    }
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                        declaredSize = cursor.getLong(sizeIndex).takeIf { it >= 0 }
                    }
                }
            }

        val extension = displayName.substringAfterLast('.', "")
            .lowercase()
            .filter { it.isLetterOrDigit() }
            .take(MAX_EXTENSION_CHARS)
        val targetDir = File(context.filesDir, "attachments").apply { mkdirs() }
        require(targetDir.isDirectory) { "Não foi possível preparar a pasta privada de anexos." }
        val availableForFile = (targetDir.usableSpace - STORAGE_HEADROOM).coerceAtLeast(0L)
        val maxAllowed = minOf(MAX_ATTACHMENT_BYTES, availableForFile)
        require(maxAllowed > 0) { "Não há espaço livre suficiente para importar anexos." }
        declaredSize?.takeIf { it > 0 }?.let { size ->
            require(size <= maxAllowed) {
                "O anexo é grande demais ou não há espaço livre suficiente para importá-lo."
            }
        }

        val target = File(
            targetDir,
            buildString {
                append(UUID.randomUUID())
                if (extension.isNotBlank()) append('.').append(extension)
            },
        )

        val copied = try {
            resolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Não foi possível abrir o arquivo." }
                target.outputStream().buffered().use { output ->
                    val buffer = ByteArray(COPY_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        total += read
                        require(total <= maxAllowed) {
                            "O anexo excedeu o limite permitido durante a cópia."
                        }
                        output.write(buffer, 0, read)
                    }
                    output.flush()
                    total
                }
            }
        } catch (t: Throwable) {
            runCatching { target.delete() }
            throw t
        }

        require(copied > 0) {
            runCatching { target.delete() }
            "O arquivo selecionado está vazio."
        }
        declaredSize?.takeIf { it > 0 }?.let { expected ->
            require(copied == expected) {
                runCatching { target.delete() }
                "A cópia do anexo ficou incompleta ($copied de $expected bytes)."
            }
        }

        return PendingAttachment(
            id = UUID.randomUUID().toString(),
            type = if (mime?.startsWith("audio/") == true) AttachmentType.AUDIO else AttachmentType.FILE,
            fileName = displayName,
            localPath = target.absolutePath,
            mimeType = mime,
            sizeBytes = copied,
        )
    }

    private fun sanitizeDisplayName(raw: String): String = raw
        .replace('\u0000', ' ')
        .replace('\r', ' ')
        .replace('\n', ' ')
        .trim()
        .take(MAX_DISPLAY_NAME_CHARS)
        .ifBlank { "arquivo" }

    companion object {
        private const val COPY_BUFFER_SIZE = 1024 * 1024
        private const val MAX_EXTENSION_CHARS = 16
        private const val MAX_DISPLAY_NAME_CHARS = 240
        private const val MAX_ATTACHMENT_BYTES = 128L * 1024 * 1024
        private const val STORAGE_HEADROOM = 128L * 1024 * 1024
    }
}
