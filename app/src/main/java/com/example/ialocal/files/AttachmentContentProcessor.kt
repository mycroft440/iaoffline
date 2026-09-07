package com.example.ialocal.files

import android.content.Context
import com.example.ialocal.audio.OnDeviceAudioTranscriber
import com.example.ialocal.data.AttachmentType
import com.example.ialocal.data.PendingAttachment
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AttachmentContentProcessor(
    context: Context,
    private val audioTranscriber: OnDeviceAudioTranscriber = OnDeviceAudioTranscriber(context.applicationContext),
) {
    init {
        PDFBoxResourceLoader.init(context.applicationContext)
    }

    suspend fun enrich(attachment: PendingAttachment): PendingAttachment {
        val file = File(attachment.localPath)
        require(file.isFile) { "O anexo não está mais disponível." }
        val text = when {
            attachment.type == AttachmentType.AUDIO || attachment.mimeType?.startsWith("audio/") == true ->
                audioTranscriber.transcribe(file)
            isPdf(attachment) -> extractPdf(file)
            isTextLike(attachment) -> readText(file)
            else -> null
        }
        return attachment.copy(extractedText = text?.trim()?.take(MAX_EXTRACTED_CHARS))
    }

    private suspend fun extractPdf(file: File): String = withContext(Dispatchers.IO) {
        PDDocument.load(file).use { document ->
            val text = PDFTextStripper().getText(document).trim()
            require(text.isNotBlank()) { "O PDF não contém texto extraível. PDFs digitalizados exigirão OCR em uma etapa futura." }
            text.take(MAX_EXTRACTED_CHARS)
        }
    }

    private suspend fun readText(file: File): String = withContext(Dispatchers.IO) {
        file.bufferedReader().use { it.readText().take(MAX_EXTRACTED_CHARS) }
    }

    private fun isPdf(a: PendingAttachment): Boolean =
        a.mimeType.equals("application/pdf", true) || a.fileName.endsWith(".pdf", true)

    private fun isTextLike(a: PendingAttachment): Boolean {
        val mime = a.mimeType.orEmpty().lowercase()
        val name = a.fileName.lowercase()
        return mime.startsWith("text/") || mime == "application/json" ||
            name.endsWith(".txt") || name.endsWith(".md") || name.endsWith(".json") || name.endsWith(".csv")
    }

    companion object {
        private const val MAX_EXTRACTED_CHARS = 500_000
    }
}
