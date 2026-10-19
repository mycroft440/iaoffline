package com.example.ialocal.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FoundStorageModelTest {
    private fun model(name: String, status: FoundModelStatus, copied: Long = 0L) = FoundStorageModel(
        path = "/storage/emulated/0/Download/$name.gguf",
        fileName = "$name.gguf",
        name = name,
        sizeBytes = 1_000L,
        status = status,
        copiedBytes = copied,
    )

    @Test
    fun updateChangesOnlyTheGivenFile() {
        val found = listOf(model("a", FoundModelStatus.WAITING), model("b", FoundModelStatus.WAITING))

        val updated = found.update(1) { it.copy(status = FoundModelStatus.IMPORTING) }

        assertEquals(listOf(FoundModelStatus.WAITING, FoundModelStatus.IMPORTING), updated.map { it.status })
        assertEquals(found, found.update(5) { it.copy(status = FoundModelStatus.FAILED) })
    }

    @Test
    fun cancelKeepsWhatAlreadyHappenedAndCancelsTheRest() {
        val found = listOf(
            model("imported", FoundModelStatus.IMPORTED),
            model("copying", FoundModelStatus.IMPORTING, copied = 400L),
            model("checking", FoundModelStatus.CHECKING),
            model("queued", FoundModelStatus.WAITING),
            model("invalid", FoundModelStatus.INVALID),
        )

        val cancelled = found.cancelPending()

        assertEquals(
            listOf(
                FoundModelStatus.IMPORTED,
                FoundModelStatus.CANCELLED,
                FoundModelStatus.CANCELLED,
                FoundModelStatus.CANCELLED,
                FoundModelStatus.INVALID,
            ),
            cancelled.map { it.status },
        )
        assertEquals(0L, cancelled[1].copiedBytes)
    }

    @Test
    fun copyFractionOnlyWhileImporting() {
        assertEquals(0.4f, model("a", FoundModelStatus.IMPORTING, copied = 400L).copyFraction!!, 0.0001f)
        assertNull(model("a", FoundModelStatus.IMPORTED, copied = 1_000L).copyFraction)
        assertNull(model("a", FoundModelStatus.IMPORTING).copy(sizeBytes = 0L).copyFraction)
    }
}
