package com.example.ialocal.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun pausedDownloadStateIsNotBusyAndKeepsProgressForResume() {
        val state = ModelDownloadState(
            catalogId = "test-model",
            phase = ModelDownloadPhase.CANCELLED,
            downloadedBytes = 50L,
            totalBytes = 100L,
        )

        assertFalse(state.isBusy)
        assertEquals(0.5f, state.progress ?: -1f, 0.0001f)
    }

    @Test
    fun networkUnavailableExceptionKeepsSavedByteCount() {
        val error = NetworkUnavailableException(downloadedBytes = 1234L)

        assertEquals(1234L, error.downloadedBytes)
    }
}
