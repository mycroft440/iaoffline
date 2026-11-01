package com.example.ialocal.chat

import com.example.ialocal.data.MessageEntity
import com.example.ialocal.data.MessageStatus
import com.example.ialocal.data.MessageWithAttachments
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelHistoryTest {
    private fun answers(items: List<String>) =
        oneAnswerPerTurn(items, isAnswer = { it.startsWith("A") }, isEmpty = { it.length == 1 })

    @Test
    fun aRegeneratedAnswerReplacesTheOldOneForTheModel() {
        assertEquals(listOf("U1", "A1novo", "U2"), answers(listOf("U1", "A1velho", "A1novo", "U2")))
        assertEquals(listOf("U1", "A1", "U2", "A2"), answers(listOf("U1", "A1", "U2", "A2")))
    }

    @Test
    fun aFailedEmptyAnswerDoesNotHideTheOneBeforeIt() {
        assertEquals(listOf("U1", "A1velho"), answers(listOf("U1", "A1velho", "A")))
        assertEquals(listOf("U1", "A"), answers(listOf("U1", "A")))
    }

    private fun message(id: String, content: String, status: MessageStatus) = MessageWithAttachments(
        MessageEntity(id = id, conversationId = "c", role = "ASSISTANT", content = content, createdAt = 0L, status = status.name),
        emptyList(),
    )

    @Test
    fun theChatShowsTheAnswerBeingWrittenAheadOfTheDatabase() {
        val stored = listOf(message("r", "Olá", MessageStatus.SENDING))
        assertEquals("Olá, tudo bem?", withStreamingReply(stored, StreamingReply("r", "Olá, tudo bem?")).single().message.content)
        // Not once the answer is complete, nor for other messages or older text.
        val complete = listOf(message("r", "Olá, tudo bem?\n<!--timings-->", MessageStatus.COMPLETE))
        assertEquals(complete, withStreamingReply(complete, StreamingReply("r", "Olá, tudo bem?")))
        assertEquals(stored, withStreamingReply(stored, StreamingReply("outra", "Oi")))
        assertEquals(stored, withStreamingReply(stored, StreamingReply("r", "Ol")))
    }
}
