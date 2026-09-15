package com.example.ialocal.ui.codeeditor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalCodeDiagnosticsTest {
    @Test
    fun detectsUnexpectedClosingDelimiterWithoutAi() {
        val profile = CodeLanguageRegistry.find("Python")
        val issues = LocalCodeDiagnostics.analyze("print('ok')\n}", profile)

        assertTrue(issues.any { it.title.contains("inesperado", ignoreCase = true) })
        assertTrue(issues.any { it.source == CodeIssueSource.LOCAL })
    }

    @Test
    fun detectsUnclosedHtmlTag() {
        val profile = CodeLanguageRegistry.find("HTML")
        val issues = LocalCodeDiagnostics.analyze("<main><section>texto</main>", profile)

        assertTrue(issues.any { it.title.contains("tag", ignoreCase = true) })
        assertTrue(issues.any { it.severity == CodeIssueSeverity.ERROR })
    }

    @Test
    fun validSqlPassesRealParserWithoutSyntaxError() {
        val profile = CodeLanguageRegistry.find("SQL")
        val sql = """
            SELECT u.id, u.name
            FROM users u
            WHERE u.active = 1
            ORDER BY u.name;
        """.trimIndent()

        val issues = LocalCodeDiagnostics.analyze(sql, profile)

        assertFalse(issues.any { it.category == CodeIssueCategory.SYNTAX && it.severity == CodeIssueSeverity.ERROR })
    }

    @Test
    fun invalidSqlIsRejectedByRealParser() {
        val profile = CodeLanguageRegistry.find("SQL")
        val sql = "SELECT id, name FORM users WHERE active = 1;"

        val issues = LocalCodeDiagnostics.analyze(sql, profile)

        assertTrue(issues.any { issue ->
            issue.title == "Erro de sintaxe SQL" &&
                issue.category == CodeIssueCategory.SYNTAX &&
                issue.severity == CodeIssueSeverity.ERROR &&
                issue.source == CodeIssueSource.LOCAL
        })
    }

    @Test
    fun incompleteSqlIsRejectedByRealParser() {
        val profile = CodeLanguageRegistry.find("SQL")
        val sql = "SELECT * FROM users WHERE (active = 1 AND"

        val issues = LocalCodeDiagnostics.analyze(sql, profile)

        assertTrue(issues.any { it.title == "Erro de sintaxe SQL" || it.title.contains("não fechado") })
    }

    @Test
    fun recognizesRequestedScriptLanguages() {
        assertEquals("python", CodeLanguageRegistry.find("py").id)
        assertEquals("html", CodeLanguageRegistry.find("HTML5").id)
        assertEquals("powershell", CodeLanguageRegistry.find("pwsh").id)
        assertEquals("bash", CodeLanguageRegistry.find("shell").id)
        assertFalse(CodeLanguageRegistry.supportedDisplayNames.isEmpty())
    }
}
