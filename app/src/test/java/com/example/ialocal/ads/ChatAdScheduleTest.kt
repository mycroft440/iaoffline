package com.example.ialocal.ads

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatAdScheduleTest {
    @Test
    fun bannersFollowGapsOfTwoFourAndThree() {
        val scheduled = (1..20).filter { ChatAdSchedule.showsBannerAfter(it) }
        assertEquals(listOf(2, 6, 9, 11, 15, 18, 20), scheduled)
    }

    @Test
    fun onlyFirstAssistantReplyAfterScheduledUserMessageIsMarked() {
        val messages = listOf(
            "u1" to true, "a1" to false,
            "u2" to true, "a2" to false, "a2b" to false,
            "u3" to true, "a3" to false,
        )
        val ids = ChatAdSchedule.assistantIdsWithBanner(messages, { it.second }, { it.first })
        assertEquals(setOf("a2"), ids)
    }

    @Test
    fun bannerGoesBeforeFirstCodeBlock() {
        val answer = "Claro, aqui está o HTML:\n\n```html\n<p>oi</p>\n```"
        assertEquals(
            "Claro, aqui está o HTML:" to "```html\n<p>oi</p>\n```",
            ChatAdSchedule.splitForBanner(answer),
        )
    }

    @Test
    fun bannerGoesAfterFirstParagraphWithoutCode() {
        assertEquals("Oi." to "Em que posso ajudar?", ChatAdSchedule.splitForBanner("Oi.\n\nEm que posso ajudar?"))
        assertEquals("Oi." to "", ChatAdSchedule.splitForBanner("Oi."))
    }
}
