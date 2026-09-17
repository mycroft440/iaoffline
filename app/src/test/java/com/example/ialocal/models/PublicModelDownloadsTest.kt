package com.example.ialocal.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PublicModelDownloadsTest {
    @Test
    fun catalogFileMatcherRecognizesKnownFilesCaseInsensitively() {
        val model = ModelCatalog.entries.first()

        assertEquals(model, catalogModelForFileName(model.fileName))
        assertEquals(model, catalogModelForFileName(model.fileName.uppercase()))
    }

    @Test
    fun catalogFileMatcherRejectsUnknownOrPartialFiles() {
        val model = ModelCatalog.entries.first()

        assertNull(catalogModelForFileName(null))
        assertNull(catalogModelForFileName(""))
        assertNull(catalogModelForFileName("${model.fileName}.part"))
        assertNull(catalogModelForFileName("outro-modelo.gguf"))
    }
}
