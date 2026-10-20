package com.example.ialocal.runtime

import com.example.ialocal.ai.AiChatMessage
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test
    fun keepsTheConversationWithItsContext() {
        val store = SessionStore(File(folder.root, "session-cache"))
        val state = store.beginSave()
        // Written by the native side between beginSave and finishSave.
        state.writeText("state")
        store.finishSave(session)

        assertEquals(session, store.load())
    }

    @Test
    fun withoutTheNativeContextThereIsNothingToRestore() {
        val store = SessionStore(File(folder.root, "session-cache"))
        assertNull(store.load())

        store.beginSave()
        store.finishSave(session)
        assertNull(store.load())
    }

    @Test
    fun aSaveInProgressHidesThePreviousConversation() {
        val store = SessionStore(File(folder.root, "session-cache"))
        store.beginSave().writeText("state")
        store.finishSave(session)

        // The native side may fail halfway through the next save.
        store.beginSave()
        assertNull(store.load())
    }

    @Test
    fun forgetsOnlyTheConversationOfTheDeletedModel() {
        val store = SessionStore(File(folder.root, "session-cache"))
        store.beginSave().writeText("state")
        store.finishSave(session)

        store.forget("another-model")
        assertEquals(session, store.load())
        store.forget(session.modelId)
        assertNull(store.load())
        assertFalse(store.stateFile.exists())
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
