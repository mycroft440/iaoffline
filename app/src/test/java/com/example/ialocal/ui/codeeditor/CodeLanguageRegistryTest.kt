package com.example.ialocal.ui.codeeditor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeLanguageRegistryTest {
    @Test
    fun everyExposedLanguageHasAFormalSyntaxBackend() {
        val unsupported = CodeLanguageRegistry.profiles.filter { it.syntaxBackend == SyntaxBackend.NONE }

        assertTrue("Perfis sem parser formal: ${unsupported.map { it.displayName }}", unsupported.isEmpty())
    }

    @Test
    fun everyTreeSitterLanguageHasAtLeastOneGrammarCandidate() {
        val missingCandidates = CodeLanguageRegistry.profiles.filter {
            it.syntaxBackend == SyntaxBackend.TREE_SITTER && it.treeSitterCandidates.isEmpty()
        }

        assertTrue(
            "Perfis Tree-sitter sem gramática candidata: ${missingCandidates.map { it.displayName }}",
            missingCandidates.isEmpty(),
        )
    }

    @Test
    fun sqlUsesDedicatedParserInsteadOfAiOrTreeSitter() {
        val sql = CodeLanguageRegistry.find("SQL")

        assertEquals(SyntaxBackend.SQL_JSQLPARSER, sql.syntaxBackend)
        assertTrue(sql.treeSitterCandidates.isEmpty())
    }

    @Test
    fun keyRequestedLanguagesUseFormalParsers() {
        listOf("HTML", "Python", "PowerShell", "shell", "Java", "JavaScript", "C++", "Rust").forEach { name ->
            val profile = CodeLanguageRegistry.find(name)
            assertEquals("$name deveria usar Tree-sitter", SyntaxBackend.TREE_SITTER, profile.syntaxBackend)
            assertFalse("$name precisa de candidato de gramática", profile.treeSitterCandidates.isEmpty())
        }
    }

    @Test
    fun unknownLanguageIsNeverReportedAsFormallySupported() {
        val unknown = CodeLanguageRegistry.find("linguagem-que-nao-existe")

        assertEquals(SyntaxBackend.NONE, unknown.syntaxBackend)
    }
}
