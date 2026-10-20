package com.example.ialocal.models

import com.example.ialocal.data.AiModelEntity

/**
 * The company behind an installed model: its catalog entry, or for an imported GGUF a guess from
 * its name and architecture. Null when nothing matches.
 */
fun AiModelEntity.provider(): ModelProvider? =
    (
        ModelCatalog.entries.firstOrNull { apiModelId.startsWith(it.apiIdPrefix) }
            ?: ModelCatalog.entries.firstOrNull { it.displayName.equals(name, ignoreCase = true) }
        )?.provider
        ?: providerFromName("$name ${architecture.orEmpty()}")

/** Guesses the company from a model's name or architecture. */
fun providerFromName(name: String): ModelProvider? {
    val text = name.lowercase()
    return when {
        Regex("(^|[^a-z])(ling|ring)[- ](mini|lite|flash)").containsMatchIn(text) || "inclusionai" in text -> ModelProvider.INCLUSION_AI
        "smallthinker" in text || "powerinfer" in text -> ModelProvider.POWERINFER
        // GigaChat 3 and GLM-4.7-Flash use DeepSeek's "deepseek2" architecture, so they come first.
        Regex("(^|[^a-z])glm[- ]?\\d").containsMatchIn(text) || "zhipu" in text -> ModelProvider.ZAI
        "north mini" in text || "north-mini" in text || "cohere" in text -> ModelProvider.COHERE
        "laguna" in text || "poolside" in text -> ModelProvider.POOLSIDE
        "gigachat" in text -> ModelProvider.SBER
        "trinity" in text || "arcee" in text || "afmoe" in text -> ModelProvider.ARCEE
        "deepseek" in text -> ModelProvider.DEEPSEEK
        // Bonsai is built on Qwen, so it must be recognized before the Qwen check.
        "bonsai" in text || "prism" in text -> ModelProvider.PRISMML
        "minicpm" in text -> ModelProvider.OPENBMB
        "mellum" in text -> ModelProvider.JETBRAINS
        "ornith" in text -> ModelProvider.ORNITH
        "qwen" in text || "alibaba" in text -> ModelProvider.ALIBABA
        "llama" in text || "meta" in text -> ModelProvider.META
        "gemma" in text || "google" in text -> ModelProvider.GOOGLE
        "mistral" in text || "ministral" in text || "mixtral" in text -> ModelProvider.MISTRAL
        "phi" in text || "microsoft" in text -> ModelProvider.MICROSOFT
        "granite" in text || "ibm" in text -> ModelProvider.IBM
        "nemotron" in text || "nvidia" in text -> ModelProvider.NVIDIA
        "gpt-oss" in text || "gpt_oss" in text || "openai" in text -> ModelProvider.OPENAI
        "olmo" in text || "ai2" in text -> ModelProvider.AI2
        "liquid" in text || Regex("(^|[^a-z])lfm([^a-z]|$)").containsMatchIn(text) -> ModelProvider.LIQUID
        else -> null
    }
}
