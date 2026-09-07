package com.example.ialocal.files

import com.example.ialocal.data.PendingAttachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AttachmentContextBuilder {
    suspend fun build(attachments: List<PendingAttachment>): String = withContext(Dispatchers.Default) {
        if (attachments.isEmpty()) return@withContext ""
        val sections = mutableListOf<String>()
        var remaining = MAX_TOTAL_CHARS
        for (attachment in attachments) {
            if (remaining <= 0) break
            val text = attachment.extractedText?.takeIf { it.isNotBlank() } ?: continue
            val excerpt = text.take(minOf(MAX_FILE_CHARS, remaining))
            val prefix = if (attachment.mimeType?.startsWith("audio/") == true) "Transcrição" else "Conteúdo"
            sections += "$prefix de ${attachment.fileName}:\n$excerpt"
            remaining -= excerpt.length
        }
        sections.joinToString("\n\n---\n\n")
    }

    companion object {
        private const val MAX_FILE_CHARS = 8_000
        private const val MAX_TOTAL_CHARS = 12_000
    }
}
