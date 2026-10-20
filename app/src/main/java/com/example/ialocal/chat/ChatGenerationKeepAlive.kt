package com.example.ialocal.chat

/**
 * Foreground generation outlives an Activity/Composable and must keep CPU sleep disabled even
 * when an answer takes longer than the previous one-hour wake lock timeout.
 *
 * The OS can still kill the process for critical memory pressure or user force-stop; this only
 * addresses avoidable suspension / background process priority losses.
 */
internal object ChatGenerationKeepAlive {
    /** Android automatically releases the lease if the service unexpectedly dies. */
    const val WAKE_LOCK_LEASE_MS = 15L * 60L * 1000L

    /** Refresh well before the lease expires, while at least one answer is ongoing. */
    const val WAKE_LOCK_RENEW_MS = 5L * 60L * 1000L

    /** Do not stop a just-started service before the sender publishes its active generation. */
    const val SERVICE_IDLE_GRACE_MS = 1_500L

    fun needsCpuWakeLock(activeGenerations: Int): Boolean = activeGenerations > 0
}
