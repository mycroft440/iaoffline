package com.example.ialocal.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PdfPagesAndNamesTest {
    @Test
    fun parsesPagesInTheOrderGiven() {
        assertEquals(listOf(0, 1, 2, 4), PageRanges.parse("1-3,5", 6))
        assertEquals(listOf(4, 0), PageRanges.parse(" 5 , 1 ", 6))
        assertEquals(listOf(2, 1, 0), PageRanges.parse("3-1", 6))
    }

    @Test
    fun openRangesRunToTheFirstOrLastPage() {
        assertEquals(listOf(3, 4, 5), PageRanges.parse("4-", 6))
        assertEquals(listOf(0, 1), PageRanges.parse("-2", 6))
    }

    @Test
    fun noPagesMeansAllOfThem() {
        assertEquals(listOf(0, 1, 2), PageRanges.parse(null, 3))
        assertEquals(listOf(0, 1, 2), PageRanges.parse("  ", 3))
    }

    @Test
    fun rejectsPagesThatDoNotExist() {
        val error = assertThrows(IllegalArgumentException::class.java) { PageRanges.parse("2,9", 3) }
        assertEquals("Página 9 não existe; o PDF tem 3 páginas.", error.message)
        assertThrows(IllegalArgumentException::class.java) { PageRanges.parse("0", 3) }
        assertThrows(IllegalArgumentException::class.java) { PageRanges.parse("a-b", 3) }
    }

    @Test
    fun fileNamesAreSafeAndEndInPdf() {
        assertEquals("Relatório final.pdf", PdfLibrary.fileName("Relatório final"))
        assertEquals("contrato.pdf", PdfLibrary.fileName("contrato.pdf"))
        assertEquals("a b c.pdf", PdfLibrary.fileName("a/b:c"))
        assertEquals("documento.pdf", PdfLibrary.fileName("  "))
        assertEquals(84, PdfLibrary.fileName("x".repeat(200)).length)
    }
}
