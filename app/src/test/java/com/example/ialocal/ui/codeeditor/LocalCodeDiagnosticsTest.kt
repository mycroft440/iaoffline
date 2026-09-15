package com.example.ialocal.ui.codeeditor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalCodeDiagnosticsTest {
    @Test
    fun validSqlPassesRealParserWithoutSyntaxError() {
        val sql = """
            SELECT u.id, u.name
            FROM users u
            WHERE u.active = 1
            ORDER BY u.name;
        """.trimIndent()

        val issues = SqlSyntaxDiagnostics.analyze(sql)

        assertFalse(issues.any { it.category == CodeIssueCategory.SYNTAX && it.severity == CodeIssueSeverity.ERROR })
    }

    @Test
    fun invalidSqlIsRejectedByRealParser() {
        val sql = "SELECT id, name FORM users WHERE active = 1;"

        val issues = SqlSyntaxDiagnostics.analyze(sql)
        val syntaxError = issues.firstOrNull { it.title == "Erro de sintaxe SQL" }

        assertNotNull(syntaxError)
        assertEquals(CodeIssueCategory.SYNTAX, syntaxError?.category)
        assertEquals(CodeIssueSeverity.ERROR, syntaxError?.severity)
        assertEquals(CodeIssueSource.LOCAL, syntaxError?.source)
        assertEquals(1, syntaxError?.startLine)
    }

    @Test
    fun invalidSecondSqlStatementIsAlsoRejected() {
        val sql = """
            SELECT 1;
            SELECT * FORM users;
        """.trimIndent()

        val issues = SqlSyntaxDiagnostics.analyze(sql)

        assertTrue(issues.any { issue ->
            issue.title == "Erro de sintaxe SQL" && issue.startLine >= 2
        })
    }

    @Test
    fun incompleteSqlIsRejectedByRealParser() {
        val sql = "SELECT * FROM users WHERE (active = 1 AND"

        val issues = SqlSyntaxDiagnostics.analyze(sql)

        assertTrue(issues.any { it.title == "Erro de sintaxe SQL" })
    }

    @Test
    fun recognizesRequestedScriptLanguages() {
        assertEquals("python", CodeLanguageRegistry.find("py").id)
        assertEquals("html", CodeLanguageRegistry.find("HTML5").id)
        assertEquals("powershell", CodeLanguageRegistry.find("pwsh").id)
        assertEquals("bash", CodeLanguageRegistry.find("shell").id)
        assertFalse(CodeLanguageRegistry.supportedDisplayNames.isEmpty())
    }

    @Test
    fun requestedLanguagesNowRequireFormalSyntaxBackends() {
        assertEquals(SyntaxBackend.TREE_SITTER, CodeLanguageRegistry.find("Python").syntaxBackend)
        assertEquals(SyntaxBackend.TREE_SITTER, CodeLanguageRegistry.find("HTML").syntaxBackend)
        assertEquals(SyntaxBackend.TREE_SITTER, CodeLanguageRegistry.find("PowerShell").syntaxBackend)
        assertEquals(SyntaxBackend.TREE_SITTER, CodeLanguageRegistry.find("Bash").syntaxBackend)
        assertEquals(SyntaxBackend.SQL_JSQLPARSER, CodeLanguageRegistry.find("SQL").syntaxBackend)
    }
}
