package com.example.ialocal.runtime

import com.example.ialocal.ai.AiChatMessage
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationStateStoreTest {
    private val directory: File = Files.createTempDirectory("states").toFile()
    private val model = ConversationStateStore.Model("qwen", "/models/qwen.gguf", 4_673_643_744L)
    private val messages = listOf(AiChatMessage("user", "Qual é a capital da Austrália?"))

    @After
    fun cleanUp() {
        directory.deleteRecursively()
    }

    private fun store(maxBytes: Long = 1_000, maxFiles: Int = 8) =
        ConversationStateStore(directory, maxBytes = maxBytes, maxFiles = maxFiles, minFreeBytes = 0)

    /** What the native binding writes: the state and its chat file. */
    private fun save(store: ConversationStateStore, key: String, bytes: Int, age: Long = 0): File {
        val state = requireNotNull(store.fileForSaving(key))
        state.writeBytes(ByteArray(bytes))
        File(state.path + ".chat").writeBytes(ByteArray(10))
        val time = System.currentTimeMillis() - age
        state.setLastModified(time)
        File(state.path + ".chat").setLastModified(time)
        return state
    }

    @Test
    fun keysDependOnTheModelFileThePromptAndTheMessages() {
        val store = store()
        val key = store.conversationKey(model, "Sistema", messages)
        assertEquals(key, store.conversationKey(model, "Sistema", messages))
        assertNotEquals(key, store.conversationKey(model.copy(sizeBytes = 1), "Sistema", messages))
        assertNotEquals(key, store.conversationKey(model.copy(path = "/outro.gguf"), "Sistema", messages))
        assertNotEquals(key, store.conversationKey(model, "Sistema\n/no_think", messages))
        assertNotEquals(key, store.conversationKey(model, "Sistema", messages + AiChatMessage("assistant", "Canberra.")))
        assertNotEquals(key, store.systemPromptKey(model, "Sistema"))
    }

    @Test
    fun theAnsweredMessageMatchesItsHistoryCopyWithoutTheDeepThinkSwitch() {
        val store = store()
        val sent = listOf(AiChatMessage("user", "Qual é a capital da Austrália? /no_think"))
        assertEquals(store.conversationKey(model, "Sistema", sent), store.conversationKey(model, "Sistema", messages))
    }

    @Test
    fun findsOnlyStatesWithTheirChatFile() {
        val store = store()
        val key = store.systemPromptKey(model, "Sistema")
        assertNull(store.find(key))
        val state = requireNotNull(store.fileForSaving(key))
        state.writeBytes(ByteArray(10))
        assertNull(store.find(key))
        File(state.path + ".chat").writeBytes(ByteArray(10))
        assertEquals(state, store.find(key))
        store.remove(key)
        assertNull(store.find(key))
        assertFalse(File(state.path + ".chat").exists())
    }

    /** Checked without find(), which would mark the state as just used. */
    private fun saved(key: String) = File(directory, "$key.state").isFile

    @Test
    fun dropsTheLeastRecentlyUsedStatesBeyondTheLimits() {
        val store = store(maxBytes = 1_000, maxFiles = 2)
        save(store, "old", 100, age = 30_000)
        save(store, "middle", 100, age = 20_000)
        save(store, "new", 100, age = 10_000)
        store.prune()
        assertFalse(saved("old"))
        assertTrue(saved("middle"))
        assertTrue(saved("new"))

        // Size: "big" is the most recent, so the others go first.
        save(store, "big", 900)
        store.prune()
        assertTrue(saved("big"))
        assertFalse(saved("new"))
        assertFalse(saved("middle"))
    }

    @Test
    fun usingAStateKeepsItOverOlderOnes() {
        val store = store(maxBytes = 1_000, maxFiles = 1)
        save(store, "restored", 100, age = 30_000)
        save(store, "other", 100, age = 10_000)
        assertNotNull(store.find("restored"))
        store.prune()
        assertTrue(saved("restored"))
        assertFalse(saved("other"))
    }

    @Test
    fun keepsTheStateJustSavedEvenOverTheLimit() {
        val store = store(maxBytes = 100, maxFiles = 1)
        save(store, "recent", 50)
        save(store, "saved-now", 500, age = 60_000)
        store.prune(keep = "saved-now")
        assertNotNull(store.find("saved-now"))
    }

    @Test
    fun removesChatFilesWhoseStateIsGone() {
        val store = store()
        val state = save(store, "orphan", 10)
        state.delete()
        store.prune()
        assertFalse(File(state.path + ".chat").exists())
    }

    @Test
    fun savesNothingWhenStorageIsShort() {
        val short = ConversationStateStore(directory, minFreeBytes = Long.MAX_VALUE)
        assertNull(short.fileForSaving("key"))
        assertTrue(directory.isDirectory)
    }
}
