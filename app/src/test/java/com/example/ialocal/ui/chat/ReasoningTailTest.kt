package com.example.ialocal.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ReasoningTailTest {
    @Test
    fun keepsTheLastTwoLinesOfTheReasoning() {
        val reasoning = "O usuário pediu um site de petshop.\n\n1. Código completo\n2. Estrutura semântica\n"
        assertEquals("1. Código completo\n2. Estrutura semântica", reasoningTail(reasoning))
    }

    @Test
    fun shortReasoningIsShownWhole() {
        assertEquals("Pensando", reasoningTail("Pensando"))
        assertEquals("", reasoningTail("  \n "))
    }

    @Test
    fun aVeryLongLineIsCutFromTheStart() {
        val tail = reasoningTail("início " + "x".repeat(1_000) + " fim", maxChars = 50)
        assertEquals(50, tail.length)
        assertEquals(true, tail.endsWith(" fim"))
    }
}
