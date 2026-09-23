package com.example.ialocal.agent

import com.example.ialocal.agent.ReasoningStreamSplitter.Answer
import com.example.ialocal.agent.ReasoningStreamSplitter.Part
import com.example.ialocal.agent.ReasoningStreamSplitter.Reasoning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReasoningStreamSplitterTest {
    @Test
    fun explicitTagsSplitAcrossChunks() {
        val splitter = ReasoningStreamSplitter(expectReasoning = false)
        val parts = feed(splitter, "<thi", "nk>pensando ", "mais</th", "ink>\n\nResposta")
        assertEquals("pensando mais", reasoning(parts))
        assertEquals("\n\nResposta", answer(parts))
        assertFalse(splitter.unclosedImplicitReasoning)
    }

    @Test
    fun reasoningModelWithoutOpeningTagIsReasoningUntilClose() {
        val splitter = ReasoningStreamSplitter(expectReasoning = true)
        val parts = feed(splitter, "Vamos ver", "...</think>", "Oi!")
        assertEquals("Vamos ver...", reasoning(parts))
        assertEquals("Oi!", answer(parts))
        assertFalse(splitter.unclosedImplicitReasoning)
    }

    @Test
    fun implicitReasoningThatNeverClosesIsReported() {
        val splitter = ReasoningStreamSplitter(expectReasoning = true)
        feed(splitter, "Olá, ", "tudo bem?")
        assertTrue(splitter.unclosedImplicitReasoning)
        assertEquals("Olá, tudo bem?", splitter.reasoning)
    }

    @Test
    fun plainModelStreamsAnswerOnly() {
        val splitter = ReasoningStreamSplitter(expectReasoning = false)
        val parts = feed(splitter, "Olá", ", mundo")
        assertEquals("", reasoning(parts))
        assertEquals("Olá, mundo", answer(parts))
    }

    @Test
    fun mistralStyleTags() {
        val splitter = ReasoningStreamSplitter(expectReasoning = false)
        val parts = feed(splitter, "[THINK]a[/THINK]b")
        assertEquals("a", reasoning(parts))
        assertEquals("b", answer(parts))
    }

    @Test
    fun reasoningAndAnalysisTags() {
        val parts = feed(ReasoningStreamSplitter(expectReasoning = false), "<reasoning>r</reas", "oning>x")
        assertEquals("r", reasoning(parts))
        assertEquals("x", answer(parts))
        val analysis = feed(ReasoningStreamSplitter(expectReasoning = false), "<analysis>a</analysis>y")
        assertEquals("a", reasoning(analysis))
        assertEquals("y", answer(analysis))
    }

    @Test
    fun emptyReasoningFromNoThinkSwitch() {
        val splitter = ReasoningStreamSplitter(expectReasoning = false)
        val parts = feed(splitter, "<think>\n\n</think>\n\nResposta direta")
        assertEquals("\n\n", reasoning(parts))
        assertEquals("\n\nResposta direta", answer(parts))
    }

    private fun feed(splitter: ReasoningStreamSplitter, vararg chunks: String): List<Part> =
        chunks.flatMap { splitter.push(it) } + splitter.finish()

    private fun reasoning(parts: List<Part>) = parts.filterIsInstance<Reasoning>().joinToString("") { it.text }
    private fun answer(parts: List<Part>) = parts.filterIsInstance<Answer>().joinToString("") { it.text }
}
