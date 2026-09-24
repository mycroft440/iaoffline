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
}
