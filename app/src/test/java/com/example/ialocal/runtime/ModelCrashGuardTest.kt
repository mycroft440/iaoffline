package com.example.ialocal.runtime

import android.app.ApplicationExitInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCrashGuardTest {
    private val startedAt = 1_000_000L

    @Test
    fun lowMemoryKillsAndNativeCrashesDuringARunCount() {
        listOf(
            ApplicationExitInfo.REASON_LOW_MEMORY,
            ApplicationExitInfo.REASON_CRASH_NATIVE,
            ApplicationExitInfo.REASON_SIGNALED,
            ApplicationExitInfo.REASON_ANR,
        ).forEach { reason ->
            assertTrue(ModelCrashGuard.diedFromMemoryOrCrash(reason, startedAt + 5_000, startedAt))
        }
    }

    @Test
    fun theUserClosingTheAppDoesNotCount() {
        assertFalse(ModelCrashGuard.diedFromMemoryOrCrash(ApplicationExitInfo.REASON_USER_REQUESTED, startedAt + 5_000, startedAt))
        assertFalse(ModelCrashGuard.diedFromMemoryOrCrash(ApplicationExitInfo.REASON_USER_STOPPED, startedAt + 5_000, startedAt))
        assertFalse(ModelCrashGuard.diedFromMemoryOrCrash(ApplicationExitInfo.REASON_EXIT_SELF, startedAt + 5_000, startedAt))
    }

    @Test
    fun anOlderOrMissingRecordDoesNotCount() {
        assertFalse(ModelCrashGuard.diedFromMemoryOrCrash(ApplicationExitInfo.REASON_LOW_MEMORY, startedAt - 1, startedAt))
        assertFalse(ModelCrashGuard.diedFromMemoryOrCrash(null, null, startedAt))
    }

    @Test
    fun eachCrashLowersMemoryUseUpToTheSafestLevel() {
        assertEquals(1, ModelCrashGuard.nextLevel(0))
        assertEquals(2, ModelCrashGuard.nextLevel(1))
        assertEquals(ModelCrashGuard.MAX_LEVEL, ModelCrashGuard.nextLevel(ModelCrashGuard.MAX_LEVEL))
    }
}
