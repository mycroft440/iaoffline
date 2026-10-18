package com.example.ialocal.ads

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadAdPacingTest {
    @Test
    fun firstDownloadShowsTheAd() {
        assertTrue(DownloadAdPacing.canShow(nowMs = 1_000_000L, lastShownAtMs = 0L))
    }

    @Test
    fun downloadsPickedInARowShareOneAd() {
        val shownAt = 10_000_000L
        assertFalse(DownloadAdPacing.canShow(shownAt + 30_000L, shownAt))
        assertFalse(DownloadAdPacing.canShow(shownAt + DownloadAdPacing.MIN_GAP_MS - 1, shownAt))
        assertTrue(DownloadAdPacing.canShow(shownAt + DownloadAdPacing.MIN_GAP_MS, shownAt))
    }

    @Test
    fun clockMovedBackwardsDoesNotBlockAds() {
        assertTrue(DownloadAdPacing.canShow(nowMs = 5_000L, lastShownAtMs = 10_000_000L))
    }

    @Test
    fun loadedAdIsReplacedBeforeItExpires() {
        val loadedAt = 10_000_000L
        assertTrue(DownloadAdPacing.isFresh(loadedAt + 30 * 60_000L, loadedAt))
        assertFalse(DownloadAdPacing.isFresh(loadedAt + DownloadAdPacing.AD_LIFETIME_MS, loadedAt))
        assertFalse(DownloadAdPacing.isFresh(loadedAt - 1, loadedAt))
    }
}
