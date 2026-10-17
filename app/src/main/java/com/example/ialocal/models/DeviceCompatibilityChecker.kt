package com.example.ialocal.models

import android.content.Context
import android.os.Build
import android.os.StatFs
import com.example.ialocal.runtime.DeviceMemory
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
        val memory = DeviceMemory.read(context)
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
        // What a foreground app can count on, not the whole RAM: the system keeps a share.
        val likelyFits = estimate?.let { it < memory.foregroundBudgetBytes }

        val warnings = buildList {
            if (!supportedAbi) {
                add("ABI $primaryAbi não está entre as ABI validadas do runtime. O app ainda tentará carregar o GGUF e deixará o llama.cpp decidir a compatibilidade real.")
            }
            if (!canStore) add("Não há espaço livre suficiente para copiar este modelo para o armazenamento privado do app.")
            if (likelyFits == false) {
                add("A estimativa indica alta pressão de RAM, mas ela não bloqueia o modelo; o app ainda executará o teste real de carregamento.")
            }
            if (memory.availableBytes < 1L * 1024 * 1024 * 1024) {
                add("Há menos de 1 GB de RAM disponível agora. O app ainda tentará carregar o modelo, e o Android pode encerrar o processo se faltar memória.")
            }
        }

        return Result(
            primaryAbi = primaryAbi,
            supportedAbi = supportedAbi,
            totalRamBytes = memory.totalBytes,
            availableRamBytes = memory.availableBytes,
            availableStorageBytes = storage,
            estimatedModelRamBytes = estimate,
            canStore = canStore,
            likelyFitsRam = likelyFits,
            warnings = warnings,
        )
    }

    companion object {
        private const val COPY_STORAGE_HEADROOM = 256L * 1024 * 1024
    }
}
