package com.example.ialocal.files

import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.util.Matrix
import java.io.File
import kotlin.math.cos
import kotlin.math.sin

/** Pages written as "1-3,5,8-": 1-based in the text, 0-based in the result, in the order given. */
object PageRanges {
    fun parse(spec: String?, pageCount: Int): List<Int> {
        require(pageCount > 0) { "O PDF não tem páginas." }
        if (spec.isNullOrBlank()) return (0 until pageCount).toList()
        val pages = mutableListOf<Int>()
        spec.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { part ->
            val dash = part.indexOf('-')
            if (dash < 0) {
                pages += page(part, pageCount)
            } else {
                val first = page(part.substring(0, dash).trim().ifEmpty { "1" }, pageCount)
                val last = page(part.substring(dash + 1).trim().ifEmpty { pageCount.toString() }, pageCount)
                pages += if (first <= last) (first..last).toList() else (first downTo last).toList()
            }
        }
        require(pages.isNotEmpty()) { "Nenhuma página indicada em \"$spec\"." }
        return pages
    }

    private fun page(text: String, pageCount: Int): Int {
        val number = requireNotNull(text.toIntOrNull()) { "Página inválida: \"$text\"." }
        require(number in 1..pageCount) { "Página $number não existe; o PDF tem $pageCount páginas." }
        return number - 1
    }
}

/** What a created, merged or edited PDF ended up with. */
data class PdfResult(val pages: Int)

/**
 * Creates, assembles, edits and reads PDFs with PdfBox. Sources are never modified: every
 * operation writes a new file. Text uses a Unicode font from the system when one loads (so
 * accents and most scripts come out right) and Helvetica otherwise, with characters the font
 * cannot draw replaced by "?".
 */
class PdfEngine(private val fontFiles: List<String> = ANDROID_FONTS) {

    fun create(output: File, title: String?, content: String): PdfResult {
        require(title?.isNotBlank() == true || content.isNotBlank()) { "O PDF precisa de um título ou de um texto." }
        require(content.length <= MAX_TEXT_CHARS) { "Texto longo demais para um PDF (máximo de $MAX_TEXT_CHARS caracteres)." }
        PDDocument().use { document ->
            TextWriter(document, fonts(document)).write(title, content)
            return save(document, output)
        }
    }

    /** One PDF from [sources], each with its pages (null for all) in the order given. */
    fun merge(output: File, sources: List<Pair<File, String?>>): PdfResult {
        require(sources.isNotEmpty()) { "Indique ao menos um PDF para montar." }
        require(sources.size <= MAX_SOURCES) { "No máximo $MAX_SOURCES PDFs por vez." }
        val opened = mutableListOf<PDDocument>()
        try {
            PDDocument().use { destination ->
                val merger = PDFMergerUtility()
                sources.forEach { (file, pages) ->
                    val source = load(file).also(opened::add)
                    requireEditable(source, file)
                    arrange(source, PageRanges.parse(pages, source.numberOfPages))
                    merger.appendDocument(destination, source)
                }
                return save(destination, output)
            }
        } finally {
            opened.forEach { it.close() }
        }
    }

    /** A copy of [source] with [operations] applied in order. */
    fun edit(source: File, output: File, operations: List<PdfEdit>): PdfResult {
        require(operations.isNotEmpty()) { "Indique ao menos uma operação de edição." }
        load(source).use { document ->
            requireEditable(document, source)
            operations.forEach { operation -> apply(document, operation) }
            return save(document, output)
        }
    }

    /** Text of [pages] (null for all), each page headed by its number. */
    fun read(source: File, pages: String?, maxChars: Int): String {
        load(source).use { document ->
            val stripper = PDFTextStripper().apply { sortByPosition = true }
            val text = buildString {
                for (index in PageRanges.parse(pages, document.numberOfPages)) {
                    stripper.startPage = index + 1
                    stripper.endPage = index + 1
                    append("[Página ${index + 1}]\n")
                    append(stripper.getText(document).trim())
                    append("\n\n")
                    if (length > maxChars) break
                }
            }.trim()
            return if (text.length > maxChars) text.take(maxChars) + "\n[… texto cortado]" else text
        }
    }

    fun pageCount(source: File): Int = load(source).use { it.numberOfPages }

