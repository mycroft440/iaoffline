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
        var size = 0L

        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIndex >= 0) displayName = cursor.getString(nameIndex) ?: displayName
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                }
            }

        val extension = displayName.substringAfterLast('.', "")
        val targetDir = File(context.filesDir, "attachments").apply { mkdirs() }
        val target = File(
            targetDir,
            buildString {
                append(UUID.randomUUID())
                if (extension.isNotBlank()) append('.').append(extension)
            },
        )

        resolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Não foi possível abrir o arquivo." }
            target.outputStream().use { output -> input.copyTo(output) }
        }

        if (size <= 0) size = target.length()

        return PendingAttachment(
            id = UUID.randomUUID().toString(),
            type = if (mime?.startsWith("audio/") == true) AttachmentType.AUDIO else AttachmentType.FILE,
            fileName = displayName,
            localPath = target.absolutePath,
            mimeType = mime,
            sizeBytes = size,
        )
    }
}
