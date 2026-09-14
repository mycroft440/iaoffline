package com.example.ialocal.models

import android.content.Context
import com.example.ialocal.data.AiModelEntity

enum class DeepThinkLevel(val storedValue: Int, val label: String, val minimumOutputTokens: Int) {
    AUTO(0, "Automático", 0),
    LOW(1, "Baixo", 1536),
    MEDIUM(2, "Médio", 2560),
    HIGH(3, "Alto", 4096),
    ;

    companion object {
        fun fromStoredValue(value: Int): DeepThinkLevel =
            entries.firstOrNull { it.storedValue == value } ?: AUTO
    }
}

enum class DeepThinkControlMode {
    NONE,
    SOFT_SWITCH,
    REASONING_MODEL,
}

data class DeepThinkCapability(
    val mode: DeepThinkControlMode,
    val description: String,
) {
    val supported: Boolean get() = mode != DeepThinkControlMode.NONE
}

object DeepThinkSupport {
    fun capability(model: AiModelEntity): DeepThinkCapability {
        val identity = "${model.name} ${model.apiModelId} ${model.architecture.orEmpty()}".lowercase()

        return when {
            identity.contains("qwen3-coder") || identity.contains("qwen3 coder") -> DeepThinkCapability(
                DeepThinkControlMode.NONE,
                "Esta variante Qwen Coder é somente non-thinking.",
            )
            identity.contains("qwen3") || identity.contains("qwen-3") -> DeepThinkCapability(
                DeepThinkControlMode.SOFT_SWITCH,
                "Este modelo aceita controle de modo de raciocínio por instrução.",
            )
            identity.contains("deepseek-r1") || identity.contains("deepseek r1") -> DeepThinkCapability(
                DeepThinkControlMode.REASONING_MODEL,
                "Este é um modelo de raciocínio; o nível controla a profundidade solicitada e o orçamento de saída.",
            )
            identity.contains("phi-4-reasoning") || identity.contains("phi 4 reasoning") -> DeepThinkCapability(
                DeepThinkControlMode.REASONING_MODEL,
                "Este é um modelo Phi de raciocínio; o nível controla a profundidade solicitada e o orçamento de saída.",
            )
            else -> DeepThinkCapability(
                DeepThinkControlMode.NONE,
                "DeepThink não foi detectado para este modelo.",
            )
        }
    }

    fun instruction(model: AiModelEntity, level: DeepThinkLevel): String {
        if (level == DeepThinkLevel.AUTO) return ""
        val capability = capability(model)
        if (!capability.supported) return ""

        val depth = when (level) {
            DeepThinkLevel.AUTO -> return ""
            DeepThinkLevel.LOW -> "Use raciocínio breve e objetivo; verifique apenas os passos essenciais antes da resposta final."
            DeepThinkLevel.MEDIUM -> "Use raciocínio cuidadoso em múltiplas etapas; confira premissas, cálculos e possíveis erros antes da resposta final."
            DeepThinkLevel.HIGH -> "Use raciocínio aprofundado; explore alternativas, valide premissas e resultados e faça uma revisão final rigorosa antes de responder."
        }

        return when (capability.mode) {
            DeepThinkControlMode.SOFT_SWITCH -> "/think\n$depth"
            DeepThinkControlMode.REASONING_MODEL -> depth
            DeepThinkControlMode.NONE -> ""
        }
    }

    fun effectiveMaxTokens(baseMaxTokens: Int, level: DeepThinkLevel): Int {
        val base = baseMaxTokens.coerceIn(16, 4096)
        if (level == DeepThinkLevel.AUTO) return base
        return maxOf(base, level.minimumOutputTokens).coerceAtMost(4096)
    }
}

class DeepThinkStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getLevel(agentId: String): DeepThinkLevel =
        DeepThinkLevel.fromStoredValue(preferences.getInt(key(agentId), DeepThinkLevel.AUTO.storedValue))

    fun setLevel(agentId: String, level: DeepThinkLevel) {
        preferences.edit().putInt(key(agentId), level.storedValue).apply()
    }

    fun clear(agentId: String) {
        preferences.edit().remove(key(agentId)).apply()
    }

    private fun key(agentId: String): String = "level_$agentId"

    companion object {
        private const val PREFS_NAME = "deepthink_agent_settings"
    }
}
