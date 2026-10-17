package com.example.ialocal.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeviceMemoryTest {
    private val gib = 1024L * 1024L * 1024L

    // A 12 GB phone with a 3 GB model mapped: the model's pages are in Cached, which Android up
    // to 15 counts as free (MemFree + Cached = 7.1 GB), while the kernel estimates 4.2 GB.
    private val meminfo = """
        MemTotal:       11534336 kB
        MemFree:          524288 kB
        MemAvailable:    4404019 kB
        Buffers:           10240 kB
        Cached:          6920601 kB
        SwapCached:        12288 kB
        SwapTotal:       6291452 kB
        SwapFree:        5242876 kB
        Shmem:            716800 kB
    """.trimIndent()
    private val status = """
        Name:	com.example.ialocal
        VmPeak:	27845632 kB
        VmRSS:	 3355443 kB
        RssAnon:	  901120 kB
        RssFile:	 2454323 kB
        Threads:	62
    """.trimIndent()

    @Test
    fun readsTheKernelEstimateOfFreeRamAndTheAppsOwnUse() {
        val snapshot = requireNotNull(DeviceMemory.fromProc(meminfo, status))

        assertEquals(11534336L * 1024, snapshot.totalBytes)
        assertEquals(4404019L * 1024, snapshot.availableBytes)
        assertEquals(3355443L * 1024, snapshot.appBytes)
        assertEquals(snapshot.totalBytes - snapshot.availableBytes, snapshot.usedBytes)
        assertEquals(0.618f, snapshot.usedFraction, 0.001f)
    }

    @Test
    fun withoutMemAvailableFallsBackToAndroid() {
        assertNull(DeviceMemory.fromProc("MemTotal: 1000 kB\nMemFree: 10 kB", null))
        assertNull(DeviceMemory.fromProc("", null))
    }

    @Test
    fun appUseIsOptional() {
        assertEquals(0L, DeviceMemory.fromProc(meminfo, null)?.appBytes)
    }

    @Test
    fun reportsThePhoneRamAsSold() {
        assertEquals(12.0, DeviceMemory.advertisedGigabytes(11534336L * 1024), 0.0)
        assertEquals(12.0, DeviceMemory.advertisedGigabytes(108L * gib / 10), 0.0)
        assertEquals(8.0, DeviceMemory.advertisedGigabytes(74L * gib / 10), 0.0)
        assertEquals(6.0, DeviceMemory.advertisedGigabytes(55L * gib / 10), 0.0)
        assertEquals(16.0, DeviceMemory.advertisedGigabytes(15L * gib), 0.0)
        assertEquals(4.0, DeviceMemory.advertisedGigabytes(37L * gib / 10), 0.0)
        assertEquals(0.0, DeviceMemory.advertisedGigabytes(0L), 0.0)
    }

    @Test
    fun foregroundBudgetLeavesTheSystemItsShare() {
        // 11 GB usable, little free right now: Android will close background apps, but the
        // system keeps about a third.
        val busy = MemorySnapshot(totalBytes = 11 * gib, availableBytes = 2 * gib)
        assertEquals(11 * gib - (11 * gib * 0.35).toLong(), busy.foregroundBudgetBytes)
        // Much free RAM: all of it.
        assertEquals(9 * gib, MemorySnapshot(totalBytes = 11 * gib, availableBytes = 9 * gib).foregroundBudgetBytes)
        // Small phones: the system keeps at least 1.5 GB.
        assertEquals(3 * gib / 2, MemorySnapshot(totalBytes = 3 * gib, availableBytes = gib).foregroundBudgetBytes)
        // Big phones: at most 4.5 GB.
        assertEquals(24 * gib - 9 * gib / 2, MemorySnapshot(totalBytes = 24 * gib, availableBytes = gib).foregroundBudgetBytes)
    }
}
