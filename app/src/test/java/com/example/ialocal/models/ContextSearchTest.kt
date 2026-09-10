package com.example.ialocal.models

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContextSearchTest {
    @Test
    fun `finds 23k when larger probes fail`() = runBlocking {
        val threshold = 23 * 1024
        val result = ContextSearch().search(maxContextTokens = 64 * 1024) { candidate ->
            candidate <= threshold
        }

        assertEquals(threshold, result.maxStableTokens)
        assertEquals(24 * 1024, result.firstFailedTokens)
    }

    @Test
    fun `stops at model declared limit when every probe works`() = runBlocking {
        val result = ContextSearch().search(maxContextTokens = 48 * 1024) { true }

        assertEquals(48 * 1024, result.maxStableTokens)
        assertNull(result.firstFailedTokens)
    }

    @Test
    fun `returns no stable context when conservative baseline fails`() = runBlocking {
        val result = ContextSearch().search(maxContextTokens = 32 * 1024) { false }

        assertNull(result.maxStableTokens)
        assertEquals(8 * 1024, result.firstFailedTokens)
    }
}
