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
    fun qwen35FamilyCanSkipReasoningByPrefillingAnEmptyThinkBlock() {
        listOf(
            model("Qwen3.5 4B", "local-catalog-qwen3.5-4b-q4-k-m-1"),
            model("Qwen3.8 27B", "local-catalog-qwen3.8-27b-q4-k-m-1"),
            model("Qwen3.6 35B-A3B", "local-catalog-qwen3.6-35b-a3b-q3-k-m-1"),
            model("Ornith 1.5 9B", "local-catalog-ornith1.5-9b-iq4-xs-1", "qwen35"),
            model("meu-finetune", "local-meu-finetune-1", "qwen35moe"),
            model("MiniCPM5 2B", "local-catalog-minicpm5-2b-q4-k-m-1", "llama"),
        ).forEach { model ->
            val capability = DeepThinkSupport.capability(model)
            assertEquals(model.name, DeepThinkControlMode.HYBRID_THINKING, capability.mode)
            // Their own templates reason by default, so DeepThink starts on.
            assertTrue(model.name, capability.defaultEnabled)
            assertEquals("<think>\n", DeepThinkSupport.plan(model, 8192, true, DeepThinkLevel.HIGH).assistantPrefix)
            assertEquals("<think>\n\n</think>\n\n", DeepThinkSupport.plan(model, 8192, false, DeepThinkLevel.HIGH).assistantPrefix)
        }
    }

    @Test
    fun gemma4AndGranite42SwitchReasoningTheirOwnWay() {
        val gemma = model("Gemma 4 E4B QAT", "local-catalog-gemma4-e4b-it-qat-q4-0-1", "gemma4")
        assertEquals(DeepThinkControlMode.HYBRID_THINKING, DeepThinkSupport.capability(gemma).mode)
        assertTrue(DeepThinkSupport.capability(gemma).defaultEnabled)
        assertFalse(DeepThinkSupport.plan(gemma, 8192, false, DeepThinkLevel.HIGH).templateThinking)
        assertTrue(DeepThinkSupport.plan(gemma, 8192, true, DeepThinkLevel.HIGH).templateThinking)
        assertEquals("", DeepThinkSupport.plan(gemma, 8192, false, DeepThinkLevel.HIGH).assistantPrefix)

        val granite = model("Granite 4.2 8B", "local-catalog-granite4.2-8b-q3-k-m-1", "granite")
        assertEquals("<think></think>", DeepThinkSupport.plan(granite, 8192, false, DeepThinkLevel.HIGH).assistantPrefix)
        assertEquals("<think>\n", DeepThinkSupport.plan(granite, 8192, true, DeepThinkLevel.HIGH).assistantPrefix)
    }

    @Test
    fun qwen3HybridStillStartsWithReasoningOff() {
        assertFalse(DeepThinkSupport.capability(model("Qwen3 8B", "local-qwen3-8b-1")).defaultEnabled)
    }

    @Test
    fun newerQwenAndReasoningVariantsAlwaysReason() {
        listOf(
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
            DeepThinkControlMode.HYBRID_THINKING,
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
        assertEquals(
            DeepThinkControlMode.REASONING_MODEL,
            DeepThinkSupport.capability(model("gpt-oss 20B", "local-catalog-gpt-oss-20b-q3-k-m-1")).mode,
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

    @Test
    fun bonsaiGenerationsAreClassified() {
        // The first Bonsai generation keeps the qwen3 architecture but never thinks, so it must not
        // be treated as a Qwen3 hybrid that understands /no_think.
        listOf(
            "Bonsai 8B" to "local-catalog-bonsai-8b-q1-0-1a2b3c4d",
            "Bonsai 8B Ternário" to "local-catalog-bonsai-ternary-8b-q2-0-27b00000",
            "Bonsai 1.7B" to "local-catalog-bonsai-1.7b-q1-0-1",
        ).forEach { (name, id) ->
            assertEquals(name, DeepThinkControlMode.NONE, DeepThinkSupport.capability(model(name, id, "qwen3")).mode)
        }
        listOf(
            "Bonsai 27B" to "local-catalog-bonsai-27b-q1-0-1",
            "Bonsai 2 Ternário 9B" to "local-catalog-bonsai2-ternary-9b-q2-0-1",
            "Bonsai 2 Ternário 27B" to "local-catalog-bonsai2-ternary-27b-q2-0-1",
            "Ternary-Bonsai-2-4B" to "local-ternary-bonsai-2-4b-1",
        ).forEach { (name, id) ->
            val capability = DeepThinkSupport.capability(model(name, id, "qwen35"))
            assertEquals(name, DeepThinkControlMode.HYBRID_THINKING, capability.mode)
            assertTrue(name, capability.defaultEnabled)
        }
    }

    @Test
    fun granite42IsSwitchableAndMellumAlwaysReasons() {
        assertEquals(
            DeepThinkControlMode.HYBRID_THINKING,
            DeepThinkSupport.capability(model("Granite 4.2 8B", "local-catalog-granite4.2-8b-q3-k-m-1")).mode,
        )
        assertEquals(
            DeepThinkControlMode.NONE,
            DeepThinkSupport.capability(model("Granite 4.1 8B", "local-catalog-granite4.1-8b-q4-k-m-1")).mode,
        )
        assertEquals(
            DeepThinkControlMode.REASONING_MODEL,
            DeepThinkSupport.capability(model("Mellum 2.1 12B-A2.5B", "local-catalog-mellum2.1-12b-a2.5b-thinking-q3-k-m-1")).mode,
        )
    }

    @Test
    fun addedInstructMoeModelsHaveNoReasoningMode() {
        listOf(
            model("SmallThinker 4B-A0.6B", "local-catalog-smallthinker-4b-a0.6b-q4-k-m-1", "smallthinker"),
            model("Ling mini 2.0 16B-A1.4B", "local-catalog-ling-mini-2.0-q3-k-m-1", "bailingmoe2"),
        ).forEach { assertEquals(DeepThinkControlMode.NONE, DeepThinkSupport.capability(it).mode) }
        // Laguna's template ends the prompt with <think> (or </think> when reasoning is off).
        val laguna = model("Laguna XS 2.1 33B-A3B", "local-catalog-laguna-xs-2.1-q3-k-m-1", "laguna")
        assertEquals(DeepThinkControlMode.HYBRID_THINKING, DeepThinkSupport.capability(laguna).mode)
        assertFalse(DeepThinkSupport.plan(laguna, 8192, false, DeepThinkLevel.HIGH).templateThinking)
        // Ornith 1.5 35B-A3B is Qwen3.5 underneath, with the same prefilled switch.
        assertEquals(
            "<think>\n\n</think>\n\n",
            DeepThinkSupport.plan(
                model("Ornith 1.5 35B-A3B", "local-catalog-ornith1.5-35b-a3b-q3-k-m-1", "qwen35moe"),
                8192, false, DeepThinkLevel.HIGH,
            ).assistantPrefix,
        )
        // Gemma 4's QAT MoE keeps the family's reasoning switch.
        assertEquals(
            DeepThinkControlMode.HYBRID_THINKING,
            DeepThinkSupport.capability(model("Gemma 4 26B-A4B QAT", "local-catalog-gemma4-26b-a4b-it-qat-q4-0-1", "gemma4")).mode,
        )
    }

    private fun model(name: String, apiModelId: String, architecture: String? = null) = AiModelEntity(
        id = "model-id",
        name = name,
        apiModelId = apiModelId,
        format = "GGUF",
        architecture = architecture,
        filePath = "/tmp/model.gguf",
        sizeBytes = 1,
        importedAt = 1,
    )
}
