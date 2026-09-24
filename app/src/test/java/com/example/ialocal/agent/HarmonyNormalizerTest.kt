package com.example.ialocal.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class HarmonyNormalizerTest {
    private fun normalize(chunks: List<String>): String {
        val normalizer = HarmonyNormalizer()
        return chunks.joinToString("") { normalizer.push(it) } + normalizer.finish()
    }

    private val harmony =
        "<|channel|>analysis<|message|>User greets. Reply.<|end|><|start|>assistant<|channel|>final<|message|>Olá! Tudo bem?"

    @Test
    fun analysisBecomesThinkBlockAndFinalBecomesAnswer() {
        assertEquals("<think>User greets. Reply.</think>Olá! Tudo bem?", normalize(listOf(harmony)))
    }

    @Test
    fun tokensSplitAcrossChunksAreRecognized() {
        val chunks = harmony.chunked(3)
        assertEquals("<think>User greets. Reply.</think>Olá! Tudo bem?", normalize(chunks))
        assertEquals("<think>User greets. Reply.</think>Olá! Tudo bem?", normalize(harmony.map { it.toString() }))
    }

    @Test
    fun unfinishedAnalysisIsClosed() {
        assertEquals("<think>Pensando</think>", normalize(listOf("<|channel|>analysis<|message|>Pensando")))
    }

    @Test
    fun finalChannelWithoutEndClosesReasoning() {
        assertEquals(
            "<think>R</think>A",
            normalize(listOf("<|channel|>analysis<|message|>R<|start|>assistant<|channel|>final<|message|>A<|return|>")),
        )
    }

    @Test
    fun plainOutputPassesThrough() {
        val text = "if (a < b && c <| d) return x < y"
        assertEquals(text, normalize(text.chunked(2)))
        assertEquals("<think>x</think>y", normalize(listOf("<think>x</think>y")))
    }

    @Test
    fun feedsTheReasoningSplitter() {
        val splitter = ReasoningStreamSplitter(expectReasoning = true)
        val normalizer = HarmonyNormalizer()
        val parts = harmony.chunked(4).flatMap { splitter.push(normalizer.push(it)) } +
            splitter.push(normalizer.finish()) + splitter.finish()
        assertEquals("User greets. Reply.", parts.filterIsInstance<ReasoningStreamSplitter.Reasoning>().joinToString("") { it.text })
        assertEquals("Olá! Tudo bem?", parts.filterIsInstance<ReasoningStreamSplitter.Answer>().joinToString("") { it.text })
    }
}
