package com.example.ialocal.ui.models

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
}
