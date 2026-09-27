package com.example.ialocal.files

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.net.URLConnection

/** Opens or shares a file kept by the app (attachments and created PDFs) with other apps. */
object AttachmentOpener {
    fun open(context: Context, fileName: String, localPath: String, mimeType: String?) {
        val uri = uriFor(context, localPath) ?: return
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, typeOf(fileName, mimeType))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        start(context, Intent.createChooser(view, "Abrir $fileName"))
    }

    fun share(context: Context, fileName: String, localPath: String, mimeType: String?) {
        val uri = uriFor(context, localPath) ?: return
        val send = Intent(Intent.ACTION_SEND)
            .setType(typeOf(fileName, mimeType))
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_TITLE, fileName)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        start(context, Intent.createChooser(send, "Compartilhar $fileName"))
    }

    private fun uriFor(context: Context, localPath: String) = try {
        val file = File(localPath)
        require(file.isFile)
        FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    } catch (_: IllegalArgumentException) {
        Toast.makeText(context, "O arquivo não está mais disponível.", Toast.LENGTH_SHORT).show()
        null
    }

    private fun typeOf(fileName: String, mimeType: String?): String =
        mimeType?.takeIf { it.isNotBlank() } ?: URLConnection.guessContentTypeFromName(fileName) ?: "*/*"

    private fun start(context: Context, intent: Intent) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "Nenhum app do aparelho abre este arquivo.", Toast.LENGTH_SHORT).show()
        }
    }
}
