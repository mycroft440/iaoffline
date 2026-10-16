package com.example.ialocal.knowledge

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

class ZimArchiveTest {
    private lateinit var file: File
    private lateinit var archive: ZimArchive

    @Before
    fun setUp() {
        file = File.createTempFile("wiki", ".zim")
        sampleWiki().write(file)
        archive = ZimArchive.open(file, ZstdDecompressor)
    }

    @After
    fun tearDown() {
        archive.close()
        file.delete()
    }

    @Test
    fun readsHeaderMetadataAndTitleIndex() {
        assertEquals('C', archive.articleNamespace)
        assertEquals(5, archive.titleCount)
        assertEquals("Wikipédia de teste", archive.metadata("Title"))
    }

    @Test
    fun findsArticlesByPathAndExactTitle() {
        assertEquals("França", archive.findByPath("França")?.title)
        assertEquals("Revolução Francesa", archive.findByTitle("Revolução Francesa")?.title)
        assertNull(archive.findByTitle("revolução francesa"))
        assertNull(archive.findByPath("Alemanha"))
    }

    @Test
    fun followsRedirectsAndReadsCompressedContent() {
        val redirect = requireNotNull(archive.findByTitle("Revolução francesa"))
        assertTrue(redirect.isRedirect)
        val target = requireNotNull(archive.resolve(redirect))
        assertEquals("Revolução Francesa", target.title)
        val html = String(archive.read(redirect), Charsets.UTF_8)
        assertTrue(html.contains("1789"))
        // Every blob of the zstd cluster, first and last included.
        assertTrue(String(archive.read(requireNotNull(archive.findByPath("Brasil"))), Charsets.UTF_8).contains("Brasília"))
        assertTrue(String(archive.read(requireNotNull(archive.findByPath("Revolução_Francesa"))), Charsets.UTF_8).contains("Bastilha"))
    }

    @Test
    fun limitsHowMuchOfAnEntryIsRead() {
        val entry = requireNotNull(archive.findByPath("Brasil"))
        assertEquals(10, archive.read(entry, maxBytes = 10).size)
    }

    @Test
    fun listsTitlesByPrefix() {
        assertEquals(listOf("Fotossíntese", "França"), archive.titlesWithPrefix("F", 10).map { it.title })
        assertEquals(listOf("França"), archive.titlesWithPrefix("Fr", 10).map { it.title })
        assertEquals(1, archive.titlesWithPrefix("Revolução", 1).size)
        assertTrue(archive.titlesWithPrefix("Z", 10).isEmpty())
    }

    @Test
    fun lookupAnswersQuestionsWithArticleExcerpts() {
        val lookup = WikipediaLookup { listOf(archive) }
        val context = lookup.contextFor("O que foi a revolução francesa?")
        assertNotNull(context)
        assertTrue(context!!.contains("### Revolução Francesa"))
        assertTrue(context.contains("tomada da Bastilha"))
        assertFalse(context.contains("[1]"))

        val hit = requireNotNull(lookup.findArticle("frança"))
        assertEquals("França", hit.second.title)
        assertEquals(listOf("França"), lookup.search("Fra", 5).map { it.second.title })
        assertNull(lookup.contextFor("bom dia, tudo bem?"))
    }

    /**
     * Reads a real Kiwix file when one is given, e.g.
     * IAOFFLINE_ZIM_FILE=wikipedia_pt_top_mini_2026-10.zim ./gradlew :app:testDebugUnitTest
     */
    @Test
    fun readsARealKiwixFileWhenProvided() {
        val path = System.getenv("IAOFFLINE_ZIM_FILE")
        assumeTrue(path != null && File(path).isFile)
        ZimArchive.open(File(path!!), ZstdDecompressor).use { real ->
            assertTrue(real.titleCount > 1000)
            val lookup = WikipediaLookup { listOf(real) }
            val (_, entry) = requireNotNull(lookup.findArticle("Brasil"))
            val hit = lookup.read(real, entry, 600)
            assertTrue(hit.text.length > 100)
            assertNotNull(lookup.contextFor("Quem foi Tiradentes?"))
        }
    }

    private fun sampleWiki() = TestZimBuilder()
        .metadata("Title", "Wikipédia de teste")
        .article(
            "Brasil",
            "Brasil",
            "<html><body><div id=\"mw-content-text\"><p>O <b>Brasil</b> é o maior país da América do Sul. Sua capital é Brasília.</p></div></body></html>",
        )
        .article(
            "Fotossíntese",
            "Fotossíntese",
            "<html><body><div id=\"mw-content-text\"><p>A fotossíntese converte luz em energia química.</p></div></body></html>",
        )
        .article(
            "França",
            "França",
            "<html><body><div id=\"mw-content-text\"><p>A França é um país da Europa Ocidental cuja capital é Paris.</p></div></body></html>",
        )
        .article(
            "Revolução_Francesa",
            "Revolução Francesa",
            "<html><body><div id=\"mw-content-text\"><table class=\"infobox\"><tr><th>Data</th><td>1789–1799</td></tr></table>" +
                "<p>A <b>Revolução Francesa</b> começou em 1789 com a tomada da Bastilha.<sup class=\"reference\">[1]</sup></p></div></body></html>",
        )
        .redirect("Revolução_francesa", "Revolução francesa", "Revolução_Francesa")
}
