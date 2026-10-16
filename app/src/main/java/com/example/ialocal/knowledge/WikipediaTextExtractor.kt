package com.example.ialocal.knowledge

/** Plain text of a Wikipedia article: the infobox facts and the paragraphs, without markup. */
data class WikipediaArticleText(
    val facts: List<String>,
    val text: String,
)

/**
 * Turns the HTML that mwoffliner writes into ZIM files into text a small model can read: the
 * infobox becomes "label: value" lines and the article keeps its paragraphs and section headings,
 * without references, tables, styles or scripts. It is regex-based on purpose, so it runs on long
 * articles without building a DOM.
 */
object WikipediaTextExtractor {
    fun extract(html: String, maxChars: Int, maxFacts: Int = 10): WikipediaArticleText {
        val contentStart = html.indexOf("id=\"mw-content-text\"").takeIf { it >= 0 }
            ?: html.indexOf("<body").takeIf { it >= 0 }
            ?: 0
        var body = html.substring(contentStart)
        body = COMMENT.replace(body, " ")
        body = STYLE.replace(body, " ")
        body = SCRIPT.replace(body, " ")
        val facts = infoboxFacts(body, maxFacts)
        body = removeTables(body)
        body = REFERENCE.replace(body, "")

        val text = StringBuilder()
        for (match in BLOCK.findAll(body)) {
            val tag = match.groupValues[1].lowercase()
            val content = clean(match.groupValues[2])
            if (content.isEmpty()) continue
            if (tag != "p") {
                if (END_SECTIONS.any { content.equals(it, ignoreCase = true) }) break
                text.append("\n\n## ").append(content).append("\n\n")
            } else {
                if (text.isNotEmpty() && !text.endsWith("\n\n")) text.append("\n\n")
                text.append(content)
            }
            if (text.length >= maxChars) break
        }
        return WikipediaArticleText(facts, truncate(text.toString().trim(), maxChars))
    }

    private fun infoboxFacts(body: String, maxFacts: Int): List<String> {
        val start = INFOBOX_START.find(body)?.range?.first ?: return emptyList()
        val table = body.substring(start, tableEnd(body, start))
        val facts = mutableListOf<String>()
        for (row in ROW.findAll(table)) {
            val label = row.groupValues[1].let(::clean)
            val value = row.groupValues[2].let(::clean)
            if (label.isEmpty() || value.isEmpty() || label.length > 60) continue
            facts += "$label: ${truncate(value, 160)}"
            if (facts.size >= maxFacts) break
        }
        return facts
    }

    /** Drops every table, nested ones included. */
    private fun removeTables(body: String): String {
        val out = StringBuilder(body.length)
        var index = 0
        while (true) {
            val start = TABLE_OPEN.find(body, index)?.range?.first
            if (start == null) {
                out.append(body, index, body.length)
                return out.toString()
            }
            out.append(body, index, start).append(' ')
            index = tableEnd(body, start)
        }
    }

    /** Index just past the `</table>` that closes the table opened at [start]. */
    private fun tableEnd(body: String, start: Int): Int {
        var depth = 0
        var index = start
        while (index < body.length) {
            val open = TABLE_OPEN.find(body, index)?.range?.first ?: Int.MAX_VALUE
            val close = body.indexOf("</table", index, ignoreCase = true).takeIf { it >= 0 } ?: Int.MAX_VALUE
            if (open == Int.MAX_VALUE && close == Int.MAX_VALUE) return body.length
            if (open < close) {
                depth++
                index = open + 6
            } else {
                depth--
                val end = body.indexOf('>', close).takeIf { it >= 0 }?.plus(1) ?: body.length
                if (depth <= 0) return end
                index = end
            }
        }
        return body.length
    }

