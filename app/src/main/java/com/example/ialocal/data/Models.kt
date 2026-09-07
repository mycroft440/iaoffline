package com.example.ialocal.data

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val isPinned: Boolean = false,
    val agentId: String? = null,
)

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("conversationId")],
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val role: String,
    val content: String,
    val createdAt: Long,
    val status: String = MessageStatus.COMPLETE.name,
)

@Entity(
    tableName = "attachments",
    foreignKeys = [
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["messageId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("messageId")],
)
data class AttachmentEntity(
    @PrimaryKey val id: String,
    val messageId: String,
    val type: String,
    val fileName: String,
    val localPath: String,
    val mimeType: String?,
    val sizeBytes: Long,
    val createdAt: Long,
    val extractedText: String? = null,
)

@Entity(
    tableName = "ai_models",
    indices = [Index(value = ["apiModelId"], unique = true)],
)
data class AiModelEntity(
    @PrimaryKey val id: String,
    val name: String,
    val apiModelId: String,
    val format: String,
    val architecture: String?,
    val filePath: String,
    val sizeBytes: Long,
    val importedAt: Long,
    val isActive: Boolean = false,
    /** Effective context supported by the bundled Android runtime. */
    val contextLength: Int = 8192,
    val sizeLabel: String? = null,
    val ggufVersion: Int = 0,
    val tensorCount: Long = 0,
    val declaredContextLength: Int? = null,
    val verificationStatus: String = ModelVerificationStatus.IMPORTED.name,
    val lastError: String? = null,
    val lastVerifiedAt: Long? = null,
)

@Entity(
    tableName = "agents",
    foreignKeys = [
        ForeignKey(
            entity = AiModelEntity::class,
            parentColumns = ["id"],
            childColumns = ["modelId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("modelId")],
)
data class AgentEntity(
    @PrimaryKey val id: String,
    val name: String,
    val modelId: String,
    val systemPrompt: String,
    val temperature: Float = 0.3f,
    val maxTokens: Int = 1024,
    val isDefault: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
)

data class MessageWithAttachments(
    @Embedded val message: MessageEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "messageId",
    )
    val attachments: List<AttachmentEntity>,
)

data class ConversationListItem(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val isPinned: Boolean,
    val lastMessage: String?,
)

enum class MessageRole { USER, ASSISTANT, SYSTEM }
enum class MessageStatus { SENDING, COMPLETE, ERROR }
enum class AttachmentType { FILE, AUDIO }
enum class ModelVerificationStatus { IMPORTED, VERIFYING, VERIFIED, ERROR }

data class PendingAttachment(
    val id: String,
    val type: AttachmentType,
    val fileName: String,
    val localPath: String,
    val mimeType: String?,
    val sizeBytes: Long,
    val extractedText: String? = null,
)
