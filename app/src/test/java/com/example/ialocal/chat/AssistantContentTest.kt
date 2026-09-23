package com.example.ialocal.chat

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
}
