package com.example.ialocal.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeCanvasTest {
    private val site = """
        Aqui está o site:

        ```html
        <!doctype html>
        <html><head><title>PetShop Patas &amp; Amor</title></head>
        <body><h1>Bem-vindo</h1></body></html>
        ```

        Abra no navegador.
    """.trimIndent()

    @Test
    fun splitsProseAndCodeBlocksInOrder() {
        val parts = splitAnswer(site)
        assertEquals(3, parts.size)
        assertEquals(AnswerPart.Prose("Aqui está o site:"), parts[0])
        val code = parts[1] as AnswerPart.Code
        assertEquals("html", code.language)
        assertTrue(code.complete)
        assertTrue(code.code.startsWith("<!doctype html>"))
        assertTrue(code.code.endsWith("</html>"))
        assertEquals(AnswerPart.Prose("Abra no navegador."), parts[2])
    }

    @Test
    fun aBlockBeingWrittenIsIncomplete() {
        val parts = splitAnswer("Começando:\n```python\nprint('oi')\n")
        val code = parts.last() as AnswerPart.Code
        assertFalse(code.complete)
        assertEquals("print('oi')", code.code)
    }

    @Test
    fun htmlBecomesAPreviewableCanvasNamedAfterItsTitle() {
        val canvas = CodeCanvases.at(site, 0)!!
        assertEquals("PetShop Patas & Amor", canvas.title)
        assertEquals("HTML", canvas.languageLabel)
        assertEquals(PreviewKind.HTML, canvas.preview)
        assertEquals(canvas.code, CodeCanvases.previewDocument(canvas))
        assertTrue(CodeCanvases.isCanvas(splitAnswer(site)[1] as AnswerPart.Code))
    }

    @Test
    fun shortCodeThatCannotBePreviewedStaysInline() {
        val short = AnswerPart.Code("bash", "ls -la", complete = true)
        assertFalse(CodeCanvases.isCanvas(short))
        val long = AnswerPart.Code("python", (1..CodeCanvases.MIN_LINES).joinToString("\n") { "x = $it" }, complete = true)
        assertTrue(CodeCanvases.isCanvas(long))
        val canvas = CodeCanvases.of(long)
        assertEquals("Código Python", canvas.title)
        assertNull(canvas.preview)
        assertNull(CodeCanvases.previewDocument(canvas))
        assertEquals(CodeCanvases.MIN_LINES, canvas.lineCount)
    }

    @Test
    fun javascriptRunsInAPageThatShowsTheConsole() {
        val canvas = CodeCanvases.of(AnswerPart.Code("js", "console.log('</script>')", complete = true))
        val page = CodeCanvases.previewDocument(canvas)!!
        assertEquals(PreviewKind.JAVASCRIPT, canvas.preview)
        // The code cannot close the script tag it runs in.
        assertTrue(page.contains("console.log('<\\/script>')"))
    }

    @Test
    fun svgWithoutALanguageIsRecognized() {
        val canvas = CodeCanvases.of(AnswerPart.Code("", "<svg viewBox=\"0 0 10 10\"></svg>", complete = true))
        assertEquals(PreviewKind.SVG, canvas.preview)
        assertEquals("Imagem SVG", canvas.title)
    }
}
