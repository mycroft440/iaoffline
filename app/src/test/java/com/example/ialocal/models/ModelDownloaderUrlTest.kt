package com.example.ialocal.models

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelDownloaderUrlTest {
    @Test
    fun huggingFaceUrlPrefersOfficialShortDomainAndKeepsCanonicalFallback() {
        val canonical = "https://huggingface.co/org/repo/resolve/main/model.gguf?download=true"

        assertEquals(
            listOf(
                "https://hf.co/org/repo/resolve/main/model.gguf?download=true",
                canonical,
            ),
            downloadUrlCandidates(canonical),
        )
    }

    @Test
    fun nonHuggingFaceUrlIsNotRewritten() {
        val url = "https://example.com/model.gguf"

        assertEquals(listOf(url), downloadUrlCandidates(url))
    }
}
