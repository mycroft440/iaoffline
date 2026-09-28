package com.example.ialocal.agent

import com.example.ialocal.ai.AiChatMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class AgentStreamTest {
    private val question = listOf(AiChatMessage("user", "oi"))

    @Test
    fun reasoningIsStreamedBeforeTheAnswer() = runBlocking {
        val emitted = run(expectReasoning = true, rounds = listOf(listOf("Pensando", " bem</think>", "\n\nOlá!")))
        assertEquals(listOf("<think>", "Pensando", " bem", "</think>\n\n", "Olá!"), emitted)
    }

    @Test
    fun plainAnswerHasNoReasoningBlock() = runBlocking {
        assertEquals("Olá, mundo", run(false, listOf(listOf("Olá", ", mundo"))).joinToString(""))
    }

    @Test
    fun toolCallIsHiddenAndReasoningContinuesInOneBlock() = runBlocking {
        val tool = """{"tool":"current_time","arguments":{}}"""
        val seen = mutableListOf<List<AiChatMessage>>()
        val output = run(
            expectReasoning = false,
            rounds = listOf(
                listOf("<think>preciso da hora</think>", tool),
                listOf("<think>agora sei</think>", "São 10h."),
            ),
            toolRound = { answer -> if (answer.trim() == tool) ToolRoundResult("current_time", "10:00") else null },
            seen = seen,
        ).joinToString("")
        assertEquals("<think>preciso da hora\n\nagora sei</think>\n\nSão 10h.", output)
        assertEquals("[RESULTADO DA FERRAMENTA current_time]", seen[1].last().content.lineSequence().first())
    }

    @Test
    fun heldBackJsonThatIsNotAToolIsStillShown() = runBlocking {
        assertEquals("""{"a": 1}""", run(false, listOf(listOf("""{"a": 1}"""))).joinToString(""))
    }

    @Test
    fun toolCallAfterASentenceIsNotShown() = runBlocking {
        val tool = """{"tool":"txt_read","arguments":{"id":"notas.txt"}}"""
        val calls = mutableListOf<String>()
        val output = run(
            expectReasoning = false,
            rounds = listOf(
                listOf("Vou ler o arquivo.\n\n", tool.take(20), tool.drop(20)),
                listOf("A linha 3 fala do Pedro."),
            ),
            toolRound = { answer -> calls += answer; if (answer.trim() == tool) ToolRoundResult("txt_read", "1| a") else null },
        ).joinToString("")
        assertEquals("Vou ler o arquivo.\n\nA linha 3 fala do Pedro.", output)
        assertEquals(tool, calls.first())
    }

    @Test
    fun answerAfterAToolCallIsSeparatedFromTheSentenceBeforeIt() = runBlocking {
        val tool = """{"tool":"current_time","arguments":{}}"""
        val output = run(
            expectReasoning = false,
            rounds = listOf(listOf("Vou ver a hora.\n$tool"), listOf("São 10h.")),
            toolRound = { answer -> if (answer.trim() == tool) ToolRoundResult("current_time", "10:00") else null },
        ).joinToString("")
        assertEquals("Vou ver a hora.\n\nSão 10h.", output)
    }

    @Test
    fun jsonLineThatIsNotAToolIsShownAtTheEnd() = runBlocking {
        val output = run(false, listOf(listOf("Exemplo:\n", "{\"a\": 1}\n", "fim"))).joinToString("")
        assertEquals("Exemplo:\n{\"a\": 1}\nfim", output)
    }

    @Test
    fun codeBlocksStillStreamLive() = runBlocking {
        val emitted = run(false, listOf(listOf("Código:\n", "```kotlin\n", "val a = 1\n", "```")))
        assertEquals("Código:\n```kotlin\nval a = 1\n```", emitted.joinToString(""))
        assertEquals(4, emitted.size)
    }

    @Test
    fun blankReasoningFromNoThinkIsDropped() = runBlocking {
        assertEquals("Direto.", run(false, listOf(listOf("<think>\n\n</think>\n\nDireto."))).joinToString(""))
    }

    @Test
    fun implicitReasoningWithoutCloseBecomesTheAnswer() = runBlocking {
        val output = run(true, listOf(listOf("Resposta sem tags"))).joinToString("")
        assertEquals("<think>Resposta sem tags</think>\n\nResposta sem tags", output)
    }

    private suspend fun run(
        expectReasoning: Boolean,
        rounds: List<List<String>>,
        toolRound: suspend (String) -> ToolRoundResult? = { null },
        seen: MutableList<List<AiChatMessage>> = mutableListOf(),
    ): List<String> {
        var round = 0
        val generate: (List<AiChatMessage>) -> Flow<String> = { messages ->
            seen += messages.toList()
            flowOf(*rounds[round++].toTypedArray())
        }
        return agentStream(expectReasoning, 4, question, generate, toolRound).toList()
    }
}
