package com.example.ialocal.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {
    @Query(
        """
        SELECT c.id, c.title, c.createdAt, c.updatedAt, c.isPinned,
               (SELECT m.content
                FROM messages m
                WHERE m.conversationId = c.id
                ORDER BY m.createdAt DESC
                LIMIT 1) AS lastMessage
        FROM conversations c
        ORDER BY c.isPinned DESC, c.updatedAt DESC
        """
    )
    fun observeConversationList(): Flow<List<ConversationListItem>>

    @Query("SELECT * FROM conversations WHERE id = :id LIMIT 1")
    fun observeConversation(id: String): Flow<ConversationEntity?>

    @Query("SELECT * FROM conversations WHERE id = :id LIMIT 1")
    suspend fun getConversation(id: String): ConversationEntity?

    @Transaction
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt ASC")
    fun observeMessages(conversationId: String): Flow<List<MessageWithAttachments>>

    @Transaction
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt ASC")
    suspend fun getMessages(conversationId: String): List<MessageWithAttachments>

    @Query(
        """
        SELECT a.id AS id,
               m.conversationId AS conversationId,
               c.title AS conversationTitle,
               a.type AS type,
               a.fileName AS fileName,
               a.localPath AS localPath,
               a.mimeType AS mimeType,
               a.sizeBytes AS sizeBytes,
               a.createdAt AS createdAt
        FROM attachments a
        INNER JOIN messages m ON m.id = a.messageId
        INNER JOIN conversations c ON c.id = m.conversationId
        ORDER BY a.createdAt DESC
        """
    )
    fun observeAllAttachments(): Flow<List<ChatAttachmentListItem>>

    @Query(
        """
        SELECT c.id, c.title, c.createdAt, c.updatedAt, c.isPinned,
               (SELECT m.content FROM messages m WHERE m.conversationId = c.id ORDER BY m.createdAt DESC LIMIT 1) AS lastMessage
        FROM conversations c
        WHERE c.title LIKE :query OR EXISTS (
            SELECT 1 FROM messages m WHERE m.conversationId = c.id AND m.content LIKE :query
        )
        ORDER BY c.updatedAt DESC
        LIMIT :limit
        """
    )
    suspend fun searchConversations(query: String, limit: Int): List<ConversationListItem>

    @Query(
        """
        SELECT c.id, c.title, c.createdAt, c.updatedAt, c.isPinned,
               (SELECT m.content FROM messages m WHERE m.conversationId = c.id ORDER BY m.createdAt DESC LIMIT 1) AS lastMessage
        FROM conversations c
        ORDER BY c.updatedAt DESC
        LIMIT :limit
        """
    )
    suspend fun listRecentConversations(limit: Int): List<ConversationListItem>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConversation(conversation: ConversationEntity)

    @Update
    suspend fun updateConversation(conversation: ConversationEntity)

    @Query("UPDATE conversations SET title = :title WHERE id = :id")
    suspend fun renameConversation(id: String, title: String)

    @Query("UPDATE conversations SET isPinned = :isPinned WHERE id = :id")
    suspend fun setPinned(id: String, isPinned: Boolean)

    @Query("UPDATE conversations SET updatedAt = :updatedAt WHERE id = :id")
    suspend fun touchConversation(id: String, updatedAt: Long)

    @Query("UPDATE conversations SET agentId = :agentId WHERE id = :id")
    suspend fun setConversationAgent(id: String, agentId: String?)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun deleteConversation(id: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAttachments(attachments: List<AttachmentEntity>)

    @Query("UPDATE messages SET status = :status WHERE id = :messageId")
    suspend fun updateMessageStatus(messageId: String, status: String)

    @Query("UPDATE messages SET content = :content WHERE id = :messageId")
    suspend fun updateMessageContent(messageId: String, content: String)
}
