package com.example.ialocal

import com.example.ialocal.data.AiModelEntity
import com.example.ialocal.data.ModelVerificationStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatStartupModelTest {
    private fun model(id: String, verified: Boolean = true, active: Boolean = false) = AiModelEntity(
        id = id,
        name = "Modelo $id",
        apiModelId = "test/$id",
        format = "GGUF",
        architecture = null,
        filePath = "/models/$id.gguf",
        sizeBytes = 1234L,
        importedAt = 1L,
        isActive = active,
        verificationStatus = if (verified) ModelVerificationStatus.VERIFIED.name
            else ModelVerificationStatus.IMPORTED.name,
    )

    @Test
    fun newChatUsesLastUserSelectedModelBeforeActiveDefault() {
        val entries = listOf(model("older"), model("chosen"), model("active", active = true))
        assertEquals("chosen", preferredChatStartupModel(entries, "chosen", "older"))
    }

    @Test
    fun fallsBackToLastConversationWhenSelectedModelNoLongerExists() {
        val entries = listOf(model("older"), model("recent"))
        assertEquals("recent", preferredChatStartupModel(entries, "removed", "recent"))
    }

    @Test
    fun onlyInstalledModelIsChosenAutomatically() {
        assertEquals("single", preferredChatStartupModel(listOf(model("single")), null, null))
        assertEquals("single", preferredChatStartupModel(listOf(model("single", verified = false)), null, null))
    }

    @Test
    fun neverPreselectsUnverifiedWhenVerifiedOptionsExist() {
        val entries = listOf(model("broken", verified = false), model("ready", active = true))
        assertEquals("ready", preferredChatStartupModel(entries, "broken", "broken"))
        assertNull(preferredChatStartupModel(emptyList(), "old", "old"))
    }
}
