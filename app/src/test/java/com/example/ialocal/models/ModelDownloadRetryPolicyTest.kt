package com.example.ialocal.models

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelDownloadRetryPolicyTest {
    @Test
    fun firstErrorRetriesImmediatelyThenEveryThirtySeconds() {
        assertEquals(0L, ModelDownloadRetryPolicy.delayBeforeRetryMs(1))
        assertEquals(30_000L, ModelDownloadRetryPolicy.delayBeforeRetryMs(2))
        assertEquals(30_000L, ModelDownloadRetryPolicy.delayBeforeRetryMs(3))
        assertEquals(30_000L, ModelDownloadRetryPolicy.RETRY_INTERVAL_MS)
    }

    @Test
    fun consecutiveOfflineFailuresSaturateWithoutOverflowing() {
        var failures = 0
        failures = ModelDownloadRetryPolicy.nextFailureCount(failures)
        assertEquals(1, failures)
        failures = ModelDownloadRetryPolicy.nextFailureCount(failures)
        assertEquals(2, failures)
        failures = ModelDownloadRetryPolicy.nextFailureCount(failures)
        assertEquals(2, failures)
        assertEquals(2, ModelDownloadRetryPolicy.nextFailureCount(Int.MAX_VALUE))
    }

    @Test
    fun insufficientDiskAndDeniedFileAccessAreNotRetriedForever() {
        assertTrue(ModelDownloadRetryPolicy.isStorageFailure(IOException("write failed: ENOSPC")))
        assertTrue(ModelDownloadRetryPolicy.isStorageFailure(IOException("No space left on device")))
        assertTrue(ModelDownloadRetryPolicy.isStorageFailure(IOException("Permission denied")))
        assertTrue(ModelDownloadRetryPolicy.isStorageFailure(IOException("Read-only file system")))
        assertTrue(ModelDownloadRetryPolicy.isStorageFailure(IOException("writing", IOException("EACCES"))))
    }

    @Test
    fun transientNetworkIoIsNotMistakenForPermanentStorageFailure() {
        assertFalse(ModelDownloadRetryPolicy.isStorageFailure(IOException("Connection reset by peer")))
        assertFalse(ModelDownloadRetryPolicy.isStorageFailure(IOException("HTTP 503")))
        assertFalse(ModelDownloadRetryPolicy.isStorageFailure(IOException("Read timed out")))
    }
}
