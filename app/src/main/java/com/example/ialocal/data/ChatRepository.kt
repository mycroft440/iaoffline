package com.example.ialocal.data

import java.util.UUID
import kotlinx.coroutines.flow.Flow

class ChatRepository(
    private val dao: ChatDao,
) {
    val conversations: Flow<List<ConversationListItem>> = dao.observeConversationList()
    val attachments: Flow<List<ChatAttachmentListItem>> = dao.observeAllAttachments()

    fun observeConversation(id: String): Flow<ConversationEntity?> = dao.observeConversation(id)

    fun observeMessages(conversationId: String): Flow<List<MessageWithAttachments>> =
        dao.observeMessages(conversationId)

    suspend fun createConversation(agentId: String? = null): String {
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        dao.insertConversation(
            ConversationEntity(
                id = id,
                title = "Nova conversa",
                createdAt = now,
                updatedAt = now,
                agentId = agentId,
            )
        )
        return id
    }

    suspend fun getConversation(id: String): ConversationEntity? = dao.getConversation(id)

    suspend fun renameConversation(id: String, title: String) {
        val clean = title.trim().ifBlank { "Nova conversa" }
        dao.renameConversation(id, clean)
    }

    suspend fun setPinned(id: String, pinned: Boolean) = dao.setPinned(id, pinned)

    suspend fun deleteConversation(id: String) = dao.deleteConversation(id)

    suspend fun setConversationAgent(id: String, agentId: String?) = dao.setConversationAgent(id, agentId)

    suspend fun addMessage(
        conversationId: String,
        role: MessageRole,
        content: String,
        pendingAttachments: List<PendingAttachment> = emptyList(),
        status: MessageStatus = MessageStatus.COMPLETE,
    ): String {
        val now = System.currentTimeMillis()
        val messageId = UUID.randomUUID().toString()
        dao.insertMessage(
            MessageEntity(
                id = messageId,
                conversationId = conversationId,
                role = role.name,
                content = content,
                createdAt = now,
                status = status.name,
            )
        )

        if (pendingAttachments.isNotEmpty()) {
            dao.insertAttachments(
                pendingAttachments.map { pending ->
                    AttachmentEntity(
                        id = pending.id,
                        messageId = messageId,
                        type = pending.type.name,
                        fileName = pending.fileName,
                        localPath = pending.localPath,
                        mimeType = pending.mimeType,
                        sizeBytes = pending.sizeBytes,
                        createdAt = now,
                        extractedText = pending.extractedText,
                    )
                }
            )
        }

        dao.touchConversation(conversationId, now)
        maybeCreateAutomaticTitle(conversationId, role, content)
        return messageId
    }

    suspend fun updateMessageStatus(messageId: String, status: MessageStatus) {
        dao.updateMessageStatus(messageId, status.name)
    }

    suspend fun updateMessageContent(messageId: String, content: String) {
        dao.updateMessageContent(messageId, content)
    }

    suspend fun getMessages(conversationId: String): List<MessageWithAttachments> =
        dao.getMessages(conversationId)

    suspend fun searchConversations(query: String, limit: Int = 50): List<ConversationListItem> =
        dao.searchConversations("%${query.trim()}%", limit)

    suspend fun listRecentConversations(limit: Int = 50): List<ConversationListItem> =
        dao.listRecentConversations(limit)

    private suspend fun maybeCreateAutomaticTitle(
        conversationId: String,
        role: MessageRole,
        content: String,
    ) {
        if (role != MessageRole.USER) return
        val conversation = dao.getConversation(conversationId) ?: return
        if (conversation.title != "Nova conversa") return

        val title = content
            .replace("\n", " ")
            .trim()
            .take(42)
            .ifBlank { "Conversa com IA" }
        dao.renameConversation(conversationId, title)
    }
}
