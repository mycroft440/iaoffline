package com.example.ialocal.runtime

import com.example.ialocal.ai.AiChatMessage
import com.example.ialocal.data.AiModelEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

enum class RuntimeStatus { IDLE, INITIALIZING, LOADING, READY, GENERATING, UNLOADING, ERROR }

data class RuntimeState(
    val status: RuntimeStatus = RuntimeStatus.IDLE,
    val modelId: String? = null,
    val modelName: String? = null,
    val error: String? = null,
)

data class VerificationResult(val output: String)

/** Additions for one request that are not part of the conversation history. */
data class TurnOptions(
    /** Appended to the latest user message for this request only (e.g. /no_think, encyclopedia excerpts). */
    val userSuffix: String = "",
    /** Prefilled at the start of the answer, the way chat templates switch reasoning on or off. */
    val assistantPrefix: String = "",
    /** enable_thinking for templates that read it (Gemma 4); off renders them without reasoning. */
    val templateThinking: Boolean = true,
    /**
     * The end of the system prompt that changes from one day to the next (the date). The runtime
     * keeps the system prompt processed up to it between loads, so only this part is read again.
     */
    val systemPromptTail: String = "",
)

/** What the next answer starts from, processed while the user is still writing the message. */
data class PromptPrefill(
    val systemPrompt: String,
    /** The conversation so far, without the message being written. */
    val history: List<AiChatMessage>,
    /** The answer budget of the next request, which decides how much of the history fits. */
    val maxTokens: Int,
    val turn: TurnOptions,
)

interface ModelRuntime {
    val state: StateFlow<RuntimeState>
    /** Loads [model]; with [prefill], also processes what the next answer starts from. */
    suspend fun warmUp(model: AiModelEntity, prefill: PromptPrefill? = null)

    /** Runs a short test answer. The model is freed afterwards unless [keepLoaded] and it passed. */
    suspend fun verify(model: AiModelEntity, keepLoaded: Boolean = false): VerificationResult

    fun streamComplete(
        model: AiModelEntity,
        systemPrompt: String,
        messages: List<AiChatMessage>,
        temperature: Float = 0.3f,
        maxTokens: Int = 1024,
        turn: TurnOptions = TurnOptions(),
    ): Flow<String>

    suspend fun complete(
        model: AiModelEntity,
        systemPrompt: String,
        messages: List<AiChatMessage>,
        temperature: Float = 0.3f,
        maxTokens: Int = 1024,
        turn: TurnOptions = TurnOptions(),
    ): String

    suspend fun unload()

    /** Frees the loaded model unless it is loading or answering; true if it was freed. */
    suspend fun releaseIfIdle(): Boolean = false
}
