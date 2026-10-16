package com.example.ialocal.agent

import com.example.ialocal.ai.AiChatMessage
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnswerGuidanceTest {
    private val now = ZonedDateTime.of(2026, 10, 15, 22, 40, 0, 0, ZoneId.of("America/Sao_Paulo"))

    @Test
    fun tellsTheModelTodaysDateInPortuguese() {
        val suffix = AnswerGuidance.baseSuffix(now)
        assertTrue(suffix, suffix.contains("quinta-feira, 15 de outubro de 2026"))
        assertTrue(suffix.contains("calculate"))
        assertTrue(suffix.contains("em vez de inventar"))
    }

    @Test
    fun systemPartOnlyChangesFromOneDayToTheNext() {
        // Minutes do not appear, so later messages of a conversation keep the same system prompt.
        assertFalse(AnswerGuidance.baseSuffix(now).contains("22:40"))
        assertEquals(AnswerGuidance.baseSuffix(now), AnswerGuidance.baseSuffix(now.plusHours(1)))
    }

    @Test
    fun withoutWikipediaNothingIsAddedToTheMessage() = runBlocking {
        val guidance = AnswerGuidance(wikipedia = null, clock = { now })
        assertEquals(AnswerGuidance.baseSuffix(now), guidance.systemSuffix())
        assertEquals("", guidance.turnSuffix(listOf(AiChatMessage("user", "Quem foi Tiradentes?"))))
    }
}
