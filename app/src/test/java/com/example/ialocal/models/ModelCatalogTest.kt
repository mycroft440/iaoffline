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
    fun catalogIncludesQwen3EightB() {
        val model = ModelCatalog.entries.firstOrNull { it.id.startsWith("qwen3-8b") }
        assertNotNull(model)
        assertEquals("Q4_K_M", model?.quantization)
    }
}
