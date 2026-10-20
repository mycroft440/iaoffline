package com.example.ialocal.ui.chat

import com.example.ialocal.models.ModelCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ChatModelLabelTest {
    @Test
    fun bonsaiVersionAndBillionsWithoutQuantizationOrTernary() {
        assertEquals("Bonsai 2 27B", chatModelLabel("Bonsai 2 Ternário 27B"))
        assertEquals("Bonsai 2 4B", chatModelLabel("Bonsai 2 Ternário 4B"))
    }

    @Test
    fun mistralRetainsVersionAndParameterSizeOnly() {
        assertEquals("Ministral 3 3B", chatModelLabel("Ministral 3 3B Reasoning"))
        assertEquals("Mistral 3 4B", chatModelLabel("Mistral 3 4B Q4_K_M.gguf"))
    }

    @Test
    fun importedGgufNamesLoseWeightAndFileSuffix() {
        assertEquals("Qwen3.5 9B", chatModelLabel("Qwen3.5-9B-Instruct-Q4_K_M.gguf"))
        assertEquals("LFM2.5 8B", chatModelLabel("LFM2.5-8B-A1B-Q4_K_M.gguf"))
        assertEquals("Granite 3.1 3B", chatModelLabel("Granite 3.1 3B-A800M GGUF"))
        assertFalse(chatModelLabel("Bonsai 2 Ternário 27B").contains("Q2"))
    }

    @Test
    fun catalogWithoutSizeInNameGetsTotalBillions() {
        assertEquals("Phi mini 3.8B", chatModelLabel("Phi mini", 3.8))
    }

    @Test
    fun catalogNamesKeepTheirDeclaredSize() {
        val bonsai = ModelCatalog.entries.first { it.id == "bonsai2-ternary-27b-q2-0" }
        assertEquals("Bonsai 2 27B", chatModelLabel(bonsai.displayName, bonsai.totalParametersBillions))
    }
}
