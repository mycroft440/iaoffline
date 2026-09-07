package com.example.ialocal.diagnostics

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Small persistent diagnostic log for the import -> runtime -> API critical path. */
class AiEventLogger(context: Context) {
    data class Event(
        val timestamp: Long,
        val stage: String,
        val message: String,
        val isError: Boolean = false,
    )

    private val logDir = File(context.filesDir, "diagnostics").apply { mkdirs() }
    private val logFile = File(logDir, "ai-runtime.log")
    private val _events = MutableStateFlow<List<Event>>(emptyList())
    val events: StateFlow<List<Event>> = _events.asStateFlow()
    private val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun info(stage: String, message: String) = append(Event(System.currentTimeMillis(), stage, message, false))

    @Synchronized
    fun error(stage: String, message: String, throwable: Throwable? = null) {
        val detail = buildString {
            append(message)
            throwable?.let {
                append(" | ")
                append(it::class.java.simpleName)
                it.message?.takeIf(String::isNotBlank)?.let { m -> append(": ").append(m) }
            }
        }
        append(Event(System.currentTimeMillis(), stage, detail, true))
    }

    fun logFilePath(): String = logFile.absolutePath

    @Synchronized
    fun tail(limit: Int = 80): List<Event> = _events.value.takeLast(limit.coerceIn(1, 500))

    private fun append(event: Event) {
        val androidTag = "IALocal/${event.stage.take(18)}"
        if (event.isError) Log.e(androidTag, event.message) else Log.i(androidTag, event.message)

        val updated = (_events.value + event).takeLast(MAX_MEMORY_EVENTS)
        _events.value = updated

        runCatching {
            if (logFile.length() > MAX_FILE_BYTES) rotate()
            logFile.appendText(
                "${formatter.format(Date(event.timestamp))} ${if (event.isError) "ERROR" else "INFO"} " +
                    "[${event.stage}] ${event.message}\n"
            )
        }
    }

    private fun rotate() {
        val old = File(logDir, "ai-runtime.previous.log")
        if (old.exists()) old.delete()
        logFile.renameTo(old)
    }

    companion object {
        private const val MAX_MEMORY_EVENTS = 200
        private const val MAX_FILE_BYTES = 2L * 1024 * 1024
    }
}
