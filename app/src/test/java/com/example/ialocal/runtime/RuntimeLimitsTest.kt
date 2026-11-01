package com.example.ialocal.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeLimitsTest {
    private val gb = 1024L * 1024L * 1024L

    @Test
    fun eightBillionModelOnTwelveGigabytePhoneUsesLowMemoryMode() {
        // S22 Ultra reports ~11 GB total; an 8B Q4_K_M file is ~4.9 GB.
        assertTrue(RuntimeLimits.needsLowMemoryMode(modelBytes = 49 * gb / 10, deviceTotalMemory = 11 * gb))
    }

    @Test
    fun fourBillionModelOnTwelveGigabytePhoneKeepsTheFastPath() {
        assertFalse(RuntimeLimits.needsLowMemoryMode(modelBytes = 25 * gb / 10, deviceTotalMemory = 11 * gb))
    }

    @Test
    fun deepSeekLatentAttentionModelsKeepTheirWeightsUnrepacked() {
        // GigaChat 3.1 10B aborted the AI process with repacked weights and ran without them.
        assertTrue(RuntimeLimits.needsPlainWeights("deepseek2"))
        assertFalse(RuntimeLimits.needsPlainWeights("qwen3"))
        assertFalse(RuntimeLimits.needsPlainWeights(null))
    }

    @Test
    fun unknownDeviceMemoryDoesNotForceLowMemoryMode() {
        assertFalse(RuntimeLimits.needsLowMemoryMode(modelBytes = 5 * gb, deviceTotalMemory = 0))
    }

    @Test
    fun kvBudgetIsWhatRemainsOfTheRamShareAfterTheModel() {
        // 40% of 11 GB is 4.4 GB; a 4B model of 2.5 GB leaves 1.9 GB for the context.
        val budget = RuntimeLimits.kvCacheBudgetBytes(25 * gb / 10, 11 * gb, experimental = false)
        assertEquals(1.9, budget.toDouble() / gb, 0.01)
    }

    @Test
    fun kvBudgetStaysWithinItsBounds() {
        assertEquals(RuntimeLimits.MIN_KV_BUDGET_BYTES, RuntimeLimits.kvCacheBudgetBytes(6 * gb, 11 * gb, experimental = false))
        assertEquals(RuntimeLimits.MAX_KV_BUDGET_BYTES, RuntimeLimits.kvCacheBudgetBytes(gb / 2, 16 * gb, experimental = false))
        assertEquals(RuntimeLimits.MIN_KV_BUDGET_BYTES, RuntimeLimits.kvCacheBudgetBytes(gb, 0, experimental = false))
    }

    @Test
    fun aModelThatCrashedTheAppGetsLessContextMemoryEachTime() {
        val budget = 2 * gb
        assertEquals(budget, RuntimeLimits.safeKvBudgetBytes(budget, safeLevel = 0))
        assertEquals(gb, RuntimeLimits.safeKvBudgetBytes(budget, safeLevel = 1))
        assertEquals(RuntimeLimits.MIN_KV_BUDGET_BYTES, RuntimeLimits.safeKvBudgetBytes(budget, safeLevel = 2))
        // Never below the smallest context.
        assertEquals(
            RuntimeLimits.MIN_KV_BUDGET_BYTES,
            RuntimeLimits.safeKvBudgetBytes(RuntimeLimits.MIN_KV_BUDGET_BYTES, safeLevel = 1),
        )
    }

    @Test
    fun gemma4E4bWithItsEmbeddingsOnStorageGetsARealContext() {
        // E4B QAT: 5.15 GB file, 2.31 GB of per-layer embeddings read on demand.
        val resident = 5_154_941_280L - 2_312_110_080L
        val budget = RuntimeLimits.kvCacheBudgetBytes(resident, 11 * gb, experimental = false)
        assertTrue(budget > gb)
        assertEquals(
            RuntimeLimits.MIN_KV_BUDGET_BYTES,
            RuntimeLimits.kvCacheBudgetBytes(5_154_941_280L, 11 * gb, experimental = false),
        )
    }

    private fun memory(totalGb: Double, freeGb: Double) =
        MemorySnapshot(totalBytes = (totalGb * gb).toLong(), availableBytes = (freeGb * gb).toLong())

    @Test
    fun theAiGetsTheFreeRamMinusHalfAGigabyte() {
        // 10 GB phone with 8 GB free: 7.5 GB for the AI.
        assertEquals(7.5, RuntimeLimits.aiBudgetBytes(memory(10.0, 8.0)).toDouble() / gb, 0.001)
        // Little free right now: Android will close background apps, but keeps its own share.
        assertEquals(10.0 - 3.5 - 0.5, RuntimeLimits.aiBudgetBytes(memory(10.0, 2.0)).toDouble() / gb, 0.001)
    }

    @Test
    fun aDenseModelThatCannotFitIsRefusedInsteadOfFreezingThePhone() {
        // Gemma 4 12B QAT: 7 GB of weights, with 7.5 GB for the AI.
        val plan = RuntimeLimits.planMemory(7 * gb, memory(10.0, 8.0), expertBytes = 0, safeLevel = 0)
        assertFalse(plan.fits)
        assertFalse(plan.streamsExperts)
        assertTrue(plan.minimumBytes > plan.budgetBytes)
    }

    @Test
    fun aMixtureOfExpertsLargerThanTheRamReadsItsExpertsFromStorage() {
        // Gemma 4 26B-A4B UD-Q3_K_M on a 12 GB S22 Ultra (about 11 GB reported): 12.71 GB of
        // weights, 10.31 GB of them experts; the AI gets 6.6 GB.
        val plan = RuntimeLimits.planMemory(12_710_000_000L, memory(11.0, 5.0), expertBytes = 10_310_000_000L, safeLevel = 0)
        assertTrue(plan.streamsExperts)
        assertTrue(plan.fits)
        // The other 2.4 GB fit twice (while repacked), so they are kept in RAM.
        assertFalse(plan.lowMemory)
        assertEquals(RuntimeLimits.EXPERIMENTAL_KV_BUDGET_BYTES, plan.kvBudgetBytes)
        assertEquals(2_400_000_000L + RuntimeLimits.MIN_KV_BUDGET_BYTES + RuntimeLimits.WORKING_MEMORY_BYTES, plan.minimumBytes)
    }

    @Test
    fun aMixtureOfExpertsKeepsItsSharedWeightsFileBackedWhenTwoCopiesDoNotFit() {
        // 8 GB phone (about 7.3 GB reported): 4.2 GB for the AI, less than two copies of 2.4 GB.
        val plan = RuntimeLimits.planMemory(12_710_000_000L, memory(7.3, 3.0), expertBytes = 10_310_000_000L, safeLevel = 0)
        assertTrue(plan.streamsExperts)
        assertTrue(plan.fits)
        assertTrue(plan.lowMemory)
    }

    @Test
    fun aMixtureOfExpertsWhoseSharedWeightsDoNotFitIsRefused() {
        val plan = RuntimeLimits.planMemory(60 * gb, memory(7.3, 3.0), expertBytes = 50 * gb, safeLevel = 0)
        assertTrue(plan.streamsExperts)
        assertFalse(plan.fits)
    }

    @Test
    fun aMixtureOfExpertsThatFitsLoadsLikeAnyOtherModel() {
        // Granite 4.0 H Tiny (7B-A1B, about 4 GB) on a 12 GB phone.
        val plan = RuntimeLimits.planMemory(4 * gb, memory(11.0, 5.0), expertBytes = 3 * gb, safeLevel = 0)
        assertFalse(plan.streamsExperts)
        assertTrue(plan.fits)
    }

    @Test
    fun aMixtureOfExpertsThatCrashedKeepsItsSharedWeightsFileBacked() {
        val plan = RuntimeLimits.planMemory(12_710_000_000L, memory(11.0, 5.0), expertBytes = 10_310_000_000L, safeLevel = 1)
        assertTrue(plan.lowMemory)
        assertEquals(RuntimeLimits.MIN_KV_BUDGET_BYTES, plan.kvBudgetBytes)
    }

    @Test
    fun repackingNeedsRoomForTwoCopiesOfTheWeights() {
        // 6 GB phone, 1.5 GB of weights: the 30% rule alone would repack them, but the copy and
        // the mapped file together do not fit in the 3.4 GB the AI gets.
        val tight = RuntimeLimits.planMemory(3 * gb / 2, memory(6.0, 3.0), expertBytes = 0, safeLevel = 0)
        assertTrue(tight.fits)
        assertTrue(tight.lowMemory)
        assertEquals(0.9, tight.kvBudgetBytes.toDouble() / gb, 0.001)

        val roomy = RuntimeLimits.planMemory(2 * gb, memory(16.0, 12.0), expertBytes = 0, safeLevel = 0)
        assertFalse(roomy.lowMemory)
        assertEquals(RuntimeLimits.MAX_KV_BUDGET_BYTES, roomy.kvBudgetBytes)
    }

    @Test
    fun contextNeverTakesMoreThanTheBudgetLeaves() {
        // 3 GB phone with almost nothing free: 1 GB for the AI, 0.3 GB of weights.
        val plan = RuntimeLimits.planMemory(3 * gb / 10, memory(3.0, 0.5), expertBytes = 0, safeLevel = 0)
        assertEquals(RuntimeLimits.MIN_KV_BUDGET_BYTES, plan.kvBudgetBytes)
    }

    @Test
    fun aModelThatCrashedLoadsLeaner() {
        val plan = RuntimeLimits.planMemory(2 * gb, memory(16.0, 12.0), expertBytes = 0, safeLevel = 1)
        assertTrue(plan.lowMemory)
        assertEquals(RuntimeLimits.MAX_KV_BUDGET_BYTES / 2, plan.kvBudgetBytes)
    }

    private val q4Weights = RuntimeLimits.WeightProfile(
        known = true,
        repackableBytes = 2_700_000_000L,
        requantizedBytes = 300_000_000L,
        largestRequantizedValues = 600_000_000L,
    )

    @Test
    fun economyKeepsTheWeightsInTheFile() {
        val plan = RuntimeLimits.planMemory(
            2 * gb,
            memory(16.0, 12.0),
            expertBytes = 0,
            safeLevel = 0,
            weights = q4Weights,
            tuning = RuntimeTuning(performance = PerformanceLevel.ECONOMY),
        )
        assertTrue(plan.lowMemory)
    }

    @Test
    fun maximumKeepsAnOptimizedCopyWhenOneCopyFits() {
        // Qwen3.5 4B Q4_K_M (2.7 GB) on an 8 GB phone: two copies do not fit, one does.
        val legacy = RuntimeLimits.planMemory(2_700_000_000L, memory(7.5, 4.0), expertBytes = 0, safeLevel = 0, weights = q4Weights)
        assertTrue(legacy.lowMemory)

        val max = RuntimeLimits.planMemory(
            2_700_000_000L,
            memory(7.5, 4.0),
            expertBytes = 0,
            safeLevel = 0,
            weights = q4Weights,
            tuning = RuntimeTuning(performance = PerformanceLevel.MAX),
        )
        assertFalse(max.lowMemory)
        // The loader drops the file's pages as each tensor is copied.
        assertTrue(max.repackRelease)

        // Automatic keeps the copy for phones of 12 GB and up only.
        val auto = RuntimeLimits.planMemory(
            2_700_000_000L,
            memory(7.5, 4.0),
            expertBytes = 0,
            safeLevel = 0,
            weights = q4Weights,
            tuning = RuntimeTuning(performance = PerformanceLevel.AUTO),
        )
        assertTrue(auto.lowMemory)
    }

    @Test
    fun automaticKeepsTheCopyOnLargePhones() {
        // 8B Q4_K_M (4.9 GB) on a 16 GB phone, above the 30% rule.
        val weights = q4Weights.copy(repackableBytes = 4_900_000_000L)
        val auto = RuntimeLimits.planMemory(
            4_900_000_000L,
            memory(15.0, 11.0),
            expertBytes = 0,
            safeLevel = 0,
            weights = weights,
            tuning = RuntimeTuning(performance = PerformanceLevel.AUTO),
        )
        assertFalse(auto.lowMemory)

        // A copy that made the AI process close is not kept again, nor after a crash.
        val blocked = RuntimeLimits.planMemory(
            4_900_000_000L,
            memory(15.0, 11.0),
            expertBytes = 0,
            safeLevel = 0,
            weights = weights,
            tuning = RuntimeTuning(performance = PerformanceLevel.AUTO),
            performanceBlocked = true,
        )
        assertTrue(blocked.lowMemory)
        val crashed = RuntimeLimits.planMemory(
            4_900_000_000L,
            memory(15.0, 11.0),
            expertBytes = 0,
            safeLevel = 1,
            weights = weights,
            tuning = RuntimeTuning(performance = PerformanceLevel.MAX),
        )
        assertTrue(crashed.lowMemory)
    }

    @Test
    fun weightsWithoutRepackedKernelsGainNothingFromTheCopy() {
        // A Q3_K_M file: little of it is of a repackable type.
        val weights = q4Weights.copy(repackableBytes = 300_000_000L, requantizedBytes = 300_000_000L)
        val plan = RuntimeLimits.planMemory(
            4_900_000_000L,
            memory(15.0, 11.0),
            expertBytes = 0,
            safeLevel = 0,
            weights = weights,
            tuning = RuntimeTuning(performance = PerformanceLevel.AUTO),
        )
        assertTrue(plan.lowMemory)
    }

    @Test
    fun longContextAddsMemoryToDenseModelsOnLargePhones() {
        val weights = q4Weights.copy(repackableBytes = 3 * gb)
        val normal = RuntimeLimits.planMemory(3 * gb, memory(11.2, 8.0), expertBytes = 0, safeLevel = 0, weights = weights)
        val long = RuntimeLimits.planMemory(
            3 * gb,
            memory(11.2, 8.0),
            expertBytes = 0,
            safeLevel = 0,
            weights = weights,
            tuning = RuntimeTuning(longContext = TuningSwitch.AUTO),
        )
        assertEquals(RuntimeLimits.LONG_CONTEXT_BOOST_BYTES, long.kvBudgetBytes - normal.kvBudgetBytes)
        assertEquals(RuntimeLimits.LONG_CONTEXT_BOOST_BYTES, long.longContextBytes)

        // Models with recurrent layers already get long contexts from little memory.
        val hybrid = RuntimeLimits.planMemory(
            3 * gb,
            memory(11.2, 8.0),
            expertBytes = 0,
            safeLevel = 0,
            weights = weights.copy(recurrent = true),
            tuning = RuntimeTuning(longContext = TuningSwitch.AUTO),
        )
        assertEquals(0L, hybrid.longContextBytes)
        // Nor after a crash.
        val crashed = RuntimeLimits.planMemory(
            3 * gb,
            memory(11.2, 8.0),
            expertBytes = 0,
            safeLevel = 1,
            weights = weights,
            tuning = RuntimeTuning(longContext = TuningSwitch.ON),
        )
        assertEquals(0L, crashed.longContextBytes)
    }

    @Test
    fun largerMicroBatchesOnlyForMixturesOfExperts() {
        val phone = memory(11.2, 6.0)
        assertEquals(1024, RuntimeLimits.ubatchFor(TuningSwitch.AUTO, true, true, phone, safeLevel = 0))
        assertEquals(2048, RuntimeLimits.ubatchFor(TuningSwitch.ON, true, true, phone, safeLevel = 0))
        assertEquals(512, RuntimeLimits.ubatchFor(TuningSwitch.AUTO, false, true, phone, safeLevel = 0))
        assertEquals(256, RuntimeLimits.ubatchFor(TuningSwitch.AUTO, false, false, phone, safeLevel = 0))
        assertEquals(256, RuntimeLimits.ubatchFor(TuningSwitch.OFF, true, true, phone, safeLevel = 0))
        assertEquals(256, RuntimeLimits.ubatchFor(TuningSwitch.ON, true, true, phone, safeLevel = 1))

        val plan = RuntimeLimits.planMemory(
            12_710_000_000L,
            memory(11.0, 5.0),
            expertBytes = 10_310_000_000L,
            safeLevel = 0,
            tuning = RuntimeTuning(longPromptMoe = TuningSwitch.AUTO),
        )
        assertTrue(plan.streamsExperts)
        assertEquals(1024, plan.ubatch)
    }

    @Test
    fun experimentalModelsGetAFixedKvBudget() {
        assertEquals(
            RuntimeLimits.EXPERIMENTAL_KV_BUDGET_BYTES,
            RuntimeLimits.kvCacheBudgetBytes(16 * gb, 11 * gb, experimental = true),
        )
    }
}
