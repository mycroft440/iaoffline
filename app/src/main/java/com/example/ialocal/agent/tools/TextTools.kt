package com.example.ialocal.agent.tools

import com.example.ialocal.files.DocumentLibrary
import com.example.ialocal.files.DocumentLibrary.Kind
import com.example.ialocal.files.TextEdit
import com.example.ialocal.files.TextFiles
import org.json.JSONArray
import org.json.JSONObject

/**
 * Agent tools to read, create and edit text files (.txt and other plain-text formats). Like the
 * PDF tools, they work on the conversation's files and never change or delete one: an edit is a
 * new file, attached to the answer in the chat and copied to Documents/IA Offline.
 */
class TextTools(private val library: DocumentLibrary) : ConversationFileTools {
    override val definitions = listOf(
        ToolDefinition(
            "txt_list",
            "Lista os arquivos de texto desta conversa (.txt, .md, .csv e outros; anexados pelo usuário e criados pelas ferramentas) com id, nome e número de linhas.",
            "{}",
        ),
        ToolDefinition(
            "txt_read",
            "Lê um arquivo de texto desta conversa com as linhas numeradas como \"12| texto\" (o número não faz parte do texto). Use lines para ler um trecho ou query para achar as linhas que contêm um termo.",
            "{\"id\": texto, \"lines\": \"opcional, ex. 1-80\", \"query\": \"texto opcional\"}",
        ),
        ToolDefinition(
            "txt_create",
            "Cria um arquivo de texto novo com o conteúdo exatamente como escrito. O nome termina em .txt, ou em .md, .csv e similares se você pedir.",
            "{\"file_name\": texto, \"content\": texto}",
            ToolPermission.CREATE_FILES,
        ),
        ToolDefinition(
            "txt_edit",
            "Cria uma cópia editada de um arquivo de texto desta conversa, aplicando as operações em ordem: replace {find, replace} (troca todas as ocorrências do trecho exato; all=false só a primeira), append {text}, prepend {text}, insert_lines {after_line, text}, delete_lines {lines}, replace_lines {lines, text}. As linhas são as numeradas pelo txt_read.",
            "{\"id\": texto, \"file_name\": texto opcional, \"operations\": [{\"op\": \"replace\", \"find\": \"texto exato\", \"replace\": \"texto novo\"}]}",
            ToolPermission.CREATE_FILES,
        ),
    )

    override suspend fun execute(name: String, arguments: JSONObject, conversationId: String?): String = when (name) {
        "txt_list" -> {
            val documents = library.list(conversationId, Kind.TEXT)
            JSONObject()
                .put("files", JSONArray().apply {
                    documents.forEach { document ->
                        put(
                            JSONObject()
                                .put("id", document.id)
                                .put("name", document.name)
                                .put("origin", document.origin.label)
                                .put("lines", runCatching { TextFiles.load(document.file).lines.size }.getOrDefault(0))
                        )
                    }
                })
                .apply { if (documents.isEmpty()) put("note", "Nenhum arquivo de texto nesta conversa. O usuário pode anexar arquivos pelo clipe.") }
                .toString()
        }
        "txt_read" -> {
            val document = library.find(conversationId, Kind.TEXT, arguments.requireText("id"))
            val excerpt = TextFiles.read(
                TextFiles.load(document.file),
                arguments.optText("lines"),
                arguments.optText("query"),
                MAX_READ_CHARS,
            )
            JSONObject()
                .put("id", document.id)
                .put("name", document.name)
                .put("total_lines", excerpt.totalLines)
                .put("text", excerpt.text)
                .apply { excerpt.note?.let { put("note", it) } }
                .toString()
        }
        "txt_create" -> {
            val content = requireNotNull(arguments.rawText("content") ?: arguments.rawText("text")) { "Informe 'content' com o texto do arquivo." }
            val text = TextFiles.created(content)
            val (document, _) = library.create(conversationId, Kind.TEXT, arguments.optText("file_name") ?: Kind.TEXT.defaultName) { file ->
                file.writeBytes(TextFiles.encode(text))
            }
            createdResult(document).put("lines", TextFiles.lines(text).size).toString()
        }
        "txt_edit" -> {
            val source = library.find(conversationId, Kind.TEXT, arguments.requireText("id"))
            val original = TextFiles.load(source.file)
            val edited = TextFiles.edit(original.text, parseOperations(arguments.optJSONArray("operations")))
            val extension = source.name.substringAfterLast('.', "")
            val name = arguments.optText("file_name")?.let { DocumentLibrary.fileName(it, Kind.TEXT, extension) }
                ?: DocumentLibrary.editedName(source.name, Kind.TEXT)
            val (document, _) = library.create(conversationId, Kind.TEXT, name) { file ->
                file.writeBytes(TextFiles.encode(edited.text, original.lineSeparator, original.byteOrderMark))
            }
            createdResult(document)
                .put("lines", TextFiles.lines(edited.text).size)
                .put("changes", JSONArray(edited.changes))
                .toString()
        }
        else -> error("Ferramenta de texto desconhecida: $name")
    }

    companion object {
        private const val MAX_READ_CHARS = 12_000

        /** The model's "operations" as edits for [TextFiles.edit]. */
        internal fun parseOperations(array: JSONArray?): List<TextEdit> {
            require(array != null && array.length() > 0) { "Informe 'operations' com ao menos uma operação." }
            return (0 until array.length()).map { i ->
                val item = array.optJSONObject(i) ?: throw IllegalArgumentException("Cada operação deve ser um objeto com 'op'.")
                when (val op = item.requireText("op")) {
                    "replace" -> TextEdit.Replace(
                        find = item.rawText("find")?.takeIf { it.isNotEmpty() } ?: throw IllegalArgumentException("Em replace, informe 'find' com o trecho exato."),
                        replacement = item.rawText("replace") ?: item.rawText("replacement") ?: "",
                        all = item.optBoolean("all", true),
                    )
                    "append" -> TextEdit.Append(item.rawText("text").orEmpty())
                    "prepend" -> TextEdit.Prepend(item.rawText("text").orEmpty())
                    "insert_lines" -> TextEdit.InsertLines(
                        afterLine = if (item.has("after_line")) item.optInt("after_line", -1) else throw IllegalArgumentException("Em insert_lines, informe 'after_line' (0 para o início)."),
                        text = item.rawText("text").orEmpty(),
                    )
                    "delete_lines" -> TextEdit.DeleteLines(item.requireText("lines"))
                    "replace_lines" -> TextEdit.ReplaceLines(item.requireText("lines"), item.rawText("text").orEmpty())
                    else -> throw IllegalArgumentException(
                        "Operação desconhecida: $op. Use replace, append, prepend, insert_lines, delete_lines ou replace_lines.",
                    )
                }
            }
        }
    }
}
