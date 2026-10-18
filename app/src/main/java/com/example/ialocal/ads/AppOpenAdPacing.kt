package com.example.ialocal.ads

/**
 * When the ad shown on coming back to the app may appear: not in the first openings, at most once
 * every [MIN_GAP_MS], and not within minutes of the download ad. A loaded app open ad expires after
 * four hours; it is replaced a little before that.
 */
object AppOpenAdPacing {
    /** Openings count every time the app comes to the foreground, the first launch included. */
    const val FIRST_AT_OPENING = 3
    const val MIN_GAP_MS = 4 * 60 * 60_000L
    const val AD_LIFETIME_MS = 3 * 60 * 60_000L + 50 * 60_000L

    fun canShow(nowMs: Long, opening: Int, lastShownAtMs: Long, lastDownloadAdAtMs: Long): Boolean =
        opening >= FIRST_AT_OPENING &&
            hasPassed(nowMs, lastShownAtMs, MIN_GAP_MS) &&
            DownloadAdPacing.canShow(nowMs, lastDownloadAdAtMs)

    /** Whether an ad loaded now could still be shown, at [nextOpening], before it expires. */
    fun worthLoading(nowMs: Long, nextOpening: Int, lastShownAtMs: Long): Boolean =
        nextOpening >= FIRST_AT_OPENING && hasPassed(nowMs + AD_LIFETIME_MS, lastShownAtMs, MIN_GAP_MS)

    fun isFresh(nowMs: Long, loadedAtMs: Long): Boolean = nowMs - loadedAtMs in 0 until AD_LIFETIME_MS

    /** A clock moved backwards does not block ads. */
    private fun hasPassed(nowMs: Long, sinceMs: Long, gapMs: Long): Boolean {
        val elapsed = nowMs - sinceMs
        return elapsed < 0 || elapsed >= gapMs
    }
}
