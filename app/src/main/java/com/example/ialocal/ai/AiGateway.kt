package com.example.ialocal.ai

import com.example.ialocal.data.PendingAttachment
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList

data class AiChatMessage(val role: String, val content: String)

data class AiChatRequest(
    val conversationId: String,
    val messages: List<AiChatMessage>,
    val attachments: List<PendingAttachment> = emptyList(),
    val agentId: String? = null,
)

interface AiGateway {
    fun streamChat(request: AiChatRequest): Flow<String>

    suspend fun chat(request: AiChatRequest): String =
        streamChat(request).toList().joinToString("")
}
