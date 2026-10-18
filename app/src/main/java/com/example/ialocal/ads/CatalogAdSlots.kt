package com.example.ialocal.ads

/**
 * Where native ads go in the catalog: after the 2nd company and then after every 4th one (6th,
 * 10th…), never after the last company, which the bottom banner already follows.
 */
object CatalogAdSlots {
    private const val FIRST_AFTER = 2
    private const val EVERY = 4

    /** [groupIndex] is 0-based. */
    fun showsAfterGroup(groupIndex: Int, groupCount: Int): Boolean {
        if (groupIndex < 0 || groupIndex >= groupCount - 1) return false
        val position = groupIndex + 1
        return position >= FIRST_AFTER && (position - FIRST_AFTER) % EVERY == 0
    }
}
