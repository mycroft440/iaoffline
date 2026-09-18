package com.example.ialocal.ui.codeeditor

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class CodeEditSessionSnapshot(
    val id: String,
    val target: CodeEditTarget,
    val resultCode: String?,
    val replacement: String?,
    val summary: String?,
) {
    val applied: Boolean get() = resultCode != null
}

class CodeEditSessionStore {
    private data class Session(
        val id: String,
        val originalCode: String,
        val target: CodeEditTarget,
        var resultCode: String? = null,
        var replacement: String? = null,
        var summary: String? = null,
    )

    private val sessions = ConcurrentHashMap<String, Session>()

    fun create(code: String, target: CodeEditTarget): CodeEditSessionSnapshot {
        require(target.endOffset <= code.length) { "Faixa de edição fora do documento." }
        require(code.substring(target.startOffset, target.endOffset) == target.original) {
            "O trecho selecionado não corresponde mais ao documento."
        }
        val session = Session(UUID.randomUUID().toString(), code, target)
        sessions[session.id] = session
        return session.snapshot()
    }

    fun applyReplacement(sessionId: String, replacement: String, summary: String?): CodeEditSessionSnapshot {
        require(replacement.length <= MAX_REPLACEMENT_CHARS) { "A substituição excede o limite do editor." }
        require('\u0000' !in replacement) { "A substituição contém caractere inválido." }
        val session = sessions[sessionId] ?: throw IllegalArgumentException("Sessão de edição inexistente ou expirada.")
        return synchronized(session) {
            check(session.resultCode == null) { "Esta sessão já executou sua única edição autorizada." }
            check(session.originalCode.substring(session.target.startOffset, session.target.endOffset) == session.target.original) {
                "O trecho autorizado mudou antes da edição."
            }
            require(replacement != session.target.original) { "A substituição é idêntica ao trecho atual." }
            val updated = buildString(session.originalCode.length - session.target.original.length + replacement.length) {
                append(session.originalCode, 0, session.target.startOffset)
                append(replacement)
                append(session.originalCode, session.target.endOffset, session.originalCode.length)
            }
            session.resultCode = updated
            session.replacement = replacement
            session.summary = summary?.trim()?.take(MAX_SUMMARY_CHARS)?.takeIf { it.isNotBlank() }
            session.snapshot()
        }
    }

    fun get(sessionId: String): CodeEditSessionSnapshot? = sessions[sessionId]?.let { session ->
        synchronized(session) { session.snapshot() }
    }

    fun remove(sessionId: String) { sessions.remove(sessionId) }
    fun hasActiveSessions(): Boolean = sessions.isNotEmpty()

    private fun Session.snapshot() = CodeEditSessionSnapshot(id, target, resultCode, replacement, summary)

    private companion object {
        const val MAX_REPLACEMENT_CHARS = 128 * 1024
        const val MAX_SUMMARY_CHARS = 240
    }
}
