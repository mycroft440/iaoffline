package com.example.ialocal.agent.tools

import com.example.ialocal.files.DocumentLibrary
import org.json.JSONObject

/** Tools that work on the documents of one conversation (PDFs, text files). */
interface ConversationFileTools {
    val definitions: List<ToolDefinition>

    fun handles(name: String): Boolean = definitions.any { it.name == name }

    suspend fun execute(name: String, arguments: JSONObject, conversationId: String?): String
}

/** What the model gets back after a tool created [document]. */
internal fun createdResult(document: DocumentLibrary.Document): JSONObject = JSONObject()
    .put("status", "created")
    .put("id", document.id)
    .put("name", document.name)
    .put("saved_to", document.sharedPath ?: "arquivos do app")
    .put("note", "O arquivo aparece anexado à sua resposta no chat. Diga ao usuário o nome do arquivo e onde ele foi salvo.")

/** A trimmed, non-empty text argument, or null. */
internal fun JSONObject.optText(key: String): String? =
    if (isNull(key)) null else optString(key).trim().takeIf { it.isNotEmpty() }

internal fun JSONObject.requireText(key: String): String =
    requireNotNull(optText(key)) { "Informe '$key'." }

/** A text argument exactly as written (spaces and line breaks matter when editing text). */
internal fun JSONObject.rawText(key: String): String? = if (isNull(key)) null else optString(key)
