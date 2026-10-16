package com.example.ialocal.agent

import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnswerGuidanceTest {
    private val now = ZonedDateTime.of(2026, 10, 15, 22, 40, 0, 0, ZoneId.of("America/Sao_Paulo"))

    @Test
    fun tellsTheModelTheCurrentDateInPortuguese() {
        val suffix = AnswerGuidance.baseSuffix(now)
        assertTrue(suffix, suffix.contains("quinta-feira, 15 de outubro de 2026, 22:40"))
        assertTrue(suffix.contains("calculate"))
        assertTrue(suffix.contains("em vez de inventar"))
    }

    @Test
    fun withoutWikipediaOnlyTheBaseGuidanceIsAdded() = runBlocking {
        val guidance = AnswerGuidance(wikipedia = null, clock = { now })
        assertEquals(
            AnswerGuidance.baseSuffix(now),
            guidance.systemSuffix(listOf(com.example.ialocal.ai.AiChatMessage("user", "Quem foi Tiradentes?"))),
        )
    }
}
