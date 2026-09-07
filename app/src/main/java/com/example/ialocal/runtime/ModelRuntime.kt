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

interface ModelRuntime {
    val state: StateFlow<RuntimeState>
    suspend fun warmUp(model: AiModelEntity)
    suspend fun verify(model: AiModelEntity): VerificationResult

    fun streamComplete(
        model: AiModelEntity,
        systemPrompt: String,
        messages: List<AiChatMessage>,
        temperature: Float = 0.3f,
        maxTokens: Int = 1024,
    ): Flow<String>

    suspend fun complete(
        model: AiModelEntity,
        systemPrompt: String,
        messages: List<AiChatMessage>,
        temperature: Float = 0.3f,
        maxTokens: Int = 1024,
    ): String

    suspend fun unload()
}
