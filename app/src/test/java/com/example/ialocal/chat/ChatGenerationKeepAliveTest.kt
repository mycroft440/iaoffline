package com.example.ialocal.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatGenerationKeepAliveTest {
    @Test
    fun holdsCpuWhenAnyConversationIsGenerating() {
        assertTrue(ChatGenerationKeepAlive.needsCpuWakeLock(1))
        assertTrue(ChatGenerationKeepAlive.needsCpuWakeLock(2))
        assertFalse(ChatGenerationKeepAlive.needsCpuWakeLock(0))
    }

    @Test
    fun refreshesTimedWakeLockBeforeItsLeaseExpires() {
        assertTrue(ChatGenerationKeepAlive.WAKE_LOCK_RENEW_MS > 0L)
        assertTrue(ChatGenerationKeepAlive.WAKE_LOCK_LEASE_MS > ChatGenerationKeepAlive.WAKE_LOCK_RENEW_MS)
        assertTrue(ChatGenerationKeepAlive.WAKE_LOCK_LEASE_MS <= 60L * 60L * 1000L)
    }

    @Test
    fun givesSenderTimeToPublishGenerationAfterServiceStarts() {
        assertTrue(ChatGenerationKeepAlive.SERVICE_IDLE_GRACE_MS >= 500L)
        assertFalse(ChatGenerationKeepAlive.needsCpuWakeLock(-1))
    }
}
