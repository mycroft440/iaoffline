package com.example.ialocal.models

import com.example.ialocal.runtime.RuntimeLimits
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/**
 * A profile the chat answers with: one of the [BuiltInProfile]s or one the user wrote in
 * Configurações → Perfis de I.A. Every installed model gets one agent per profile, named after it.
 */
data class AiProfile(
    val id: String,
    val displayName: String,
    val systemPrompt: String,
    val description: String,
    val maxTokens: Int,
    /** The built-in profile this is, or null for one the user created. */
    val builtIn: BuiltInProfile? = null,
) {
    val isCustom: Boolean get() = builtIn == null

    /** One line about the profile: its description, or the start of its instructions. */
    val summary: String get() = description.ifBlank { systemPrompt }

    companion object {
        val BUILT_IN: List<AiProfile> = BuiltInProfile.entries.map { profile ->
            AiProfile(
                // The enum name is what the selected profile was saved as before custom profiles.
                id = profile.name,
                displayName = profile.displayName,
                systemPrompt = profile.systemPrompt,
                description = profile.description,
                maxTokens = profile.maxTokens,
                builtIn = profile,
            )
        }

        val DEFAULT: AiProfile = of(BuiltInProfile.PROGRAMMER)

        fun of(profile: BuiltInProfile): AiProfile = BUILT_IN.first { it.builtIn == profile }
    }
}

/** Rules and storage format of the profiles the user creates. */
object CustomProfiles {
    const val MAX_NAME_LENGTH = 40
    const val MAX_DESCRIPTION_LENGTH = 120
    const val MAX_PROMPT_LENGTH = 4000
    private const val ID_PREFIX = "custom-"

    /**
     * Names that already belong to an agent of every model: the built-in profiles, and the old
     * names the app still renames to them.
     */
    private val RESERVED_NAMES = AiProfile.BUILT_IN.map { it.displayName } +
        listOf(ModelRepository.LEGACY_ENGINEER_NAME, ModelRepository.LEGACY_UNCENSORED_NAME)

    fun create(name: String, description: String, systemPrompt: String): AiProfile = AiProfile(
        id = ID_PREFIX + UUID.randomUUID().toString(),
        displayName = name.trim(),
        systemPrompt = systemPrompt.trim(),
        description = description.trim(),
        maxTokens = RuntimeLimits.MAX_OUTPUT_TOKENS,
    )

    /**
     * Why a profile with these fields cannot be saved, or null when it can. [editingId] is the
     * profile being changed, whose current name does not count as taken.
     */
    fun validate(
        name: String,
        description: String,
        systemPrompt: String,
        existing: List<AiProfile>,
        editingId: String? = null,
    ): String? {
        val cleanName = name.trim()
        return when {
            cleanName.isEmpty() -> "Dê um nome ao perfil."
            cleanName.length > MAX_NAME_LENGTH -> "O nome pode ter até $MAX_NAME_LENGTH caracteres."
            RESERVED_NAMES.any { it.equals(cleanName, ignoreCase = true) } ->
                "Esse nome é de um pré-perfil. Escolha outro."
            existing.any { it.id != editingId && it.displayName.equals(cleanName, ignoreCase = true) } ->
                "Já existe um perfil com esse nome."
            description.trim().length > MAX_DESCRIPTION_LENGTH ->
                "A descrição pode ter até $MAX_DESCRIPTION_LENGTH caracteres."
            systemPrompt.isBlank() -> "Escreva as instruções do perfil."
            systemPrompt.trim().length > MAX_PROMPT_LENGTH ->
                "As instruções podem ter até $MAX_PROMPT_LENGTH caracteres."
            else -> null
        }
    }

    fun encode(profiles: List<AiProfile>): String = JSONArray().apply {
        profiles.filter { it.isCustom }.forEach { profile ->
            put(
                JSONObject()
                    .put("id", profile.id)
                    .put("name", profile.displayName)
                    .put("description", profile.description)
                    .put("prompt", profile.systemPrompt)
            )
        }
    }.toString()

    /** The saved profiles; an unreadable entry is skipped instead of losing the others. */
    fun decode(json: String?): List<AiProfile> {
        if (json.isNullOrBlank()) return emptyList()
        val array = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            runCatching {
                val entry = array.getJSONObject(index)
                AiProfile(
                    id = entry.getString("id"),
                    displayName = entry.getString("name"),
                    systemPrompt = entry.getString("prompt"),
                    description = entry.optString("description"),
                    maxTokens = RuntimeLimits.MAX_OUTPUT_TOKENS,
                )
            }.getOrNull()?.takeIf { it.displayName.isNotBlank() && it.systemPrompt.isNotBlank() }
        }.distinctBy { it.displayName.lowercase() }
    }
}
