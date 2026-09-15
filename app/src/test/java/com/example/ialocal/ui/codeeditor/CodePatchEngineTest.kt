package com.example.ialocal.ui.codeeditor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CodePatchEngineTest {
    @Test
    fun appliesOnlyTheExactReportedFragment() {
        val code = """fun total(a: Int, b: Int): Int {
    return a - b
}
"""
        val issue = CodeIssue(
            id = "issue-1",
            title = "Operador incorreto",
            explanation = "A função deve somar os argumentos.",
            startLine = 2,
            endLine = 2,
            original = "return a - b",
            replacement = "return a + b",
            severity = CodeIssueSeverity.ERROR,
        )

        val updated = CodePatchEngine.applyIssue(code, issue)

        assertEquals(
            """fun total(a: Int, b: Int): Int {
    return a + b
}
""",
            updated,
        )
    }

    @Test
    fun refusesToApplyWhenTheOriginalFragmentChanged() {
        val code = "return a * b"
        val staleIssue = CodeIssue(
            id = "issue-1",
            title = "Patch antigo",
            explanation = "",
            startLine = 1,
            endLine = 1,
            original = "return a - b",
            replacement = "return a + b",
            severity = CodeIssueSeverity.WARNING,
        )

        assertNull(CodePatchEngine.applyIssue(code, staleIssue))
    }
}
