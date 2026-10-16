package com.example.ialocal.knowledge

import java.util.Locale

/** One article found in an offline Wikipedia. */
data class WikipediaHit(
    val title: String,
    val facts: List<String>,
    val text: String,
)

/**
 * Guesses which Wikipedia articles a question is about, without a model and without a full-text
 * index: quoted phrases, the subject of common question forms ("quem foi X", "o que é X", "what
 * is X") and runs of capitalized words ("Revolução Francesa", "Júlio César", "ONU").
 */
object WikipediaQueryPlanner {
    fun candidates(question: String, limit: Int = 6): List<String> {
        val text = question.trim().take(MAX_QUESTION_CHARS)
        if (text.isEmpty()) return emptyList()
        val found = LinkedHashSet<String>()
        QUOTED.findAll(text).forEach { found += it.groupValues[1].trim() }
        SUBJECT.findAll(text).forEach { match -> subjectOf(match.groupValues[1])?.let { found += it } }
        capitalizedPhrases(text).forEach { found += it }
        return found.filter { it.length in 2..MAX_CANDIDATE_CHARS }.take(limit)
    }

    /**
     * Spellings to try for a title, since titles are matched exactly: as written, sentence case
     * ("Fotossíntese"), title case ("Revolução Francesa") and lowercase after the first letter,
     * keeping acronyms ("Sede da ONU").
     */
    fun spellings(candidate: String): List<String> {
        val trimmed = candidate.trim().replace(Regex("\\s+"), " ")
        val words = trimmed.split(' ').filter { it.isNotEmpty() }
        val titleCase = words.mapIndexed { index, word ->
            if (index > 0 && word.lowercase() in CONNECTORS) word.lowercase()
            else word.replaceFirstChar { it.titlecase(Locale.ROOT) }
        }.joinToString(" ")
        val lowerRest = words.mapIndexed { index, word ->
            when {
                index == 0 -> word.lowercase().replaceFirstChar { it.titlecase(Locale.ROOT) }
                word.length > 1 && word.all { !it.isLetter() || it.isUpperCase() } -> word
                else -> word.lowercase()
            }
        }.joinToString(" ")
        return listOf(trimmed, sentenceCase(trimmed), titleCase, lowerRest).distinct()
    }

    fun sentenceCase(text: String): String = text.trim().replaceFirstChar { it.titlecase(Locale.ROOT) }

    private fun subjectOf(raw: String): String? {
        var subject = raw.trim()
        subject = subject.split(Regex("[?!.;:\\n]|,\\s"), limit = 2).first().trim()
        val words = subject.split(Regex("\\s+")).filter { it.isNotEmpty() }.toMutableList()
        while (words.isNotEmpty() && words.first().lowercase() in LEADING_ARTICLES) words.removeAt(0)
        if (words.isEmpty() || words.size > MAX_SUBJECT_WORDS) return null
        return words.joinToString(" ").trim('"', '\'', '“', '”', '«', '»')
    }

    private fun capitalizedPhrases(text: String): List<String> {
        val phrases = mutableListOf<String>()
        for (sentence in text.split(Regex("(?<=[.!?])\\s+|\\n+"))) {
            val tokens = TOKEN.findAll(sentence).map { it.value }.toList()
            var current = mutableListOf<String>()
            var pendingConnectors = mutableListOf<String>()
            fun flush() {
                if (current.isNotEmpty()) {
                    phrases += current.joinToString(" ")
                    // "Júlio César e Pompeia" may be two subjects as well as one title.
                    if (current.any { it in LIST_CONNECTORS }) {
                        phrases += current.joinToString(" ").split(Regex(" (?:e|and|y) ")).map { it.trim() }
                    }
                }
                current = mutableListOf()
                pendingConnectors = mutableListOf()
            }
            tokens.forEach { token ->
                when {
                    token.first().isUpperCase() && token.lowercase() !in QUESTION_WORDS -> {
                        current.addAll(pendingConnectors)
                        pendingConnectors = mutableListOf()
                        current += token
                    }
                    current.isNotEmpty() && token.lowercase() in CONNECTORS && pendingConnectors.size < 2 ->
                        pendingConnectors += token.lowercase()
                    else -> flush()
                }
            }
            flush()
        }
        // Longer phrases first: "Revolução Francesa" before "Revolução".
        return phrases.distinct().sortedByDescending { it.count { char -> char == ' ' } }
    }

