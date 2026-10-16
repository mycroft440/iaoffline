package com.example.ialocal.models

import android.content.Context
import com.example.ialocal.data.AiModelEntity
import com.example.ialocal.runtime.RuntimeLimits

enum class DeepThinkLevel(val storedValue: Int, val label: String, val minimumOutputTokens: Int) {
    AUTO(0, "Automático", 0),
    LOW(1, "Baixo", 1536),
    MEDIUM(2, "Médio", 2560),
    HIGH(3, "Máximo", 4096),
    ;

    companion object {
        fun fromStoredValue(value: Int): DeepThinkLevel =
            entries.firstOrNull { it.storedValue == value } ?: HIGH
    }
}

enum class DeepThinkControlMode {
    NONE,
    HYBRID_THINKING,
    REASONING_MODEL,
}

data class DeepThinkCapability(
    val mode: DeepThinkControlMode,
    val description: String,
    /** Whether reasoning is on before the user chooses: the default of the model's own template. */
    val defaultEnabled: Boolean = false,
    internal val switch: ThinkingSwitch = ThinkingSwitch.NONE,
) {
    val supported: Boolean get() = mode != DeepThinkControlMode.NONE
}

/** How a model's reasoning is turned on and off. */
enum class ThinkingSwitch {
    NONE,
    /** Qwen3: "/no_think" after the user message. */
    QWEN3_SLASH,
    /** Nemotron Nano v2: "/no_think" in the system prompt. */
    NEMOTRON_SLASH,
    /** Qwen3.5 and later (Bonsai 2, Ornith…), MiniCPM5: the answer is prefilled with an empty or open think block. */
    THINK_PREFILL,
    /** Granite 4.2: same idea, with its own empty block. */
    GRANITE_PREFILL,
    /** Gemma 4: the template's enable_thinking flag. */
    TEMPLATE_FLAG,
}

/** How a model is asked to reason for one request. */
data class ReasoningPlan(
    val maxTokens: Int,
    /** Text appended to the system prompt, e.g. Nemotron's `/no_think`. */
    val systemSuffix: String = "",
    /** Text appended to the latest user message, e.g. Qwen3's `/no_think`. */
    val userSuffix: String = "",
    /** Prefilled at the start of the answer, e.g. an empty `<think></think>` to skip reasoning. */
    val assistantPrefix: String = "",
    /** enable_thinking for templates that read it (Gemma 4). */
    val templateThinking: Boolean = true,
)

object DeepThinkSupport {
    fun capability(model: AiModelEntity): DeepThinkCapability {
        val identity = "${model.name} ${model.apiModelId} ${model.architecture.orEmpty()}".lowercase()

        return when {
            // Bonsai 2 and Bonsai 27B are Qwen3.5/3.6 underneath; the first Bonsai generation keeps
            // the Qwen3 architecture but was trained without thinking.
            BONSAI_REASONING.containsMatchIn(identity) -> switchable(ThinkingSwitch.THINK_PREFILL, defaultEnabled = true)
            identity.contains("bonsai") -> DeepThinkCapability(
                DeepThinkControlMode.NONE,
                "Esta variante responde direto, sem modo DeepThink.",
            )
            identity.contains("qwen3-coder") || identity.contains("qwen3 coder") ||
                QWEN3_NON_THINKING.containsMatchIn(identity) -> DeepThinkCapability(
                DeepThinkControlMode.NONE,
                "Esta variante não oferece modo DeepThink.",
            )
            ALWAYS_REASONING.containsMatchIn(identity) -> DeepThinkCapability(
                DeepThinkControlMode.REASONING_MODEL,
                "Este modelo sempre raciocina antes de responder; o raciocínio não pode ser desligado.",
            )
            // Their templates reason by default; turning it off answers much faster on a phone.
            QWEN35_FAMILY.containsMatchIn(identity) || MINICPM5.containsMatchIn(identity) ->
                switchable(ThinkingSwitch.THINK_PREFILL, defaultEnabled = true)
            GRANITE_42.containsMatchIn(identity) -> switchable(ThinkingSwitch.GRANITE_PREFILL, defaultEnabled = true)
            GEMMA_4.containsMatchIn(identity) -> switchable(ThinkingSwitch.TEMPLATE_FLAG, defaultEnabled = true)
            // Qwen3 and Nemotron Nano v2 accept the /think and /no_think switches.
            QWEN3_HYBRID.containsMatchIn(identity) -> switchable(ThinkingSwitch.QWEN3_SLASH, defaultEnabled = false)
            NEMOTRON_V2.containsMatchIn(identity) -> switchable(ThinkingSwitch.NEMOTRON_SLASH, defaultEnabled = false)
            else -> DeepThinkCapability(
                DeepThinkControlMode.NONE,
                "DeepThink não foi detectado para este modelo.",
            )
        }
    }

    private fun switchable(switch: ThinkingSwitch, defaultEnabled: Boolean) = DeepThinkCapability(
        mode = DeepThinkControlMode.HYBRID_THINKING,
        description = "Modelo híbrido: o DeepThink liga ou desliga o raciocínio e ajusta o orçamento de geração. " +
            "Desligado, a resposta sai bem mais rápido.",
        defaultEnabled = defaultEnabled,
        switch = switch,
    )

