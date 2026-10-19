package com.example.ialocal.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomProfilesTest {
    private val teacher = CustomProfiles.create("Professor", "Explica com calma", "Você é um professor paciente.")
    private val existing = AiProfile.BUILT_IN + teacher

    @Test
    fun builtInProfilesKeepTheIdsTheSelectionWasSavedWith() {
        assertEquals(listOf("PROGRAMMER", "UNCENSORED"), AiProfile.BUILT_IN.map { it.id })
        assertEquals(BuiltInProfile.PROGRAMMER, AiProfile.DEFAULT.builtIn)
    }

    @Test
    fun acceptsANewProfile() {
        assertNull(CustomProfiles.validate("Revisor", "", "Revise o texto do usuário.", existing))
    }

    @Test
    fun createTrimsTheFieldsAndMarksTheProfileAsCustom() {
        val profile = CustomProfiles.create("  Revisor ", " Corrige textos ", " Revise. ")

        assertEquals("Revisor", profile.displayName)
        assertEquals("Corrige textos", profile.description)
        assertEquals("Revise.", profile.systemPrompt)
        assertTrue(profile.isCustom)
        assertTrue(profile.id != teacher.id)
    }

    @Test
    fun rejectsBlankNameAndBlankInstructions() {
        assertNotNull(CustomProfiles.validate("   ", "", "Algo", existing))
        assertNotNull(CustomProfiles.validate("Revisor", "", "  ", existing))
    }

    @Test
    fun rejectsTheNamesOfTheBuiltInProfilesAndTheirOldNames() {
        assertNotNull(CustomProfiles.validate("programador", "", "Algo", existing))
        assertNotNull(CustomProfiles.validate("Sem Censura", "", "Algo", existing))
        assertNotNull(CustomProfiles.validate("Engenheiro de Software", "", "Algo", existing))
    }

    @Test
    fun rejectsANameAlreadyTakenExceptByTheProfileBeingEdited() {
        assertNotNull(CustomProfiles.validate("PROFESSOR", "", "Algo", existing))
        assertNull(CustomProfiles.validate("Professor", "", "Outra instrução", existing, editingId = teacher.id))
    }

    @Test
    fun rejectsFieldsOverTheirLimits() {
        val longName = "a".repeat(CustomProfiles.MAX_NAME_LENGTH + 1)
        val longDescription = "a".repeat(CustomProfiles.MAX_DESCRIPTION_LENGTH + 1)
        val longPrompt = "a".repeat(CustomProfiles.MAX_PROMPT_LENGTH + 1)

        assertNotNull(CustomProfiles.validate(longName, "", "Algo", existing))
        assertNotNull(CustomProfiles.validate("Revisor", longDescription, "Algo", existing))
        assertNotNull(CustomProfiles.validate("Revisor", "", longPrompt, existing))
    }

    @Test
    fun savesOnlyTheCustomProfilesAndReadsThemBack() {
        val decoded = CustomProfiles.decode(CustomProfiles.encode(existing))

        assertEquals(listOf(teacher), decoded)
    }

    @Test
    fun unreadableStorageGivesNoProfiles() {
        assertEquals(emptyList<AiProfile>(), CustomProfiles.decode(null))
        assertEquals(emptyList<AiProfile>(), CustomProfiles.decode("não é json"))
    }

    @Test
    fun skipsBrokenEntriesAndKeepsTheOthers() {
        val json = """[{"id":"custom-1","name":"Bom","prompt":"Ok"},{"id":"custom-2"},{"id":"custom-3","name":"Vazio","prompt":" "}]"""

        assertEquals(listOf("Bom"), CustomProfiles.decode(json).map { it.displayName })
    }

    @Test
    fun summaryFallsBackToTheInstructions() {
        val profile = CustomProfiles.create("Revisor", "", "Revise o texto.")

        assertEquals("Revise o texto.", profile.summary)
        assertEquals("Explica com calma", teacher.summary)
    }
}
