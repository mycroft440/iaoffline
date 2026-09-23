package com.example.ialocal.agent

import com.example.ialocal.agent.tools.AgentToolRegistry
import com.example.ialocal.ai.AiChatMessage
import com.example.ialocal.data.AgentEntity
import com.example.ialocal.data.AiModelEntity
import com.example.ialocal.data.ModelVerificationStatus
import com.example.ialocal.models.DeepThinkControlMode
import com.example.ialocal.models.DeepThinkStore
import com.example.ialocal.models.DeepThinkSupport
import com.example.ialocal.models.ModelRepository
import com.example.ialocal.runtime.ModelRuntime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList

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
        val toolsUsed = mutableListOf<String>()
        val (agent, model, chunks) = prepareAgent(agentId, messages, maxTokensOverride, temperatureOverride) {
            toolsUsed += it
        }
        val output = chunks.toList().joinToString("").trim()
        return AgentRunResult(agent, model, output, toolsUsed)
    }

    /**
     * Streams a profile run as it is generated. Reasoning is re-emitted inside one canonical
     * `<think>…</think>` block (across tool rounds) so the chat can show it live; an answer that
     * starts like a tool call is held back until it is known not to be one.
     */
    suspend fun streamAgent(
        agentId: String?,
        messages: List<AiChatMessage>,
        maxTokensOverride: Int? = null,
        temperatureOverride: Float? = null,
    ): Pair<AiModelEntity, Flow<String>> {
        val (_, model, chunks) = prepareAgent(agentId, messages, maxTokensOverride, temperatureOverride) {}
        return model to chunks
    }

    private data class PreparedAgent(val agent: AgentEntity, val model: AiModelEntity, val chunks: Flow<String>)

    private suspend fun prepareAgent(
        agentId: String?,
        messages: List<AiChatMessage>,
        maxTokensOverride: Int?,
        temperatureOverride: Float?,
        onToolUsed: (String) -> Unit,
    ): PreparedAgent {
        val agent = resolveAgent(agentId)
        val model = models.getModel(agent.modelId)
            ?: throw IllegalStateException("O modelo deste agente não está mais disponível.")
        requireVerified(model)

        val capability = DeepThinkSupport.capability(model)
        val deepThinkEnabled = deepThinkStore.isEnabled(agent.id)
        val plan = DeepThinkSupport.plan(
            model = model,
            baseMaxTokens = maxTokensOverride ?: agent.maxTokens,
            enabled = deepThinkEnabled,
            level = deepThinkStore.getLevel(agent.id),
        )
        val expectReasoning = capability.mode == DeepThinkControlMode.REASONING_MODEL ||
            (capability.mode == DeepThinkControlMode.HYBRID_THINKING && deepThinkEnabled)
        val systemPrompt = agent.systemPrompt + tools.promptInstructions() + plan.systemSuffix
        val temperature = temperatureOverride ?: agent.temperature

        val chunks = agentStream(
            expectReasoning = expectReasoning,
            maxRounds = MAX_TOOL_ROUNDS,
            messages = messages,
            generate = { working ->
                runtime.streamComplete(
                    model = model,
                    systemPrompt = systemPrompt,
                    messages = withUserSuffix(working, plan.userSuffix),
                    temperature = temperature,
                    maxTokens = plan.maxTokens,
                )
            },
            toolRound = { answer ->
                tools.parseCall(answer)?.let { call ->
                    val output = tools.execute(call)
                    onToolUsed(call.name)
                    ToolRoundResult(call.name, output)
                }
            },
        )
        return PreparedAgent(agent, model, chunks)
    }

    private fun withUserSuffix(messages: List<AiChatMessage>, suffix: String): List<AiChatMessage> {
        if (suffix.isEmpty()) return messages
        val lastUser = messages.indexOfLast { it.role.equals("user", ignoreCase = true) }
        if (lastUser < 0) return messages
        return messages.mapIndexed { index, message ->
            if (index == lastUser) message.copy(content = message.content.trimEnd() + suffix) else message
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

    companion object { private const val MAX_TOOL_ROUNDS = 4 }
}