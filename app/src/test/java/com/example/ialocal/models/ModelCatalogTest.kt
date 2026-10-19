package com.example.ialocal.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCatalogTest {
    @Test
    fun entriesUsePinnedHttpsGgufsWithUniqueIds() {
        val entries = ModelCatalog.entries
        assertTrue(entries.isNotEmpty())
        assertEquals(entries.size, entries.map { it.id }.toSet().size)

        entries.forEach { model ->
            assertTrue(model.downloadUrl.startsWith("https://huggingface.co/"))
            assertTrue(model.fileName.endsWith(".gguf", ignoreCase = true))
            assertTrue(model.sha256.matches(Regex("^[0-9a-f]{64}$")))
            assertTrue(model.approximateSizeBytes > 0L)
            assertTrue(model.recommendedRamBytes > 0L)
            assertTrue("${model.displayName} excede 50B", model.totalParametersBillions <= 50.0)
            assertTrue(model.totalParametersBillions > 0.0)
            model.activeParametersBillions?.let { active ->
                assertTrue(active > 0.0)
                assertTrue(active <= model.totalParametersBillions)
            }
        }
    }

    @Test
    fun catalogUsesQwen38AndDoesNotReintroduceLegacyQwen3GeneralModels() {
        val qwen38 = ModelCatalog.entries.firstOrNull { it.id.startsWith("qwen3.8-27b") }
        val coder = ModelCatalog.entries.firstOrNull { it.id.startsWith("qwen3-coder-30b") }

        assertNotNull(qwen38)
        assertNotNull(coder)
        assertEquals(ModelProvider.ALIBABA, qwen38?.provider)
        assertEquals(ModelProvider.ALIBABA, coder?.provider)
        assertFalse(
            ModelCatalog.entries.any { model ->
                model.id.matches(Regex("qwen3-(0\\.6b|1\\.7b|4b|8b).*"))
            }
        )
    }

    @Test
    fun catalogContainsTheRequestedRecentFamiliesUnder50B() {
        assertTrue(ModelCatalog.entries.size >= 22)
        assertTrue(ModelCatalog.entries.any { it.displayName.startsWith("Gemma 4") })
        assertTrue(ModelCatalog.entries.any { it.displayName.startsWith("Llama 3.2") })
        assertTrue(ModelCatalog.entries.any { it.displayName.startsWith("Ministral 3") })
        assertTrue(ModelCatalog.entries.any { it.displayName.startsWith("DeepSeek R1") })
        assertTrue(ModelCatalog.entries.any { it.displayName.startsWith("Phi-4") })
    }

    @Test
    fun providerSectionsHaveInstallableModels() {
        assertEquals("Modelos do Google", ModelProvider.GOOGLE.sectionLabel)
        assertEquals("Modelos da Alibaba / Qwen", ModelProvider.ALIBABA.sectionLabel)
        assertEquals("Modelos da Meta", ModelProvider.META.sectionLabel)
        assertEquals("Modelos da Mistral", ModelProvider.MISTRAL.sectionLabel)
        assertEquals("Modelos da DeepSeek", ModelProvider.DEEPSEEK.sectionLabel)
        assertEquals("Modelos da Microsoft", ModelProvider.MICROSOFT.sectionLabel)

        ModelProvider.values().forEach { provider ->
            assertTrue("Sem modelos para $provider", ModelCatalog.entries.any { it.provider == provider })
        }
    }

    @Test
    fun bonsaiModelsAreOfferedInTheirLowBitFormats() {
        val bonsai = ModelCatalog.entries.filter { it.provider == ModelProvider.PRISMML }
        assertEquals(
            setOf(
                "bonsai-1.7b-q1-0", "bonsai-4b-q1-0", "bonsai-8b-q1-0", "bonsai-ternary-8b-q2-0", "bonsai-27b-q1-0",
                "bonsai2-ternary-2b-q2-0", "bonsai2-ternary-4b-q2-0", "bonsai2-ternary-9b-q2-0",
                "bonsai2-ternary-27b-q2-0",
            ),
            bonsai.map { it.id }.toSet(),
        )
        bonsai.forEach { model ->
            assertTrue(model.repository.startsWith("prism-ml/"))
            assertTrue(model.quantization == "Q1_0" || model.quantization == "Q2_0")
            // PrismML-only packings (PQ2_0, PTQ1_0) are refused by upstream llama.cpp.
            assertFalse(model.fileName.contains("PQ2_0") || model.fileName.contains("PTQ1_0"))
        }
        // The first ternary generation is only upstream-compatible in its 64-weight groups.
        assertEquals("Ternary-Bonsai-8B-Q2_0_g64.gguf", ModelCatalog.requireById("bonsai-ternary-8b-q2-0").fileName)
        // A 27B that fits a phone: dense, but under 4 GB.
        val bonsai27 = ModelCatalog.requireById("bonsai-27b-q1-0")
        assertFalse(bonsai27.isExperimental)
        assertTrue(bonsai27.approximateSizeBytes < 4_000_000_000L)
        // The small Bonsai 2 models want a presence penalty; the 27B and everything else do not.
        val penalized = setOf("bonsai2-ternary-2b-q2-0", "bonsai2-ternary-4b-q2-0", "bonsai2-ternary-9b-q2-0")
        assertTrue(ModelCatalog.entries.filter { it.id in penalized }.all { it.presencePenalty == 1.0f })
        assertTrue(ModelCatalog.entries.filterNot { it.id in penalized }.all { it.presencePenalty == 0f })
        // A dense 27B that fits a 12 GB phone, unlike the experimental MoE models.
        assertFalse(ModelCatalog.requireById("bonsai2-ternary-27b-q2-0").isExperimental)
    }

    @Test
    fun newPhoneSizedModelsArePresent() {
        listOf("granite4.2-3b-q4-k-m", "granite4.2-8b-q3-k-m", "minicpm5-2b-q4-k-m", "mellum2.1-12b-a2.5b-thinking-q3-k-m")
            .forEach { id -> assertNotNull(id, ModelCatalog.entries.firstOrNull { it.id == id }) }
        // 12B in total but only 2.5B active: it fits in RAM, so it is not one of the slow experimental MoE.
        assertFalse(ModelCatalog.requireById("mellum2.1-12b-a2.5b-thinking-q3-k-m").isExperimental)
    }

    @Test
    fun strongestNewModelsKeepQualityFormats() {
        // Google's quantization-aware checkpoints, in the fast ARM Q4_0 format.
        listOf("gemma4-e2b-it-qat-q4-0", "gemma4-e4b-it-qat-q4-0", "gemma4-12b-it-qat-q4-0", "gemma4-26b-a4b-it-qat-q4-0").forEach { id ->
            val model = ModelCatalog.requireById(id)
            assertTrue(model.repository.startsWith("google/"))
            assertTrue(model.quantization.startsWith("Q4_0"))
        }
        assertEquals("IQ4_XS", ModelCatalog.requireById("ornith1.5-9b-iq4-xs").quantization)
        // 24B in total but 2.45B active: too big for RAM, so it runs from storage.
        assertTrue(ModelCatalog.requireById("lfm2.5-24b-a2b-qad-q4-0").isExperimental)
    }

    @Test
    fun onlyLargeMoeModelsAreExperimental() {
        val experimental = ModelCatalog.entries.filter { it.isExperimental }.map { it.id }.toSet()
        assertTrue("gemma4-26b-a4b-it-q4-0" in experimental)
        assertTrue("qwen3-coder-30b-a3b-instruct-q4-k-m" in experimental)
        assertTrue("qwen3.6-35b-a3b-q3-k-m" in experimental)
        assertTrue("nemotron3-nano-30b-a3b-q3-k-m" in experimental)
        assertTrue("gpt-oss-20b-q3-k-m" in experimental)
        listOf("ling-mini-2.0-q3-k-m", "gemma4-26b-a4b-it-qat-q4-0", "glm4.7-flash-ud-q3-k-xl", "north-mini-code-1.0-ud-q3-k-m", "laguna-xs-2.1-q3-k-m")
            .forEach { assertTrue(it, it in experimental) }
        assertFalse("smallthinker-4b-a0.6b-q4-k-m" in experimental)
        // Small MoE fit in RAM and dense large models cannot run from storage at a usable speed.
        assertFalse("granite4.0-h-tiny-q3-k-m" in experimental)
        assertFalse("lfm2.5-8b-a1b-q4-k-m" in experimental)
        assertFalse("qwen3.8-27b-q4-k-m" in experimental)
        assertFalse("deepseek-r1-distill-qwen-32b-q4-k-m" in experimental)
    }
}
