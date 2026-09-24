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
        assertEquals(8192, DeepThinkSupport.effectiveMaxTokens(8192, DeepThinkLevel.LOW))
        assertEquals(8192, DeepThinkSupport.effectiveMaxTokens(20_000, DeepThinkLevel.HIGH))
    }

    @Test
    fun newerQwenAndReasoningVariantsAlwaysReason() {
        listOf(
            "Qwen3.5 4B" to "local-catalog-qwen3.5-4b-q4-k-m-1",
            "Qwen3.8 27B" to "local-catalog-qwen3.8-27b-q4-k-m-1",
            "Ministral 3 8B Reasoning" to "local-catalog-ministral3-8b-reasoning-q4-k-m-1",
            "OLMo 3 7B Think" to "local-catalog-olmo3-7b-think-q4-k-m-1",
        ).forEach { (name, id) ->
            assertEquals(name, DeepThinkControlMode.REASONING_MODEL, DeepThinkSupport.capability(model(name, id)).mode)
        }
    }

    @Test
    fun instructOnlyModelsHaveNoDeepThink() {
        listOf(
            "OLMo 3 7B Instruct" to "local-catalog-olmo3-7b-instruct-q4-k-m-1",
            "Llama 3.2 3B" to "local-catalog-llama3.2-3b-instruct-q4-k-m-1",
            "Ministral 3 8B" to "local-catalog-ministral3-8b-instruct-q4-k-m-1",
        ).forEach { (name, id) ->
            assertEquals(name, DeepThinkControlMode.NONE, DeepThinkSupport.capability(model(name, id)).mode)
        }
    }

    @Test
    fun newFourBillionModelsAreClassified() {
        assertEquals(
            DeepThinkControlMode.REASONING_MODEL,
            DeepThinkSupport.capability(model("Phi-4 mini Reasoning 3.8B", "local-catalog-phi4-mini-reasoning-q4-k-m-1")).mode,
        )
        assertEquals(
            DeepThinkControlMode.NONE,
            DeepThinkSupport.capability(model("Phi-4 mini 3.8B", "local-catalog-phi4-mini-instruct-q4-k-m-1")).mode,
        )
        // Nemotron 3 is not the v2 family whose /no_think switch the app knows.
        assertEquals(
            DeepThinkControlMode.NONE,
            DeepThinkSupport.capability(model("Nemotron 3 Nano 4B", "local-catalog-nemotron3-nano-4b-q4-k-m-1")).mode,
        )
    }

    @Test
    fun newMoeModelsAreClassified() {
        assertEquals(
            DeepThinkControlMode.REASONING_MODEL,
            DeepThinkSupport.capability(model("Qwen3.6 35B-A3B", "local-catalog-qwen3.6-35b-a3b-q3-k-m-1")).mode,
        )
        assertEquals(
            DeepThinkControlMode.NONE,
            DeepThinkSupport.capability(model("Nemotron 3 Nano 30B-A3B", "local-catalog-nemotron3-nano-30b-a3b-q3-k-m-1")).mode,
        )
        assertEquals(
            DeepThinkControlMode.NONE,
            DeepThinkSupport.capability(model("Granite 4.0 H Tiny 7B-A1B", "local-catalog-granite4.0-h-tiny-q3-k-m-1")).mode,
        )
    }

    @Test
    fun nemotronNanoV2IsHybrid() {
        val model = model("Nemotron Nano 9B v2", "local-catalog-nemotron-nano-9b-v2-q4-k-m-1")
        assertEquals(DeepThinkControlMode.HYBRID_THINKING, DeepThinkSupport.capability(model).mode)
    }

    @Test
    fun disabledHybridIsToldNotToThink() {
        val qwen = model("Qwen3 8B", "local-qwen3-8b-1")
        val plan = DeepThinkSupport.plan(qwen, 1024, enabled = false, level = DeepThinkLevel.HIGH)
        assertEquals(1024, plan.maxTokens)
        assertEquals(" /no_think", plan.userSuffix)
        assertEquals("", plan.systemSuffix)

        val nemotron = model("Nemotron Nano 9B v2", "local-catalog-nemotron-nano-9b-v2-1")
        assertEquals("\n/no_think", DeepThinkSupport.plan(nemotron, 1024, false, DeepThinkLevel.HIGH).systemSuffix)
    }

    @Test
    fun enabledHybridUsesLevelAndReasoningModelsGetFullBudget() {
        val qwen = model("Qwen3 8B", "local-qwen3-8b-1")
        val enabled = DeepThinkSupport.plan(qwen, 1024, enabled = true, level = DeepThinkLevel.MEDIUM)
        assertEquals(2560, enabled.maxTokens)
        assertEquals("", enabled.userSuffix)

        val r1 = model("DeepSeek R1 Distill 7B", "local-catalog-deepseek-r1-distill-qwen-7b-1")
        assertEquals(8192, DeepThinkSupport.plan(r1, 1024, enabled = false, level = DeepThinkLevel.AUTO).maxTokens)
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
