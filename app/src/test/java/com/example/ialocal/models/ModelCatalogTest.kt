package com.example.ialocal.models

import org.junit.Assert.assertEquals
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
        }
    }

    @Test
    fun catalogIncludesRequestedQwenFamilies() {
        val qwen3EightB = ModelCatalog.entries.firstOrNull { it.id.startsWith("qwen3-8b") }
        val qwen38 = ModelCatalog.entries.firstOrNull { it.id.startsWith("qwen3.8-27b") }

        assertNotNull(qwen3EightB)
        assertNotNull(qwen38)
        assertEquals("Q4_K_M", qwen3EightB?.quantization)
        assertEquals("Q4_K_M", qwen38?.quantization)
    }
}
