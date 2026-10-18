package com.example.ialocal.ads

import org.junit.Assert.assertEquals
import org.junit.Test

class CatalogAdSlotsTest {
    @Test
    fun adsFollowTheSecondCompanyAndThenEveryFourth() {
        val slots = (0 until 14).filter { CatalogAdSlots.showsAfterGroup(it, groupCount = 14) }
        assertEquals(listOf(1, 5, 9), slots)
    }

    @Test
    fun neverAfterTheLastCompany() {
        assertEquals(emptyList<Int>(), (0 until 2).filter { CatalogAdSlots.showsAfterGroup(it, groupCount = 2) })
        assertEquals(emptyList<Int>(), (0 until 1).filter { CatalogAdSlots.showsAfterGroup(it, groupCount = 1) })
        assertEquals(listOf(1), (0 until 6).filter { CatalogAdSlots.showsAfterGroup(it, groupCount = 6) })
    }
}
