package com.example.ialocal.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
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

    @Update
    suspend fun updateAgent(agent: AgentEntity)

    @Query("UPDATE ai_models SET isActive = 0")
    suspend fun clearActiveModel()

    @Query("UPDATE ai_models SET isActive = 1 WHERE id = :id")
    suspend fun markModelActive(id: String)

    @Query("UPDATE ai_models SET verificationStatus = :status, lastError = :lastError, lastVerifiedAt = :verifiedAt WHERE id = :id")
    suspend fun updateVerification(id: String, status: String, lastError: String?, verifiedAt: Long?)

    @Query(
        """
        UPDATE ai_models SET
            contextLength = :safeContext,
            calibratedContextLength = NULL,
            contextCalibrationStatus = :status,
            contextCalibrationKey = :calibrationKey,
            contextCalibrationUpdatedAt = :updatedAt,
            lastFailedContextLength = NULL,
            lastContextExitReason = NULL,
            contextCalibrationError = NULL
        WHERE id = :id
        """
    )
    suspend fun startContextCalibration(
        id: String,
        safeContext: Int,
        status: String,
        calibrationKey: String,
        updatedAt: Long,
    )

    @Query(
        """
        UPDATE ai_models SET
            contextLength = :contextLength,
            calibratedContextLength = :contextLength,
            contextCalibrationUpdatedAt = :updatedAt
        WHERE id = :id
        """
    )
    suspend fun recordContextProbeSuccess(id: String, contextLength: Int, updatedAt: Long)

    @Query(
        """
        UPDATE ai_models SET
            lastFailedContextLength = :contextLength,
            lastContextExitReason = :reason,
            contextCalibrationUpdatedAt = :updatedAt
        WHERE id = :id
        """
    )
    suspend fun recordContextProbeFailure(id: String, contextLength: Int, reason: String?, updatedAt: Long)

    @Query(
        """
        UPDATE ai_models SET
            contextLength = :contextLength,
            calibratedContextLength = :contextLength,
            contextCalibrationStatus = :status,
            contextCalibrationUpdatedAt = :updatedAt,
            contextCalibrationError = NULL
        WHERE id = :id
        """
    )
    suspend fun finishContextCalibration(
        id: String,
        contextLength: Int,
        status: String,
        updatedAt: Long,
    )

    @Query(
        """
        UPDATE ai_models SET
            contextCalibrationStatus = :status,
            contextCalibrationUpdatedAt = :updatedAt,
            contextCalibrationError = :error
        WHERE id = :id
        """
    )
    suspend fun failContextCalibration(id: String, status: String, error: String, updatedAt: Long)

    @Query("UPDATE agents SET isDefault = 0")
    suspend fun clearDefaultAgent()

    @Query("UPDATE agents SET isDefault = 1, updatedAt = :updatedAt WHERE id = :id")
    suspend fun markAgentDefault(id: String, updatedAt: Long)

    @Query("DELETE FROM ai_models WHERE id = :id")
    suspend fun deleteModel(id: String)
}
