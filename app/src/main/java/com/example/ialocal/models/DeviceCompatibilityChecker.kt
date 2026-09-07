package com.example.ialocal.models

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.StatFs
import kotlin.math.roundToLong

class DeviceCompatibilityChecker(private val context: Context) {
    data class Result(
        val primaryAbi: String,
        val supportedAbi: Boolean,
        val totalRamBytes: Long,
        val availableRamBytes: Long,
        val availableStorageBytes: Long,
        val estimatedModelRamBytes: Long?,
        val canStore: Boolean,
        val likelyFitsRam: Boolean?,
        val warnings: List<String>,
    ) {
        val canImport: Boolean get() = supportedAbi && canStore
    }

    fun check(modelSizeBytes: Long?): Result {
        val activity = context.getSystemService(ActivityManager::class.java)
        val memoryInfo = ActivityManager.MemoryInfo().also(activity::getMemoryInfo)
        val storage = StatFs(context.filesDir.absolutePath).availableBytes
        val primaryAbi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
        val supportedAbi = Build.SUPPORTED_ABIS.any { it == "arm64-v8a" || it == "x86_64" }

        val estimate = modelSizeBytes?.takeIf { it > 0 }?.let {
            // File weights are normally memory-mapped, but context/KV/native buffers add headroom.
            (it * 1.20).roundToLong() + 512L * 1024 * 1024
        }
        val canStore = modelSizeBytes?.takeIf { it > 0 }?.let {
            storage > it + COPY_STORAGE_HEADROOM
        } ?: true
        val likelyFits = estimate?.let { it < memoryInfo.totalMem * MAX_RAM_SHARE }

        val warnings = buildList {
            if (!supportedAbi) add("ABI $primaryAbi não é suportada pelo runtime Android atual.")
            if (!canStore) add("Não há espaço livre suficiente para copiar este modelo para o armazenamento privado do app.")
            if (likelyFits == false) add("O modelo parece grande para a RAM total deste aparelho e pode falhar ao carregar.")
            if (memoryInfo.availMem < 1L * 1024 * 1024 * 1024) add("Há menos de 1 GB de RAM disponível agora; feche outros apps antes de carregar o modelo.")
        }

        return Result(
            primaryAbi = primaryAbi,
            supportedAbi = supportedAbi,
            totalRamBytes = memoryInfo.totalMem,
            availableRamBytes = memoryInfo.availMem,
            availableStorageBytes = storage,
            estimatedModelRamBytes = estimate,
            canStore = canStore,
            likelyFitsRam = likelyFits,
            warnings = warnings,
        )
    }

    companion object {
        private const val COPY_STORAGE_HEADROOM = 256L * 1024 * 1024
        private const val MAX_RAM_SHARE = 0.82
    }
}
