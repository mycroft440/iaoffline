package com.example.ialocal.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ModelDao {
    @Query("SELECT * FROM ai_models ORDER BY isActive DESC, importedAt DESC")
    fun observeModels(): Flow<List<AiModelEntity>>

    @Query("SELECT * FROM agents ORDER BY isDefault DESC, updatedAt DESC")
    fun observeAgents(): Flow<List<AgentEntity>>

    @Query("SELECT * FROM ai_models ORDER BY isActive DESC, importedAt DESC")
    suspend fun getModels(): List<AiModelEntity>

    @Query("SELECT * FROM agents ORDER BY isDefault DESC, updatedAt DESC")
    suspend fun getAgents(): List<AgentEntity>

    @Query("SELECT * FROM ai_models WHERE id = :id LIMIT 1")
    suspend fun getModel(id: String): AiModelEntity?

    @Query("SELECT * FROM ai_models WHERE apiModelId = :apiModelId LIMIT 1")
    suspend fun getModelByApiId(apiModelId: String): AiModelEntity?

    @Query("SELECT * FROM ai_models WHERE isActive = 1 LIMIT 1")
    suspend fun getActiveModel(): AiModelEntity?

    @Query("SELECT * FROM agents WHERE id = :id LIMIT 1")
    suspend fun getAgent(id: String): AgentEntity?

    @Query("SELECT * FROM agents WHERE isDefault = 1 LIMIT 1")
    suspend fun getDefaultAgent(): AgentEntity?

    @Query("SELECT * FROM agents WHERE modelId = :modelId ORDER BY isDefault DESC, createdAt ASC LIMIT 1")
    suspend fun getAgentForModel(modelId: String): AgentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertModel(model: AiModelEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAgent(agent: AgentEntity)

    @Transaction
    suspend fun insertModelWithAgent(model: AiModelEntity, agent: AgentEntity) {
        insertModel(model)
        insertAgent(agent)
    }

    @Update
    suspend fun updateAgent(agent: AgentEntity)

    @Query("UPDATE ai_models SET isActive = 0")
    suspend fun clearActiveModel()

    @Query("UPDATE ai_models SET isActive = 1 WHERE id = :id")
    suspend fun markModelActive(id: String)

    @Transaction
    suspend fun activateModelAtomically(id: String) {
        clearActiveModel()
        markModelActive(id)
    }

    @Query("UPDATE ai_models SET verificationStatus = :status, lastError = :lastError, lastVerifiedAt = :verifiedAt WHERE id = :id")
    suspend fun updateVerification(id: String, status: String, lastError: String?, verifiedAt: Long?)

    @Query("UPDATE agents SET isDefault = 0")
    suspend fun clearDefaultAgent()

    @Query("UPDATE agents SET isDefault = 1, updatedAt = :updatedAt WHERE id = :id")
    suspend fun markAgentDefault(id: String, updatedAt: Long)

    @Transaction
    suspend fun setDefaultAgentAtomically(id: String, updatedAt: Long) {
        clearDefaultAgent()
        markAgentDefault(id, updatedAt)
    }

    @Query("UPDATE ai_models SET isActive = 0 WHERE verificationStatus != :verifiedStatus")
    suspend fun clearActiveUnverifiedModels(verifiedStatus: String)

    @Query(
        """
        UPDATE agents SET isDefault = 0
        WHERE modelId NOT IN (
            SELECT id FROM ai_models WHERE verificationStatus = :verifiedStatus
        )
        """
    )
    suspend fun clearDefaultAgentsForUnverifiedModels(verifiedStatus: String)

    @Transaction
    suspend fun repairSelections(verifiedStatus: String, updatedAt: Long) {
        clearActiveUnverifiedModels(verifiedStatus)
        val verifiedModels = getModels().filter { it.verificationStatus == verifiedStatus }
        if (verifiedModels.count { it.isActive } != 1) {
            clearActiveModel()
            verifiedModels.firstOrNull()?.let { markModelActive(it.id) }
        }

        clearDefaultAgentsForUnverifiedModels(verifiedStatus)
        val verifiedModelIds = verifiedModels.mapTo(mutableSetOf()) { it.id }
        val eligibleAgents = getAgents().filter { it.modelId in verifiedModelIds }
        if (eligibleAgents.count { it.isDefault } != 1) {
            clearDefaultAgent()
            eligibleAgents.firstOrNull()?.let { markAgentDefault(it.id, updatedAt) }
        }
    }

    @Query("UPDATE conversations SET agentId = NULL WHERE agentId IN (SELECT id FROM agents WHERE modelId = :modelId)")
    suspend fun clearConversationAgentsForModel(modelId: String)

    @Query("DELETE FROM ai_models WHERE id = :id")
    suspend fun deleteModel(id: String)

    @Transaction
    suspend fun deleteModelAtomically(id: String) {
        clearConversationAgentsForModel(id)
        deleteModel(id)
    }
}
