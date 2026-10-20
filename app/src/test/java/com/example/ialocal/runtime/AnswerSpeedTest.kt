package com.example.ialocal.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class AnswerSpeedTest {
    @Test
    fun readsTheNativeTimings() {
        assertEquals(AnswerSpeed(25, 3907, 40, 15541), AnswerSpeed.parse("25 3907 40 15541"))
        assertEquals(AnswerSpeed(25, 3907, 40, 15541), AnswerSpeed.parse("25 3907 40 15541 0 0"))
        assertEquals(AnswerSpeed(25, 3907, 40, 15541, 1920, 1402), AnswerSpeed.parse("25 3907 40 15541 1920 1402"))
        assertNull(AnswerSpeed.parse(null))
        assertNull(AnswerSpeed.parse("25 3907 40"))
        assertNull(AnswerSpeed.parse("25 3907 40 15541 1920"))
        assertNull(AnswerSpeed.parse("25 3907 x 15541"))
    }

    @Test
    fun readsTheProcessCounters() {
        val io = "rchar: 123\nwchar: 45\nsyscr: 6\nsyscw: 7\nread_bytes: 5021868032\nwrite_bytes: 0\ncancelled_write_bytes: 0\n"
        // The process name may hold spaces and parentheses; majflt is the 12th field.
        val stat = "2311 (com.example.ialocal:ai (x)) S 1 2311 2311 0 -1 4194560 91234 0 1748 0 120 30 0 0 20 0 13"

        val counters = ProcessIo.parse(io, stat)

        assertEquals(5_021_868_032L, counters.readBytes)
        assertEquals(1748L, counters.majorFaults)
        assertEquals(ProcessIo(null, null), ProcessIo.parse(null, "garbage"))
    }

    @Test
    fun describesSpeedAndStorageReadsPerToken() {
        val speed = AnswerSpeed(promptTokens = 25, promptMillis = 3907, answerTokens = 40, answerMillis = 15541)

        val line = speed.describe(
            before = ProcessIo(readBytes = 0, majorFaults = 100),
            after = ProcessIo(readBytes = 4792L * 1024 * 1024, majorFaults = 1848),
        )

        assertEquals(
            "mensagem: 25 tokens em 3,9 s (6,40 tokens/s); resposta: 40 tokens em 15,5 s (2,57 tokens/s); " +
                "lidos do armazenamento: 4792 MB (119,8 MB por token); 1748 esperas por leitura do armazenamento",
            line,
        )
    }

    @Test
    fun describesHowManyExpertsWereGuessedRight() {
        val speed = AnswerSpeed(10, 1000, 40, 20000, guessedExperts = 1920, guessedRight = 1402)

        val line = speed.describe(ProcessIo(null, null), ProcessIo(null, null))

        assertEquals(
            "mensagem: 10 tokens em 1,0 s (10,00 tokens/s); resposta: 40 tokens em 20,0 s (2,00 tokens/s); " +
                "especialistas previstos uma camada antes: 73% certos",
            line,
        )
    }

    @Test
    fun leavesOutCountersTheSystemDoesNotShow() {
        val line = AnswerSpeed(10, 0, 0, 0).describe(ProcessIo(null, null), ProcessIo(null, null))

        assertEquals("mensagem: 10 tokens em 0,0 s (-); resposta: 0 tokens em 0,0 s (-)", line)
        assertFalse(line.contains("armazenamento"))
    }
}
