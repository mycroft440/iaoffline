package com.example.ialocal.ads

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppOpenAdPacingTest {
    private val hour = 60 * 60_000L
    private val now = 100 * hour

    @Test
    fun firstOpeningsNeverShowTheAd() {
        assertFalse(AppOpenAdPacing.canShow(now, opening = 1, lastShownAtMs = 0L, lastDownloadAdAtMs = 0L))
        assertFalse(AppOpenAdPacing.canShow(now, opening = 2, lastShownAtMs = 0L, lastDownloadAdAtMs = 0L))
        assertTrue(AppOpenAdPacing.canShow(now, opening = 3, lastShownAtMs = 0L, lastDownloadAdAtMs = 0L))
    }

    @Test
    fun atMostOnceEveryFourHours() {
        assertFalse(AppOpenAdPacing.canShow(now, opening = 9, lastShownAtMs = now - 3 * hour, lastDownloadAdAtMs = 0L))
        assertTrue(AppOpenAdPacing.canShow(now, opening = 9, lastShownAtMs = now - 4 * hour, lastDownloadAdAtMs = 0L))
    }

    @Test
    fun notRightAfterTheDownloadAd() {
        assertFalse(AppOpenAdPacing.canShow(now, opening = 9, lastShownAtMs = 0L, lastDownloadAdAtMs = now - 60_000L))
        assertTrue(
            AppOpenAdPacing.canShow(now, opening = 9, lastShownAtMs = 0L, lastDownloadAdAtMs = now - DownloadAdPacing.MIN_GAP_MS),
        )
    }

    @Test
    fun loadsOnlyWhenTheAdCouldStillBeShownBeforeItExpires() {
        assertFalse(AppOpenAdPacing.worthLoading(now, nextOpening = 2, lastShownAtMs = 0L))
        assertTrue(AppOpenAdPacing.worthLoading(now, nextOpening = 3, lastShownAtMs = 0L))
        // Shown a minute ago: an ad loaded now would expire before the four hours pass.
        assertFalse(AppOpenAdPacing.worthLoading(now, nextOpening = 9, lastShownAtMs = now - 60_000L))
        assertTrue(AppOpenAdPacing.worthLoading(now, nextOpening = 9, lastShownAtMs = now - hour))
    }

    @Test
    fun loadedAdIsReplacedBeforeItExpires() {
        assertTrue(AppOpenAdPacing.isFresh(now + 3 * hour, now))
        assertFalse(AppOpenAdPacing.isFresh(now + 4 * hour, now))
    }
}
