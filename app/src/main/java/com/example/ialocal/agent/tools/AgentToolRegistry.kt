package com.example.ialocal.agent.tools

import com.example.ialocal.data.ChatRepository
import com.example.ialocal.diagnostics.AiEventLogger
import java.text.DateFormat
import java.util.Date
import org.json.JSONArray
import org.json.JSONObject

enum class ToolPermission { READ_ONLY, CONFIRM_REQUIRED }

data class ToolDefinition(
    val name: String,
    val description: String,
    val arguments: String,
    val permission: ToolPermission = ToolPermission.READ_ONLY,
)

data class ToolCall(val name: String, val arguments: JSONObject)

class AgentToolRegistry(
    private val chats: ChatRepository,
    private val logger: AiEventLogger? = null,
) {
    val definitions = listOf(
        ToolDefinition("list_recent_conversations", "Lista as conversas mais recentes do aplicativo.", "{\"limit\": número opcional de 1 a 20}"),
        ToolDefinition("search_conversations", "Busca conversas por palavras no título ou nas mensagens.", "{\"query\": texto, \"limit\": número opcional}"),
        ToolDefinition("read_conversation", "Lê as mensagens de uma conversa pelo id.", "{\"conversation_id\": texto}"),
        ToolDefinition("current_time", "Retorna a data e hora local atuais do aparelho.", "{}"),
    )

    fun promptInstructions(): String = buildString {
        append("\n\nVocê tem ferramentas locais de SOMENTE LEITURA. Use uma ferramenta apenas quando necessário.\n")
        append("Para chamar uma ferramenta, responda SOMENTE com um JSON válido neste formato: ")
        append("{\"tool\":\"nome\",\"arguments\":{...}}. Não use markdown nessa resposta.\n")
        append("Depois de receber um resultado de ferramenta, continue e responda normalmente ao usuário.\nFerramentas:\n")
        definitions.forEach { append("- ${it.name}: ${it.description} Argumentos: ${it.arguments}\n") }
    }

    fun parseCall(output: String): ToolCall? {
        val clean = output.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val start = clean.indexOf('{')
        val end = clean.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val json = runCatching { JSONObject(clean.substring(start, end + 1)) }.getOrNull() ?: return null
        val name = json.optString("tool").takeIf { it.isNotBlank() } ?: return null
        if (definitions.none { it.name == name }) return null
        return ToolCall(name, json.optJSONObject("arguments") ?: JSONObject())
    }

    suspend fun execute(call: ToolCall): String {
        val definition = definitions.firstOrNull { it.name == call.name }
            ?: throw IllegalArgumentException("Ferramenta desconhecida: ${call.name}")
        check(definition.permission == ToolPermission.READ_ONLY) {
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
            else -> error("Ferramenta não implementada.")
        }
    }
}
