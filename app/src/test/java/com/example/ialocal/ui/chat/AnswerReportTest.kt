package com.example.ialocal.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnswerReportTest {
    @Test
    fun bodyCarriesTheReasonTheModelTheVersionAndTheAnswer() {
        val body = AnswerReport.body(ReportReason.DANGEROUS, " Gemma 4 E2B ", "  Resposta ruim.  ", " explica algo perigoso ", "0.1.600")

        assertTrue(body.contains("Motivo: ${ReportReason.DANGEROUS.label}"))
        assertTrue(body.contains("Modelo: Gemma 4 E2B\n"))
        assertTrue(body.contains("Versão do app: 0.1.600"))
        assertTrue(body.contains("Detalhes: explica algo perigoso"))
        assertTrue(body.endsWith("Resposta denunciada:\nResposta ruim."))
    }

    @Test
    fun blankDetailsAndBlankModelAreNotLeftAsEmptyLines() {
        val body = AnswerReport.body(ReportReason.OTHER, "  ", "Texto", "   ", "1.0")

        assertFalse(body.contains("Detalhes:"))
        assertTrue(body.contains("Modelo: não informado"))
    }

    @Test
    fun aLongAnswerIsCutSoTheEmailIntentStaysSmall() {
        val answer = "a".repeat(AnswerReport.MAX_ANSWER_CHARS + 250)
        val body = AnswerReport.body(ReportReason.OFFENSIVE, "IA", answer, "d".repeat(900), "1.0")

        assertTrue(body.contains("a".repeat(AnswerReport.MAX_ANSWER_CHARS) + "\n[resposta cortada"))
        assertFalse(body.contains("a".repeat(AnswerReport.MAX_ANSWER_CHARS + 1)))
        assertTrue(body.contains("Detalhes: " + "d".repeat(AnswerReport.MAX_DETAILS_CHARS) + "\n"))
        assertEquals(5, ReportReason.entries.size)
    }
}
