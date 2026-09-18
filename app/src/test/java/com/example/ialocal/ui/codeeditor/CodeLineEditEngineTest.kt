package com.example.ialocal.ui.codeeditor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CodeLineEditEngineTest {
    @Test
    fun cursorTargetsOnlyItsCurrentLine() {
        val code = "primeira\nsegunda\nterceira"
        val offset = code.indexOf("segunda") + 2

        assertEquals(
            CodeLineRange(2, 2),
            CodeLineEditEngine.lineRangeForSelection(code, offset, offset),
        )
    }

    @Test
    fun selectionEndingAtNextLineStartDoesNotIncludeThatLine() {
        val code = "a\nb\nc"
        val start = code.indexOf("b")
        val end = code.indexOf("c")

        assertEquals(
            CodeLineRange(2, 2),
            CodeLineEditEngine.lineRangeForSelection(code, start, end),
        )
    }

    @Test
    fun appliesOnlyTheRequestedLine() {
        val code = "val a = 1\nval b = 2\nprintln(a + b)"

        val updated = CodeLineEditEngine.applyExactLines(
            code = code,
            range = CodeLineRange(2, 2),
            replacement = "val b = 5",
        )

        assertEquals("val a = 1\nval b = 5\nprintln(a + b)", updated)
    }

    @Test
    fun refusesReplacementThatChangesLineCount() {
        val code = "a\nb\nc"

        assertNull(
            CodeLineEditEngine.applyExactLines(
                code = code,
                range = CodeLineRange(2, 2),
                replacement = "b1\nb2",
            ),
        )
    }

    @Test
    fun extractsExactlyTheSelectedRange() {
        val code = "one\ntwo\nthree\nfour"

        assertEquals(
            "two\nthree",
            CodeLineEditEngine.extractLines(code, CodeLineRange(2, 3)),
        )
    }
}
