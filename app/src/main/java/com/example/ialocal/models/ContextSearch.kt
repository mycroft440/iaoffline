package com.example.ialocal.models

/**
 * Finds the largest stable context without linearly walking every possible size.
 *
 * Strategy:
 * 1. prove a conservative baseline;
 * 2. jump directly to 32k (the product target);
 * 3. if 32k works, grow exponentially until the model limit or first failure;
 * 4. after a failure, binary-search the interval in 1024-token steps.
 *
 * A failed probe is assumed monotonic: if N tokens cannot allocate a native context,
 * larger contexts are not attempted until the search has moved below N.
 */
class ContextSearch(
    private val stepTokens: Int = 1024,
    private val baselineTokens: Int = 8192,
    private val targetTokens: Int = 32768,
) {
    data class Attempt(val contextTokens: Int, val success: Boolean)

    data class Result(
        val maxStableTokens: Int?,
        val firstFailedTokens: Int?,
        val attempts: List<Attempt>,
    )

    suspend fun search(
        maxContextTokens: Int,
        probe: suspend (Int) -> Boolean,
    ): Result {
        require(stepTokens >= 1)
        val maximum = normalizeDown(maxContextTokens.coerceAtLeast(stepTokens))
        val attempts = mutableListOf<Attempt>()

        suspend fun runProbe(candidate: Int): Boolean {
            val success = probe(candidate)
            attempts += Attempt(candidate, success)
            return success
        }

        var knownGood = normalizeDown(minOf(baselineTokens, maximum)).coerceAtLeast(stepTokens)
        if (!runProbe(knownGood)) {
            return Result(
                maxStableTokens = null,
                firstFailedTokens = knownGood,
                attempts = attempts,
            )
        }

        var knownBad: Int? = null
        val productTarget = normalizeDown(minOf(targetTokens, maximum))
        if (productTarget > knownGood) {
            if (runProbe(productTarget)) knownGood = productTarget else knownBad = productTarget
        }

        while (knownBad == null && knownGood < maximum) {
            val doubled = knownGood.toLong() * 2L
            val next = normalizeDown(minOf(maximum.toLong(), doubled).toInt())
            if (next <= knownGood) break
            if (runProbe(next)) knownGood = next else knownBad = next
        }

        while (knownBad != null && knownBad - knownGood > stepTokens) {
            var candidate = normalizeDown(knownGood + (knownBad - knownGood) / 2)
            if (candidate <= knownGood) candidate = knownGood + stepTokens
            if (candidate >= knownBad) break
            if (runProbe(candidate)) knownGood = candidate else knownBad = candidate
        }

        return Result(
            maxStableTokens = knownGood,
            firstFailedTokens = knownBad,
            attempts = attempts,
        )
    }

    private fun normalizeDown(value: Int): Int = (value / stepTokens) * stepTokens
}
