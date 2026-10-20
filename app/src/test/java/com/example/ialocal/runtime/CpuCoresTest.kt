package com.example.ialocal.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CpuCoresTest {
    /** /proc/cpuinfo of an arm64 kernel: one block per processor with its implementer and part. */
    private fun cpuinfo(vararg cores: Pair<Int, Int>): String = cores.withIndex().joinToString("\n\n") { (cpu, core) ->
        """
        processor	: $cpu
        BogoMIPS	: 38.40
        Features	: fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics fphp asimdhp cpuid asimdrdm lrcpc dcpop asimddp
        CPU implementer	: 0x${core.first.toString(16)}
        CPU architecture: 8
        CPU variant	: 0x1
        CPU part	: 0x${core.second.toString(16)}
        CPU revision	: 0
        """.trimIndent()
    }

    @Test
    fun findsTheLittleCoresOfASnapdragon8Gen3() {
        // 2 Cortex-A520, 5 Cortex-A720, 1 Cortex-X4.
        val text = cpuinfo(
            0x41 to 0xd80, 0x41 to 0xd80,
            0x41 to 0xd81, 0x41 to 0xd81, 0x41 to 0xd81, 0x41 to 0xd81, 0x41 to 0xd81,
            0x41 to 0xd82,
        )

        assertEquals(setOf(0, 1), CpuCores.parse(text))
    }

    @Test
    fun findsQualcommKryoSilverCores() {
        // Snapdragon 855: 4 Kryo 485 Silver (A55-based), 4 Kryo 485 Gold.
        val text = cpuinfo(*Array(4) { 0x51 to 0x805 }, *Array(4) { 0x51 to 0x804 })

        assertEquals(setOf(0, 1, 2, 3), CpuCores.parse(text))
    }

    @Test
    fun aCpuWithoutEfficiencyCoresHasAnEmptyList() {
        // Snapdragon 8 Elite: 8 Oryon cores.
        assertEquals(emptySet<Int>(), CpuCores.parse(cpuinfo(*Array(8) { 0x51 to 0x001 })))
        // Dimensity 9300: 4 Cortex-X4, 4 Cortex-A720.
        assertEquals(emptySet<Int>(), CpuCores.parse(cpuinfo(*Array(4) { 0x41 to 0xd82 }, *Array(4) { 0x41 to 0xd81 })))
    }

    @Test
    fun withoutPartNumbersTheCoreTypesAreUnknown() {
        val x86 = """
            processor	: 0
            vendor_id	: GenuineIntel
            model name	: Intel(R) Core(TM) i5-1235U

            processor	: 1
            vendor_id	: GenuineIntel
        """.trimIndent()

        assertNull(CpuCores.parse(x86))
        assertNull(CpuCores.parse(""))
    }

    @Test
    fun recognizesEachInOrderCore() {
        listOf(0xd01, 0xd03, 0xd04, 0xd05, 0xd46, 0xd80).forEach { assertTrue(CpuCores.isEfficiencyCore(0x41, it)) }
        listOf(0x801, 0x803, 0x805).forEach { assertTrue(CpuCores.isEfficiencyCore(0x51, it)) }
        // Cortex-A76/A78/A710/A715/A720/X4 and Kryo Gold are fast cores; so is anything from another vendor.
        listOf(0xd0b, 0xd41, 0xd47, 0xd4d, 0xd81, 0xd82).forEach { assertFalse(CpuCores.isEfficiencyCore(0x41, it)) }
        assertFalse(CpuCores.isEfficiencyCore(0x51, 0x804))
        assertFalse(CpuCores.isEfficiencyCore(0x48, 0xd05))
    }

    @Test
    fun encodesTheListTheNativeSideReads() {
        assertEquals("0,1,3", CpuCores.encode(setOf(3, 0, 1)))
        assertEquals("", CpuCores.encode(emptySet()))
    }
}