    /**
     * Resolves the token budget and thinking switch for a request. Models that always reason get the
     * largest budget, because a truncated reasoning never reaches the answer. Hybrid models reason
     * only when DeepThink is on; otherwise they are explicitly told not to think.
     */
    fun plan(model: AiModelEntity, baseMaxTokens: Int, enabled: Boolean, level: DeepThinkLevel): ReasoningPlan {
        val capability = capability(model)
        return when (capability.mode) {
            DeepThinkControlMode.NONE -> ReasoningPlan(effectiveMaxTokens(baseMaxTokens, DeepThinkLevel.AUTO))
            DeepThinkControlMode.REASONING_MODEL -> ReasoningPlan(RuntimeLimits.MAX_OUTPUT_TOKENS)
            DeepThinkControlMode.HYBRID_THINKING -> {
                val maxTokens = effectiveMaxTokens(baseMaxTokens, if (enabled) level else DeepThinkLevel.AUTO)
                when (capability.switch) {
                    ThinkingSwitch.QWEN3_SLASH ->
                        ReasoningPlan(maxTokens, userSuffix = if (enabled) "" else " /no_think")
                    ThinkingSwitch.NEMOTRON_SLASH ->
                        ReasoningPlan(maxTokens, systemSuffix = if (enabled) "" else "\n/no_think")
                    ThinkingSwitch.THINK_PREFILL ->
                        ReasoningPlan(maxTokens, assistantPrefix = if (enabled) THINK_OPEN else THINK_EMPTY)
                    ThinkingSwitch.GRANITE_PREFILL ->
                        ReasoningPlan(maxTokens, assistantPrefix = if (enabled) THINK_OPEN else GRANITE_THINK_EMPTY)
                    ThinkingSwitch.TEMPLATE_FLAG -> ReasoningPlan(maxTokens, templateThinking = enabled)
                    ThinkingSwitch.NONE -> ReasoningPlan(maxTokens)
                }
            }
        }
    }

    private const val THINK_OPEN = "<think>\n"
    private const val THINK_EMPTY = "<think>\n\n</think>\n\n"
    private const val GRANITE_THINK_EMPTY = "<think></think>"
    private val QWEN3_NON_THINKING = Regex("qwen3[^a-z]*[\\w.-]*instruct-2507")
    private val ALWAYS_REASONING = Regex(
        "deepseek[- ]r1|gpt-oss|phi[- ]4[- ]reasoning|reasoning|magistral|qwq|olmo[- ]?3[\\w .-]*think|" +
            "qwen3[\\w.-]*thinking-2507|mellum",
    )
    private val BONSAI_REASONING = Regex("bonsai[- ]?2(?!\\d)|bonsai[- ]?27b")
    /** Qwen3.5, 3.6, 3.8 and models built on them (Ornith, FrogNano…), by name or architecture. */
    private val QWEN35_FAMILY = Regex("qwen3[.]\\d|\\bqwen35|ornith")
    private val MINICPM5 = Regex("minicpm[- ]?5")
    private val GRANITE_42 = Regex("granite[- ]?4[.]2")
    private val GEMMA_4 = Regex("gemma[- ]?4")
    private val QWEN3_HYBRID = Regex("qwen-?3(?![.\\d])")
    private val NEMOTRON_V2 = Regex("nemotron[\\w .-]*nano[\\w .-]*v2")

    fun effectiveMaxTokens(baseMaxTokens: Int, level: DeepThinkLevel): Int {
        val base = baseMaxTokens.coerceIn(RuntimeLimits.MIN_OUTPUT_TOKENS, RuntimeLimits.MAX_OUTPUT_TOKENS)
        if (level == DeepThinkLevel.AUTO) return base
        return maxOf(base, level.minimumOutputTokens).coerceAtMost(RuntimeLimits.MAX_OUTPUT_TOKENS)
    }
}

class DeepThinkStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getLevel(agentId: String): DeepThinkLevel =
        DeepThinkLevel.fromStoredValue(preferences.getInt(levelKey(agentId), DeepThinkLevel.HIGH.storedValue))

    fun setLevel(agentId: String, level: DeepThinkLevel) {
        preferences.edit().putInt(levelKey(agentId), level.storedValue).apply()
    }

    /** The user's choice for this profile, or [default] (the model's own) before they choose. */
    fun isEnabled(agentId: String, default: Boolean = false): Boolean =
        preferences.getBoolean(enabledKey(agentId), default)

    fun setEnabled(agentId: String, enabled: Boolean) {
        preferences.edit().putBoolean(enabledKey(agentId), enabled).apply()
    }

    fun clear(agentId: String) {
        preferences.edit()
            .remove(levelKey(agentId))
            .remove(enabledKey(agentId))
            .apply()
    }

    private fun levelKey(agentId: String): String = "level_$agentId"
    private fun enabledKey(agentId: String): String = "enabled_$agentId"

    companion object {
        private const val PREFS_NAME = "deepthink_agent_settings"
    }
}
