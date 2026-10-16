package com.example.ialocal.knowledge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WikipediaTextTest {
    @Test
    fun keepsParagraphsAndInfoboxFactsWithoutMarkup() {
        val html = """
            <html><head><title>Tiradentes</title><script>var x = 1;</script></head><body>
            <div id="mw-content-text"><style>.infobox{color:red}</style>
            <table class="infobox"><tbody>
              <tr><th colspan="2">Tiradentes</th></tr>
              <tr><th scope="row">Nascimento</th><td>12 de novembro de 1746</td></tr>
              <tr><th scope="row">Morte</th><td>21 de abril de 1792 (45 anos)<br>Rio de Janeiro</td></tr>
              <tr><th scope="row">Ocupação</th><td><div class="plainlist"><ul><li>dentista</li><li>militar</li></ul></div></td></tr>
            </tbody></table>
            <p><b>Joaquim José da Silva Xavier</b>, o <a href="Tiradentes">Tiradentes</a>, foi um dentista e
            militar.<sup class="reference"><a href="#cite">[1]</a></sup> Liderou a Inconfidência&nbsp;Mineira.</p>
            <table class="wikitable"><tr><td><p>Tabela que não deve aparecer</p></td></tr></table>
            <h2 id="Vida">Vida</h2>
            <p>Nasceu na fazenda do Pombal &amp; cresceu em Minas.</p>
            <h2 id="Refs">Referências</h2>
            <p>Fonte que não deve aparecer.</p>
            </div></body></html>
        """.trimIndent()

        val article = WikipediaTextExtractor.extract(html, maxChars = 2000)

        assertEquals(
            listOf(
                "Nascimento: 12 de novembro de 1746",
                "Morte: 21 de abril de 1792 (45 anos) Rio de Janeiro",
                "Ocupação: dentista militar",
            ),
            article.facts,
        )
        assertTrue(article.text.startsWith("Joaquim José da Silva Xavier, o Tiradentes, foi um dentista e militar. Liderou"))
        assertTrue(article.text.contains("Inconfidência Mineira."))
        assertTrue(article.text.contains("## Vida"))
        assertTrue(article.text.contains("Pombal & cresceu"))
        assertFalse(article.text.contains("[1]"))
        assertFalse(article.text.contains("Tabela"))
        assertFalse(article.text.contains("Fonte que não"))
        assertFalse(article.text.contains("color:red"))
    }

    @Test
    fun truncatesAtASentenceOrWord() {
        assertEquals("Primeira frase.", WikipediaTextExtractor.truncate("Primeira frase. Segunda frase longa.", 22))
        assertEquals("Uma palavra…", WikipediaTextExtractor.truncate("Uma palavra comprida", 15))
        assertEquals("curto", WikipediaTextExtractor.truncate("curto", 15))
    }

    @Test
    fun decodesNumericAndNamedEntities() {
        assertEquals("a & b — “c” é", WikipediaTextExtractor.decodeEntities("a &amp; b &mdash; &ldquo;c&rdquo; &#233;"))
        assertEquals("€", WikipediaTextExtractor.decodeEntities("&#x20AC;"))
    }

    @Test
    fun plannerFindsTheSubjectOfCommonQuestions() {
        assertEquals("Tiradentes", WikipediaQueryPlanner.candidates("Quem foi Tiradentes?").first())
        assertEquals("fotossíntese", WikipediaQueryPlanner.candidates("O que é a fotossíntese?").first())
        assertTrue("Revolução Francesa" in WikipediaQueryPlanner.candidates("Me fale sobre a Revolução Francesa e suas causas"))
        assertTrue("Júlio César" in WikipediaQueryPlanner.candidates("Qual era a relação entre Júlio César e Pompeia?"))
        assertTrue("Pompeia" in WikipediaQueryPlanner.candidates("Qual era a relação entre Júlio César e Pompeia?"))
        assertTrue("Albert Einstein" in WikipediaQueryPlanner.candidates("what was Albert Einstein known for"))
        assertTrue("ONU" in WikipediaQueryPlanner.candidates("Quando a ONU foi criada?"))
        assertTrue(WikipediaQueryPlanner.candidates("bom dia, tudo bem?").isEmpty())
    }

    @Test
    fun plannerTriesCommonTitleSpellings() {
        assertEquals(
            listOf("revolução francesa", "Revolução francesa", "Revolução Francesa"),
            WikipediaQueryPlanner.spellings("revolução francesa"),
        )
        assertTrue("Casa da Moeda" in WikipediaQueryPlanner.spellings("casa da moeda"))
        assertTrue("Sede da ONU" in WikipediaQueryPlanner.spellings("sede da ONU"))
    }
}
