package com.example.ialocal.ads

/**
 * Avoid re-requesting an ad just because users move between Wikipedia, compiler downloads and
 * profile settings. A shared persistent last-preload timestamp covers all three screens.
 *
 * This throttles only automatic preloading when entering those screens. A user who explicitly
 * chooses an eligible rewarded action may request an ad on demand at any time.
 */
internal object RewardedEntryPacing {
    const val ENTRY_PRELOAD_INTERVAL_MS = 30 * 60_000L

    fun canPreload(nowMs: Long, lastPreloadedAtMs: Long): Boolean {
        if (lastPreloadedAtMs <= 0L) return true
        val elapsed = nowMs - lastPreloadedAtMs
        return elapsed < 0 || elapsed >= ENTRY_PRELOAD_INTERVAL_MS
    }
}
