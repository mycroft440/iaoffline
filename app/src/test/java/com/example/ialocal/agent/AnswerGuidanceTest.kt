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
        val note = AnswerGuidance.dateNote(now)
        assertTrue(note, note.contains("quinta-feira, 15 de outubro de 2026"))
        assertTrue(AnswerGuidance.RULES.contains("calculate"))
        assertTrue(AnswerGuidance.RULES.contains("em vez de inventar"))
    }

    @Test
    fun systemPartOnlyChangesFromOneDayToTheNext() {
        // Minutes do not appear, so later messages of a conversation keep the same system prompt.
        assertFalse(AnswerGuidance.dateNote(now).contains("22:40"))
        assertEquals(AnswerGuidance.dateNote(now), AnswerGuidance.dateNote(now.plusHours(1)))
        // The rules have no date, so the runtime can keep them processed from one day to the next.
        assertFalse(AnswerGuidance.RULES.contains("2026"))
    }

    @Test
    fun withoutWikipediaNothingIsAddedToTheMessage() = runBlocking {
        val guidance = AnswerGuidance(wikipedia = null, clock = { now })
        assertEquals(AnswerGuidance.dateNote(now), guidance.dateNote())
        assertEquals("", guidance.turnSuffix(listOf(AiChatMessage("user", "Quem foi Tiradentes?"))))
    }
}