    private fun apply(document: PDDocument, operation: PdfEdit) {
        val count = document.numberOfPages
        when (operation) {
            is PdfEdit.RemovePages -> {
                val removed = PageRanges.parse(operation.pages, count).toSet()
                require(removed.size < count) { "Não é possível remover todas as páginas." }
                arrange(document, (0 until count).filterNot { it in removed })
            }
            is PdfEdit.KeepPages -> arrange(document, PageRanges.parse(operation.pages, count))
            is PdfEdit.Reorder -> {
                // Pages not listed keep their relative order after the listed ones.
                val first = PageRanges.parse(operation.order, count)
                arrange(document, first + (0 until count).filterNot { it in first.toSet() })
            }
            is PdfEdit.Rotate -> {
                require(operation.degrees % 90 == 0) { "A rotação precisa ser múltipla de 90 graus." }
                PageRanges.parse(operation.pages, count).forEach { index ->
                    val page = document.getPage(index)
                    page.rotation = Math.floorMod(page.rotation + operation.degrees, 360)
                }
            }
            is PdfEdit.AddText -> {
                val fonts = fonts(document)
                PageRanges.parse(operation.pages, count).forEach { index ->
                    stamp(document, document.getPage(index), fonts.regular, operation.text, operation.position, operation.size)
                }
            }
            is PdfEdit.AppendText -> TextWriter(document, fonts(document)).write(operation.title, operation.text)
            is PdfEdit.Watermark -> {
                val fonts = fonts(document)
                PageRanges.parse(operation.pages, count).forEach { index ->
                    watermark(document, document.getPage(index), fonts.bold, operation.text)
                }
            }
        }
        require(document.numberOfPages <= MAX_PAGES) { "O PDF passaria de $MAX_PAGES páginas." }
    }

    /**
     * Keeps only [order] of the document's pages, in that order. Inherited attributes (page size,
     * resources, rotation) are copied onto each page first, so moving it keeps its look.
     */
    private fun arrange(document: PDDocument, order: List<Int>) {
        require(order.toSet().size == order.size) { "Cada página só pode aparecer uma vez." }
        val pages = document.pages.toList()
        if (order == pages.indices.toList()) return
        pages.forEach { page ->
            page.mediaBox = page.mediaBox
            page.cropBox = page.cropBox
            page.resources = page.resources
            page.rotation = page.rotation
        }
        pages.forEach { document.pages.remove(it) }
        order.forEach { document.pages.add(pages[it]) }
    }

    /**
     * A content stream drawing in the page as it is displayed: origin at the bottom left of what
     * the reader sees, whatever the page's /Rotate, with that visible width and height.
     */
    private fun displayStream(document: PDDocument, page: PDPage): Triple<PDPageContentStream, Float, Float> {
        val box = page.mediaBox
        val (w, h, x, y) = listOf(box.width, box.height, box.lowerLeftX, box.lowerLeftY)
        val stream = PDPageContentStream(document, page, PDPageContentStream.AppendMode.APPEND, true, true)
        return when (Math.floorMod(page.rotation, 360)) {
            90 -> {
                stream.transform(Matrix(0f, 1f, -1f, 0f, x + w, y))
                Triple(stream, h, w)
            }
            180 -> {
                stream.transform(Matrix(-1f, 0f, 0f, -1f, x + w, y + h))
                Triple(stream, w, h)
            }
            270 -> {
                stream.transform(Matrix(0f, -1f, 1f, 0f, x, y + h))
                Triple(stream, h, w)
            }
            else -> {
                stream.transform(Matrix(1f, 0f, 0f, 1f, x, y))
                Triple(stream, w, h)
            }
        }
    }

    private fun stamp(document: PDDocument, page: PDPage, font: PDFont, text: String, position: String, size: Float) {
        val (stream, width, height) = displayStream(document, page)
        stream.use {
            val lines = text.trim().lines().flatMap { wrap(font, printable(font, it), size, width - 2 * STAMP_MARGIN) }
            val leading = size * 1.3f
            val top = when (position) {
                "bottom" -> STAMP_MARGIN + leading * (lines.size - 1)
                "center" -> height / 2 + leading * (lines.size - 1) / 2
                else -> height - STAMP_MARGIN - size
            }
            stream.setNonStrokingColor(0f, 0f, 0f)
            lines.forEachIndexed { i, line ->
                val lineWidth = font.getStringWidth(line) / 1000 * size
                stream.beginText()
                stream.setFont(font, size)
                stream.newLineAtOffset((width - lineWidth) / 2, top - i * leading)
                stream.showText(line)
                stream.endText()
            }
        }
    }

