package com.example.ialocal.models

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceCompatibilityCheckerTest {
    @Test
    fun hardwareHeuristicsDoNotBlockRuntimeAttemptWhenStorageIsAvailable() {
        val result = DeviceCompatibilityChecker.Result(
            primaryAbi = "unknown-abi",
            supportedAbi = false,
            totalRamBytes = 2L * 1024 * 1024 * 1024,
            availableRamBytes = 256L * 1024 * 1024,
            availableStorageBytes = 8L * 1024 * 1024 * 1024,
            estimatedModelRamBytes = 4L * 1024 * 1024 * 1024,
            canStore = true,
            likelyFitsRam = false,
            warnings = listOf("hardware não validado"),
        )

        assertTrue(result.canImport)
    }

    @Test
    fun insufficientStorageStillBlocksImportBecauseRuntimeCannotReceiveACompleteFile() {
        val result = DeviceCompatibilityChecker.Result(
            primaryAbi = "arm64-v8a",
            supportedAbi = true,
            totalRamBytes = 12L * 1024 * 1024 * 1024,
            availableRamBytes = 8L * 1024 * 1024 * 1024,
            availableStorageBytes = 128L * 1024 * 1024,
            estimatedModelRamBytes = null,
            canStore = false,
            likelyFitsRam = true,
            warnings = listOf("armazenamento insuficiente"),
        )

        assertFalse(result.canImport)
    }
}
