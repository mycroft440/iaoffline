package com.example.ialocal.agent

import com.example.ialocal.ai.AiChatMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Result of a tool the model asked for during a profile run. */
data class ToolRoundResult(val name: String, val output: String)

/**
 * Runs up to [maxRounds] generations for a profile and streams what the user should see.
 *
 * Reasoning is re-emitted inside one canonical `<think>…</think>` block across rounds so the chat
 * can show it live. An answer that starts like a tool call (`{` or a code fence) is held back until
 * the round ends; [toolRound] then decides whether it was a tool call, in which case its result is
 * fed back to the model and another round runs. Models also write a sentence first ("Vou ler o
 * arquivo.") and the call after it, so in a live answer a line starting with `{` or a ```json fence
 * is held back the same way, and the JSON never shows up in the chat.
 */
internal fun agentStream(
    expectReasoning: Boolean,
    maxRounds: Int,
    messages: List<AiChatMessage>,
    generate: (List<AiChatMessage>) -> Flow<String>,
    toolRound: suspend (answer: String) -> ToolRoundResult?,
): Flow<String> = flow {
    val working = messages.toMutableList()
    var thinkOpen = false
    var answerStarted = false
    val heldReasoning = StringBuilder()
    // The end of the answer shown so far, and whether the next round's answer needs a blank line
    // to separate it from a sentence shown before a tool call.
    var answerTail = ""
    var separateNext = false

    suspend fun emitReasoning(text: String) {
        if (answerStarted) return // Reasoning of later tool rounds is not shown after the answer.
        if (!thinkOpen) {
            heldReasoning.append(text)
            if (heldReasoning.isBlank()) return
            emit("<think>")
            emit(heldReasoning.toString().trimStart())
            heldReasoning.setLength(0)
            thinkOpen = true
        } else {
            emit(text)
        }
    }

    suspend fun emitAnswer(text: String) {
        if (thinkOpen) {
            emit("</think>\n\n")
            thinkOpen = false
        }
        if (separateNext) {
            separateNext = false
            val breaks = answerTail.takeLastWhile { it == '\n' }.length + text.takeWhile { it == '\n' }.length
            if (breaks < 2) emit("\n".repeat(2 - breaks))
        }
        answerStarted = true
        emit(text)
        answerTail = (answerTail + text).takeLast(2)
    }

    repeat(maxRounds) { round ->
        val splitter = ReasoningStreamSplitter(expectReasoning)
        val raw = StringBuilder()
        val answer = StringBuilder()
        // null: undecided; true: streamed live; false: held back as a possible tool call.
        var live: Boolean? = null
        // In a live answer: how much of it was shown, and where a possible tool call starts (-1: none).
        var shown = 0
        var heldFrom = -1
        if (round > 0 && thinkOpen) emit("\n\n")

        /** Shows the live answer up to a line that may start a tool call, or one not known yet. */
        suspend fun showLive() {
            if (heldFrom >= 0) return
            var position = shown
            while (position < answer.length) {
                val newline = answer.indexOf('\n', position)
                val complete = newline >= 0
                val lineEnd = if (complete) newline + 1 else answer.length
                if (position == 0 || answer[position - 1] == '\n') {
                    val start = answer.substring(position, lineEnd).trim().lowercase()
                    if (start.startsWith("{") || start.startsWith("```json")) {
                        heldFrom = position
                        break
                    }
                    if (!complete && (start.isEmpty() || "```json".startsWith(start))) break
                }
                position = lineEnd
            }
            if (position > shown) {
                emitAnswer(answer.substring(shown, position))
                shown = position
            }
        }

        suspend fun handle(parts: List<ReasoningStreamSplitter.Part>) {
            parts.forEach { part ->
                when (part) {
                    is ReasoningStreamSplitter.Reasoning -> emitReasoning(part.text)
                    is ReasoningStreamSplitter.Answer -> {
                        answer.append(part.text)
                        when (live) {
                            true -> showLive()
                            false -> Unit
                            null -> {
                                val first = answer.indexOfFirst { !it.isWhitespace() }
                                if (first >= 0) {
                                    live = !(answer[first] == '{' || answer[first] == '`')
                                    if (live == true) {
                                        shown = first // Leading blank lines are not shown.
                                        showLive()
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        generate(working).collect { chunk ->
            raw.append(chunk)
            handle(splitter.push(chunk))
        }
        handle(splitter.finish())
        if (splitter.unclosedImplicitReasoning && answer.isBlank()) {
            // The model answered without reasoning tags; what looked like reasoning is the answer.
            handle(listOf(ReasoningStreamSplitter.Answer(splitter.reasoning)))
        }

        val tool = toolRound(if (heldFrom >= 0) answer.substring(heldFrom) else answer.toString())
        if (tool == null) {
            if (live == false) emitAnswer(answer.trimStart().toString())
            if (live == true && shown < answer.length) emitAnswer(answer.substring(shown))
            if (thinkOpen) {
                emit("</think>")
                thinkOpen = false
            }
            return@flow
        }
        if (live == true) separateNext = true
        working += AiChatMessage("assistant", raw.toString())
        working += AiChatMessage(
            "user",
            "[RESULTADO DA FERRAMENTA ${tool.name}]\n${tool.output}\n\nUse este resultado para continuar atendendo a solicitação original.",
        )
    }
    throw IllegalStateException("O agente atingiu o limite de $maxRounds chamadas de ferramentas sem produzir uma resposta final.")
}
