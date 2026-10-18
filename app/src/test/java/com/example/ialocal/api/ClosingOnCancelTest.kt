package com.example.ialocal.api

import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ClosingOnCancelTest {
    @Test
    fun stopEndsAReadThatIsWaitingForTheModel() = runBlocking {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            Socket(server.inetAddress, server.localPort).use { client ->
                // Accepted and left silent, like the local API while the model loads.
                server.accept().use {
                    val reading = CompletableDeferred<Unit>()
                    val outcome = CompletableDeferred<Throwable?>()
                    val job = launch(Dispatchers.IO) {
                        try {
                            closingOnCancel(close = client::close) {
                                reading.complete(Unit)
                                client.getInputStream().read()
                            }
                            outcome.complete(null)
                        } catch (t: Throwable) {
                            outcome.complete(t)
                        }
                    }
                    reading.await()
                    job.cancel()
                    // Without closing on cancel the read would wait for data that never comes.
                    withTimeout(5_000) { job.join() }
                    val ended = outcome.await()
                    assertTrue("Expected a cancellation, got $ended", ended is CancellationException)
                    assertTrue(client.isClosed)
                }
            }
        }
    }

    @Test
    fun finishedReadReturnsItsResultAndCloses() = runBlocking {
        val closes = AtomicInteger()
        val result = closingOnCancel(close = { closes.incrementAndGet() }) { 42 }
        assertEquals(42, result)
        assertTrue(closes.get() >= 1)
    }

    @Test
    fun failureWithoutStopStillFails() = runBlocking {
        try {
            closingOnCancel(close = {}) { throw IOException("conexão recusada") }
            fail("The failure was swallowed")
        } catch (failure: IOException) {
            assertEquals("conexão recusada", failure.message)
        }
    }
}
