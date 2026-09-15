package com.example.ialocal.ui.codeeditor

import kotlinx.coroutines.runBlocking
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
        val sql = CodeLanguageRegistry.find("SQL") { "sql" }

        assertEquals(SyntaxBackend.SQL_JSQLPARSER, sql.syntaxBackend)
        assertTrue(sql.treeSitterCandidates.isEmpty())
    }

    @Test
    fun firstAuditedLanguagesUseExpectedTreeSitterGrammars() {
        val expectedGrammarByLanguage = linkedMapOf(
            "Python" to "python",
            "HTML" to "html",
            "PowerShell" to "powershell",
            "shell" to "bash",
            "PHP" to "php",
            "C++" to "cpp",
            "C#" to "csharp",
            "C" to "c",
        )

        expectedGrammarByLanguage.forEach { (name, expectedGrammar) ->
            val profile = CodeLanguageRegistry.find(name) { error("perfil curado não deveria consultar resolver dinâmico") }
            assertEquals("$name deveria usar Tree-sitter", SyntaxBackend.TREE_SITTER, profile.syntaxBackend)
            assertFalse("$name precisa de candidato de gramática", profile.treeSitterCandidates.isEmpty())
            assertTrue(
                "$name deveria incluir a gramática canônica $expectedGrammar, mas possui ${profile.treeSitterCandidates}",
                expectedGrammar in profile.treeSitterCandidates,
            )
        }
    }

    @Test
    fun firstAuditedLanguageAliasesResolveToTheCorrectProfile() {
        val expectedProfileByAlias = linkedMapOf(
            "py" to "Python",
            "html5" to "HTML",
            "pwsh" to "PowerShell",
            "bash shell" to "Bash",
            "php8" to "PHP",
            "cplusplus" to "C++",
            "cs" to "C#",
            "c11" to "C",
        )

        expectedProfileByAlias.forEach { (alias, expectedDisplayName) ->
            assertEquals(
                expectedDisplayName,
                CodeLanguageRegistry.find(alias) { error("alias curado não deveria consultar resolver dinâmico") }.displayName,
            )
        }
    }

    @Test
    fun grammarOutsideCuratedProfilesBecomesFormalTreeSitterProfile() {
        val zig = CodeLanguageRegistry.find("zig") { candidate ->
            candidate.removePrefix(".").takeIf { it == "zig" }
        }

        assertEquals(SyntaxBackend.TREE_SITTER, zig.syntaxBackend)
        assertEquals("zig", zig.id)
        assertEquals("Zig", zig.displayName)
        assertEquals(listOf("zig"), zig.treeSitterCandidates)
    }

    @Test
    fun extensionOutsideCuratedProfilesCanResolveDynamically() {
        val profile = CodeLanguageRegistry.find(".nix") { candidate ->
            candidate.removePrefix(".").takeIf { it == "nix" }
        }

        assertEquals(SyntaxBackend.TREE_SITTER, profile.syntaxBackend)
        assertEquals("nix", profile.id)
        assertEquals(listOf("nix"), profile.treeSitterCandidates)
    }

    @Test
    fun filePathOutsideCuratedProfilesCanResolveDynamically() {
        val profile = CodeLanguageRegistry.find("infra/main.tf") { candidate ->
            when {
                candidate.endsWith(".tf") -> "hcl"
                candidate == "infra/main.tf" -> "hcl"
                else -> null
            }
        }

        assertEquals(SyntaxBackend.TREE_SITTER, profile.syntaxBackend)
        assertEquals("hcl", profile.id)
    }

    @Test
    fun curatedSqlAlwaysWinsOverDynamicTreeSitterCatalog() {
        val sql = CodeLanguageRegistry.find("sql") { "sql" }

        assertEquals("SQL", sql.displayName)
        assertEquals(SyntaxBackend.SQL_JSQLPARSER, sql.syntaxBackend)
        assertTrue(sql.treeSitterCandidates.isEmpty())
    }

    @Test
    fun unknownLanguageIsNeverReportedAsFormallySupported() {
        val unknown = CodeLanguageRegistry.find("linguagem-que-nao-existe") { null }

        assertEquals(SyntaxBackend.NONE, unknown.syntaxBackend)
    }

    @Test
    fun blankUnknownLanguageIsStillParserUnavailable() = runBlocking {
        val unknown = CodeLanguageRegistry.find("linguagem-que-nao-existe") { null }
        val result = FormalSyntaxDiagnostics.analyze("", unknown)

        assertEquals(SyntaxValidationState.PARSER_UNAVAILABLE, result.state)
    }
}
