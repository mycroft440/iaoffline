package com.example.ialocal.agent.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ToolCallTextTest {
    @Test
    fun escapesRawLineBreaksInsideStrings() {
        val written = "{\"tool\":\"pdf_create\",\"arguments\":{\"content\":\"# Título\nLinha\t2\"}}"
        assertEquals(
            "{\"tool\":\"pdf_create\",\"arguments\":{\"content\":\"# Título\\nLinha\\t2\"}}",
            ToolCallText.jsonObject(written),
        )
    }

    @Test
    fun keepsLineBreaksBetweenValuesAndEscapedQuotes() {
        val written = "{\n  \"tool\": \"pdf_read\",\n  \"arguments\": {\"id\": \"a\\\"b\"}\n}"
        assertEquals(written, ToolCallText.jsonObject(written))
    }

    @Test
    fun removesAMarkdownFence() {
        val fence = "```"
        assertEquals("{\"tool\":\"pdf_list\"}", ToolCallText.jsonObject("${fence}json\n{\"tool\":\"pdf_list\"}\n$fence"))
    }

    @Test
    fun findsNothingWithoutAnObject() {
        assertNull(ToolCallText.jsonObject("Claro, vou criar o PDF."))
    }
}