    private fun watermark(document: PDDocument, page: PDPage, font: PDFont, text: String) {
        val line = printable(font, text.replace('\n', ' ').trim())
        require(line.isNotEmpty()) { "A marca-d'água precisa de texto." }
        val (stream, width, height) = displayStream(document, page)
        stream.use {
            val diagonal = kotlin.math.sqrt(width * width + height * height)
            val size = (diagonal * 0.7f / (font.getStringWidth(line) / 1000)).coerceIn(12f, 96f)
            val textWidth = font.getStringWidth(line) / 1000 * size
            val angle = kotlin.math.atan2(height, width)
            val x = width / 2 - (textWidth / 2) * cos(angle) + (size / 3) * sin(angle)
            val y = height / 2 - (textWidth / 2) * sin(angle) - (size / 3) * cos(angle)
            stream.setGraphicsStateParameters(PDExtendedGraphicsState().apply { nonStrokingAlphaConstant = 0.18f })
            stream.setNonStrokingColor(0.45f, 0.45f, 0.45f)
            stream.beginText()
            stream.setFont(font, size)
            stream.setTextMatrix(Matrix.getRotateInstance(angle.toDouble(), x, y))
            stream.showText(line)
            stream.endText()
        }
    }

    private fun load(file: File): PDDocument {
        require(file.isFile) { "O arquivo ${file.name} não está mais disponível." }
        require(file.length() <= MAX_SOURCE_BYTES) { "O PDF ${file.name} passa de ${MAX_SOURCE_BYTES / (1024 * 1024)} MB." }
        return try {
            PDDocument.load(file, MemoryUsageSetting.setupMixed(MAX_MAIN_MEMORY_BYTES))
        } catch (e: Exception) {
            throw IllegalArgumentException("Não foi possível abrir ${file.name} como PDF: ${e.message}", e)
        }
    }

    /** Protected PDFs are read, but not copied into new files. */
    private fun requireEditable(document: PDDocument, file: File) {
        require(!document.isEncrypted) { "O PDF ${file.name} é protegido e não pode ser editado nem montado." }
    }

    private fun save(document: PDDocument, output: File): PdfResult {
        require(document.numberOfPages in 1..MAX_PAGES) { "O PDF precisa ter de 1 a $MAX_PAGES páginas." }
        output.parentFile?.mkdirs()
        document.save(output)
        return PdfResult(document.numberOfPages)
    }

    private class Fonts(val regular: PDFont, val bold: PDFont)

    private fun fonts(document: PDDocument): Fonts {
        fun load(paths: List<String>): PDFont? = paths.firstNotNullOfOrNull { path ->
            val file = File(path)
            if (!file.isFile) return@firstNotNullOfOrNull null
            runCatching { PDType0Font.load(document, file) }.getOrNull()
        }
        val regular = load(fontFiles.filterNot { it.contains("Bold") })
        val bold = load(fontFiles.filter { it.contains("Bold") }) ?: regular
        return if (regular != null && bold != null) Fonts(regular, bold)
        else Fonts(PDType1Font.HELVETICA, PDType1Font.HELVETICA_BOLD)
    }

    /** Lays text out on new A4 pages: "# " titles, "## "/"### " subtitles, "- " lists, blank-line paragraphs. */
    private inner class TextWriter(private val document: PDDocument, private val fonts: Fonts) {
        private var stream: PDPageContentStream? = null
        private var y = 0f

        fun write(title: String?, content: String) {
            try {
                val heading = title?.trim()?.takeIf { it.isNotEmpty() }
                heading?.let { block(it, fonts.bold, 20f, before = 0f, after = 12f) }
                var lines = content.replace("\r\n", "\n").replace('\r', '\n').replace('\t', ' ').lines()
                // Models often repeat the title as the content's first "# " heading.
                val firstIndex = lines.indexOfFirst { it.isNotBlank() }
                if (heading != null && firstIndex >= 0 &&
                    lines[firstIndex].trim().removePrefix("#").trim().equals(heading, ignoreCase = true)
                ) {
                    lines = lines.drop(firstIndex + 1)
                }
                var gap = 0f
                lines.forEach { raw ->
                    val line = raw.trimEnd().replace("**", "").replace("__", "")
                    val text = line.trimStart()
                    when {
                        text.isEmpty() -> gap = 7f
                        text.startsWith("### ") -> block(text.drop(4), fonts.bold, 12f, before = gap + 6f, after = 3f)
                        text.startsWith("## ") -> block(text.drop(3), fonts.bold, 14f, before = gap + 8f, after = 4f)
                        text.startsWith("# ") -> block(text.drop(2), fonts.bold, 17f, before = gap + 10f, after = 5f)
                        BULLET.matches(text) -> block(text.drop(2), fonts.regular, BODY, before = gap, after = 1f, marker = "•")
                        NUMBERED.matches(text) -> {
                            val number = text.substringBefore(' ')
                            block(text.substringAfter(' '), fonts.regular, BODY, before = gap, after = 1f, marker = number)
                        }
                        else -> block(text, fonts.regular, BODY, before = gap, after = 1f)
                    }
                    if (text.isNotEmpty()) gap = 0f
                }
            } finally {
                stream?.close()
                stream = null
            }
        }

        private fun block(text: String, font: PDFont, size: Float, before: Float, after: Float, marker: String? = null) {
            val indent = if (marker != null) 20f else 0f
            val lines = wrap(font, printable(font, text.trim()), size, PAGE.width - 2 * MARGIN - indent)
            val leading = size * 1.35f
            y -= before
            lines.forEachIndexed { index, line ->
                val current = ensureRoom(leading)
                if (index == 0 && marker != null) show(current, font, size, MARGIN, printable(font, marker))
                show(current, font, size, MARGIN + indent, line)
                y -= leading
            }
            y -= after
        }

        private fun ensureRoom(height: Float): PDPageContentStream {
            val current = stream
            if (current != null && y - height >= MARGIN) return current
            current?.close()
            val page = PDPage(PAGE)
            document.addPage(page)
            y = PAGE.height - MARGIN
            return PDPageContentStream(document, page).also { stream = it }
        }

        private fun show(stream: PDPageContentStream, font: PDFont, size: Float, x: Float, text: String) {
            if (text.isEmpty()) return
            stream.beginText()
            stream.setFont(font, size)
            stream.newLineAtOffset(x, y - size)
            stream.showText(text)
            stream.endText()
        }
    }

