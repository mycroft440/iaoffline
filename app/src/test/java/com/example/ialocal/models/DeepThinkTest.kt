package com.example.ialocal.models

import com.example.ialocal.data.AiModelEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepThinkTest {
    @Test
    fun deepSeekR1WithQwenInNameIsReasoningModel() {
        val model = model(
            name = "DeepSeek R1 0528 Qwen3 8B",
            apiModelId = "local-catalog-deepseek-r1-0528-qwen3-8b-q4-k-m-12345678",
        )

        val capability = DeepThinkSupport.capability(model)

        assertTrue(capability.supported)
        assertEquals(DeepThinkControlMode.REASONING_MODEL, capability.mode)
    }

    @Test
    fun qwen3CoderDoesNotExposeDeepThink() {
        val model = model(
            name = "Qwen3-Coder 30B-A3B",
            apiModelId = "local-catalog-qwen3-coder-30b-a3b-instruct-q4-k-m-12345678",
        )

        val capability = DeepThinkSupport.capability(model)

        assertFalse(capability.supported)
        assertEquals(DeepThinkControlMode.NONE, capability.mode)
    }

    @Test
    fun regularQwen3ExposesHybridDeepThink() {
        val model = model(
            name = "Qwen3 30B-A3B",
            apiModelId = "local-qwen3-30b-a3b-12345678",
        )

        assertEquals(
            DeepThinkControlMode.HYBRID_THINKING,
            DeepThinkSupport.capability(model).mode,
        )
    }

    @Test
    fun levelRaisesGenerationBudgetWithoutExceedingRuntimeLimit() {
        assertEquals(1024, DeepThinkSupport.effectiveMaxTokens(1024, DeepThinkLevel.AUTO))
        assertEquals(1536, DeepThinkSupport.effectiveMaxTokens(1024, DeepThinkLevel.LOW))
        assertEquals(2560, DeepThinkSupport.effectiveMaxTokens(1024, DeepThinkLevel.MEDIUM))
        assertEquals(4096, DeepThinkSupport.effectiveMaxTokens(1024, DeepThinkLevel.HIGH))
        assertEquals(4096, DeepThinkSupport.effectiveMaxTokens(9000, DeepThinkLevel.HIGH))
    }

    private fun model(name: String, apiModelId: String) = AiModelEntity(
        id = "model-id",
        name = name,
        apiModelId = apiModelId,
        format = "GGUF",
        architecture = null,
        filePath = "/tmp/model.gguf",
        sizeBytes = 1,
        importedAt = 1,
    )
}
