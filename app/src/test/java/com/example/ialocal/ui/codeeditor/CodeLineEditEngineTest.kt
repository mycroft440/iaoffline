package com.example.ialocal.ui.codeeditor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeLineEditEngineTest {
    @Test fun cursorAuthorizesOnlyCurrentLine() {
        val code = "primeira\nsegunda\nterceira"
        val offset = code.indexOf("segunda") + 2
        val target = CodeLineEditEngine.targetForSelection(code, offset, offset)
        assertFalse(target.explicitSelection)
        assertEquals(2, target.startLine)
        assertEquals("segunda", target.original)
    }

    @Test fun explicitSelectionAuthorizesOnlyExactCharacters() {
        val code = "val total = price * count"
        val start = code.indexOf("price")
        val target = CodeLineEditEngine.targetForSelection(code, start, start + "price".length)
        assertTrue(target.explicitSelection)
        assertEquals("price", target.original)
        assertEquals(start, target.startOffset)
        assertEquals(start + 5, target.endOffset)
    }

    @Test fun multiLineSelectionKeepsExactOffsets() {
        val code = "one\ntwo\nthree\nfour"
        val start = code.indexOf("two")
        val end = code.indexOf("four") - 1
        val target = CodeLineEditEngine.targetForSelection(code, start, end)
        assertEquals("two\nthree", target.original)
        assertEquals(2, target.startLine)
        assertEquals(3, target.endLine)
    }
}
