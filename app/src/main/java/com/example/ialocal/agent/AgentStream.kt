package com.example.ialocal.agent

import com.example.ialocal.ai.AiChatMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Whether an answer that starts like [answer] can still be a tool call, in the format the tools ask
 * for (`{"tool":"…","arguments":{…}}`, optionally in a json code fence): true when it has that shape,
 * false when it cannot have it (prose, inline code, a fence of another language), null while too
 * little of it arrived to tell. Only a possible tool call is held back; everything else shows as it
 * is written.
 */
internal fun mayBeToolCall(answer: CharSequence): Boolean? {
    val text = answer.trimStart()
    if (text.isEmpty()) return null
    if (text.startsWith("{")) return jsonToolCallPrefix(text)
    if (!text.startsWith("`")) return false
    if (text.length < FENCE.length) return if (FENCE.startsWith(text)) null else false
    if (!text.startsWith(FENCE)) return false
    val newline = text.indexOf('\n')
    val info = (if (newline < 0) text.substring(FENCE.length) else text.substring(FENCE.length, newline)).trim().lowercase()
    if (info.isNotEmpty() && !"json".startsWith(info)) return false
    if (newline < 0) return null
    if (info.isNotEmpty() && info != "json") return false
    val body = text.substring(newline + 1).trimStart()
    if (body.isEmpty()) return null
    return if (body.startsWith("{")) jsonToolCallPrefix(body) else false
}

/** [text] starts with `{`: whether, ignoring whitespace, it starts with a tool call's first key. */
private fun jsonToolCallPrefix(text: CharSequence): Boolean? {
    val compact = text.filterNot { it.isWhitespace() }
    return when {
        TOOL_CALL_STARTS.any { compact.startsWith(it) } -> true
        TOOL_CALL_STARTS.any { it.startsWith(compact) } -> null
        else -> false
    }
}

private const val FENCE = "```"
private val TOOL_CALL_STARTS = listOf("{\"tool\"", "{\"arguments\"")

/** Result of a tool the model asked for during a profile run. */
data class ToolRoundResult(val name: String, val output: String)

/**
 * Runs up to [maxRounds] generations for a profile and streams what the user should see.
 *
 * Reasoning is re-emitted inside one canonical `<think>…</think>` block across rounds so the chat
 * can show it live. An answer that starts like a tool call ([mayBeToolCall]) is held back until
 * the round ends; [toolRound] then decides whether it was a tool call, in which case its result is
 * fed back to the model and another round runs. While nothing is visible yet, [onHiddenTokens]
 * gets the number of tokens the round has written, so the chat can say the answer is being written.
 */
internal fun agentStream(
    expectReasoning: Boolean,
    maxRounds: Int,
    messages: List<AiChatMessage>,
    generate: (List<AiChatMessage>) -> Flow<String>,
    toolRound: suspend (answer: String) -> ToolRoundResult?,
    onHiddenTokens: (Int) -> Unit = {},
): Flow<String> = flow {
    val working = messages.toMutableList()
    var thinkOpen = false
    var answerStarted = false
    val heldReasoning = StringBuilder()

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
        answerStarted = true
        emit(text)
    }

    repeat(maxRounds) { round ->
        val splitter = ReasoningStreamSplitter(expectReasoning)
        val raw = StringBuilder()
        val answer = StringBuilder()
        // null: undecided; true: streamed live; false: held back as a possible tool call.
        var live: Boolean? = null
        if (round > 0 && thinkOpen) emit("\n\n")

        suspend fun handle(parts: List<ReasoningStreamSplitter.Part>) {
            parts.forEach { part ->
                when (part) {
                    is ReasoningStreamSplitter.Reasoning -> emitReasoning(part.text)
                    is ReasoningStreamSplitter.Answer -> {
                        answer.append(part.text)
                        when (live) {
                            true -> emitAnswer(part.text)
                            false -> Unit
                            null -> when (mayBeToolCall(answer)) {
                                null -> Unit
                                true -> live = false
                                false -> {
                                    live = true
                                    emitAnswer(answer.trimStart().toString())
                                }
                            }
                        }
                    }
                }
            }
        }

        var tokens = 0
        generate(working).collect { chunk ->
            raw.append(chunk)
            handle(splitter.push(chunk))
            tokens++
            if (!answerStarted && !thinkOpen) onHiddenTokens(tokens)
        }
        handle(splitter.finish())
        if (splitter.unclosedImplicitReasoning && answer.isBlank()) {
            // The model answered without reasoning tags; what looked like reasoning is the answer.
            handle(listOf(ReasoningStreamSplitter.Answer(splitter.reasoning)))
        }

        val tool = toolRound(answer.toString())
        if (tool == null) {
            if (live == false) emitAnswer(answer.trimStart().toString())
            if (thinkOpen) {
                emit("</think>")
                thinkOpen = false
            }
            return@flow
        }
        working += AiChatMessage("assistant", raw.toString())
        working += AiChatMessage(
            "user",
            "[RESULTADO DA FERRAMENTA ${tool.name}]\n${tool.output}\n\nUse este resultado para continuar atendendo a solicitação original.",
        )
    }
    throw IllegalStateException("O agente atingiu o limite de $maxRounds chamadas de ferramentas sem produzir uma resposta final.")
}
