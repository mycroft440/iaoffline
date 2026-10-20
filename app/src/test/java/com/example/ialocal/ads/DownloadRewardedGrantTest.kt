package com.example.ialocal.ads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadRewardedGrantTest {
    @Test
    fun noDownloadBeforeTheEarnedRewardCallback() {
        var downloads = 0
        val reward = RewardedDownloadGrant { downloads++ }
        assertFalse(reward.granted)
        assertEquals(0, downloads)
        reward.earn()
        assertTrue(reward.granted)
        assertEquals(1, downloads)
    }

    @Test
    fun duplicateRewardCallbacksOnlyAuthorizeOneDownload() {
        var downloads = 0
        val reward = RewardedDownloadGrant { downloads++ }
        reward.earn()
        reward.earn()
        assertEquals(1, downloads)
    }
}
