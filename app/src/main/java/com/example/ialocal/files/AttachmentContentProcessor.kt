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
        require(file.length() <= MAX_PDF_BYTES) {
            "O PDF é grande demais para processamento seguro no aparelho."
        }
        PDDocument.load(file).use { document ->
            val text = PDFTextStripper().getText(document).trim()
            require(text.isNotBlank()) { "O PDF não contém texto extraível. PDFs digitalizados exigirão OCR em uma etapa futura." }
            text.take(MAX_EXTRACTED_CHARS)
        }
    }

    private suspend fun readText(file: File): String = withContext(Dispatchers.IO) {
        file.bufferedReader().use { reader ->
            val output = StringBuilder(minOf(MAX_EXTRACTED_CHARS, 16_384))
            val buffer = CharArray(TEXT_BUFFER_CHARS)
            var remaining = MAX_EXTRACTED_CHARS
            while (remaining > 0) {
                val read = reader.read(buffer, 0, minOf(buffer.size, remaining))
                if (read < 0) break
                if (read == 0) continue
                output.append(buffer, 0, read)
                remaining -= read
            }
            output.toString()
        }
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
        private const val TEXT_BUFFER_CHARS = 8_192
        private const val MAX_PDF_BYTES = 64L * 1024 * 1024
    }
}
