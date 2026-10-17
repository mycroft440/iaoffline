package com.example.ialocal.diagnostics

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import com.example.ialocal.runtime.ModelCrashGuard
import java.text.DateFormat
import java.util.Date

/** Why the app's previous run ended unexpectedly, as recorded by Android. */
data class LastExitReport(val summary: String, val details: String)

/**
 * Reads Android's record of how the previous process ended. A native crash, an uncaught exception or
 * a kill for lack of memory is reported once, so the user can see what happened and share it.
 */
class LastExitReporter(
    context: Context,
    private val logger: AiEventLogger? = null,
    private val crashGuard: ModelCrashGuard? = null,
) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Returns the latest unexpected exit not reported yet, or null. Does disk I/O. */
    fun takeUnreportedExit(): LastExitReport? {
        // Lowers the memory use of a model that was loading or answering when the app died.
        val recovery = crashGuard?.recoverInterruptedRun()
        val activityManager = appContext.getSystemService(ActivityManager::class.java) ?: return null
        val latest = runCatching {
            activityManager.getHistoricalProcessExitReasons(null, 0, 1).firstOrNull()
        }.getOrNull() ?: return null

        val lastSeen = preferences.getLong(KEY_LAST_SEEN, 0L)
        if (latest.timestamp <= lastSeen) return null
        preferences.edit().putLong(KEY_LAST_SEEN, latest.timestamp).apply()
        // The first run after installing this version must not report older history.
        if (lastSeen == 0L) return null

        val summary = when (latest.reason) {
            ApplicationExitInfo.REASON_CRASH_NATIVE ->
                "O app foi fechado por uma falha no motor de IA (código nativo)."
            ApplicationExitInfo.REASON_CRASH ->
                "O app foi fechado por um erro inesperado."
            ApplicationExitInfo.REASON_LOW_MEMORY -> {
                // Android closes apps in the background to free memory all the time; that is not a
                // failure of the app unless it was in use or running a model.
                if (recovery == null && latest.importance >= ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE) {
                    logger?.info("APP_EXIT", "O Android encerrou o app em segundo plano para liberar memória")
                    return null
                }
                "O Android fechou o app por falta de memória."
            }
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE ->
                "O Android fechou o app por uso excessivo de recursos."
            ApplicationExitInfo.REASON_ANR ->
                "O app parou de responder e foi fechado."
            ApplicationExitInfo.REASON_SIGNALED ->
                "O sistema encerrou o app (sinal ${latest.status})."
            else -> return null
        } + recovery?.let { " " + recoveryNotice(it) }.orEmpty()

        val details = buildString {
            appendLine(summary)
            appendLine("Quando: ${DateFormat.getDateTimeInstance().format(Date(latest.timestamp))}")
            appendLine("Motivo Android: ${latest.reason} · status ${latest.status} · importância ${latest.importance}")
            latest.description?.takeIf { it.isNotBlank() }?.let { appendLine("Descrição: $it") }
            appendLine("Memória no momento: PSS ${latest.pss / 1024} MB · RSS ${latest.rss / 1024} MB")
            // Native tombstones are binary protobuf on Android 12+; only ANR traces are text.
            val trace = when (latest.reason) {
                ApplicationExitInfo.REASON_ANR -> runCatching {
                    latest.traceInputStream?.bufferedReader()?.useLines { lines ->
                        lines.take(MAX_TRACE_LINES).joinToString("\n")
                    }
                }.getOrNull()
                ApplicationExitInfo.REASON_CRASH -> preferences.getString(KEY_UNCAUGHT, null)
                else -> null
            }
            if (!trace.isNullOrBlank()) {
                appendLine()
                appendLine("Rastro:")
                append(trace)
            }
        }.trim()

        logger?.error("APP_EXIT", details)
        return LastExitReport(summary, details)
    }

    private fun recoveryNotice(recovery: ModelCrashGuard.Recovery): String {
        val moment = if (recovery.phase == ModelCrashGuard.Phase.LOAD) "carregava" else "respondia com"
        return "Isso aconteceu enquanto o app $moment ${recovery.modelName}. Para não se repetir, " +
            "esse modelo passa a usar menos memória: pesos lidos do armazenamento e " +
            if (recovery.level >= ModelCrashGuard.MAX_LEVEL) "o menor contexto." else "metade do contexto."
    }

    /** Keeps the stack of an uncaught exception, which Android does not include in its record. */
    fun installUncaughtExceptionRecorder() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val stack = throwable.stackTraceToString().lineSequence().take(MAX_TRACE_LINES).joinToString("\n")
                // commit(): the process is about to die, an async apply() would be lost.
                preferences.edit().putString(KEY_UNCAUGHT, "Thread ${thread.name}\n$stack").commit()
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    private companion object {
        const val PREFS_NAME = "last_exit_report"
        const val KEY_LAST_SEEN = "last_seen_timestamp"
        const val KEY_UNCAUGHT = "last_uncaught_exception"
        const val MAX_TRACE_LINES = 60
    }
}
