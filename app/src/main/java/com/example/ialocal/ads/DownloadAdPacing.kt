package com.example.ialocal.ads

/**
 * When the full-screen ad shown at the start of a download may appear. People often pick several
 * AIs in a row, so one ad covers every download started within [MIN_GAP_MS] of it. A loaded ad
 * expires after an hour; it is replaced a little before that.
 */
object DownloadAdPacing {
    const val MIN_GAP_MS = 3 * 60_000L
    const val AD_LIFETIME_MS = 55 * 60_000L

    /** [lastShownAtMs] is 0 when no ad was shown yet; a clock moved backwards does not block ads. */
    fun canShow(nowMs: Long, lastShownAtMs: Long): Boolean {
        val elapsed = nowMs - lastShownAtMs
        return elapsed < 0 || elapsed >= MIN_GAP_MS
    }

    fun isFresh(nowMs: Long, loadedAtMs: Long): Boolean = nowMs - loadedAtMs in 0 until AD_LIFETIME_MS
}
