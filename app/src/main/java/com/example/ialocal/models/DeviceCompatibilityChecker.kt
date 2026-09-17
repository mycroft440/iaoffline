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
        /**
         * CPU/ABI and RAM are advisory only. If the GGUF can be stored, the app must let the
         * native runtime perform the real compatibility test instead of rejecting by heuristic.
         */
        val canImport: Boolean get() = canStore
    }

    fun check(modelSizeBytes: Long?): Result {
        val activity = context.getSystemService(ActivityManager::class.java)
        val memoryInfo = ActivityManager.MemoryInfo().also(activity::getMemoryInfo)
        val storage = StatFs(context.filesDir.absolutePath).availableBytes
        val primaryAbi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
        val supportedAbi = Build.SUPPORTED_ABIS.any { it == "arm64-v8a" || it == "x86_64" }

        val estimate = modelSizeBytes?.takeIf { it > 0 }?.let {
            // Advisory estimate only. The real decision is made by llama.cpp while loading.
            (it * 1.20).roundToLong() + 512L * 1024 * 1024
        }
        val canStore = modelSizeBytes?.takeIf { it > 0 }?.let {
            storage > it + COPY_STORAGE_HEADROOM
        } ?: true
        val likelyFits = estimate?.let { it < memoryInfo.totalMem * MAX_RAM_SHARE }

        val warnings = buildList {
            if (!supportedAbi) {
                add("ABI $primaryAbi não está entre as ABI validadas do runtime. O app ainda tentará carregar o GGUF e deixará o llama.cpp decidir a compatibilidade real.")
            }
            if (!canStore) add("Não há espaço livre suficiente para copiar este modelo para o armazenamento privado do app.")
            if (likelyFits == false) {
                add("A estimativa indica alta pressão de RAM, mas ela não bloqueia o modelo; o app ainda executará o teste real de carregamento.")
            }
            if (memoryInfo.availMem < 1L * 1024 * 1024 * 1024) {
                add("Há menos de 1 GB de RAM disponível agora. O app ainda tentará carregar o modelo, e o Android pode encerrar o processo se faltar memória.")
            }
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
