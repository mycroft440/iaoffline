package com.example.ialocal.files

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TextFilesTest {
    @Test
    fun readsUtf8AndKeepsWindowsLineBreaks() {
        val content = TextFiles.decode("Feijão\r\nCafé\r\n".toByteArray(Charsets.UTF_8))
        assertEquals("Feijão\nCafé\n", content.text)
        assertEquals("UTF-8", content.encoding)
        assertEquals("\r\n", content.lineSeparator)
        assertEquals(listOf("Feijão", "Café"), content.lines)
        assertArrayEquals("Feijão\r\nCafé\r\n".toByteArray(Charsets.UTF_8), TextFiles.encode(content.text, content.lineSeparator))
    }

    @Test
    fun oldBrazilianFilesAreReadAsWindows1252() {
        val content = TextFiles.decode("Ação é ótima".toByteArray(charset("windows-1252")))
        assertEquals("Ação é ótima", content.text)
        assertEquals("Windows-1252", content.encoding)
    }

    @Test
    fun byteOrderMarksAreReadAndKept() {
        val utf8 = TextFiles.decode(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "olá".toByteArray())
        assertEquals("olá", utf8.text)
        assertTrue(utf8.byteOrderMark)
        assertEquals(3 + "olá".toByteArray().size, TextFiles.encode(utf8.text, byteOrderMark = true).size)
        val utf16 = TextFiles.decode(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "oi".toByteArray(Charsets.UTF_16LE))
        assertEquals("oi", utf16.text)
        assertEquals("UTF-16", utf16.encoding)
    }

    @Test
    fun binaryFilesAreRefused() {
        assertThrows(IllegalArgumentException::class.java) { TextFiles.decode(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x00, 0x00)) }
    }

    @Test
    fun createdFilesEndInALineBreak() {
        assertEquals("a\nb\n", TextFiles.created("a\r\nb"))
        assertEquals("", TextFiles.created(""))
    }

    @Test
    fun readNumbersLinesAndSearches() {
        val content = TextContent("um\ndois\ntrês\nDois de novo\n")
        assertEquals("1| um\n2| dois\n3| três\n4| Dois de novo", TextFiles.read(content, null, null, 1000).text)
        assertEquals("2| dois\n3| três", TextFiles.read(content, "2-3", null, 1000).text)
        val found = TextFiles.read(content, null, "dois", 1000)
        assertEquals("2| dois\n4| Dois de novo", found.text)
        assertEquals(4, found.totalLines)
        assertEquals("Nenhuma linha contém \"cinco\".", TextFiles.read(content, null, "cinco", 1000).note)
    }

    @Test
    fun longReadsStopAtWholeLinesAndSayWhereToContinue() {
        val content = TextContent((1..100).joinToString("\n") { "linha $it" })
        val excerpt = TextFiles.read(content, null, null, 60)
        assertTrue(excerpt.text.length <= 60)
        val shown = excerpt.text.lines().size
        assertEquals("Texto cortado; para continuar, leia com lines \"${shown + 1}-\".", excerpt.note)
    }

    @Test
    fun replaceChangesEveryOccurrenceUnlessAskedForOne() {
        val all = TextFiles.edit("gato, gato e gato\n", listOf(TextEdit.Replace("gato", "cão")))
        assertEquals("cão, cão e cão\n", all.text)
        assertEquals(listOf("trocado em 3 lugares"), all.changes)
        assertEquals("cão, gato e gato\n", TextFiles.edit("gato, gato e gato\n", listOf(TextEdit.Replace("gato", "cão", all = false))).text)
    }

    @Test
    fun replaceExplainsWhenTheTextIsMissing() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            TextFiles.edit("Olá mundo\n", listOf(TextEdit.Replace("olá", "oi")))
        }
        assertTrue(error.message!!.contains("maiúsculas e minúsculas"))
    }

    @Test
    fun lineNumbersCopiedFromTxtReadAreIgnored() {
        val edited = TextFiles.edit("um\ndois\ntrês\n", listOf(TextEdit.Replace("2| dois\n3| três", "2| DOIS\n3| TRÊS")))
        assertEquals("um\nDOIS\nTRÊS\n", edited.text)
        // Only stripped when every line has a number, so real text that looks like one stays.
        assertEquals("12| não é número de linha\ntexto", TextFiles.withoutLineNumbers("12| não é número de linha\ntexto"))
    }

    @Test
    fun appendAndPrependAddLines() {
        assertEquals("a\nb\n", TextFiles.edit("a\n", listOf(TextEdit.Append("b"))).text)
        assertEquals("a\nb", TextFiles.edit("a", listOf(TextEdit.Append("b"))).text)
        assertEquals("b\n", TextFiles.edit("", listOf(TextEdit.Append("b\n"))).text)
        assertEquals("título\na\n", TextFiles.edit("a\n", listOf(TextEdit.Prepend("título"))).text)
    }

    @Test
    fun lineOperationsWorkOnTheTextAsItIsAtThatStep() {
        val edited = TextFiles.edit(
            "1\n2\n3\n4\n5\n",
            listOf(
                TextEdit.DeleteLines("2,4"),
                TextEdit.InsertLines(0, "início"),
                TextEdit.ReplaceLines("3-4", "meio"),
                TextEdit.InsertLines(3, "fim\nfinal"),
            ),
        )
        assertEquals("início\n1\nmeio\nfim\nfinal\n", edited.text)
        assertEquals("2 linhas apagadas", edited.changes[0])
        assertEquals("linhas 3-4 trocadas por 1 linha", edited.changes[2])
    }

    @Test
    fun replaceLinesNeedsAContinuousRange() {
        assertThrows(IllegalArgumentException::class.java) {
            TextFiles.edit("a\nb\nc\n", listOf(TextEdit.ReplaceLines("1,3", "x")))
        }
        assertEquals("a\n", TextFiles.edit("a\nb\nc\n", listOf(TextEdit.ReplaceLines("2-3", ""))).text)
    }

    @Test
    fun insertAfterALineThatDoesNotExistFails() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            TextFiles.edit("a\nb\n", listOf(TextEdit.InsertLines(5, "x")))
        }
        assertNull(error.cause)
        assertTrue(error.message!!.contains("de 0"))
    }
}
