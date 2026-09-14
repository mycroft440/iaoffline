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
}
