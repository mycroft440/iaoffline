package com.example.ialocal.runtime

import com.example.ialocal.ai.AiChatMessage
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SessionStoreTest {
    @get:Rule val folder = TemporaryFolder()

    private val session = ChatSession(
        modelId = "gemma-4-e4b",
        key = "Você é um assistente útil.\nHoje é 20 de outubro de 2026.\u0000thinking=true",
        conversation = listOf(
            AiChatMessage("user", "Oi"),
            AiChatMessage("assistant", "Olá! Como posso ajudar?"),
            AiChatMessage("user", "Explique \"mmap\" em uma frase."),
        ),
        reply = "<think>Curto.</think>\nO mmap mapeia um arquivo na memória.",
        tokens = 1_337,
    )

    private fun SessionStore.save(session: ChatSession) {
        // Written by the native side between beginSave and finishSave.
        beginSave(session).writeText("state")
        finishSave(session)
    }

    private fun SessionStore.saved(): List<ChatSession> = all().map { it.session }

    @Test
    fun keepsTheConversationWithItsContext() {
        val store = SessionStore(File(folder.root, "session-cache"))
        store.save(session)

        assertEquals(listOf(session), store.saved())
        assertEquals(session, store.find { it.modelId == session.modelId }?.session)
    }

    @Test
    fun withoutTheNativeContextThereIsNothingToRestore() {
        val store = SessionStore(File(folder.root, "session-cache"))
        assertTrue(store.all().isEmpty())

        store.beginSave(session)
        store.finishSave(session)
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun aSaveInProgressHidesThePreviousStateOfTheConversation() {
        val store = SessionStore(File(folder.root, "session-cache"))
        store.save(session)

        // The native side may fail halfway through the next save of the same conversation.
        store.beginSave(session.copy(tokens = 2_000))
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun keepsSeveralConversationsAndDropsTheLeastRecentlyUsed() {
        var ready = 2
        val store = SessionStore(File(folder.root, "session-cache"), capacity = { ready })
        val other = session.copy(conversation = listOf(AiChatMessage("user", "Outra conversa")), opening = "Outra conversa")
        val third = session.copy(conversation = listOf(AiChatMessage("user", "Terceira")), opening = "Terceira")
        store.save(session)
        store.save(other)
        assertEquals(2, store.all().size)

        // The newer state of a conversation replaces the older one.
        store.save(session.copy(tokens = 2_000))
        assertEquals(2, store.all().size)
        assertEquals(2_000, store.find { it.opening.isEmpty() }?.session?.tokens)

        // Beyond the capacity, the least recently used goes.
        store.save(third)
        assertEquals(setOf("Terceira", ""), store.saved().map { it.opening }.toSet())
        ready = 1
        store.save(other)
        assertEquals(listOf(other), store.saved())
    }

    @Test
    fun forgetsOnlyTheConversationsOfTheDeletedModel() {
        val store = SessionStore(File(folder.root, "session-cache"), capacity = { 5 })
        val another = session.copy(modelId = "another-model")
        store.save(session)
        store.save(another)

        store.forget("missing-model")
        assertEquals(2, store.all().size)
        store.forget(session.modelId)
        assertEquals(listOf(another), store.saved())
    }

    @Test
    fun roundTripsThroughJson() {
        assertEquals(session, SessionStore.decode(SessionStore.encode(session)))
    }

    @Test
    fun savesWhenReadingTheConversationAgainWouldTakeLong() {
        // Read the message at 20 tokens/s: 1,000 tokens again would take 50 s.
        val slow = AnswerSpeed(promptTokens = 40, promptMillis = 2_000, answerTokens = 100, answerMillis = 20_000)
        assertTrue(SessionStore.worthSaving(1_000, slow))
        // At 400 tokens/s the same context is read again in 2.5 s.
        val fast = AnswerSpeed(promptTokens = 40, promptMillis = 100, answerTokens = 100, answerMillis = 2_000)
        assertFalse(SessionStore.worthSaving(1_000, fast))
        assertTrue(SessionStore.worthSaving(8_000, fast))
    }

    @Test
    fun withoutAUsableMeasurementLongContextsAreSaved() {
        val tiny = AnswerSpeed(promptTokens = 3, promptMillis = 900, answerTokens = 10, answerMillis = 1_000)
        assertFalse(SessionStore.worthSaving(600, tiny))
        assertTrue(SessionStore.worthSaving(2_000, tiny))
        assertTrue(SessionStore.worthSaving(2_000, null))
        assertFalse(SessionStore.worthSaving(500, null))
    }
}
