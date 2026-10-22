package com.example.ialocal.runtime.remote

import com.example.ialocal.ai.AiChatMessage
import com.example.ialocal.data.AiModelEntity
import com.example.ialocal.runtime.PromptPrefill
import com.example.ialocal.runtime.TurnOptions
import org.json.JSONArray
import org.json.JSONObject

/** One answer requested from the AI process. */
internal data class GenerateRequest(
    val model: AiModelEntity,
    val systemPrompt: String,
    val messages: List<AiChatMessage>,
    val temperature: Float,
    val maxTokens: Int,
    val turn: TurnOptions,
    val safeLevel: Int,
    val performanceProfile: String = "CACHE",
)

/** JSON for what crosses to the AI process; small enough for a Binder transaction. */
internal object RemoteProtocol {
    /**
     * A Binder transaction is limited to 1 MB and strings travel as UTF-16, so a request keeps at
     * most this many characters of conversation. That is more than the context of any model on a
     * phone holds; the runtime trims the rest to the context anyway.
     */
    const val MAX_CONVERSATION_CHARS = 240_000
    private const val CUT_NOTICE = "[início cortado] "

    fun encodeModel(model: AiModelEntity, profile: String = "CACHE"): String = modelJson(model).put("performanceProfile", profile).toString()

    fun decodeProfile(json: String): String = JSONObject(json).optString("performanceProfile", "CACHE")

    fun decodeModel(json: String): AiModelEntity = model(JSONObject(json))

    fun encodeRequest(request: GenerateRequest): String = JSONObject()
        .put("model", modelJson(request.model))
        .put("systemPrompt", request.systemPrompt)
        .put("messages", messagesJson(fitToLimit(request.messages)))
        .put("temperature", request.temperature.toDouble())
        .put("maxTokens", request.maxTokens)
        .putTurn(request.turn)
        .put("safeLevel", request.safeLevel)
        .put("performanceProfile", request.performanceProfile)
        .toString()

    fun decodeRequest(json: String): GenerateRequest {
        val o = JSONObject(json)
        return GenerateRequest(
            model = model(o.getJSONObject("model")),
            systemPrompt = o.getString("systemPrompt"),
            messages = messages(o.getJSONArray("messages")),
            temperature = o.getDouble("temperature").toFloat(),
            maxTokens = o.getInt("maxTokens"),
            turn = turn(o),
            safeLevel = o.optInt("safeLevel"),
            performanceProfile = o.optString("performanceProfile", "CACHE"),
        )
    }

    /** Limited like a request's messages, so it fits the same Binder transaction. */
    fun encodePrefill(prefill: PromptPrefill): String = JSONObject()
        .put("systemPrompt", prefill.systemPrompt)
        .put("history", messagesJson(fitToLimit(prefill.history)))
        .put("maxTokens", prefill.maxTokens)
        .putTurn(prefill.turn)
        .toString()

    fun decodePrefill(json: String): PromptPrefill {
        val o = JSONObject(json)
        return PromptPrefill(
            systemPrompt = o.getString("systemPrompt"),
            history = messages(o.getJSONArray("history")),
            maxTokens = o.getInt("maxTokens"),
            turn = turn(o),
        )
    }

    private fun messagesJson(messages: List<AiChatMessage>) = JSONArray().apply {
        messages.forEach { put(JSONObject().put("role", it.role).put("content", it.content)) }
    }

    private fun messages(array: JSONArray) = List(array.length()) { index ->
        val item = array.getJSONObject(index)
        AiChatMessage(item.getString("role"), item.getString("content"))
    }

    private fun JSONObject.putTurn(turn: TurnOptions) = this
        .put("userSuffix", turn.userSuffix)
        .put("assistantPrefix", turn.assistantPrefix)
        .put("templateThinking", turn.templateThinking)
        .put("systemPromptTail", turn.systemPromptTail)

    private fun turn(o: JSONObject) = TurnOptions(
        userSuffix = o.optString("userSuffix"),
        assistantPrefix = o.optString("assistantPrefix"),
        templateThinking = o.optBoolean("templateThinking", true),
        systemPromptTail = o.optString("systemPromptTail"),
    )

    /** The most recent messages within [limit] characters; the latest one is always kept. */
    fun fitToLimit(messages: List<AiChatMessage>, limit: Int = MAX_CONVERSATION_CHARS): List<AiChatMessage> {
        if (messages.sumOf { it.content.length } <= limit) return messages
        val kept = ArrayDeque<AiChatMessage>()
        var used = 0
        for (message in messages.asReversed()) {
            if (kept.isEmpty() && message.content.length > limit) {
                // A single huge message: keep its end, where the question usually is.
                kept.addFirst(message.copy(content = CUT_NOTICE + message.content.takeLast(limit - CUT_NOTICE.length)))
                break
            }
            if (used + message.content.length > limit) break
            kept.addFirst(message)
            used += message.content.length
        }
        return kept.toList()
    }

    private fun modelJson(model: AiModelEntity) = JSONObject()
        .put("id", model.id)
        .put("name", model.name)
        .put("apiModelId", model.apiModelId)
        .put("format", model.format)
        .put("architecture", model.architecture ?: JSONObject.NULL)
        .put("filePath", model.filePath)
        .put("sizeBytes", model.sizeBytes)
        .put("importedAt", model.importedAt)
        .put("isActive", model.isActive)
        .put("contextLength", model.contextLength)
        .put("sizeLabel", model.sizeLabel ?: JSONObject.NULL)
        .put("ggufVersion", model.ggufVersion)
        .put("tensorCount", model.tensorCount)
        .put("declaredContextLength", model.declaredContextLength ?: JSONObject.NULL)
        .put("verificationStatus", model.verificationStatus)
        .put("lastError", model.lastError ?: JSONObject.NULL)
        .put("lastVerifiedAt", model.lastVerifiedAt ?: JSONObject.NULL)

    private fun model(o: JSONObject) = AiModelEntity(
        id = o.getString("id"),
        name = o.getString("name"),
        apiModelId = o.getString("apiModelId"),
        format = o.getString("format"),
        architecture = o.optStringOrNull("architecture"),
        filePath = o.getString("filePath"),
        sizeBytes = o.getLong("sizeBytes"),
        importedAt = o.getLong("importedAt"),
        isActive = o.optBoolean("isActive"),
        contextLength = o.optInt("contextLength", 8192),
        sizeLabel = o.optStringOrNull("sizeLabel"),
        ggufVersion = o.optInt("ggufVersion"),
        tensorCount = o.optLong("tensorCount"),
        declaredContextLength = if (o.isNull("declaredContextLength")) null else o.getInt("declaredContextLength"),
        verificationStatus = o.getString("verificationStatus"),
        lastError = o.optStringOrNull("lastError"),
        lastVerifiedAt = if (o.isNull("lastVerifiedAt")) null else o.getLong("lastVerifiedAt"),
    )

    private fun JSONObject.optStringOrNull(key: String): String? = if (isNull(key)) null else getString(key)
}
