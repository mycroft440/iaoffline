package com.example.ialocal.files

import com.example.ialocal.files.DocumentLibrary.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RangesAndNamesTest {
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
    fun linesUseTheirOwnWords() {
        assertEquals(listOf(9, 10, 11), LineRanges.parse("10-12", 20))
        val error = assertThrows(IllegalArgumentException::class.java) { LineRanges.parse("5", 1) }
        assertEquals("Linha 5 não existe; o arquivo tem 1 linha.", error.message)
    }

    @Test
    fun pdfNamesAreSafeAndEndInPdf() {
        assertEquals("Relatório final.pdf", DocumentLibrary.fileName("Relatório final", Kind.PDF))
        assertEquals("contrato.pdf", DocumentLibrary.fileName("contrato.pdf", Kind.PDF))
        assertEquals("contrato.pdf", DocumentLibrary.fileName("contrato.PDF", Kind.PDF))
        assertEquals("a b c.pdf", DocumentLibrary.fileName("a/b:c", Kind.PDF))
        assertEquals("documento.pdf", DocumentLibrary.fileName("  ", Kind.PDF))
        assertEquals(84, DocumentLibrary.fileName("x".repeat(200), Kind.PDF).length)
    }

    @Test
    fun textNamesKeepATextExtension() {
        assertEquals("notas.txt", DocumentLibrary.fileName("notas", Kind.TEXT))
        assertEquals("notas.md", DocumentLibrary.fileName("notas.md", Kind.TEXT))
        assertEquals("dados.csv", DocumentLibrary.fileName("dados", Kind.TEXT, "csv"))
        assertEquals("versão 1.2.txt", DocumentLibrary.fileName("versão 1.2", Kind.TEXT))
        assertEquals("texto.txt", DocumentLibrary.fileName(".txt", Kind.TEXT))
        assertEquals("notas.txt", DocumentLibrary.fileName("notas.", Kind.TEXT))
    }

    @Test
    fun editedCopiesSayEditadoOnce() {
        assertEquals("notas editado.md", DocumentLibrary.editedName("notas.md", Kind.TEXT))
        assertEquals("notas editado.md", DocumentLibrary.editedName("notas editado.md", Kind.TEXT))
        assertEquals("contrato editado.pdf", DocumentLibrary.editedName("contrato editado editado.pdf", Kind.PDF))
    }

    @Test
    fun filesAreTextOrPdfByNameOrType() {
        assertEquals(Kind.TEXT, DocumentLibrary.kindOf("lista.TXT", null))
        assertEquals(Kind.TEXT, DocumentLibrary.kindOf("arquivo", "text/plain"))
        assertEquals(Kind.PDF, DocumentLibrary.kindOf("scan", "application/pdf"))
        assertEquals(null, DocumentLibrary.kindOf("foto.jpg", "image/jpeg"))
    }
}
