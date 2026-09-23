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
 * fed back to the model and another round runs.
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
                            null -> {
                                val trimmed = answer.trimStart()
                                if (trimmed.isNotEmpty()) {
                                    live = !(trimmed.startsWith("{") || trimmed.startsWith("`"))
                                    if (live == true) emitAnswer(trimmed.toString())
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
