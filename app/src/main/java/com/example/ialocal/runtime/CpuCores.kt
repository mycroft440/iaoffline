package com.example.ialocal.runtime

import java.io.File

/**
 * Efficiency cores of the phone's CPU. The native runtime runs one thread per fast core the AI
 * process may use and fills in with these only up to 4 threads: each operation is split among the
 * threads and waits for the last one, and an in-order core is several times slower (see step 17 of
 * scripts/prepare_llama_android.sh).
 */
object CpuCores {
    /** CPU numbers of the efficiency cores in /proc/cpuinfo; null when it lists no CPU parts (x86_64). */
    fun efficiencyCpus(): Set<Int>? = runCatching { parse(File("/proc/cpuinfo").readText()) }.getOrNull()

    /** "0,1,2,3", the form the native side reads; empty when the CPU has no efficiency cores. */
    fun encode(cpus: Set<Int>): String = cpus.sorted().joinToString(",")

    /** Reads the "CPU implementer" and "CPU part" of each "processor" (the cores' MIDR fields). */
    internal fun parse(cpuinfo: String): Set<Int>? {
        val efficiency = sortedSetOf<Int>()
        var listed = false
        var cpu = -1
        var implementer = -1
        cpuinfo.lineSequence().forEach { line ->
            val value = line.substringAfter(':', missingDelimiterValue = "").trim()
            when (line.substringBefore(':', missingDelimiterValue = "").trim()) {
                "processor" -> {
                    cpu = value.toIntOrNull() ?: -1
                    implementer = -1
                }
                "CPU implementer" -> implementer = value.hexOrNull() ?: -1
                "CPU part" -> {
                    val part = value.hexOrNull()
                    if (cpu >= 0 && part != null) {
                        listed = true
                        if (isEfficiencyCore(implementer, part)) efficiency += cpu
                    }
                }
            }
        }
        return efficiency.takeIf { listed }
    }

    /** Arm's in-order cores (Cortex-A32/A53/A35/A55/A510/A520) and Qualcomm's Kryo Silver built on them. */
    internal fun isEfficiencyCore(implementer: Int, part: Int): Boolean = when (implementer) {
        ARM -> part in ARM_EFFICIENCY_PARTS
        QUALCOMM -> part in QUALCOMM_EFFICIENCY_PARTS
        else -> false
    }

    private fun String.hexOrNull(): Int? = removePrefix("0x").removePrefix("0X").toIntOrNull(16)

    private const val ARM = 0x41
    private const val QUALCOMM = 0x51
    private val ARM_EFFICIENCY_PARTS = setOf(0xd01, 0xd03, 0xd04, 0xd05, 0xd46, 0xd80)
    /** Kryo 2xx, 3xx and 4xx/5xx Silver; later Snapdragons report Arm's own part numbers. */
    private val QUALCOMM_EFFICIENCY_PARTS = setOf(0x801, 0x803, 0x805)
}