    /** [text] with every character [font] cannot draw replaced by "?". */
    private fun printable(font: PDFont, text: String): String {
        val clean = text.filter { it == ' ' || !it.isISOControl() }
        if (encodes(font, clean)) return clean
        val out = StringBuilder()
        var i = 0
        while (i < clean.length) {
            val codePoint = clean.codePointAt(i)
            val character = String(Character.toChars(codePoint))
            out.append(if (encodes(font, character)) character else "?")
            i += Character.charCount(codePoint)
        }
        return out.toString()
    }

    private fun encodes(font: PDFont, text: String): Boolean =
        runCatching { font.encode(text) }.isSuccess

    /** Lines no wider than [width], broken between words (inside a word only when it alone is wider). */
    private fun wrap(font: PDFont, text: String, size: Float, width: Float): List<String> {
        fun widthOf(value: String) = font.getStringWidth(value) / 1000 * size
        val lines = mutableListOf<String>()
        var line = ""
        text.split(' ').filter { it.isNotEmpty() }.forEach { word ->
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (widthOf(candidate) <= width) {
                line = candidate
                return@forEach
            }
            if (line.isNotEmpty()) lines += line
            var rest = word
            while (widthOf(rest) > width) {
                var cut = rest.length - 1
                while (cut > 1 && widthOf(rest.substring(0, cut)) > width) cut--
                lines += rest.substring(0, cut)
                rest = rest.substring(cut)
            }
            line = rest
        }
        if (line.isNotEmpty() || lines.isEmpty()) lines += line
        return lines
    }

    companion object {
        /** Regular faces first; files with "Bold" in the name are the bold face. */
        val ANDROID_FONTS = listOf(
            "/system/fonts/NotoSans-Regular.ttf",
            "/system/fonts/RobotoStatic-Regular.ttf",
            "/system/fonts/Roboto-Regular.ttf",
            "/system/fonts/DroidSans.ttf",
            "/system/fonts/NotoSans-Bold.ttf",
            "/system/fonts/RobotoStatic-Bold.ttf",
            "/system/fonts/Roboto-Bold.ttf",
            "/system/fonts/DroidSans-Bold.ttf",
        )
        private val PAGE = PDRectangle.A4
        private const val MARGIN = 56f
        private const val STAMP_MARGIN = 36f
        private const val BODY = 11f
        private val BULLET = Regex("^[-*•] .*")
        private val NUMBERED = Regex("^\\d{1,3}[.)] .*")
        const val MAX_TEXT_CHARS = 200_000
        const val MAX_PAGES = 500
        const val MAX_SOURCES = 20
        private const val MAX_SOURCE_BYTES = 100L * 1024 * 1024
        private const val MAX_MAIN_MEMORY_BYTES = 32L * 1024 * 1024
    }
}

/** Edits applied by [PdfEngine.edit], in order; page numbers refer to the PDF as it is at that step. */
sealed class PdfEdit {
    data class RemovePages(val pages: String) : PdfEdit()
    data class KeepPages(val pages: String) : PdfEdit()
    data class Reorder(val order: String) : PdfEdit()
    data class Rotate(val pages: String?, val degrees: Int) : PdfEdit()
    data class AddText(val pages: String?, val text: String, val position: String, val size: Float) : PdfEdit()
    data class AppendText(val title: String?, val text: String) : PdfEdit()
    data class Watermark(val pages: String?, val text: String) : PdfEdit()
}
