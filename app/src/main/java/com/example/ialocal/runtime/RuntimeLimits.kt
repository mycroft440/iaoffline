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
     * The same for a dense model without recurrent layers, now that the context reserves logits for
     * 16 tokens instead of a whole micro-batch (step 19 h of the native patch). Recurrent state
     * and the activations of mixtures of experts keep [WORKING_MEMORY_BYTES].
     */
    const val DENSE_WORKING_MEMORY_BYTES = 384L * 1024 * 1024
    /** Activations a micro-batch token needs beyond the default 256 (a large mixture of experts). */
    const val UBATCH_TOKEN_BYTES = 200L * 1024
    const val DEFAULT_UBATCH = 256

    /** What [RuntimeTuning] was before it existed: the plans of the tests and of older callers. */
    val LEGACY_TUNING = RuntimeTuning(
        performance = PerformanceLevel.BALANCED,
        longContext = TuningSwitch.OFF,
        rewind = TuningSwitch.OFF,
        longPromptMoe = TuningSwitch.OFF,
        fastMoe = TuningSwitch.OFF,
    )

    /** "Contexto longo": more memory for the conversation, so long chats are not read again. */
    const val LONG_CONTEXT_BOOST_BYTES = 512L * 1024 * 1024
    const val LARGE_PHONE_LONG_CONTEXT_BOOST_BYTES = 1024L * 1024 * 1024
    const val LARGE_PHONE_MAX_KV_BUDGET_BYTES = 3L * 1024 * 1024 * 1024
    /** Free RAM a setting that takes more of it must still leave, for the screen and other apps. */
    const val TUNING_HEADROOM_BYTES = 1024L * 1024 * 1024
    /** "Desempenho: automático" keeps the optimized copy for phones of this size and up. */
    const val AUTO_PERFORMANCE_MIN_GB = 12.0
    /** ...for weights that are mostly of repackable types... */
    const val AUTO_PERFORMANCE_MIN_REPACKABLE_FRACTION = 0.70
    /** ...and whose copy, which Android cannot drop, stays under this share of the RAM. */
    const val AUTO_PERFORMANCE_MAX_RAM_FRACTION = 0.35
    /** KleidiAI keeps Q6_K and Q8_0 weights as int8 per channel: about this much larger. */
    const val REQUANTIZED_EXPANSION = 1.22
    /** A mixture of experts read from storage needs page cache for the experts it uses. */
    const val EXPERT_CACHE_RESERVE_BYTES = 1024L * 1024 * 1024
    /**
     * Most of a mixture of experts read from storage that is read into the page cache when it loads:
     * the experts it used most before (step 23 b of the native patch).
     */
    const val MAX_EXPERT_PRELOAD_BYTES = 1024L * 1024 * 1024

    /**
     * RAM the AI may use: what is free, or what Android frees by closing background apps,
     * minus [AI_MARGIN_BYTES]. With 8 GB free it is 7.5 GB.
     */
    fun aiBudgetBytes(memory: MemorySnapshot): Long =
        (memory.foregroundBudgetBytes - AI_MARGIN_BYTES).coerceAtLeast(0L)

    /** What the GGUF says about the weights ([com.example.ialocal.models.GgufInspector]). */
    data class WeightProfile(
        /** False when the GGUF could not be read: the plan then assumes the larger working memory. */
        val known: Boolean = false,
        val repackableBytes: Long = 0L,
        val fixedRepackableBytes: Long = 0L,
        val requantizedBytes: Long = 0L,
        val largestRequantizedValues: Long = 0L,
        val recurrent: Boolean = false,
    ) {
        /** Anonymous memory of the repacked copy of [repackableBytes]. */
        val repackedCopyBytes: Long
            get() = repackableBytes + ((REQUANTIZED_EXPANSION - 1.0) * requantizedBytes).toLong()

        /** The same for the weights read for every token of a mixture of experts. */
        val fixedRepackedCopyBytes: Long
            get() = (fixedRepackableBytes * REQUANTIZED_EXPANSION).toLong()
    }

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
        /** Micro-batch for reading prompts (IAOFFLINE_UBATCH). */
        val ubatch: Int = DEFAULT_UBATCH,
        /**
         * The repacked copy is kept although the copy and the mapped file do not fit together: the
         * native loader drops each tensor's pages from the file as soon as it is copied.
         */
        val repackRelease: Boolean = false,
        /** Part of [kvBudgetBytes] that "Contexto longo" added. */
        val longContextBytes: Long = 0L,
        /**
         * Experts used most in earlier loads read into the page cache after the load
         * (IAOFFLINE_EXPERT_PRELOAD_MB): half of the RAM left to the experts, at most
         * [MAX_EXPERT_PRELOAD_BYTES]. Only when the experts are read from storage. The kernel can drop
         * those pages again, so they do not make the AI process a target for Android.
         */
        val expertPreloadBytes: Long = 0L,
        /**
         * A mixture of experts that fits in RAM only without a copy of all its weights: instead of the
         * low-memory mode, its experts stay mapped from the file and only the weights read for every
         * token get the optimized copy (IAOFFLINE_MAPPED_EXPERTS, step 24 of the native patch), their
         * file pages released as they are copied ([repackRelease]), so the model is in RAM about once.
         */
        val mappedExperts: Boolean = false,
    )

    /**
     * Fits the weights in RAM ([residentWeightsBytes], without what is read from the file on
     * demand), the context and the working memory into [aiBudgetBytes]. A dense model reads all
     * its weights for every token, so they must fit even when file-backed, or the phone spends
     * its time reading them from storage and stops responding. A mixture of experts
     * ([expertBytes] of its weights are experts) reads only a few experts per token, so when it
     * does not fit its experts are read from storage and only the rest must fit.
     *
     * [tuning] is what the user chose in Settings; a model that already made the AI process close
     * ([safeLevel] above 0) or whose optimized copy did ([performanceBlocked]) loads without
     * the choices that take more RAM.
     */
    fun planMemory(
        residentWeightsBytes: Long,
        memory: MemorySnapshot,
        expertBytes: Long,
        safeLevel: Int,
        weights: WeightProfile = WeightProfile(),
        tuning: RuntimeTuning = LEGACY_TUNING,
        performanceBlocked: Boolean = false,
    ): MemoryPlan {
        val budget = aiBudgetBytes(memory)
        val baseWorking = if (weights.known && !weights.recurrent && expertBytes == 0L) DENSE_WORKING_MEMORY_BYTES else WORKING_MEMORY_BYTES
        val performance = when {
            performanceBlocked && tuning.performance != PerformanceLevel.ECONOMY -> PerformanceLevel.BALANCED
            else -> tuning.performance
        }
        val streams = expertBytes > 0 && residentWeightsBytes + MIN_KV_BUDGET_BYTES + baseWorking > budget
        val ubatch = ubatchFor(tuning.longPromptMoe, streams, expertBytes > 0, memory, safeLevel)
        val working = baseWorking + (ubatch - DEFAULT_UBATCH).coerceAtLeast(0) * UBATCH_TOKEN_BYTES
        val minimum = residentWeightsBytes + MIN_KV_BUDGET_BYTES + working
        val stagingBytes = weights.largestRequantizedValues
        if (streams) {
            // Attention, shared experts and output: read for every token, so they must fit.
            val fixedBytes = (residentWeightsBytes - expertBytes).coerceAtLeast(0L)
            val fixedMinimum = fixedBytes + MIN_KV_BUDGET_BYTES + working
            // While the weights are repacked, the copy and the mapped file are in RAM together...
            val repackFitsTwice = 2 * fixedBytes + MIN_KV_BUDGET_BYTES + working <= budget
            // ...unless the file is not read up front (experts are streamed): then one copy and the
            // staging of the largest requantized tensor, leaving page cache for the experts.
            val repackFitsOnce = weights.fixedRepackableBytes > 0 &&
                fixedBytes - weights.fixedRepackableBytes + weights.fixedRepackedCopyBytes + stagingBytes +
                MIN_KV_BUDGET_BYTES + working + EXPERT_CACHE_RESERVE_BYTES <= budget
            val repack = when (performance) {
                PerformanceLevel.ECONOMY -> false
                PerformanceLevel.BALANCED -> repackFitsTwice
                PerformanceLevel.AUTO, PerformanceLevel.MAX -> repackFitsTwice || repackFitsOnce
            }
            val kvBudget = safeKvBudgetBytes(kvCacheBudgetBytes(fixedBytes, memory.totalBytes, experimental = true), safeLevel)
            // What is left of the budget once the weights read for every token, the context and the
            // working memory are in RAM: the page cache that keeps experts.
            val expertCache = (budget - fixedBytes - kvBudget - working).coerceAtLeast(0L)
            return MemoryPlan(
                budgetBytes = budget,
                lowMemory = safeLevel > 0 || !repack,
                kvBudgetBytes = kvBudget,
                fits = fixedMinimum <= budget,
                minimumBytes = fixedMinimum,
                streamsExperts = true,
                ubatch = ubatch,
                repackRelease = safeLevel == 0 && repack && !repackFitsTwice,
                expertPreloadBytes = if (safeLevel > 0) 0L else (expertCache / 2).coerceAtMost(MAX_EXPERT_PRELOAD_BYTES),
            )
        }
        // While the weights are repacked, the copy and the mapped file are in RAM together.
        val repackFitsTwice = 2 * residentWeightsBytes + MIN_KV_BUDGET_BYTES + working <= budget
        val legacyLowMemory = !repackFitsTwice || needsLowMemoryMode(residentWeightsBytes, memory.totalBytes)
        val kvBudget = minOf(
            kvCacheBudgetBytes(residentWeightsBytes, memory.totalBytes, experimental = false),
            budget - residentWeightsBytes - working,
        ).coerceAtLeast(MIN_KV_BUDGET_BYTES)
        // The copy Android cannot drop, the weights of other types still read from the file, the
        // context and the int8 staging of the largest requantized tensor while it is repacked.
        val copyBytes = weights.repackedCopyBytes
        val optimizedDemand = copyBytes + (residentWeightsBytes - weights.repackableBytes).coerceAtLeast(0L) +
            kvBudget + working + stagingBytes
        val optimizedFits = copyBytes > 0 && optimizedDemand <= budget
        val autoOptimized = optimizedFits &&
            memory.advertisedGigabytes >= AUTO_PERFORMANCE_MIN_GB &&
            weights.repackableBytes >= AUTO_PERFORMANCE_MIN_REPACKABLE_FRACTION * residentWeightsBytes &&
            copyBytes <= AUTO_PERFORMANCE_MAX_RAM_FRACTION * memory.totalBytes &&
            optimizedDemand + TUNING_HEADROOM_BYTES <= budget
        val lowMemory = safeLevel > 0 || when (performance) {
            PerformanceLevel.ECONOMY -> true
            PerformanceLevel.BALANCED -> legacyLowMemory
            PerformanceLevel.MAX -> legacyLowMemory && !optimizedFits
            PerformanceLevel.AUTO -> legacyLowMemory && !autoOptimized
        }
        val boost = longContextBoost(tuning.longContext, memory, weights, safeLevel) { extra ->
            budget - residentWeightsBytes - kvBudget - working - extra >= TUNING_HEADROOM_BYTES
        }
        val kvCap = if (memory.advertisedGigabytes >= 16.0) LARGE_PHONE_MAX_KV_BUDGET_BYTES else MAX_KV_BUDGET_BYTES
        val boostedKv = if (boost > 0) {
            minOf(kvBudget + boost, maxOf(kvBudget, kvCap), budget - residentWeightsBytes - working).coerceAtLeast(kvBudget)
        } else {
            kvBudget
        }
        val finalKv = safeKvBudgetBytes(boostedKv, safeLevel)
        // A mixture of experts left without the optimized copy: the copy of only the weights read for
        // every token (about half of what a token reads in LFM2.5 8B-A1B, under a tenth of the file),
        // the experts mapped as in the low-memory mode. Their file pages are released as they are
        // copied, so this costs the difference between the copy and those pages, plus the int8
        // staging of the largest requantized tensor while it is repacked.
        val mappedDemand = residentWeightsBytes - weights.fixedRepackableBytes + weights.fixedRepackedCopyBytes +
            stagingBytes + finalKv + working
        val mappedExperts = lowMemory && safeLevel == 0 && expertBytes > 0 && weights.known &&
            weights.fixedRepackableBytes > 0 && when (performance) {
                PerformanceLevel.AUTO -> mappedDemand + TUNING_HEADROOM_BYTES <= budget
                PerformanceLevel.MAX -> mappedDemand <= budget
                else -> false
            }
        return MemoryPlan(
            budgetBytes = budget,
            lowMemory = lowMemory && !mappedExperts,
            kvBudgetBytes = finalKv,
            fits = minimum <= budget,
            minimumBytes = minimum,
            ubatch = ubatch,
            repackRelease = mappedExperts || (!lowMemory && !repackFitsTwice),
            longContextBytes = if (safeLevel > 0) 0L else boostedKv - kvBudget,
            mappedExperts = mappedExperts,
        )
    }

    /**
     * Extra context memory for "Contexto longo": automatic on phones of 12 GB and up for models
     * without recurrent layers (those already get long contexts from little memory); when the user
     * turns it on, only while [fits] still leaves room for the screen and other apps.
     */
    private fun longContextBoost(
        choice: TuningSwitch,
        memory: MemorySnapshot,
        weights: WeightProfile,
        safeLevel: Int,
        fits: (Long) -> Boolean,
    ): Long {
        if (safeLevel > 0 || choice == TuningSwitch.OFF) return 0L
        val boost = if (memory.advertisedGigabytes >= 16.0) LARGE_PHONE_LONG_CONTEXT_BOOST_BYTES else LONG_CONTEXT_BOOST_BYTES
        return when (choice) {
            TuningSwitch.AUTO -> if (memory.advertisedGigabytes >= AUTO_PERFORMANCE_MIN_GB && weights.known &&
                !weights.recurrent && fits(boost)
            ) {
                boost
            } else {
                0L
            }
            else -> if (fits(boost)) boost else 0L
        }
    }

    /**
     * Micro-batch for reading a prompt. Each micro-batch of a long message makes a mixture of experts
     * use nearly all its experts, so when they are read from storage a larger one reads the file fewer
     * times; for a dense model in RAM a larger one is not faster.
     */
    fun ubatchFor(choice: TuningSwitch, streamsExperts: Boolean, mixtureOfExperts: Boolean, memory: MemorySnapshot, safeLevel: Int): Int = when {
        safeLevel > 0 || choice == TuningSwitch.OFF -> DEFAULT_UBATCH
        streamsExperts && choice == TuningSwitch.ON && memory.advertisedGigabytes >= AUTO_PERFORMANCE_MIN_GB -> 2048
        streamsExperts -> 1024
        mixtureOfExperts -> 512
        else -> DEFAULT_UBATCH
    }

    /** "Modo rápido de MoE" keeps this share of the experts each token goes through, rounded up. */
    const val FAST_MOE_KEEP_FRACTION = 0.75
    /** Models that route each token to fewer experts (Mixtral: 2) keep them all. */
    const val FAST_MOE_MIN_EXPERTS = 4
    /** The automatic mode only trims models that use at least this many (8 → 6), where little is lost. */
    const val FAST_MOE_AUTO_MIN_EXPERTS = 8

    /**
     * Experts per token for "Modo rápido de MoE", or null to keep the number in the GGUF
     * ([usedExperts] of [totalExperts]). Fewer experts per token means a quarter fewer read from
     * storage when they are streamed ([streamsExperts]) and a quarter less computed in RAM, with no
     * memory added; the answers may lose a little quality. AUTO only trims models read from
     * storage, where the gain is largest, and only from 8 experts per token.
     */
    fun fastMoeExperts(usedExperts: Int?, totalExperts: Int?, choice: TuningSwitch, streamsExperts: Boolean): Int? {
        if (usedExperts == null || usedExperts < FAST_MOE_MIN_EXPERTS) return null
        if (totalExperts != null && totalExperts < usedExperts) return null
        val enabled = when (choice) {
            TuningSwitch.OFF -> false
            TuningSwitch.ON -> true
            TuningSwitch.AUTO -> streamsExperts && usedExperts >= FAST_MOE_AUTO_MIN_EXPERTS
        }
        if (!enabled) return null
        return kotlin.math.ceil(usedExperts * FAST_MOE_KEEP_FRACTION).toInt().coerceIn(2, usedExperts - 1)
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
