package com.example.ialocal.runtime

object RuntimeLimits {
    /**
     * Largest answer the app requests: one full native context (8192 tokens). The model still stops
     * on its own when the answer is done; this only decides how long it may keep going. Past the
     * context window the native binding shifts the context (keeping the system prompt), so the
     * answer continues instead of being cut.
     */
    const val MAX_OUTPUT_TOKENS = 8192
    const val MIN_OUTPUT_TOKENS = 16
}
