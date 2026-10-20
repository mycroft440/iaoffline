package com.example.ialocal.ui.chat

import com.example.ialocal.runtime.GenerationStage
import com.example.ialocal.runtime.GenerationStage.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationStageTextTest {
    @Test
    fun loadingNamesTheModelAndItsSize() {
        val stage = GenerationStage(Kind.LOADING_MODEL, "Gemma 4 E4B", total = 3_870)
        assertTrue(stageText(stage, finished = false).startsWith("Carregando Gemma 4 E4B na memória ("))
        assertTrue(stageText(stage, finished = false).endsWith(" GB)"))
        assertEquals("Gemma 4 E4B carregada na memória", stageText(stage, finished = true))
        assertEquals("Carregando Gemma 4 E4B na memória (512 MB)", stageText(stage.copy(total = 512), finished = false))
    }

    @Test
    fun readingTheConversationCountsItsMessages() {
        val stage = GenerationStage(Kind.READING_HISTORY, done = 2, total = 5)
        assertEquals("Lendo a conversa: mensagem 2 de 5", stageText(stage, finished = false))
        assertEquals("Conversa lida (5 mensagens)", stageText(stage, finished = true))
        assertEquals("Conversa lida (1 mensagem)", stageText(stage.copy(done = 1, total = 1), finished = true))
    }

    @Test
    fun waitingSaysWhatTheAiIsBusyWith() {
        assertEquals(
            "Terminando a preparação feita enquanto você escrevia",
            stageText(GenerationStage(Kind.WAITING_FOR_AI, GenerationStage.BUSY_PREFILL), finished = false),
        )
        assertEquals(
            "Aguardando a IA terminar a resposta de outra conversa",
            stageText(GenerationStage(Kind.WAITING_FOR_AI, GenerationStage.BUSY_ANSWER), finished = false),
        )
        assertEquals(
            "Aguardando a IA salvar a conversa anterior",
            stageText(GenerationStage(Kind.WAITING_FOR_AI, GenerationStage.BUSY_SAVE), finished = false),
        )
    }

    @Test
    fun aConversationBackFromStorageSaysSo() {
        val stage = GenerationStage(Kind.CONTEXT_REUSED, GenerationStage.REUSED_STORAGE)
        assertEquals("Restaurando a conversa salva no armazenamento", stageText(stage, finished = false))
        assertEquals("Conversa restaurada do armazenamento, sem lê-la de novo", stageText(stage, finished = true))
        assertEquals(
            "Conversa já está na memória da IA",
            stageText(GenerationStage(Kind.CONTEXT_REUSED, GenerationStage.REUSED_SESSION), finished = true),
        )
    }

    @Test
    fun toolsAndTheirResultsHaveReadableNames() {
        assertEquals("Usando a ferramenta: calculadora", stageText(GenerationStage(Kind.USING_TOOL, "calculate"), false))
        assertEquals(
            "Lendo o resultado da ferramenta",
            stageText(GenerationStage(Kind.READING_MESSAGE, GenerationStage.MESSAGE_TOOL_RESULT), false),
        )
        assertEquals("Lendo a sua mensagem", stageText(GenerationStage(Kind.READING_MESSAGE), false))
    }

    @Test
    fun everyStageHasText() {
        Kind.entries.forEach { kind ->
            listOf(false, true).forEach { finished ->
                assertTrue(stageText(GenerationStage(kind, total = 1, done = 1), finished).isNotBlank())
            }
        }
    }
}