    private const val MAX_QUESTION_CHARS = 400
    private const val MAX_CANDIDATE_CHARS = 80
    private const val MAX_SUBJECT_WORDS = 6
    private val QUOTED = Regex("[\"“«]([^\"“”«»]{2,80})[\"”»]")
    private val TOKEN = Regex("[\\p{L}\\p{N}][\\p{L}\\p{N}'’.-]*")
    private val SUBJECT = Regex(
        "(?:o que (?:é|são|foi|foram|era|significa)|quem (?:é|foi|são|foram|era|eram|inventou|criou|descobriu|" +
            "escreveu|pintou|fundou|compôs)|onde (?:fica|ficam|é|foi|está|nasceu|morreu)|" +
            "quando (?:foi|é|aconteceu|ocorreu|nasceu|morreu|começou|terminou)|" +
            "(?:me )?(?:fale|conte|explique|diga)(?: um pouco| mais)? sobre|explique|resuma|defina|" +
            "hist[óo]ria d[aeo]s?|biografia d[aeo]|significado d[aeo]|what (?:is|are|was|were)|" +
            "who (?:is|was|were|are|invented|discovered|wrote|founded)|tell me about|where is|" +
            "when (?:was|did|is))\\s+(.+)",
        RegexOption.IGNORE_CASE,
    )
    private val LEADING_ARTICLES = setOf("o", "a", "os", "as", "um", "uma", "the", "an")
    private val LIST_CONNECTORS = setOf("e", "and", "y")
    private val CONNECTORS = setOf("de", "da", "do", "das", "dos", "e", "del", "della", "di", "du", "von", "van", "der", "y", "of", "the")
    private val QUESTION_WORDS = setOf(
        "quem", "qual", "quais", "que", "o", "a", "os", "as", "um", "uma", "quando", "onde", "como", "por",
        "porque", "explique", "fale", "me", "diga", "conte", "resuma", "defina", "eu", "você", "voce", "olá",
        "ola", "oi", "bom", "boa", "obrigado", "obrigada", "sim", "não", "nao", "pode", "poderia", "é", "e",
        "what", "who", "where", "when", "why", "how", "is", "are", "the", "tell", "please", "can", "could",
        "i", "my", "hello", "hi", "thanks", "ok", "isso", "este", "esta", "esse", "essa", "no", "na", "em",
    )
}

/** Looks articles up across the installed offline Wikipedias, best archive first. */
class WikipediaLookup(private val archives: () -> List<ZimArchive>) {
    /** The article a candidate names, by exact title in any spelling, or null. */
    fun findArticle(candidate: String): Pair<ZimArchive, ZimEntry>? {
        for (archive in archives()) {
            for (spelling in WikipediaQueryPlanner.spellings(candidate)) {
                val entry = runCatching { archive.findByTitle(spelling) }.getOrNull() ?: continue
                val target = runCatching { archive.resolve(entry) }.getOrNull() ?: continue
                if (target.isHtml) return archive to target
            }
        }
        return null
    }

    fun read(archive: ZimArchive, entry: ZimEntry, maxChars: Int, maxFacts: Int = 10): WikipediaHit {
        val html = String(archive.read(entry, MAX_ARTICLE_BYTES), Charsets.UTF_8)
        val text = WikipediaTextExtractor.extract(html, maxChars, maxFacts)
        return WikipediaHit(entry.title, text.facts, text.text)
    }

    /** Articles for a free-text query: what the question names, then titles starting with it. */
    fun search(query: String, limit: Int): List<Pair<ZimArchive, ZimEntry>> {
        val results = LinkedHashMap<String, Pair<ZimArchive, ZimEntry>>()
        val candidates = (listOf(query.trim()) + WikipediaQueryPlanner.candidates(query)).distinct()
        for (candidate in candidates) {
            if (results.size >= limit) break
            findArticle(candidate)?.let { results.putIfAbsent(it.second.title, it) }
        }
        val prefix = WikipediaQueryPlanner.sentenceCase(query)
        for (archive in archives()) {
            if (results.size >= limit || prefix.length < 2) break
            runCatching { archive.titlesWithPrefix(prefix, limit * 3) }.getOrDefault(emptyList()).forEach { entry ->
                if (results.size >= limit) return@forEach
                val target = runCatching { archive.resolve(entry) }.getOrNull() ?: return@forEach
                if (target.isHtml) results.putIfAbsent(target.title, archive to target)
            }
        }
        return results.values.take(limit)
    }

    /**
     * Short excerpts of the articles the question is about, for the system prompt; null when it
     * names nothing found. The budget stays small so the prompt costs little to process.
     */
    fun contextFor(question: String, maxArticles: Int = 2, charsPerArticle: Int = 650): String? {
        if (question.length > MAX_AUTO_CONTEXT_QUESTION || "```" in question) return null
        val hits = LinkedHashMap<String, WikipediaHit>()
        for (candidate in WikipediaQueryPlanner.candidates(question)) {
            if (hits.size >= maxArticles) break
            val (archive, entry) = findArticle(candidate) ?: continue
            if (entry.title in hits) continue
            val hit = runCatching { read(archive, entry, charsPerArticle, maxFacts = 6) }.getOrNull() ?: continue
            if (hit.text.isNotBlank()) hits[entry.title] = hit
        }
        if (hits.isEmpty()) return null
        return buildString {
            append("\n\n[Wikipedia offline] Trechos de artigos ligados à pergunta. Use-os se forem relevantes, ")
            append("cite o título do artigo e não invente o que não estiver neles:\n")
            hits.values.forEach { hit ->
                append("\n### ").append(hit.title).append('\n')
                hit.facts.forEach { append("- ").append(it).append('\n') }
                append(hit.text).append('\n')
            }
        }
    }

    companion object {
        private const val MAX_ARTICLE_BYTES = 1024 * 1024
        /** Longer messages are documents, code or attachments, not questions to look up. */
        private const val MAX_AUTO_CONTEXT_QUESTION = 600
    }
}
