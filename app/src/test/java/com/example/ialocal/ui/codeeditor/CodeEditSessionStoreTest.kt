package com.example.ialocal.ui.codeeditor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeEditSessionStoreTest {
    @Test fun replacementChangesOnlyAuthorizedSubstring() {
        val code = "val subtotal = price * count\nval tax = subtotal * 0.1\nreturn subtotal + tax"
        val start = code.indexOf("0.1")
        val target = CodeLineEditEngine.targetForSelection(code, start, start + 3)
        val prefix = code.substring(0, target.startOffset)
        val suffix = code.substring(target.endOffset)
        val store = CodeEditSessionStore()
        val session = store.create(code, target)
        val result = store.applyReplacement(session.id, "0.18", "Atualiza a alíquota.")
        assertEquals(prefix + "0.18" + suffix, result.resultCode)
        assertTrue(requireNotNull(result.resultCode).startsWith(prefix))
        assertTrue(requireNotNull(result.resultCode).endsWith(suffix))
    }

    @Test fun replacementMayChangeLineCountInsideAuthorizedSelection() {
        val code = "before\noldCall()\nafter"
        val start = code.indexOf("oldCall()")
        val target = CodeLineEditEngine.targetForSelection(code, start, start + "oldCall()".length)
        val store = CodeEditSessionStore()
        val session = store.create(code, target)
        val result = store.applyReplacement(session.id, "prepare()\nexecute()", "Divide a chamada.")
        assertEquals("before\nprepare()\nexecute()\nafter", result.resultCode)
    }

    @Test(expected = IllegalStateException::class)
    fun sessionAllowsOnlyOneAgentWrite() {
        val code = "abc"
        val target = CodeLineEditEngine.targetForSelection(code, 0, 1)
        val store = CodeEditSessionStore()
        val session = store.create(code, target)
        store.applyReplacement(session.id, "x", null)
        store.applyReplacement(session.id, "y", null)
    }
}
