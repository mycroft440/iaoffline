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
        val plan = RuntimeLimits.planMemory(7 * gb, memory(10.0, 8.0), experimental = false, safeLevel = 0)
        assertFalse(plan.fits)
        assertTrue(plan.minimumBytes > plan.budgetBytes)
        // Experimental MoE models read a few experts per token and are meant to run from storage.
        assertTrue(RuntimeLimits.planMemory(14 * gb, memory(10.0, 8.0), experimental = true, safeLevel = 0).fits)
    }

    @Test
    fun repackingNeedsRoomForTwoCopiesOfTheWeights() {
        // 6 GB phone, 1.5 GB of weights: the 30% rule alone would repack them, but the copy and
        // the mapped file together do not fit in the 3.4 GB the AI gets.
        val tight = RuntimeLimits.planMemory(3 * gb / 2, memory(6.0, 3.0), experimental = false, safeLevel = 0)
        assertTrue(tight.fits)
        assertTrue(tight.lowMemory)
        assertEquals(0.9, tight.kvBudgetBytes.toDouble() / gb, 0.001)

        val roomy = RuntimeLimits.planMemory(2 * gb, memory(16.0, 12.0), experimental = false, safeLevel = 0)
        assertFalse(roomy.lowMemory)
        assertEquals(RuntimeLimits.MAX_KV_BUDGET_BYTES, roomy.kvBudgetBytes)
    }

    @Test
    fun contextNeverTakesMoreThanTheBudgetLeaves() {
        // 3 GB phone with almost nothing free: 1 GB for the AI, 0.3 GB of weights.
        val plan = RuntimeLimits.planMemory(3 * gb / 10, memory(3.0, 0.5), experimental = false, safeLevel = 0)
        assertEquals(RuntimeLimits.MIN_KV_BUDGET_BYTES, plan.kvBudgetBytes)
    }

    @Test
    fun aModelThatCrashedLoadsLeaner() {
        val plan = RuntimeLimits.planMemory(2 * gb, memory(16.0, 12.0), experimental = false, safeLevel = 1)
        assertTrue(plan.lowMemory)
        assertEquals(RuntimeLimits.MAX_KV_BUDGET_BYTES / 2, plan.kvBudgetBytes)
    }

    @Test
    fun experimentalModelsGetAFixedKvBudget() {
        assertEquals(
            RuntimeLimits.EXPERIMENTAL_KV_BUDGET_BYTES,
            RuntimeLimits.kvCacheBudgetBytes(16 * gb, 11 * gb, experimental = true),
        )
    }
}
