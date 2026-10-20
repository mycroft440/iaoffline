package com.example.ialocal.models

import java.io.IOException

/**
 * Retries transient GGUF-transfer failures once immediately, then every 30 seconds.
 * Applies to automatic network retry only: cancelled downloads, a failed SHA check,
 * invalid HTTP requests and invalid device/storage preconditions are never retried forever.
 */
internal object ModelDownloadRetryPolicy {
    const val RETRY_INTERVAL_MS = 30_000L

    /** Number of failed transfers since the download was last successfully running. */
    fun delayBeforeRetryMs(failures: Int): Long =
        if (failures <= 1) 0L else RETRY_INTERVAL_MS

    /** Bound the count so an hours-long disconnected phone cannot overflow Int. */
    fun nextFailureCount(previous: Int): Int = if (previous >= 2) 2 else (previous + 1).coerceAtLeast(1)

    /** Local storage failures require user action, not repeated network connections. */
    fun isStorageFailure(error: IOException): Boolean {
        val markers = listOf(
            "enospc",
            "no space left",
            "not enough space",
            "permission denied",
            "eacces",
            "read-only file system",
            "erofs",
            "storage is full",
        )
        var cause: Throwable? = error
        repeat(5) {
            val current = cause ?: return false
            if (markers.any { current.message.orEmpty().contains(it, ignoreCase = true) }) return true
            cause = current.cause
        }
        return false
    }
}