    private fun clean(html: String): String {
        // Block tags separate words ("dentista", "militar" in an infobox list); inline tags do not.
        val withoutTags = TAG.replace(BLOCK_TAG.replace(html, " "), "")
        // Wikipedia writes non-breaking and thin spaces, which \s does not match.
        val decoded = decodeEntities(withoutTags).replace('\u00A0', ' ').replace('\u202F', ' ').replace('\u2009', ' ')
        return CITATION_MARK.replace(decoded, "")
            .replace(WHITESPACE, " ")
            .replace(" ,", ",")
            .replace(" .", ".")
            .trim()
    }

    fun decodeEntities(text: String): String {
        if ('&' !in text) return text
        return ENTITY.replace(text) { match ->
            val name = match.groupValues[1]
            when {
                name.startsWith("#x", ignoreCase = true) ->
                    name.substring(2).toIntOrNull(16)?.let(::codePoint) ?: match.value
                name.startsWith("#") -> name.substring(1).toIntOrNull()?.let(::codePoint) ?: match.value
                else -> NAMED_ENTITIES[name] ?: match.value
            }
        }
    }

    private fun codePoint(value: Int): String =
        if (Character.isValidCodePoint(value)) String(Character.toChars(value)) else ""

    /** Cuts at a sentence or word boundary near [maxChars]. */
    fun truncate(text: String, maxChars: Int): String {
        if (text.length <= maxChars) return text
        val cut = text.substring(0, maxChars)
        val sentence = cut.lastIndexOf(". ")
        if (sentence >= maxChars * 2 / 3) return cut.substring(0, sentence + 1)
        val space = cut.lastIndexOf(' ')
        return (if (space > 0) cut.substring(0, space) else cut).trimEnd(',', ';', ':') + "…"
    }

    private val COMMENT = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
    private val STYLE = Regex("<style\\b.*?</style\\s*>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val SCRIPT = Regex("<script\\b.*?</script\\s*>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val REFERENCE = Regex(
        "<sup\\b[^>]*(?:reference|mw-ref|noprint)[^>]*>.*?</sup\\s*>",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )
    private val INFOBOX_START = Regex("<table\\b[^>]*class=\"[^\"]*infobox", RegexOption.IGNORE_CASE)
    private val TABLE_OPEN = Regex("<table\\b", RegexOption.IGNORE_CASE)
    // The label and value may not run into the next cell or row.
    private val ROW = Regex(
        "<tr\\b[^>]*>\\s*<th\\b[^>]*>((?:(?!</th|<tr).)*)</th\\s*>\\s*<td\\b[^>]*>((?:(?!</td|<tr).)*)</td\\s*>",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )
    private val BLOCK = Regex("<(p|h2|h3)\\b[^>]*>(.*?)</\\1\\s*>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val BLOCK_TAG = Regex("</?(?:br|li|ul|ol|div|p|tr|td|th|dd|dt)\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val TAG = Regex("<[^>]*>")
    private val ENTITY = Regex("&(#[0-9]+|#[xX][0-9a-fA-F]+|[a-zA-Z]+);")
    private val CITATION_MARK = Regex("\\[(?:\\d+|nota \\d+|[a-z]|carece de fontes\\?|citation needed)]", RegexOption.IGNORE_CASE)
    private val WHITESPACE = Regex("\\s+")
    private val END_SECTIONS = listOf(
        "Referências", "Notas", "Notas e referências", "Ligações externas", "Ver também", "Bibliografia",
        "Leitura adicional", "Fontes", "References", "Notes", "External links", "See also", "Further reading",
        "Bibliography", "Sources",
    )
    private val NAMED_ENTITIES = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "ndash" to "–", "mdash" to "—", "hellip" to "…", "laquo" to "«", "raquo" to "»",
        "lsquo" to "‘", "rsquo" to "’", "ldquo" to "“", "rdquo" to "”", "deg" to "°",
        "middot" to "·", "times" to "×", "minus" to "−", "thinsp" to " ", "ensp" to " ", "emsp" to " ",
    )
}
