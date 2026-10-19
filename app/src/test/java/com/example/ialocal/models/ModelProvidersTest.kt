package com.example.ialocal.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelProvidersTest {
    @Test
    fun guessesTheCompanyOfAnImportedModelFromItsName() {
        assertEquals(ModelProvider.DEEPSEEK, providerFromName("DeepSeek-R1-Distill-Qwen-7B-Q4_K_M"))
        assertEquals(ModelProvider.PRISMML, providerFromName("Bonsai-8B qwen3"))
        assertEquals(ModelProvider.ALIBABA, providerFromName("Qwen3-4B-Instruct"))
        assertEquals(ModelProvider.MISTRAL, providerFromName("Ministral-8B"))
        assertEquals(ModelProvider.OPENAI, providerFromName("gpt-oss-20b"))
        assertEquals(ModelProvider.LIQUID, providerFromName("LFM2-1.2B"))
    }

    @Test
    fun recognizesTheAddedMoeCompanies() {
        assertEquals(ModelProvider.INCLUSION_AI, providerFromName("Ling-mini-2.0 bailingmoe2"))
        assertEquals(ModelProvider.POWERINFER, providerFromName("SmallThinker-4BA0.6B-Instruct"))
        // Words that merely contain these names stay unknown.
        assertNull(providerFromName("darling-7b"))
    }

    @Test
    fun unknownNamesHaveNoCompany() {
        assertNull(providerFromName("meu-modelo-q4"))
    }
}
