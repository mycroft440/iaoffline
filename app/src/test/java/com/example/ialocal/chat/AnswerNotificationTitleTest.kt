package com.example.ialocal.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class AnswerNotificationTitleTest {
    @Test
    fun showsTheModelWhatItIsDoingAndTheLoadingDots() {
        val thinking = AnswerNotice("DeepSeek R1 Distill", AnswerPhase.THINKING)
        assertEquals("DeepSeek R1 Distill - Pensando .", answeringTitle(thinking, 1))
        assertEquals("DeepSeek R1 Distill - Pensando . .", answeringTitle(thinking, 2))
        assertEquals("DeepSeek R1 Distill - Escrevendo . . .", answeringTitle(thinking.copy(phase = AnswerPhase.WRITING), 3))
    }

    @Test
    fun beforeTheModelIsKnownItSaysIAOffline() {
        assertEquals("I.A offline - Pensando . . .", answeringTitle(null, 3))
        assertEquals("I.A offline - Pensando . . .", answeringTitle(AnswerNotice(" ", AnswerPhase.THINKING), 7))
    }

    @Test
    fun theLastStepIsRespondido() {
        assertEquals("Lfm2.5 8B - Respondido", answeredTitle("Lfm2.5 8B"))
        assertEquals("I.A offline - Respondido", answeredTitle(null))
    }
}
