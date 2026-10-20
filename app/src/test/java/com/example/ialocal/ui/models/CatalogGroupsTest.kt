package com.example.ialocal.ui.models

import com.example.ialocal.data.AiModelEntity
import com.example.ialocal.models.CatalogModel
import com.example.ialocal.models.ModelCatalog
import com.example.ialocal.models.ModelProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogGroupsTest {
    private val groups = catalogGroups(ModelCatalog.entries)

    @Test
    fun moeModelsComeFirstInTheirOwnSectionStillByCompany() {
        val moe = groups.takeWhile { it.moe }
        val dense = groups.drop(moe.size)
        assertTrue(moe.isNotEmpty())
        assertTrue(dense.none { it.moe })
        groups.forEach { group ->
            assertTrue(group.models.all { it.provider == group.provider && it.isMoe == group.moe })
        }
        // Each section lists its companies once, in the catalog's order.
        listOf(moe, dense).forEach { section ->
            val providers = section.map { it.provider }
            assertEquals(providers.distinct(), providers)
            assertEquals(CATALOG_PROVIDER_ORDER.filter { it in providers }, providers)
        }
    }

    @Test
    fun everyModelIsListedOnce() {
        assertTrue(ModelCatalog.entries.all { it.provider in CATALOG_PROVIDER_ORDER })
        val listed = groups.flatMap { it.models }
        assertEquals(ModelCatalog.entries.size, listed.size)
        assertEquals(ModelCatalog.entries.toSet(), listed.toSet())
    }

    @Test
    fun aCompanyKeepsItsMoeAndDenseModelsApart() {
        val google = catalogGroups(ModelCatalog.entries, onlyProvider = ModelProvider.GOOGLE)
        assertEquals(listOf(true, false), google.map { it.moe })
        assertTrue(google[0].models.any { it.displayName == "Gemma 4 26B-A4B" })
        assertTrue(google[1].models.any { it.displayName == "Gemma 4 E2B" })
        assertFalse(google[1].models.any { it.isMoe })
        // A company without MoE models has only its dense group.
        assertEquals(listOf(false), catalogGroups(ModelCatalog.entries, onlyProvider = ModelProvider.META).map { it.moe })
    }

    @Test
    fun moeModelsThePhoneCannotRunGoLast() {
        val eightGb = 8_000_000_000L
        val sections = catalogGroups(ModelCatalog.entries, deviceRamBytes = eightGb)
        assertEquals(
            listOf(CatalogSection.MOE, CatalogSection.DENSE, CatalogSection.MOE_NEEDS_MORE_RAM),
            sections.map { it.section }.distinct(),
        )
        val runnable = sections.filter { it.section == CatalogSection.MOE }.flatMap { it.models }
        val needMore = sections.filter { it.section == CatalogSection.MOE_NEEDS_MORE_RAM }.flatMap { it.models }
        // Experimental MoE models run from storage on any phone; the others need their RAM.
        assertTrue(runnable.all { it.isMoe && (it.isExperimental || it.recommendedRamBytes <= eightGb) })
        assertTrue(runnable.any { it.displayName == "Gemma 4 26B-A4B" })
        assertTrue(needMore.all { it.isMoe && !it.isExperimental && it.recommendedRamBytes > eightGb })
        assertTrue(needMore.any { it.displayName == "Mellum 2.1 12B-A2.5B" })
        // Nothing is lost or listed twice.
        assertEquals(ModelCatalog.entries.toSet(), sections.flatMap { it.models }.toSet())
        assertEquals(ModelCatalog.entries.size, sections.sumOf { it.models.size })
    }

    @Test
    fun recommendsEveryModelThatFitsCurrentFreeRamWithoutLimitsPerCompany() {
        val free = 6L * 1024 * 1024 * 1024
        val recommended = recommendedModels(ModelCatalog.entries, freeRamBytes = free)
        val fitting = ModelCatalog.entries.filter { estimatedCatalogRamBytes(it) <= free }
        assertTrue(recommended.fitFreeRam)
        assertTrue(fitting.size > 3)
        assertEquals(fitting.toSet(), recommended.models.toSet())
        assertEquals(fitting.size, recommended.models.size)
        assertEquals(
            fitting.sortedWith(compareByDescending<CatalogModel> { estimatedCatalogRamBytes(it) }.thenBy { it.id }),
            recommended.models,
        )
        assertTrue(recommended.models.groupingBy { it.provider }.eachCount().values.any { it > 1 })
        recommended.models.forEach { assertTrue(estimatedCatalogRamBytes(it) <= free) }
    }

    @Test
    fun includesEvenInstalledAndExperimentalModelsWhenTheirRamEstimateFits() {
        val result = recommendedModels(ModelCatalog.entries, freeRamBytes = Long.MAX_VALUE)
        assertTrue(result.fitFreeRam)
        assertEquals(ModelCatalog.entries.size, result.models.size)
        assertEquals(ModelCatalog.entries.toSet(), result.models.toSet())
        assertTrue(result.models.any { it.isExperimental })
    }

    @Test
    fun firstRecommendationIsTheFreeDownloadInEveryCatalogSection() {
        val result = recommendedModels(ModelCatalog.entries, freeRamBytes = 6L * 1024 * 1024 * 1024)
        assertTrue(result.models.size > 1)
        assertEquals(result.models.first().id, result.freeDownloadModelId)
        assertEquals(1, result.models.count { it.id == result.freeDownloadModelId })
    }

    @Test
    fun fallbackAlsoProvidesTheFirstFreeModelButEmptyCatalogDoesNot() {
        val fallback = recommendedModels(ModelCatalog.entries, 0L)
        assertFalse(fallback.fitFreeRam)
        assertEquals(fallback.models.first().id, fallback.freeDownloadModelId)
        assertEquals(null, recommendedModels(emptyList(), 0L).freeDownloadModelId)
    }

    @Test
    fun changesInFreeRamChangeTheNumberOfModelsRecommended() {
        val lowBytes = 2_000_000_000L
        val highBytes = 5_000_000_000L
        val low = recommendedModels(ModelCatalog.entries, freeRamBytes = lowBytes)
        val high = recommendedModels(ModelCatalog.entries, freeRamBytes = highBytes)
        assertTrue(low.fitFreeRam)
        assertTrue(high.fitFreeRam)
        assertTrue(high.models.size > low.models.size)
        assertTrue(high.models.containsAll(low.models))
        assertEquals(ModelCatalog.entries.count { estimatedCatalogRamBytes(it) <= lowBytes }, low.models.size)
        assertEquals(ModelCatalog.entries.count { estimatedCatalogRamBytes(it) <= highBytes }, high.models.size)
    }

    @Test
    fun whenNoneFitsSuggestsTheFiveLowestRamEstimates() {
        val free = 200L * 1024 * 1024
        val recommended = recommendedModels(ModelCatalog.entries, freeRamBytes = free)
        assertFalse(recommended.fitFreeRam)
        assertEquals(LOW_RAM_FALLBACK_MODEL_COUNT, recommended.models.size)
        assertEquals(
            ModelCatalog.entries.sortedWith(
                compareBy<CatalogModel> { estimatedCatalogRamBytes(it) }.thenBy { it.id },
            ).take(LOW_RAM_FALLBACK_MODEL_COUNT),
            recommended.models,
        )
        assertTrue(recommended.models.all { estimatedCatalogRamBytes(it) > free })
    }

    @Test
    fun exactlyEnoughRamIncludesAllTiesAndEmptyCatalogIsHandled() {
        val minBytes = ModelCatalog.entries.minOf { estimatedCatalogRamBytes(it) }
        val exact = recommendedModels(ModelCatalog.entries, freeRamBytes = minBytes)
        val below = recommendedModels(ModelCatalog.entries, freeRamBytes = minBytes - 1)
        assertTrue(exact.fitFreeRam)
        assertEquals(ModelCatalog.entries.filter { estimatedCatalogRamBytes(it) <= minBytes }.toSet(), exact.models.toSet())
        assertFalse(below.fitFreeRam)
        assertFalse(recommendedModels(emptyList(), freeRamBytes = 0).fitFreeRam)
        assertTrue(recommendedModels(emptyList(), freeRamBytes = 0).models.isEmpty())
    }

    @Test
    fun moeRamEstimateUsesTheWholeGgufAndNotOnlyActiveParameters() {
        val moe = ModelCatalog.requireById("lfm2-8b-a1b-q4-k-m")
        assertTrue(moe.isMoe)
        assertEquals(
            moe.approximateSizeBytes + CATALOG_WORKING_MEMORY_BYTES,
            estimatedFullMoeRamBytes(moe),
        )
        assertTrue(estimatedFullMoeRamBytes(moe) > 5_700_000_000L)
        assertTrue(moe.activeParametersBillions!! < moe.totalParametersBillions)
    }

    @Test
    fun moeRamEstimateAccountsForTheQuantizedGgufFileNotParameterNames() {
        val small = ModelCatalog.requireById("granite3.1-1b-a400m-q4-k-m")
        val large = ModelCatalog.requireById("gpt-oss-13.1b-pruned-q5-k-m")
        assertEquals(
            large.approximateSizeBytes - small.approximateSizeBytes,
            estimatedFullMoeRamBytes(large) - estimatedFullMoeRamBytes(small),
        )
        assertTrue(estimatedFullMoeRamBytes(large) > estimatedFullMoeRamBytes(small))
    }

    @Test
    fun homePreviewKeepsAllSevenInstalledModelsVisibleInTwoColumnTiles() {
        val installed = (1..7).map { index ->
            model("installed-$index", "Modelo $index").copy(importedAt = index.toLong())
        }
        val result = homeAiPreview(installed)
        assertEquals(7, result.models.size)
        assertEquals(0, result.remainingCount)
        assertEquals(installed.reversed().map { it.id }, result.models.map { it.id })
    }

    @Test
    fun homePreviewShowsAtMostEightModelsWithCorrectRemainingCount() {
        val installed = (1..13).map { index ->
            model("installed-$index", "Modelo $index").copy(importedAt = index.toLong())
        }
        val result = homeAiPreview(installed)
        assertEquals(HOME_MODEL_PREVIEW_LIMIT, result.models.size)
        assertEquals(5, result.remainingCount)
        assertEquals((13 downTo 6).map { "installed-$it" }, result.models.map { it.id })
    }

    @Test
    fun homePreviewPrioritizesActiveAiAndHandlesEmptyList() {
        val oldActive = model("active", "Em uso").copy(isActive = true, importedAt = 1L)
        val newest = model("new", "Recente").copy(importedAt = 99L)
        assertEquals(listOf(oldActive, newest), homeAiPreview(listOf(newest, oldActive)).models)
        assertTrue(homeAiPreview(emptyList()).models.isEmpty())
        assertEquals(0, homeAiPreview(emptyList()).remainingCount)
    }

    @Test
    fun compactNamesDropTheFileDetails() {
        val gemma = ModelCatalog.entries.first { it.displayName == "Gemma 4 26B-A4B QAT" }
        assertEquals("Gemma 4 26B-A4B QAT", compactModelName(model(gemma.apiIdPrefix + "abc", "qualquer nome")))
        assertEquals("Qwen3-4B-Instruct-2507", compactModelName(model("local-x", "Qwen3-4B-Instruct-2507-Q4_K_M.gguf")))
        assertEquals("Mistral-7B-Instruct-v0.3", compactModelName(model("local-y", "Mistral-7B-Instruct-v0.3.Q4_K_M")))
        assertEquals("GLM-4.7-Flash", compactModelName(model("local-z", "GLM-4.7-Flash-UD-Q2_K_XL.gguf")))
        assertEquals("Phi-4-mini", compactModelName(model("local-w", "Phi-4-mini-BF16")))
        assertEquals("Qwen3-4B", compactModelName(model("local-v", "Qwen3-4B")))
    }

    private fun model(apiModelId: String, name: String) = AiModelEntity(
        id = apiModelId,
        name = name,
        apiModelId = apiModelId,
        format = "GGUF",
        architecture = null,
        filePath = "/x.gguf",
        sizeBytes = 1L,
        importedAt = 0L,
    )
}
