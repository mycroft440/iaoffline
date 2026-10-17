package com.example.ialocal.runtime

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Process
import com.example.ialocal.diagnostics.AiEventLogger
import java.util.UUID

/**
 * Remembers which model is loading or answering. If Android kills the app for lack of memory, or
 * the native engine crashes, in the middle of it, later loads of that model use less memory
 * (weights read from storage and a shorter context) instead of crashing the same way again.
 */
class ModelCrashGuard(context: Context, private val logger: AiEventLogger? = null) {
    enum class Phase { LOAD, GENERATION }

    /** A run of [modelName] that the previous process did not survive. */
    data class Recovery(val modelId: String, val modelName: String, val phase: Phase, val level: Int)

    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private var checked = false
    private var recovery: Recovery? = null

    /** Written with commit(): the mark must already be on disk if the process dies right after. */
    fun begin(modelId: String, modelName: String, phase: Phase) {
        // A mark left by a dead process must be read before this one replaces it.
        recoverInterruptedRun()
        preferences.edit()
            .putString(KEY_MODEL, modelId)
            .putString(KEY_NAME, modelName)
            .putString(KEY_PHASE, phase.name)
            .putString(KEY_PROCESS, PROCESS_TOKEN)
            .putInt(KEY_PID, Process.myPid())
            .putLong(KEY_STARTED_AT, System.currentTimeMillis())
            .commit()
    }

    fun end() {
        preferences.edit().remove(KEY_MODEL).remove(KEY_PROCESS).commit()
    }

    /** 0 normally; raised by each crash, up to [MAX_LEVEL]. */
    fun safeLevel(modelId: String): Int = preferences.getInt(levelKey(modelId), 0)

    /** A deleted model starts over if it is installed again. */
    fun forget(modelId: String) {
        preferences.edit().remove(levelKey(modelId)).apply()
    }

    /**
     * Checks, once per process, whether the previous process died during a load or an answer, using
     * Android's record of how it ended, and lowers that model's memory use if it did.
     */
    @Synchronized
    fun recoverInterruptedRun(): Recovery? {
        if (checked) return recovery
        checked = true
        val modelId = preferences.getString(KEY_MODEL, null) ?: return null
        if (preferences.getString(KEY_PROCESS, null) == PROCESS_TOKEN) return null
        val modelName = preferences.getString(KEY_NAME, null) ?: modelId
        val phase = runCatching { Phase.valueOf(preferences.getString(KEY_PHASE, null).orEmpty()) }
            .getOrDefault(Phase.LOAD)
        val pid = preferences.getInt(KEY_PID, 0)
        val startedAt = preferences.getLong(KEY_STARTED_AT, 0L)
        end()

        val exit = runCatching {
            appContext.getSystemService(ActivityManager::class.java)
                ?.getHistoricalProcessExitReasons(null, pid, 1)
                ?.firstOrNull()
        }.getOrNull()
        if (!diedFromMemoryOrCrash(exit?.reason, exit?.timestamp, startedAt)) {
            logger?.info("MODEL_SAFETY", "Execução anterior de $modelName interrompida sem falta de memória (motivo ${exit?.reason})")
            return null
        }
        val level = nextLevel(safeLevel(modelId))
        preferences.edit().putInt(levelKey(modelId), level).apply()
        logger?.error(
            "MODEL_SAFETY",
            "O app foi fechado durante ${if (phase == Phase.LOAD) "o carregamento" else "uma resposta"} de " +
                "$modelName (motivo ${exit?.reason}); próximos carregamentos usam o nível de economia $level",
        )
        return Recovery(modelId, modelName, phase, level).also { recovery = it }
    }

    private fun levelKey(modelId: String) = "level_$modelId"

    companion object {
        const val MAX_LEVEL = 2

        /** Changes on every process start, so a mark left by a dead process is never mistaken for ours. */
        private val PROCESS_TOKEN = UUID.randomUUID().toString()

        /** Ends that more memory pressure or a native fault explain; a user's force stop is not one. */
        private val MEMORY_OR_CRASH_REASONS = setOf(
            ApplicationExitInfo.REASON_LOW_MEMORY,
            ApplicationExitInfo.REASON_CRASH_NATIVE,
            ApplicationExitInfo.REASON_SIGNALED,
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
            ApplicationExitInfo.REASON_ANR,
        )

        internal fun diedFromMemoryOrCrash(reason: Int?, exitTimestamp: Long?, startedAt: Long): Boolean =
            reason != null && exitTimestamp != null && exitTimestamp >= startedAt && reason in MEMORY_OR_CRASH_REASONS

        internal fun nextLevel(level: Int): Int = (level + 1).coerceIn(1, MAX_LEVEL)

        private const val PREFS_NAME = "model_crash_guard"
        private const val KEY_MODEL = "running_model_id"
        private const val KEY_NAME = "running_model_name"
        private const val KEY_PHASE = "running_phase"
        private const val KEY_PROCESS = "running_process"
        private const val KEY_PID = "running_pid"
        private const val KEY_STARTED_AT = "running_started_at"
    }
}
