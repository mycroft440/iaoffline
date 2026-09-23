package com.example.ialocal.agent

import com.example.ialocal.agent.tools.AgentToolRegistry
import com.example.ialocal.ai.AiChatMessage
import com.example.ialocal.data.AgentEntity
import com.example.ialocal.data.AiModelEntity
import com.example.ialocal.data.ModelVerificationStatus
import com.example.ialocal.models.DeepThinkLevel
import com.example.ialocal.models.DeepThinkStore
import com.example.ialocal.models.DeepThinkSupport
import com.example.ialocal.models.ModelRepository
import com.example.ialocal.runtime.ModelRuntime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf

data class AgentRunResult(
    val agent: AgentEntity,
    val model: AiModelEntity,
    val output: String,
    val toolsUsed: List<String> = emptyList(),
)

data class StreamingCompletion(val model: AiModelEntity, val chunks: Flow<String>)

class AiOrchestrator(
    private val models: ModelRepository,
    private val runtime: ModelRuntime,
    private val tools: AgentToolRegistry,
    private val deepThinkStore: DeepThinkStore,
) {
    suspend fun chatCompletion(
        modelSelector: String?,
        messages: List<AiChatMessage>,
        maxTokens: Int = 1024,
        temperature: Float = 0.3f,
        systemPrompt: String = ModelRepository.DEFAULT_SYSTEM_PROMPT,
    ): Pair<AiModelEntity, String> {
        val model = resolveModel(modelSelector)
        val output = runtime.complete(model, systemPrompt, messages, temperature, maxTokens)
        return model to output
    }

    suspend fun streamChatCompletion(
        modelSelector: String?,
        messages: List<AiChatMessage>,
        maxTokens: Int = 1024,
        temperature: Float = 0.3f,
        systemPrompt: String = ModelRepository.DEFAULT_SYSTEM_PROMPT,
    ): StreamingCompletion {
        val model = resolveModel(modelSelector)
        return StreamingCompletion(
            model,
            runtime.streamComplete(model, systemPrompt, messages, temperature, maxTokens),
        )
    }

    suspend fun runAgent(
        agentId: String?,
        messages: List<AiChatMessage>,
        maxTokensOverride: Int? = null,
        temperatureOverride: Float? = null,
    ): AgentRunResult {
        val agent = resolveAgent(agentId)
        val model = models.getModel(agent.modelId)
            ?: throw IllegalStateException("O modelo deste agente não está mais disponível.")
        requireVerified(model)

        val capability = DeepThinkSupport.capability(model)
        val deepThinkLevel = if (capability.supported && deepThinkStore.isEnabled(agent.id)) {
            deepThinkStore.getLevel(agent.id)
        } else {
            DeepThinkLevel.AUTO
        }
        val effectiveMaxTokens = DeepThinkSupport.effectiveMaxTokens(
            maxTokensOverride ?: agent.maxTokens,
            deepThinkLevel,
        )

        val working = messages.toMutableList()
        val toolsUsed = mutableListOf<String>()
        val systemPrompt = agent.systemPrompt + tools.promptInstructions()
        repeat(MAX_TOOL_ROUNDS) {
            val output = runtime.complete(
                model = model,
                systemPrompt = systemPrompt,
                messages = working,
                temperature = temperatureOverride ?: agent.temperature,
                maxTokens = effectiveMaxTokens,
            )
            val call = tools.parseCall(output) ?: return AgentRunResult(agent, model, output, toolsUsed)
            val result = tools.execute(call)
            toolsUsed += call.name
            working += AiChatMessage("assistant", output)
            working += AiChatMessage(
                "user",
                "[RESULTADO DA FERRAMENTA ${call.name}]\n$result\n\nUse este resultado para continuar atendendo a solicitação original.",
            )
        }
        throw IllegalStateException("O agente atingiu o limite de $MAX_TOOL_ROUNDS chamadas de ferramentas sem produzir uma resposta final.")
    }

    suspend fun streamAgent(
        agentId: String?,
        messages: List<AiChatMessage>,
        maxTokensOverride: Int? = null,
        temperatureOverride: Float? = null,
    ): Pair<AiModelEntity, Flow<String>> {
        val agent = resolveAgent(agentId)
        val model = models.getModel(agent.modelId)
            ?: throw IllegalStateException("O modelo deste agente não está mais disponível.")
        requireVerified(model)

        val capability = DeepThinkSupport.capability(model)
        val deepThinkLevel = if (capability.supported && deepThinkStore.isEnabled(agent.id)) {
            deepThinkStore.getLevel(agent.id)
        } else {
            DeepThinkLevel.AUTO
        }
        val effectiveMaxTokens = DeepThinkSupport.effectiveMaxTokens(
            maxTokensOverride ?: agent.maxTokens,
            deepThinkLevel,
        )
        val effectiveTemperature = temperatureOverride ?: agent.temperature
        val systemPrompt = agent.systemPrompt + tools.promptInstructions()

        return model to flow {
            val working = messages.toMutableList()
            var reasoningBlockOpen = false

            repeat(MAX_TOOL_ROUNDS) {
                val output = StringBuilder()
                var mode = AgentStreamRoundMode.UNDECIDED
                var reasoningPayloadStart = -1
                var reasoningEmittedUntil = -1
                var liveEmittedUntil = 0

                runtime.streamComplete(
                    model = model,
                    systemPrompt = systemPrompt,
                    messages = working,
                    temperature = effectiveTemperature,
                    maxTokens = effectiveMaxTokens,
                ).collect { token ->
                    output.append(token)
                    val current = output.toString()
                    if (mode == AgentStreamRoundMode.UNDECIDED) {
                        mode = classifyAgentStreamRound(current)
                    }

                    when (mode) {
                        AgentStreamRoundMode.REASONING -> {
                            val open = REASONING_OPEN.find(current)
                            if (open != null) {
                                if (reasoningPayloadStart < 0) {
                                    reasoningPayloadStart = open.range.last + 1
                                    reasoningEmittedUntil = reasoningPayloadStart
                                    if (!reasoningBlockOpen) {
                                        emit("<think>")
                                        reasoningBlockOpen = true
                                    } else {
                                        emit("\n\n")
                                    }
                                }

                                val close = REASONING_CLOSE.find(current, reasoningPayloadStart)
                                val safeEnd = if (close != null) {
                                    close.range.first
                                } else {
                                    (current.length - trailingReasoningClosePrefixLength(current))
                                        .coerceAtLeast(reasoningPayloadStart)
                                }
                                if (safeEnd > reasoningEmittedUntil) {
                                    emit(current.substring(reasoningEmittedUntil, safeEnd))
                                    reasoningEmittedUntil = safeEnd
                                }
                            }
                        }

                        AgentStreamRoundMode.LIVE_ANSWER -> {
                            if (reasoningBlockOpen) {
                                emit("</think>")
                                reasoningBlockOpen = false
                            }
                            if (current.length > liveEmittedUntil) {
                                emit(current.substring(liveEmittedUntil))
                                liveEmittedUntil = current.length
                            }
                        }

                        AgentStreamRoundMode.BUFFERED,
                        AgentStreamRoundMode.UNDECIDED,
                        -> Unit
                    }
                }

                val finalOutput = output.toString()
                val call = tools.parseCall(finalOutput)
                if (call != null) {
                    val result = tools.execute(call)
                    working += AiChatMessage("assistant", finalOutput)
                    working += AiChatMessage(
                        "user",
                        "[RESULTADO DA FERRAMENTA ${call.name}]\n$result\n\nUse este resultado para continuar atendendo a solicitação original.",
                    )
                } else {
                    when (mode) {
                        AgentStreamRoundMode.REASONING -> {
                            val close = if (reasoningPayloadStart >= 0) {
                                REASONING_CLOSE.find(finalOutput, reasoningPayloadStart)
                            } else {
                                null
                            }
                            val reasoningEnd = close?.range?.first ?: finalOutput.length
                            if (reasoningEmittedUntil >= 0 && reasoningEnd > reasoningEmittedUntil) {
                                emit(finalOutput.substring(reasoningEmittedUntil, reasoningEnd))
                            }
                            if (reasoningBlockOpen) {
                                emit("</think>")
                                reasoningBlockOpen = false
                            }
                            if (close != null && close.range.last + 1 < finalOutput.length) {
                                emit(finalOutput.substring(close.range.last + 1))
                            }
                        }

                        AgentStreamRoundMode.BUFFERED,
                        AgentStreamRoundMode.UNDECIDED,
                        -> {
                            if (reasoningBlockOpen) {
                                emit("</think>")
                                reasoningBlockOpen = false
                            }
                            if (finalOutput.isNotEmpty()) emit(finalOutput)
                        }

                        AgentStreamRoundMode.LIVE_ANSWER -> Unit
                    }
                    return@flow
                }
            }

            if (reasoningBlockOpen) emit("</think>")
            throw IllegalStateException("O agente atingiu o limite de $MAX_TOOL_ROUNDS chamadas de ferramentas sem produzir uma resposta final.")
        }
    }

    private suspend fun resolveModel(selector: String?): AiModelEntity {
        val model = when {
            selector.isNullOrBlank() -> models.getActiveModel()
            else -> models.getModelByApiId(selector) ?: models.getModel(selector)
        } ?: throw IllegalStateException("Nenhum modelo de IA está ativo. Importe e verifique um GGUF primeiro.")
        requireVerified(model)
        return model
    }

    private suspend fun resolveAgent(id: String?): AgentEntity =
        models.resolveAgentForUse(id)
            ?: throw IllegalStateException("Nenhum agente com modelo verificado está disponível. Verifique e ative um modelo primeiro.")

    private fun requireVerified(model: AiModelEntity) {
        check(model.verificationStatus == ModelVerificationStatus.VERIFIED.name) {
            val detail = model.lastError?.let { " Último erro: $it" }.orEmpty()
            "O modelo '${model.name}' ainda não passou pelo teste real de inferência.$detail"
        }
    }

    companion object {
        private const val MAX_TOOL_ROUNDS = 4
        private val REASONING_OPEN = Regex("(?is)<think(?:ing)?>")
        private val REASONING_CLOSE = Regex("(?is)</think(?:ing)?>")
        private val REASONING_OPEN_TAGS = listOf("<think>", "<thinking>")
        private val REASONING_CLOSE_TAGS = listOf("</think>", "</thinking>")

        private fun classifyAgentStreamRound(content: String): AgentStreamRoundMode {
            val trimmed = content.trimStart()
            if (trimmed.isEmpty()) return AgentStreamRoundMode.UNDECIDED
            val normalized = trimmed.lowercase()

            if (REASONING_OPEN_TAGS.any { tag -> tag.startsWith(normalized) }) {
                return AgentStreamRoundMode.UNDECIDED
            }
            if (REASONING_OPEN_TAGS.any { tag -> normalized.startsWith(tag) }) {
                return AgentStreamRoundMode.REASONING
            }

            if (normalized == "`" || normalized == "``") {
                return AgentStreamRoundMode.UNDECIDED
            }
            if (normalized.startsWith("{") || normalized.startsWith("```")) {
                return AgentStreamRoundMode.BUFFERED
            }
            return AgentStreamRoundMode.LIVE_ANSWER
        }

        private fun trailingReasoningClosePrefixLength(content: String): Int {
            val normalized = content.lowercase()
            var best = 0
            REASONING_CLOSE_TAGS.forEach { tag ->
                val maxLength = minOf(tag.length - 1, normalized.length)
                for (length in maxLength downTo 1) {
                    if (normalized.endsWith(tag.take(length))) {
                        best = maxOf(best, length)
                        break
                    }
                }
            }
            return best
        }
    }

    private enum class AgentStreamRoundMode {
        UNDECIDED,
        REASONING,
        LIVE_ANSWER,
        BUFFERED,
    }
}
