package com.example.ialocal.files

/**
 * Numbers written as "1-3,5,8-": 1-based in the text, 0-based in the result, in the order given.
 * [unit], [units] and [whole] name what is counted in the errors the model reads, such as
 * "página", "páginas" and "o PDF".
 */
internal class NumberRanges(private val unit: String, private val units: String, private val whole: String) {
    fun parse(spec: String?, count: Int): List<Int> {
        require(count > 0) { "${whole.replaceFirstChar { it.uppercase() }} não tem $units." }
        if (spec.isNullOrBlank()) return (0 until count).toList()
        val numbers = mutableListOf<Int>()
        spec.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { part ->
            val dash = part.indexOf('-')
            if (dash < 0) {
                numbers += number(part, count)
            } else {
                val first = number(part.substring(0, dash).trim().ifEmpty { "1" }, count)
                val last = number(part.substring(dash + 1).trim().ifEmpty { count.toString() }, count)
                numbers += if (first <= last) (first..last).toList() else (first downTo last).toList()
            }
        }
        require(numbers.isNotEmpty()) { "Nenhuma $unit indicada em \"$spec\"." }
        return numbers
    }

    private fun number(text: String, count: Int): Int {
        val number = requireNotNull(text.toIntOrNull()) { "${unit.replaceFirstChar { it.uppercase() }} inválida: \"$text\"." }
        require(number in 1..count) {
            "${unit.replaceFirstChar { it.uppercase() }} $number não existe; $whole tem $count ${if (count == 1) unit else units}."
        }
        return number - 1
    }
}

/** PDF pages, as in "1-3,5". */
object PageRanges {
    private val ranges = NumberRanges("página", "páginas", "o PDF")

    fun parse(spec: String?, pageCount: Int): List<Int> = ranges.parse(spec, pageCount)
}

/** Lines of a text file, as in "10-20". */
object LineRanges {
    private val ranges = NumberRanges("linha", "linhas", "o arquivo")

    fun parse(spec: String?, lineCount: Int): List<Int> = ranges.parse(spec, lineCount)
}
