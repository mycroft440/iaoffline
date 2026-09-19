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

object DeepThinkSupport {
    fun capability(model: AiModelEntity): DeepThinkCapability {
        val identity = "${model.name} ${model.apiModelId} ${model.architecture.orEmpty()}".lowercase()

        return when {
            identity.contains("qwen3-coder") || identity.contains("qwen3 coder") -> DeepThinkCapability(
                DeepThinkControlMode.NONE,
                "Esta variante Qwen Coder não oferece modo DeepThink.",
            )
            identity.contains("deepseek-r1") || identity.contains("deepseek r1") -> DeepThinkCapability(
                DeepThinkControlMode.REASONING_MODEL,
                "Modelo compatível com DeepThink. O nível ajusta o orçamento de geração disponível.",
            )
            identity.contains("phi-4-reasoning") || identity.contains("phi 4 reasoning") -> DeepThinkCapability(
                DeepThinkControlMode.REASONING_MODEL,
                "Modelo compatível com DeepThink. O nível ajusta o orçamento de geração disponível.",
            )
            identity.contains("qwen3") || identity.contains("qwen-3") -> DeepThinkCapability(
                DeepThinkControlMode.HYBRID_THINKING,
                "Modelo híbrido compatível com DeepThink. O nível ajusta o orçamento de geração disponível.",
            )
            else -> DeepThinkCapability(
                DeepThinkControlMode.NONE,
                "DeepThink não foi detectado para este modelo.",
            )
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
