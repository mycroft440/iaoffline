package com.example.ialocal.agent

/**
 * Splits one model output, as it streams, into reasoning and answer text. Reasoning is delimited by
 * `<think>`, `<thinking>`, `<reasoning>`, `<analysis>`, `[THINK]` or Gemma 4's `<|channel>thought`
 * blocks. Some chat templates open the reasoning block in the prompt, so for models that always
 * reason ([expectReasoning]) the output is treated as reasoning until a closing tag appears, even
 * without an opening tag.
 */
class ReasoningStreamSplitter(private val expectReasoning: Boolean) {
    sealed interface Part {
        val text: String
    }
    data class Reasoning(override val text: String) : Part
    data class Answer(override val text: String) : Part

    private enum class State { START, REASONING, ANSWER }

    private var state = State.START
    private val pending = StringBuilder()
    private var implicitReasoning = false
    private var reasoningClosed = false
    private val reasoningText = StringBuilder()

    /**
     * True when the output was assumed to be reasoning without an opening tag and never closed it:
     * the model most likely answered directly, so the caller should treat [reasoningText] as the answer.
     */
    val unclosedImplicitReasoning: Boolean
        get() = implicitReasoning && !reasoningClosed

    /** All reasoning text seen so far, without tags. */
    val reasoning: String get() = reasoningText.toString()

    fun push(chunk: String): List<Part> {
        pending.append(chunk)
        return drain(finished = false)
    }

    fun finish(): List<Part> = drain(finished = true)

    private fun drain(finished: Boolean): List<Part> {
        val parts = mutableListOf<Part>()
        while (true) {
            when (state) {
                State.START -> {
                    val trimmed = pending.trimStart().toString()
                    if (trimmed.isEmpty() && !finished) return parts
                    val open = OPEN_TAGS.firstOrNull { trimmed.startsWith(it, ignoreCase = true) }
                    when {
                        open != null -> {
                            pending.setLength(0)
                            pending.append(trimmed.substring(open.length))
                            state = State.REASONING
                        }
                        !finished && OPEN_TAGS.any { it.startsWith(trimmed, ignoreCase = true) } -> return parts
                        expectReasoning && !finished -> {
                            implicitReasoning = true
                            state = State.REASONING
                        }
                        else -> state = State.ANSWER
                    }
                }
                State.REASONING -> {
                    val text = pending.toString()
                    val close = CLOSE_TAGS
                        .mapNotNull { tag -> text.indexOf(tag, ignoreCase = true).takeIf { it >= 0 }?.let { it to tag } }
                        .minByOrNull { it.first }
                    if (close != null) {
                        addReasoning(parts, text.substring(0, close.first))
                        pending.setLength(0)
                        pending.append(text.substring(close.first + close.second.length))
                        reasoningClosed = true
                        state = State.ANSWER
                        continue
                    }
                    // Hold back a suffix that could be the start of a closing tag split across chunks.
                    val keep = if (finished) 0 else partialTagSuffix(text)
                    addReasoning(parts, text.substring(0, text.length - keep))
                    pending.setLength(0)
                    pending.append(text.substring(text.length - keep))
                    return parts
                }
                State.ANSWER -> {
                    if (pending.isNotEmpty()) {
                        parts += Answer(pending.toString())
                        pending.setLength(0)
                    }
                    return parts
                }
            }
        }
    }

    private fun addReasoning(parts: MutableList<Part>, text: String) {
        if (text.isEmpty()) return
        reasoningText.append(text)
        parts += Reasoning(text)
    }

    private fun partialTagSuffix(text: String): Int {
        val max = minOf(text.length, CLOSE_TAGS.maxOf { it.length } - 1)
        for (length in max downTo 1) {
            val suffix = text.substring(text.length - length)
            if (CLOSE_TAGS.any { it.startsWith(suffix, ignoreCase = true) }) return length
        }
        return 0
    }

    companion object {
        // "<|channel>thought" … "<channel|>" is Gemma 4's thinking block.
        private val OPEN_TAGS = listOf("<thinking>", "<think>", "<reasoning>", "<analysis>", "[THINK]", "<|channel>thought")
        private val CLOSE_TAGS = listOf("</thinking>", "</think>", "</reasoning>", "</analysis>", "[/THINK]", "<channel|>")
    }
}
