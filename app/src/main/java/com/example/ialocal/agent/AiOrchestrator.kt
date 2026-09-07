package com.example.ialocal.agent

import com.example.ialocal.agent.tools.AgentToolRegistry
import com.example.ialocal.ai.AiChatMessage
import com.example.ialocal.data.AgentEntity
import com.example.ialocal.data.AiModelEntity
import com.example.ialocal.data.ModelVerificationStatus
import com.example.ialocal.models.ModelRepository
import com.example.ialocal.runtime.ModelRuntime
import kotlinx.coroutines.flow.Flow
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

        val working = messages.toMutableList()
        val toolsUsed = mutableListOf<String>()
        val systemPrompt = agent.systemPrompt + tools.promptInstructions()
        repeat(MAX_TOOL_ROUNDS) {
            val output = runtime.complete(
                model = model,
                systemPrompt = systemPrompt,
                messages = working,
                temperature = temperatureOverride ?: agent.temperature,
                maxTokens = maxTokensOverride ?: agent.maxTokens,
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
        // Tool calls must be hidden from the user, so tool-capable runs are resolved first.
        val result = runAgent(agentId, messages, maxTokensOverride, temperatureOverride)
        return result.model to flowOf(result.output)
    }

    private suspend fun resolveModel(selector: String?): AiModelEntity {
        val model = when {
            selector.isNullOrBlank() -> models.getActiveModel()
            else -> models.getModelByApiId(selector) ?: models.getModel(selector)
        } ?: throw IllegalStateException("Nenhum modelo de IA está ativo. Importe e verifique um GGUF primeiro.")
        requireVerified(model)
        return model
    }

    private suspend fun resolveAgent(id: String?): AgentEntity = when {
        id.isNullOrBlank() -> models.getDefaultAgent()
        else -> models.getAgent(id)
    } ?: throw IllegalStateException("Nenhum agente está configurado. Importe e verifique um modelo primeiro.")

    private fun requireVerified(model: AiModelEntity) {
        check(model.verificationStatus == ModelVerificationStatus.VERIFIED.name) {
            val detail = model.lastError?.let { " Último erro: $it" }.orEmpty()
            "O modelo '${model.name}' ainda não passou pelo teste real de inferência.$detail"
        }
    }

    companion object { private const val MAX_TOOL_ROUNDS = 4 }
}
