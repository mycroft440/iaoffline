package com.example.ialocal.data

import android.content.Context
import com.example.ialocal.runtime.PerformanceLevel
import com.example.ialocal.runtime.RuntimeTuning
import com.example.ialocal.runtime.TuningSwitch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** When the app frees the loaded AI after leaving the foreground ("Manter a IA carregada ao sair do app"). */
enum class KeepLoaded {
    /** Decided by the phone's RAM: [TEN_MINUTES] from 12 GB, [RELEASE] below. */
    AUTO,
    /** Freed as soon as Android says memory is short, as before this setting existed. */
    RELEASE,
    /** Kept for ten minutes, then freed if memory is short. */
    TEN_MINUTES,
    /** Kept until Android itself stops the AI process. */
    UNTIL_ANDROID_NEEDS,
}

/**
 * Settings › Desempenho da IA: what the AI may trade RAM for speed for ([RuntimeTuning], sent to the
 * AI process), when the loaded AI is freed and whether it loads as the app opens.
 */
class PerformanceSettingsRepository(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _tuning = MutableStateFlow(readTuning())
    /** Without [RuntimeTuning.performanceBlocked], which the crash guard adds. */
    val tuning: StateFlow<RuntimeTuning> = _tuning.asStateFlow()

    private val _keepLoaded = MutableStateFlow(enumOf(prefs.getString(KEY_KEEP_LOADED, null), KeepLoaded.AUTO))
    val keepLoaded: StateFlow<KeepLoaded> = _keepLoaded.asStateFlow()

    private val _preload = MutableStateFlow(enumOf(prefs.getString(KEY_PRELOAD, null), TuningSwitch.AUTO))
    /** "Carregar a IA ao abrir o app": automatic from 12 GB of RAM. */
    val preload: StateFlow<TuningSwitch> = _preload.asStateFlow()

    fun setPerformance(level: PerformanceLevel) = updateTuning { it.copy(performance = level) }

    fun setLongContext(choice: TuningSwitch) = updateTuning { it.copy(longContext = choice) }

    fun setRewind(choice: TuningSwitch) = updateTuning { it.copy(rewind = choice) }

    fun setLongPromptMoe(choice: TuningSwitch) = updateTuning { it.copy(longPromptMoe = choice) }

    fun setReadyConversations(count: Int) = updateTuning {
        it.copy(readyConversations = count.coerceIn(RuntimeTuning.READY_CONVERSATION_CHOICES.min(), RuntimeTuning.READY_CONVERSATION_CHOICES.max()))
    }

    fun setKeepLoaded(choice: KeepLoaded) {
        prefs.edit().putString(KEY_KEEP_LOADED, choice.name).apply()
        _keepLoaded.value = choice
    }

    fun setPreload(choice: TuningSwitch) {
        prefs.edit().putString(KEY_PRELOAD, choice.name).apply()
        _preload.value = choice
    }

    private fun updateTuning(change: (RuntimeTuning) -> RuntimeTuning) {
        val tuning = change(_tuning.value)
        prefs.edit()
            .putString(KEY_PERFORMANCE, tuning.performance.name)
            .putString(KEY_LONG_CONTEXT, tuning.longContext.name)
            .putString(KEY_REWIND, tuning.rewind.name)
            .putString(KEY_LONG_PROMPT_MOE, tuning.longPromptMoe.name)
            .putInt(KEY_READY_CONVERSATIONS, tuning.readyConversations)
            .apply()
        _tuning.value = tuning
    }

    private fun readTuning() = RuntimeTuning(
        performance = enumOf(prefs.getString(KEY_PERFORMANCE, null), PerformanceLevel.AUTO),
        longContext = enumOf(prefs.getString(KEY_LONG_CONTEXT, null), TuningSwitch.AUTO),
        rewind = enumOf(prefs.getString(KEY_REWIND, null), TuningSwitch.AUTO),
        longPromptMoe = enumOf(prefs.getString(KEY_LONG_PROMPT_MOE, null), TuningSwitch.AUTO),
        readyConversations = prefs.getInt(KEY_READY_CONVERSATIONS, RuntimeTuning.DEFAULT_READY_CONVERSATIONS),
    )

    private inline fun <reified T : Enum<T>> enumOf(name: String?, default: T): T =
        enumValues<T>().firstOrNull { it.name == name } ?: default

    private companion object {
        const val PREFS_NAME = "ai_performance"
        const val KEY_PERFORMANCE = "performance"
        const val KEY_LONG_CONTEXT = "long_context"
        const val KEY_REWIND = "rewind"
        const val KEY_LONG_PROMPT_MOE = "long_prompt_moe"
        const val KEY_READY_CONVERSATIONS = "ready_conversations"
        const val KEY_KEEP_LOADED = "keep_loaded"
        const val KEY_PRELOAD = "preload"
    }
}
