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
    /** MoE models read from storage need the rest of the RAM to keep the experts they use. */
    const val EXPERIMENTAL_KV_BUDGET_BYTES = 384L * 1024 * 1024
    /** Fallback when the GGUF's per-layer expert sizes are unavailable. */
    const val DEFAULT_EXPERT_WORKING_SET_BYTES = 512L * 1024 * 1024

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

    /**
     * Architectures whose weights must stay as they are in the file (low-memory mode). The pinned
     * llama.cpp repacks a tensor of DeepSeek's latent attention ("deepseek2": DeepSeek V2, GigaChat
     * 3, GLM-4.7-Flash) into a CPU buffer that no backend can then run: loading aborts the AI
     * process, or with flash attention off the answer is garbage. Unrepacked, they run correctly.
     */
    private val PLAIN_WEIGHT_ARCHITECTURES = setOf("deepseek2")

    fun needsPlainWeights(architecture: String?): Boolean = architecture?.lowercase() in PLAIN_WEIGHT_ARCHITECTURES

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
        /**
         * A mixture of experts larger than the budget: its experts stay in the file and are read
         * as the router picks them. [lowMemory] then only concerns the rest of the weights.
         */
        val streamsExperts: Boolean = false,
        /** RAM left for file-backed expert pages, separate from KV and compute buffers. */
        val expertWorkingSetBytes: Long = 0L,
    )

    /**
     * Fits the weights in RAM ([residentWeightsBytes], without what is read from the file on
     * demand), the context and the working memory into [aiBudgetBytes]. A dense model reads all
     * its weights for every token, so they must fit even when file-backed, or the phone spends
     * its time reading them from storage and stops responding. A mixture of experts
     * ([expertBytes] of its weights are experts) reads only a few experts per token, so when it
     * does not fit its experts are read from storage. The shared weights and at least one
     * layer's expert pages must fit: a prompt batch can select every expert in that layer.
     */
    fun planMemory(
        residentWeightsBytes: Long,
        memory: MemorySnapshot,
        expertBytes: Long,
        safeLevel: Int,
        largestExpertLayerBytes: Long = 0L,
    ): MemoryPlan {
        val budget = aiBudgetBytes(memory)
        val minimum = residentWeightsBytes + MIN_KV_BUDGET_BYTES + WORKING_MEMORY_BYTES
        if (expertBytes > 0 && minimum > budget) {
            // Attention, shared experts and output: read for every token, so they must fit.
            val fixedBytes = (residentWeightsBytes - expertBytes.coerceAtMost(residentWeightsBytes)).coerceAtLeast(0L)
            val expertWorkingSet = minOf(
                expertBytes,
                largestExpertLayerBytes.takeIf { it > 0L } ?: DEFAULT_EXPERT_WORKING_SET_BYTES,
            )
            val fixedMinimum = fixedBytes + expertWorkingSet + MIN_KV_BUDGET_BYTES + WORKING_MEMORY_BYTES
            // The experimental 384 MB KV cache must fit beside the shared weights
            // and compute buffers; a model can fit with 256 MB but not with 384 MB.
            val kvRoom = (budget - fixedBytes - expertWorkingSet - WORKING_MEMORY_BYTES).coerceAtLeast(MIN_KV_BUDGET_BYTES)
            val kvBudget = safeKvBudgetBytes(
                minOf(kvCacheBudgetBytes(fixedBytes, memory.totalBytes, experimental = true), kvRoom),
                safeLevel,
            )
            return MemoryPlan(
                budgetBytes = budget,
                lowMemory = safeLevel > 0 || 2 * fixedBytes + expertWorkingSet + kvBudget + WORKING_MEMORY_BYTES > budget,
                kvBudgetBytes = kvBudget,
                fits = fixedMinimum <= budget,
                minimumBytes = fixedMinimum,
                streamsExperts = true,
                expertWorkingSetBytes = expertWorkingSet,
            )
        }
        // While the weights are repacked, the copy and the mapped file are in RAM together.
        val repackFits = 2 * residentWeightsBytes + MIN_KV_BUDGET_BYTES + WORKING_MEMORY_BYTES <= budget
        val lowMemory = safeLevel > 0 || !repackFits || needsLowMemoryMode(residentWeightsBytes, memory.totalBytes)
        val kvBudget = minOf(
            kvCacheBudgetBytes(residentWeightsBytes, memory.totalBytes, experimental = false),
            budget - residentWeightsBytes - WORKING_MEMORY_BYTES,
        ).coerceAtLeast(MIN_KV_BUDGET_BYTES)
        return MemoryPlan(
            budgetBytes = budget,
            lowMemory = lowMemory,
            kvBudgetBytes = safeKvBudgetBytes(kvBudget, safeLevel),
            fits = minimum <= budget,
            minimumBytes = minimum,
        )
    }

    data class PerformanceBudget(val cacheBytes: Long = 0, val gpuLayers: Int = 0)

    /** Extra copies must fit beside the mapped working set, including the repack peak.
     * Use memory available NOW; optional accelerators never rely on killing other apps. */
    fun performanceBudget(
        plan: MemoryPlan,
        memory: MemorySnapshot,
        fixedBytes: Long,
        largestSharedLayerBytes: Long,
        profile: PerformanceProfile,
        safeLevel: Int,
    ): PerformanceBudget {
        if (!plan.fits || !plan.streamsExperts || safeLevel > 0 || profile == PerformanceProfile.CPU) {
            return PerformanceBudget()
        }
        val budget = minOf(plan.budgetBytes, (memory.availableBytes - AI_MARGIN_BYTES).coerceAtLeast(0))
        // GPU profiles disable CPU repacking; count its transfer copy separately below.
        val fixedPeak = fixedBytes * if (plan.lowMemory || profile == PerformanceProfile.GPU) 1 else 2
        var spare = (budget - fixedPeak - plan.kvBudgetBytes - WORKING_MEMORY_BYTES - plan.expertWorkingSetBytes)
            .coerceAtLeast(0)
        var layers = 0
        if (profile == PerformanceProfile.GPU && largestSharedLayerBytes > 0) {
            val gpuBuffers = 256L * 1024 * 1024
            layers = ((spare - gpuBuffers).coerceAtLeast(0) / largestSharedLayerBytes).coerceAtMost(4).toInt()
            if (layers > 0) spare -= gpuBuffers + layers * largestSharedLayerBytes
        }
        // Keep half the headroom for the file cache and transient memory pressure.
        val cache = (spare / 2).coerceAtMost(512L * 1024 * 1024)
        return PerformanceBudget(cacheBytes = cache, gpuLayers = layers)
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
