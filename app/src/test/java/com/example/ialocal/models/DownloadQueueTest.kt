package com.example.ialocal.models

import com.example.ialocal.models.DownloadEntryStatus.ACTIVE
import com.example.ialocal.models.DownloadEntryStatus.FAILED
import com.example.ialocal.models.DownloadEntryStatus.PAUSED
import com.example.ialocal.models.DownloadEntryStatus.QUEUED
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadQueueTest {
    @Test
    fun downloadsRunInTheOrderTheyWereChosen() {
        var queue = DownloadQueue().enqueue("a").enqueue("b").enqueue("c")
        queue = queue.markActive(queue.nextQueued!!.catalogId)
        assertEquals("a", queue.active?.catalogId)
        assertEquals("b", queue.nextQueued?.catalogId)

        queue = queue.remove("a") // installed
        queue = queue.markActive(queue.nextQueued!!.catalogId)
        assertEquals("b", queue.active?.catalogId)
        assertEquals("c", queue.nextQueued?.catalogId)
    }

    @Test
    fun enqueueingTwiceDoesNotDuplicate() {
        val queue = DownloadQueue().enqueue("a").markActive("a").enqueue("a").enqueue("b").enqueue("b")
        assertEquals(listOf("a" to ACTIVE, "b" to QUEUED), queue.entries.map { it.catalogId to it.status })
    }

    @Test
    fun failedDownloadStaysVisibleAndTheNextOneRuns() {
        var queue = DownloadQueue().enqueue("a").enqueue("b").markActive("a")
        queue = queue.markFailed("a", "sem espaço")
        assertEquals(FAILED, queue.entry("a")?.status)
        assertEquals("sem espaço", queue.entry("a")?.message)
        assertNull(queue.active)
        assertEquals("b", queue.nextQueued?.catalogId)
    }

    @Test
    fun continuingAPausedDownloadPutsItNextInLine() {
        var queue = DownloadQueue().enqueue("a").enqueue("b").enqueue("c").markActive("a")
        queue = queue.markPaused("a").markActive("b")
        queue = queue.resume("a")
        assertEquals(listOf("b" to ACTIVE, "a" to QUEUED, "c" to QUEUED), queue.entries.map { it.catalogId to it.status })
        assertEquals("a", queue.nextQueued?.catalogId)
    }

    @Test
    fun interruptedDownloadResumesFirstAfterRestart() {
        val queue = DownloadQueue().enqueue("a").enqueue("b").markActive("b").recoverInterrupted()
        assertEquals(listOf("b" to QUEUED, "a" to QUEUED), queue.entries.map { it.catalogId to it.status })
    }

    @Test
    fun encodesAndDecodes() {
        val queue = DownloadQueue().enqueue("a").enqueue("b").markActive("a").markFailed("b", "erro\tcom\nquebras")
            .enqueue("c").markPaused("c", null)
        val decoded = DownloadQueue.decode(queue.encode())
        assertEquals(
            listOf(
                DownloadEntry("a", ACTIVE),
                DownloadEntry("b", FAILED, "erro com quebras"),
                DownloadEntry("c", PAUSED),
            ),
            decoded.entries,
        )
        assertEquals(DownloadQueue(), DownloadQueue.decode(null))
    }
}
