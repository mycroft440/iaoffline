package com.example.ialocal.runtime

object RuntimeLimits {
    /**
     * Largest answer the app requests: one full native context (8192 tokens). The model still stops
     * on its own when the answer is done; this only decides how long it may keep going. Past the
     * context window the native binding shifts the context (keeping the system prompt), so the
     * answer continues instead of being cut.
     */
    const val MAX_OUTPUT_TOKENS = 8192
    const val MIN_OUTPUT_TOKENS = 16

    /** Context the patched native binding allocates by default. */
    const val NATIVE_CONTEXT_TOKENS = 8192
    /** Context used for models that are large for the device's RAM; halves the KV cache. */
    const val LOW_MEMORY_CONTEXT_TOKENS = 4096
    /** Context for experimental MoE models that are larger than the device RAM. */
    const val EXPERIMENTAL_CONTEXT_TOKENS = 2048

    /**
     * A model file above this share of the device RAM is loaded in low-memory mode: weights stay
     * file-backed (no repacked copy) and the context starts at [LOW_MEMORY_CONTEXT_TOKENS]. On a
     * 12 GB phone this covers 8B models (~5 GB) and leaves 4B ones (~2.5 GB) on the fast path.
     */
    const val LOW_MEMORY_MODEL_FRACTION = 0.30

    fun needsLowMemoryMode(modelBytes: Long, deviceTotalMemory: Long): Boolean =
        deviceTotalMemory > 0 && modelBytes > deviceTotalMemory * LOW_MEMORY_MODEL_FRACTION
}
