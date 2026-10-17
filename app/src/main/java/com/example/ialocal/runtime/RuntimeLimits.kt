package com.example.ialocal.runtime

object RuntimeLimits {
    /**
     * Largest answer the app requests. The model still stops on its own when the answer is done;
     * this only decides how long it may keep going. Past the context window the native binding
     * shifts the context (keeping the system prompt), so the answer continues instead of being cut.
     */
    const val MAX_OUTPUT_TOKENS = 8192
    const val MIN_OUTPUT_TOKENS = 16

    /**
     * Largest context requested from the native binding. It allocates the most that fits the KV
     * budget from [kvCacheBudgetBytes], also capped by the context the model was trained with.
     */
    const val MAX_CONTEXT_TOKENS = 131_072
    /** Smallest context the native binding allocates; also assumed if it does not report one. */
    const val MIN_CONTEXT_TOKENS = 2048

    /**
     * Share of the device RAM that the model file and its KV cache may take together. The Q4_0
     * cache costs about 37 KB per token for an 8B model, so on a 12 GB phone a 4B model gets its
     * full 32K context and an 8B one about 13K.
     */
    const val KV_BUDGET_RAM_FRACTION = 0.40
    const val MIN_KV_BUDGET_BYTES = 256L * 1024 * 1024
    const val MAX_KV_BUDGET_BYTES = 2L * 1024 * 1024 * 1024
    /** Experimental MoE models already fill the RAM with weights read from storage. */
    const val EXPERIMENTAL_KV_BUDGET_BYTES = 384L * 1024 * 1024

    /**
     * A model file above this share of the device RAM is loaded in low-memory mode: weights stay
     * file-backed (no repacked copy). On a
     * 12 GB phone this covers 8B models (~5 GB) and leaves 4B ones (~2.5 GB) on the fast path.
     */
    const val LOW_MEMORY_MODEL_FRACTION = 0.30

    /** Memory the native KV cache may use, which decides how long the context can be. */
    fun kvCacheBudgetBytes(modelBytes: Long, deviceTotalMemory: Long, experimental: Boolean): Long = when {
        experimental -> EXPERIMENTAL_KV_BUDGET_BYTES
        deviceTotalMemory <= 0 -> MIN_KV_BUDGET_BYTES
        else -> (deviceTotalMemory * KV_BUDGET_RAM_FRACTION - modelBytes).toLong()
            .coerceIn(MIN_KV_BUDGET_BYTES, MAX_KV_BUDGET_BYTES)
    }

    fun needsLowMemoryMode(modelBytes: Long, deviceTotalMemory: Long): Boolean =
        deviceTotalMemory > 0 && modelBytes > deviceTotalMemory * LOW_MEMORY_MODEL_FRACTION

    /** Kept free for Android and the app's own screens when the AI takes its share. */
    const val AI_MARGIN_BYTES = 512L * 1024 * 1024
    /** Compute buffers (about 290 MB with 256-token micro-batches) and native overhead. */
    const val WORKING_MEMORY_BYTES = 512L * 1024 * 1024

    /**
     * RAM the AI may use: what is free, or what Android frees by closing background apps,
     * minus [AI_MARGIN_BYTES]. With 8 GB free it is 7.5 GB.
     */
    fun aiBudgetBytes(memory: MemorySnapshot): Long =
        (memory.foregroundBudgetBytes - AI_MARGIN_BYTES).coerceAtLeast(0L)

    /** How a model is loaded within the AI's share of the RAM. */
    data class MemoryPlan(
        val budgetBytes: Long,
        /** Weights stay file-backed, without the repacked copy. */
        val lowMemory: Boolean,
        val kvBudgetBytes: Long,
        /** False when even the smallest configuration needs more than the budget. */
        val fits: Boolean,
        /** What the smallest configuration needs. */
        val minimumBytes: Long,
    )

    /**
     * Fits the weights in RAM ([residentWeightsBytes], without what is read from the file on
     * demand), the context and the working memory into [aiBudgetBytes]. A dense model reads all
     * its weights for every token, so they must fit even when file-backed, or the phone spends
     * its time reading them from storage and stops responding. Experimental MoE models read only
     * a few experts per token and are meant to run from storage.
     */
    fun planMemory(
        residentWeightsBytes: Long,
        memory: MemorySnapshot,
        experimental: Boolean,
        safeLevel: Int,
    ): MemoryPlan {
        val budget = aiBudgetBytes(memory)
        val minimum = residentWeightsBytes + MIN_KV_BUDGET_BYTES + WORKING_MEMORY_BYTES
        // While the weights are repacked, the copy and the mapped file are in RAM together.
        val repackFits = 2 * residentWeightsBytes + MIN_KV_BUDGET_BYTES + WORKING_MEMORY_BYTES <= budget
        val lowMemory = experimental || safeLevel > 0 || !repackFits ||
            needsLowMemoryMode(residentWeightsBytes, memory.totalBytes)
        val kvBudget = if (experimental) {
            kvCacheBudgetBytes(residentWeightsBytes, memory.totalBytes, experimental = true)
        } else {
            minOf(
                kvCacheBudgetBytes(residentWeightsBytes, memory.totalBytes, experimental = false),
                budget - residentWeightsBytes - WORKING_MEMORY_BYTES,
            ).coerceAtLeast(MIN_KV_BUDGET_BYTES)
        }
        return MemoryPlan(
            budgetBytes = budget,
            lowMemory = lowMemory,
            kvBudgetBytes = safeKvBudgetBytes(kvBudget, safeLevel),
            fits = experimental || minimum <= budget,
            minimumBytes = minimum,
        )
    }

    /**
     * Context memory for a model that already made the app close for lack of memory
     * ([ModelCrashGuard]): half after the first time, the minimum (a 2K context) after that.
     */
    fun safeKvBudgetBytes(budget: Long, safeLevel: Int): Long = when {
        safeLevel <= 0 -> budget
        safeLevel == 1 -> (budget / 2).coerceAtLeast(MIN_KV_BUDGET_BYTES)
        else -> MIN_KV_BUDGET_BYTES
    }
}
