package com.example.ialocal.ui.chat

import com.example.ialocal.data.AiModelEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatInitialModelSelectionTest {
    private fun model(id: String, active: Boolean = false) = AiModelEntity(
        id = id,
        name = id,
        apiModelId = "test/$id",
        format = "GGUF",
        architecture = null,
        filePath = "/models/$id.gguf",
        sizeBytes = 1L,
        importedAt = 1L,
        isActive = active,
    )

    @Test
    fun existingConversationKeepsItsModelWhenDownloadedAiBecomesActive() {
        val installed = listOf(model("chat-model"), model("newly-downloaded", active = true))
        assertEquals("chat-model", initialChatModelId(installed, null, "chat-model"))
    }

    @Test
    fun explicitSelectionWinsOverTheStoredConversationModel() {
        val installed = listOf(model("chat-model"), model("user-chosen"), model("active", active = true))
        assertEquals("user-chosen", initialChatModelId(installed, "user-chosen", "chat-model"))
    }

    @Test
    fun newConversationStartsWithTheActiveAiOnce() {
        val installed = listOf(model("older"), model("active", active = true))
        assertEquals("active", initialChatModelId(installed, null, null))
    }

    @Test
    fun missingSavedModelFallsBackToAnInstalledAi() {
        val installed = listOf(model("available"))
        assertEquals("available", initialChatModelId(installed, null, "deleted"))
    }

    @Test
    fun chatWithoutInstalledModelsStartsWithoutAnAi() {
        assertNull(initialChatModelId(emptyList(), null, null))
    }
}
