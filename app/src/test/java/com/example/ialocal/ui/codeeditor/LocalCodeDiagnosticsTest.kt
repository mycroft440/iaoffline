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
    fun recognizesRequestedScriptLanguages() {
        assertEquals("python", CodeLanguageRegistry.find("py").id)
        assertEquals("html", CodeLanguageRegistry.find("HTML5").id)
        assertEquals("powershell", CodeLanguageRegistry.find("pwsh").id)
        assertEquals("bash", CodeLanguageRegistry.find("shell").id)
        assertFalse(CodeLanguageRegistry.supportedDisplayNames.isEmpty())
    }
}
