package com.example.ialocal.chat

import com.example.ialocal.models.ModelProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantContentTest {
    @Test
    fun parsesReasoningAnswerAndTimings() {
        val stored = AssistantContent.withTimings("<think>raciocínio</think>\n\nResposta", responseMs = 5_000, reasoningMs = 3_000)
        val parsed = AssistantContent.parse(stored)
        assertEquals("raciocínio", parsed.reasoning)
        assertEquals("Resposta", parsed.answer)
        assertFalse(parsed.reasoningInProgress)
        assertEquals(3_000L, parsed.reasoningMs)
        assertEquals(5_000L, parsed.responseMs)
    }

    @Test
    fun openBlockIsReasoningInProgress() {
        val parsed = AssistantContent.parse("<think>ainda pensando")
        assertEquals("ainda pensando", parsed.reasoning)
        assertEquals("", parsed.answer)
        assertTrue(parsed.reasoningInProgress)
    }

    @Test
    fun closingTagOnlyMeansReasoningBeforeIt() {
        val parsed = AssistantContent.parse("pensei</think>Oi")
        assertEquals("pensei", parsed.reasoning)
        assertEquals("Oi", parsed.answer)
    }

    @Test
    fun legacyReasoningMetadataStillParses() {
        val parsed = AssistantContent.parse("Resposta\n\n<!--nexus_reasoning_ms:1200-->")
        assertNull(parsed.reasoning)
        assertEquals("Resposta", parsed.answer)
        assertEquals(1_200L, parsed.reasoningMs)
        assertNull(parsed.responseMs)
    }

    @Test
    fun timingsAreReplacedNotDuplicated() {
        val once = AssistantContent.withTimings("Oi", responseMs = 1, reasoningMs = null)
        val twice = AssistantContent.withTimings(once, responseMs = 2, reasoningMs = null)
        assertEquals(2L, AssistantContent.parse(twice).responseMs)
        assertEquals("Oi", AssistantContent.answerForHistory(twice))
    }

    @Test
    fun gemmaThoughtChannelIsReasoning() {
        val parsed = AssistantContent.parse("<|channel>thought\nPensando<channel|>Brasília.")
        assertEquals("Pensando", parsed.reasoning)
        assertEquals("Brasília.", parsed.answer)
        assertFalse(parsed.reasoningInProgress)
    }

    @Test
    fun recordsTheCompanyOfTheModelThatAnswered() {
        val stored = AssistantContent.withTimings("Oi", responseMs = 1, reasoningMs = null, provider = ModelProvider.DEEPSEEK)
        val parsed = AssistantContent.parse(stored)
        assertEquals(ModelProvider.DEEPSEEK, parsed.provider)
        assertEquals("Oi", parsed.answer)
        assertEquals("Oi", AssistantContent.answerForHistory(stored))
        // A regenerated reply records the new model, not both.
        val regenerated = AssistantContent.withTimings(stored, responseMs = 2, reasoningMs = null, provider = ModelProvider.MISTRAL)
        assertEquals(ModelProvider.MISTRAL, AssistantContent.parse(regenerated).provider)
        assertEquals(1, Regex("nexus_provider").findAll(regenerated).count())
    }

    @Test
    fun recordsHowFastTheModelWroteWithoutShowingItInTheAnswer() {
        val stored = AssistantContent.withTimings(
            "<think>pensando</think>\n\nResposta",
            responseMs = 12_000,
            reasoningMs = 4_000,
            provider = ModelProvider.GOOGLE,
            tokensPerSecond = 2.6431,
        )

        val parsed = AssistantContent.parse(stored)

        assertEquals(2.64, requireNotNull(parsed.tokensPerSecond), 0.0001)
        assertEquals("Resposta", parsed.answer)
        assertEquals("pensando", parsed.reasoning)
        assertEquals(ModelProvider.GOOGLE, parsed.provider)
        assertEquals("Resposta", AssistantContent.answerForHistory(stored))
        // Regenerated without a measured speed: the old one is dropped, not kept or duplicated.
        val regenerated = AssistantContent.withTimings(stored, responseMs = 1, reasoningMs = null)
        assertNull(AssistantContent.parse(regenerated).tokensPerSecond)
        assertFalse(regenerated.contains("nexus_tokens_per_second"))
        assertNull(AssistantContent.parse(AssistantContent.withTimings("Oi", 1, null, tokensPerSecond = 0.0)).tokensPerSecond)
    }

    @Test
    fun savesActualAnsweringModelNameWithoutLeakingMetadataIntoTheAnswer() {
        val raw = "<think>analisando</think>Resposta"
        val saved = AssistantContent.withTimings(
            raw,
            responseMs = 123,
            reasoningMs = 99,
            modelName = "Bonsai 2 Ternário 27B",
        )
        assertEquals("Bonsai 2 Ternário 27B", AssistantContent.parse(saved).modelName)
        assertEquals("Resposta", AssistantContent.answerForHistory(saved))
        assertFalse(AssistantContent.answerForHistory(saved).contains("nexus_model_name"))
    }

    @Test
    fun regeneratingWithAnotherModelReplacesOldName() {
        val first = AssistantContent.withTimings("Oi", 12, null, modelName = "Mistral 3 4B")
        val second = AssistantContent.withTimings(first, 15, null, modelName = "Bonsai 2 27B")
        assertEquals("Bonsai 2 27B", AssistantContent.parse(second).modelName)
        assertEquals(1, Regex("nexus_model_name").findAll(second).count())
        assertNull(AssistantContent.parse("Texto antigo sem identificação").modelName)
    }

    @Test
    fun repliesSavedBeforeHaveNoCompany() {
        assertNull(AssistantContent.parse("Resposta\n\n<!--nexus_response_ms:10-->").provider)
        assertNull(AssistantContent.parse("Resposta<!--nexus_provider:DESCONHECIDA-->").provider)
    }
}
