package com.example.ialocal.runtime

import java.io.File
import java.util.Locale

/** Storage reads and page faults of this process so far, from the kernel's counters. */
data class ProcessIo(
    /** Bytes read from storage for this process (/proc/self/io), mapped model files included. */
    val readBytes: Long?,
    /** Page faults that had to wait for storage (majflt in /proc/self/stat). */
    val majorFaults: Long?,
) {
    companion object {
        fun read(): ProcessIo = parse(
            io = runCatching { File("/proc/self/io").readText() }.getOrNull(),
            stat = runCatching { File("/proc/self/stat").readText() }.getOrNull(),
        )

        internal fun parse(io: String?, stat: String?): ProcessIo = ProcessIo(
            readBytes = io?.lineSequence()
                ?.firstOrNull { it.startsWith("read_bytes:") }
                ?.substringAfter(':')?.trim()?.toLongOrNull(),
            // The process name in parentheses may hold spaces; majflt is the 10th field after it.
            majorFaults = stat?.substringAfterLast(')', "")?.trim()?.split(' ')?.getOrNull(9)?.toLongOrNull(),
        )
    }
}

/** How fast an answer went, as the native side measured it (IAOFFLINE_PERF). */
data class AnswerSpeed(
    val promptTokens: Int,
    val promptMillis: Long,
    val answerTokens: Int,
    val answerMillis: Long,
) {
    /** One line for the log; [before] and [after] are the process counters around the answer. */
    fun describe(before: ProcessIo, after: ProcessIo): String = buildString {
        append("mensagem: $promptTokens tokens em ${seconds(promptMillis)} (${rate(promptTokens, promptMillis)}); ")
        append("resposta: $answerTokens tokens em ${seconds(answerMillis)} (${rate(answerTokens, answerMillis)})")
        if (before.readBytes != null && after.readBytes != null) {
            val read = (after.readBytes - before.readBytes).coerceAtLeast(0L)
            append("; lidos do armazenamento: ${read / MB} MB")
            if (answerTokens > 0) append(" (${format("%.1f", read.toDouble() / MB / answerTokens)} MB por token)")
        }
        if (before.majorFaults != null && after.majorFaults != null) {
            append("; ${(after.majorFaults - before.majorFaults).coerceAtLeast(0L)} esperas por leitura do armazenamento")
        }
    }

    companion object {
        /** "prompt_tokens prompt_ms answer_tokens answer_ms", as the native side publishes it. */
        fun parse(raw: String?): AnswerSpeed? {
            val fields = raw?.trim()?.split(' ')?.map { it.toLongOrNull() ?: return null } ?: return null
            if (fields.size != 4) return null
            return AnswerSpeed(fields[0].toInt(), fields[1], fields[2].toInt(), fields[3])
        }

        private fun seconds(millis: Long) = format("%.1f s", millis / 1000.0)

        private fun rate(tokens: Int, millis: Long) =
            if (millis <= 0) "-" else format("%.2f tokens/s", tokens * 1000.0 / millis)

        private fun format(pattern: String, value: Double) = String.format(PT_BR, pattern, value)

        private val PT_BR = Locale.forLanguageTag("pt-BR")
        private const val MB = 1024L * 1024
    }
}
