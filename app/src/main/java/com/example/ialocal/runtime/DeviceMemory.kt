package com.example.ialocal.runtime

import android.app.ActivityManager
import android.content.Context
import java.io.File
import kotlin.math.ceil

/** RAM figures as the kernel reports them. */
data class MemorySnapshot(
    /** MemTotal: the RAM Android manages, a little less than the RAM on the box. */
    val totalBytes: Long,
    /** MemAvailable: what can still be given to apps without swapping. */
    val availableBytes: Long,
    /** Resident memory of this app, including the pages of a memory-mapped model in use. */
    val appBytes: Long = 0L,
) {
    val usedBytes: Long get() = (totalBytes - availableBytes).coerceAtLeast(0L)

    val usedFraction: Float
        get() = if (totalBytes <= 0L) 0f else (usedBytes.toDouble() / totalBytes).toFloat().coerceIn(0f, 1f)

    /** The RAM the phone is sold with, in the "12 GB" sense of the box and of the catalog. */
    val advertisedGigabytes: Double get() = DeviceMemory.advertisedGigabytes(totalBytes)

    /**
     * RAM a foreground app can count on. Android closes background apps to make room, so it is
     * more than MemAvailable when many apps are open, but the system, its UI and the kernel always
     * keep a share.
     */
    val foregroundBudgetBytes: Long
        get() = maxOf(availableBytes, totalBytes - DeviceMemory.systemReserveBytes(totalBytes))
}

object DeviceMemory {
    /**
     * Reads /proc/meminfo. ActivityManager's availMem is MemFree + Cached up to Android 15, which
     * counts shared memory and the pages of a mapped model as free; MemAvailable is the kernel's own
     * estimate and is what Android 16 reports there too.
     */
    fun read(context: Context): MemorySnapshot {
        val fromKernel = runCatching {
            fromProc(
                meminfo = File("/proc/meminfo").readText(),
                status = runCatching { File("/proc/self/status").readText() }.getOrNull(),
            )
        }.getOrNull()
        if (fromKernel != null) return fromKernel
        val info = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java)?.getMemoryInfo(info)
        return MemorySnapshot(totalBytes = info.totalMem, availableBytes = info.availMem)
    }

    internal fun fromProc(meminfo: String, status: String?): MemorySnapshot? {
        val fields = parseKilobyteFields(meminfo)
        val total = fields["MemTotal"]?.takeIf { it > 0 } ?: return null
        val available = fields["MemAvailable"] ?: return null
        return MemorySnapshot(
            totalBytes = total,
            availableBytes = available.coerceIn(0L, total),
            appBytes = status?.let { parseKilobyteFields(it)["VmRSS"] } ?: 0L,
        )
    }

    /** "Name:   1234 kB" lines, in bytes. */
    internal fun parseKilobyteFields(text: String): Map<String, Long> = buildMap {
        text.lineSequence().forEach { line ->
            val name = line.substringBefore(':', missingDelimiterValue = "").trim()
            val parts = line.substringAfter(':', missingDelimiterValue = "").trim().split(WHITESPACE)
            val value = parts.firstOrNull()?.toLongOrNull()
            if (name.isNotEmpty() && value != null && parts.getOrNull(1).equals("kB", ignoreCase = true)) {
                put(name, value * 1024L)
            }
        }
    }

    /**
     * Rounds MemTotal up to the sizes phones are sold with. The kernel and firmware reserve a part
     * of the RAM, so a 12 GB phone reports about 11 GB.
     */
    fun advertisedGigabytes(totalBytes: Long): Double {
        if (totalBytes <= 0L) return 0.0
        val gib = totalBytes / GIB
        return ADVERTISED_SIZES_GB.firstOrNull { it >= gib } ?: ceil(gib)
    }

    /** RAM the system keeps for itself: about a third, between 1.5 and 4.5 GB. */
    fun systemReserveBytes(totalBytes: Long): Long =
        (totalBytes * SYSTEM_RESERVE_FRACTION).toLong().coerceIn(MIN_SYSTEM_RESERVE_BYTES, MAX_SYSTEM_RESERVE_BYTES)

    private const val GIB = 1024.0 * 1024.0 * 1024.0
    private val ADVERTISED_SIZES_GB = doubleArrayOf(
        1.0, 1.5, 2.0, 3.0, 4.0, 6.0, 8.0, 10.0, 12.0, 16.0, 18.0, 20.0, 24.0, 32.0, 48.0, 64.0,
    )
    private const val SYSTEM_RESERVE_FRACTION = 0.35
    private const val MIN_SYSTEM_RESERVE_BYTES = 1536L * 1024 * 1024
    private const val MAX_SYSTEM_RESERVE_BYTES = 4608L * 1024 * 1024
    private val WHITESPACE = Regex("\\s+")
}
