package com.example.ialocal.models

import android.content.Context
import com.example.ialocal.data.AiModelEntity

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
) {
    val supported: Boolean get() = mode != DeepThinkControlMode.NONE
}

/** How a model is asked to reason for one request. */
data class ReasoningPlan(
    val maxTokens: Int,
    /** Text appended to the system prompt, e.g. Nemotron's `/no_think`. */
    val systemSuffix: String = "",
    /** Text appended to the latest user message, e.g. Qwen3's `/no_think`. */
    val userSuffix: String = "",
)

object DeepThinkSupport {
    fun capability(model: AiModelEntity): DeepThinkCapability {
        val identity = "${model.name} ${model.apiModelId} ${model.architecture.orEmpty()}".lowercase()

        return when {
            identity.contains("qwen3-coder") || identity.contains("qwen3 coder") ||
                QWEN3_NON_THINKING.containsMatchIn(identity) -> DeepThinkCapability(
                DeepThinkControlMode.NONE,
                "Esta variante não oferece modo DeepThink.",
            )
            ALWAYS_REASONING.containsMatchIn(identity) -> DeepThinkCapability(
                DeepThinkControlMode.REASONING_MODEL,
                "Este modelo sempre raciocina antes de responder; o raciocínio não pode ser desligado.",
            )
            // Qwen3 and Nemotron Nano v2 accept the /think and /no_think switches. Qwen3.5 and
            // later dropped them, so those always reason (matched above).
            QWEN3_HYBRID.containsMatchIn(identity) || NEMOTRON_V2.containsMatchIn(identity) -> DeepThinkCapability(
                DeepThinkControlMode.HYBRID_THINKING,
                "Modelo híbrido: o DeepThink liga ou desliga o raciocínio e ajusta o orçamento de geração.",
            )
            else -> DeepThinkCapability(
                DeepThinkControlMode.NONE,
                "DeepThink não foi detectado para este modelo.",
            )
        }
    }

    /**
     * Resolves the token budget and thinking switch for a request. Models that always reason get the
     * largest budget, because a truncated reasoning never reaches the answer. Hybrid models reason
     * only when the user enabled DeepThink; otherwise they are explicitly told not to think.
     */
    fun plan(model: AiModelEntity, baseMaxTokens: Int, enabled: Boolean, level: DeepThinkLevel): ReasoningPlan {
        val capability = capability(model)
        return when (capability.mode) {
            DeepThinkControlMode.NONE -> ReasoningPlan(effectiveMaxTokens(baseMaxTokens, DeepThinkLevel.AUTO))
            DeepThinkControlMode.REASONING_MODEL -> ReasoningPlan(effectiveMaxTokens(baseMaxTokens, DeepThinkLevel.HIGH))
            DeepThinkControlMode.HYBRID_THINKING -> if (enabled) {
                ReasoningPlan(effectiveMaxTokens(baseMaxTokens, level))
            } else {
                val identity = "${model.name} ${model.apiModelId}".lowercase()
                val nemotron = NEMOTRON_V2.containsMatchIn(identity)
                ReasoningPlan(
                    maxTokens = effectiveMaxTokens(baseMaxTokens, DeepThinkLevel.AUTO),
                    systemSuffix = if (nemotron) "\n/no_think" else "",
                    userSuffix = if (nemotron) "" else " /no_think",
                )
            }
        }
    }

    private val QWEN3_NON_THINKING = Regex("qwen3[^a-z]*[\\w.-]*instruct-2507")
    private val ALWAYS_REASONING = Regex(
        "deepseek[- ]r1|phi[- ]4[- ]reasoning|reasoning|magistral|qwq|olmo[- ]?3[\\w .-]*think|" +
            "qwen3[.]\\d|qwen3[\\w.-]*thinking-2507",
    )
    private val QWEN3_HYBRID = Regex("qwen-?3(?![.\\d])")
    private val NEMOTRON_V2 = Regex("nemotron[\\w .-]*nano[\\w .-]*v2")

    fun effectiveMaxTokens(baseMaxTokens: Int, level: DeepThinkLevel): Int {
        val base = baseMaxTokens.coerceIn(16, 4096)
        if (level == DeepThinkLevel.AUTO) return base
        return maxOf(base, level.minimumOutputTokens).coerceAtMost(4096)
    }
}

class DeepThinkStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getLevel(agentId: String): DeepThinkLevel =
        DeepThinkLevel.fromStoredValue(preferences.getInt(levelKey(agentId), DeepThinkLevel.HIGH.storedValue))

    fun setLevel(agentId: String, level: DeepThinkLevel) {
        preferences.edit().putInt(levelKey(agentId), level.storedValue).apply()
    }

    fun isEnabled(agentId: String): Boolean =
        preferences.getBoolean(enabledKey(agentId), false)

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
