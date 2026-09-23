package com.example.ialocal.runtime

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
}
