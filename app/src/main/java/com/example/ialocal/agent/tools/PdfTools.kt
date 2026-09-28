package com.example.ialocal.agent.tools

import com.example.ialocal.files.DocumentLibrary
import com.example.ialocal.files.DocumentLibrary.Kind
import com.example.ialocal.files.PdfEdit
import com.example.ialocal.files.PdfEngine
import com.example.ialocal.files.PdfResult
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * Agent tools to create, assemble, edit and read PDFs. They work on the PDFs of the conversation
 * (attached by the user or created by these tools) and never change or delete one: every result
 * is a new file, attached to the answer in the chat and copied to Documents/IA Offline.
 */
class PdfTools(
    private val library: DocumentLibrary,
    private val engine: PdfEngine = PdfEngine(),
) : ConversationFileTools {
    override val definitions = listOf(
        ToolDefinition(
            "pdf_list",
            "Lista os PDFs desta conversa (anexados pelo usuário e criados pelas ferramentas) com id, nome e número de páginas.",
            "{}",
        ),
        ToolDefinition(
            "pdf_read",
            "Lê o texto de um PDF desta conversa.",
            "{\"id\": texto, \"pages\": \"páginas opcionais, ex. 1-3,5\"}",
        ),
        ToolDefinition(
            "pdf_create",
            "Cria um PDF novo com o texto dado. No conteúdo, linhas com \"# \" viram títulos, \"## \" subtítulos, \"- \" itens de lista e linha em branco separa parágrafos. Para mudar o texto de um PDF, leia-o com pdf_read e crie outro com o texto novo.",
            "{\"file_name\": texto, \"title\": texto opcional, \"content\": texto}",
            ToolPermission.CREATE_FILES,
        ),
        ToolDefinition(
            "pdf_merge",
            "Monta um PDF novo juntando PDFs desta conversa, ou páginas deles, na ordem dada.",
            "{\"file_name\": texto, \"sources\": [{\"id\": texto, \"pages\": \"opcional, ex. 1-3\"}]}",
            ToolPermission.CREATE_FILES,
        ),
        ToolDefinition(
            "pdf_edit",
            "Cria uma cópia editada de um PDF desta conversa, aplicando as operações em ordem: remove_pages {pages}, keep_pages {pages}, reorder {order}, rotate {pages, degrees}, add_text {pages, text, position: top|bottom|center}, append_text {title, text}, watermark {pages, text}.",
            "{\"id\": texto, \"file_name\": texto opcional, \"operations\": [{\"op\": \"remove_pages\", \"pages\": \"2,4\"}]}",
            ToolPermission.CREATE_FILES,
        ),
    )

    override suspend fun execute(name: String, arguments: JSONObject, conversationId: String?): String = when (name) {
        "pdf_list" -> {
            val documents = library.list(conversationId, Kind.PDF)
            JSONObject()
                .put("pdfs", JSONArray().apply {
                    documents.forEach { document ->
                        put(
                            JSONObject()
                                .put("id", document.id)
                                .put("name", document.name)
                                .put("origin", document.origin.label)
                                .put("pages", runCatching { engine.pageCount(document.file) }.getOrDefault(0))
                        )
                    }
                })
                .apply { if (documents.isEmpty()) put("note", "Nenhum PDF nesta conversa. O usuário pode anexar PDFs pelo clipe.") }
                .toString()
        }
        "pdf_read" -> {
            val document = library.find(conversationId, Kind.PDF, arguments.requireText("id"))
            JSONObject()
                .put("id", document.id)
                .put("name", document.name)
                .put("text", engine.read(document.file, arguments.optText("pages"), MAX_READ_CHARS))
                .toString()
        }
        "pdf_create" -> {
            val title = arguments.optText("title")
            val content = arguments.optString("content")
            created(conversationId, arguments.optText("file_name") ?: title ?: "documento") { output ->
                engine.create(output, title, content)
            }
        }
        "pdf_merge" -> {
            val sources = arguments.optJSONArray("sources")
                ?: throw IllegalArgumentException("Informe 'sources' com os ids dos PDFs, na ordem.")
            val files = (0 until sources.length()).map { i ->
                // Accept plain ids as well as {"id", "pages"} objects.
                val item = sources.opt(i)
                val (id, pages) = if (item is JSONObject) item.requireText("id") to item.optText("pages") else item.toString() to null
                library.find(conversationId, Kind.PDF, id).file to pages
            }
            created(conversationId, arguments.optText("file_name") ?: "documento montado") { output ->
                engine.merge(output, files)
            }
        }
        "pdf_edit" -> {
            val source = library.find(conversationId, Kind.PDF, arguments.requireText("id"))
            val operations = parseOperations(arguments.optJSONArray("operations"))
            val name = arguments.optText("file_name") ?: DocumentLibrary.editedName(source.name, Kind.PDF)
            created(conversationId, name) { output -> engine.edit(source.file, output, operations) }
        }
        else -> error("Ferramenta de PDF desconhecida: $name")
    }

    private suspend fun created(conversationId: String?, name: String, write: (File) -> PdfResult): String {
        val (document, result) = library.create(conversationId, Kind.PDF, name, write)
        return createdResult(document).put("pages", result.pages).toString()
    }

    private fun parseOperations(array: JSONArray?): List<PdfEdit> {
        require(array != null && array.length() > 0) { "Informe 'operations' com ao menos uma operação." }
        return (0 until array.length()).map { i ->
            val item = array.optJSONObject(i) ?: throw IllegalArgumentException("Cada operação deve ser um objeto com 'op'.")
            when (val op = item.requireText("op")) {
                "remove_pages" -> PdfEdit.RemovePages(item.requireText("pages"))
                "keep_pages" -> PdfEdit.KeepPages(item.requireText("pages"))
                "reorder" -> PdfEdit.Reorder(item.optText("order") ?: item.requireText("pages"))
                "rotate" -> PdfEdit.Rotate(item.optText("pages"), item.optInt("degrees", 90))
                "add_text" -> PdfEdit.AddText(
                    pages = item.optText("pages"),
                    text = item.requireText("text"),
                    position = item.optText("position")?.lowercase() ?: "top",
                    size = item.optDouble("size", 11.0).toFloat().coerceIn(6f, 48f),
                )
                "append_text" -> PdfEdit.AppendText(item.optText("title"), item.requireText("text"))
                "watermark" -> PdfEdit.Watermark(item.optText("pages"), item.requireText("text"))
                else -> throw IllegalArgumentException(
                    "Operação desconhecida: $op. Use remove_pages, keep_pages, reorder, rotate, add_text, append_text ou watermark.",
                )
            }
        }
    }

    companion object {
        private const val MAX_READ_CHARS = 12_000
    }
}
