package com.example.ialocal.agent.tools

import com.example.ialocal.data.ChatRepository
import com.example.ialocal.diagnostics.AiEventLogger
import com.example.ialocal.ui.codeeditor.CodeEditSessionStore
import java.text.DateFormat
import java.util.Date
import org.json.JSONArray
import org.json.JSONObject

enum class ToolPermission { READ_ONLY, SCOPED_WRITE, CONFIRM_REQUIRED }

data class ToolDefinition(
    val name: String,
    val description: String,
    val arguments: String,
    val permission: ToolPermission = ToolPermission.READ_ONLY,
)

data class ToolCall(val name: String, val arguments: JSONObject)

class AgentToolRegistry(
    private val chats: ChatRepository,
    private val codeEdits: CodeEditSessionStore,
    private val logger: AiEventLogger? = null,
) {
    val definitions = listOf(
        ToolDefinition("list_recent_conversations", "Lista as conversas mais recentes do aplicativo.", "{\"limit\": número opcional de 1 a 20}"),
        ToolDefinition("search_conversations", "Busca conversas por palavras no título ou nas mensagens.", "{\"query\": texto, \"limit\": número opcional}"),
        ToolDefinition("read_conversation", "Lê as mensagens de uma conversa pelo id.", "{\"conversation_id\": texto}"),
        ToolDefinition("current_time", "Retorna a data e hora local atuais do aparelho.", "{}"),
        ToolDefinition(
            name = "replace_code_range",
            description = "Edita diretamente SOMENTE o trecho previamente autorizado pelo Canvas. A faixa pertence ao session_id; você não escolhe offsets e não pode tocar no restante do documento. Use uma única vez.",
            arguments = "{\"session_id\": texto, \"replacement\": texto substituto exato, \"summary\": resumo curto opcional}",
            permission = ToolPermission.SCOPED_WRITE,
        ),
    )

    private fun availableDefinitions(): List<ToolDefinition> =
        if (codeEdits.hasActiveSessions()) definitions
        else definitions.filterNot { it.permission == ToolPermission.SCOPED_WRITE }

    fun promptInstructions(): String = buildString {
        append("\n\nVocê tem ferramentas locais. Use uma ferramenta apenas quando necessário.\n")
        append("Ferramentas SCOPED_WRITE só funcionam dentro de uma sessão explicitamente autorizada pela interface e não permitem ampliar a faixa de escrita.\n")
        append("Para chamar uma ferramenta, responda SOMENTE com um JSON válido neste formato: ")
        append("{\"tool\":\"nome\",\"arguments\":{...}}. Não use markdown nessa resposta.\n")
        append("Depois de receber um resultado de ferramenta, continue e responda normalmente ao usuário.\nFerramentas disponíveis:\n")
        availableDefinitions().forEach {
            append("- ${it.name} [${it.permission}]: ${it.description} Argumentos: ${it.arguments}\n")
        }
    }

    fun parseCall(output: String): ToolCall? {
        val ticks = "\u0060\u0060\u0060"
        val clean = output.trim().removePrefix(ticks + "json").removePrefix(ticks).removeSuffix(ticks).trim()
        val start = clean.indexOf('{')
        val end = clean.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val json = runCatching { JSONObject(clean.substring(start, end + 1)) }.getOrNull() ?: return null
        val name = json.optString("tool").takeIf { it.isNotBlank() } ?: return null
        if (availableDefinitions().none { it.name == name }) return null
        return ToolCall(name, json.optJSONObject("arguments") ?: JSONObject())
    }

    suspend fun execute(call: ToolCall): String {
        val definition = availableDefinitions().firstOrNull { it.name == call.name }
            ?: throw IllegalArgumentException("Ferramenta desconhecida ou indisponível: ${call.name}")
        check(definition.permission != ToolPermission.CONFIRM_REQUIRED) {
            "A ferramenta ${call.name} exige confirmação e não pode ser executada automaticamente."
        }
        logger?.info("AGENT_TOOL", "Executando ${call.name}")

        return when (call.name) {
            "list_recent_conversations" -> {
                val limit = call.arguments.optInt("limit", 10).coerceIn(1, 20)
                val items = chats.listRecentConversations(limit)
                JSONArray().apply {
                    items.forEach { item ->
                        put(JSONObject().put("id", item.id).put("title", item.title).put("updated_at", item.updatedAt))
                    }
                }.toString()
            }
            "search_conversations" -> {
                val query = call.arguments.optString("query").trim()
                require(query.isNotBlank()) { "A busca precisa de 'query'." }
                val limit = call.arguments.optInt("limit", 10).coerceIn(1, 20)
                val items = chats.searchConversations(query, limit)
                JSONArray().apply {
                    items.forEach { item ->
                        put(JSONObject().put("id", item.id).put("title", item.title).put("last_message", item.lastMessage ?: ""))
                    }
                }.toString()
            }
            "read_conversation" -> {
                val id = call.arguments.optString("conversation_id").trim()
                require(id.isNotBlank()) { "Informe 'conversation_id'." }
                val messages = chats.getMessages(id).takeLast(80)
                JSONArray().apply {
                    messages.forEach { item ->
                        put(JSONObject().put("role", item.message.role.lowercase()).put("content", item.message.content))
                    }
                }.toString()
            }
            "current_time" -> JSONObject()
                .put("local_time", DateFormat.getDateTimeInstance(DateFormat.FULL, DateFormat.LONG).format(Date()))
                .put("epoch_ms", System.currentTimeMillis())
                .toString()
            "replace_code_range" -> {
                val sessionId = call.arguments.optString("session_id").trim()
                require(sessionId.isNotBlank()) { "Informe 'session_id'." }
                require(call.arguments.has("replacement")) { "Informe 'replacement'." }
                val replacement = call.arguments.getString("replacement")
                val summary = call.arguments.optString("summary").trim().takeIf { it.isNotBlank() }
                val result = codeEdits.applyReplacement(sessionId, replacement, summary)
                JSONObject()
                    .put("status", "applied")
                    .put("session_id", result.id)
                    .put("target", result.target.label())
                    .put("summary", result.summary ?: "Trecho substituído diretamente pelo agente.")
                    .put("outside_range_unchanged", true)
                    .toString()
            }
            else -> error("Ferramenta não implementada.")
        }
    }
}
