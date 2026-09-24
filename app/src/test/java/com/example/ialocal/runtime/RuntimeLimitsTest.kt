package com.example.ialocal.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeLimitsTest {
    private val gb = 1024L * 1024L * 1024L

    @Test
    fun eightBillionModelOnTwelveGigabytePhoneUsesLowMemoryMode() {
        // S22 Ultra reports ~11 GB total; an 8B Q4_K_M file is ~4.9 GB.
        assertTrue(RuntimeLimits.needsLowMemoryMode(modelBytes = 49 * gb / 10, deviceTotalMemory = 11 * gb))
    }

    @Test
    fun fourBillionModelOnTwelveGigabytePhoneKeepsTheFastPath() {
        assertFalse(RuntimeLimits.needsLowMemoryMode(modelBytes = 25 * gb / 10, deviceTotalMemory = 11 * gb))
    }

    @Test
    fun unknownDeviceMemoryDoesNotForceLowMemoryMode() {
        assertFalse(RuntimeLimits.needsLowMemoryMode(modelBytes = 5 * gb, deviceTotalMemory = 0))
    }

    @Test
    fun kvBudgetIsWhatRemainsOfTheRamShareAfterTheModel() {
        // 40% of 11 GB is 4.4 GB; a 4B model of 2.5 GB leaves 1.9 GB for the context.
        val budget = RuntimeLimits.kvCacheBudgetBytes(25 * gb / 10, 11 * gb, experimental = false)
        assertEquals(1.9, budget.toDouble() / gb, 0.01)
    }

    @Test
    fun kvBudgetStaysWithinItsBounds() {
        assertEquals(RuntimeLimits.MIN_KV_BUDGET_BYTES, RuntimeLimits.kvCacheBudgetBytes(6 * gb, 11 * gb, experimental = false))
        assertEquals(RuntimeLimits.MAX_KV_BUDGET_BYTES, RuntimeLimits.kvCacheBudgetBytes(gb / 2, 16 * gb, experimental = false))
        assertEquals(RuntimeLimits.MIN_KV_BUDGET_BYTES, RuntimeLimits.kvCacheBudgetBytes(gb, 0, experimental = false))
    }

    @Test
    fun experimentalModelsGetAFixedKvBudget() {
        assertEquals(
            RuntimeLimits.EXPERIMENTAL_KV_BUDGET_BYTES,
            RuntimeLimits.kvCacheBudgetBytes(16 * gb, 11 * gb, experimental = true),
        )
    }
}
