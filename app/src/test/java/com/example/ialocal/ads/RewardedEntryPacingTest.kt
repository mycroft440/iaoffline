package com.example.ialocal.ads

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RewardedEntryPacingTest {
    @Test
    fun firstEntryPreloadsAndSubsequentEntriesWithinThirtyMinutesDoNot() {
        val now = 1_800_000_000_000L
        assertTrue(RewardedEntryPacing.canPreload(now, lastPreloadedAtMs = 0L))
        assertFalse(RewardedEntryPacing.canPreload(now + 100L, lastPreloadedAtMs = now))
        assertFalse(RewardedEntryPacing.canPreload(now + 29 * 60_000L, lastPreloadedAtMs = now))
    }

    @Test
    fun exactlyThirtyMinutesPermitsAnotherPreload() {
        val now = 1_800_000_000_000L
        assertTrue(RewardedEntryPacing.canPreload(now + 30 * 60_000L, now))
        assertTrue(RewardedEntryPacing.canPreload(now + 31 * 60_000L, now))
    }

    @Test
    fun clockRollbackNeverBlocksFuturePreloads() {
        assertTrue(RewardedEntryPacing.canPreload(1_000L, 2_000L))
    }
}
