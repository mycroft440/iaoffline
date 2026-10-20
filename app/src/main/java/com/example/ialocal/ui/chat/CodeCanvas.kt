package com.example.ialocal.ui.chat

/** A piece of an answer: prose, or a fenced code block (``` … ```). */
internal sealed interface AnswerPart {
    data class Prose(val text: String) : AnswerPart

    /** [complete] is false while the model has not written the closing fence yet. */
    data class Code(val language: String, val code: String, val complete: Boolean) : AnswerPart
}

private val OPENING_FENCE = Regex("^ {0,3}(`{3,}|~{3,})\\s*([^\\s`]*).*$")

/** Splits an answer at its fenced code blocks, in order. */
internal fun splitAnswer(answer: String): List<AnswerPart> {
    val parts = mutableListOf<AnswerPart>()
    val prose = StringBuilder()
    val code = StringBuilder()
    var fence: String? = null
    var language = ""

    fun flushProse() {
        val text = prose.toString().trim()
        if (text.isNotEmpty()) parts += AnswerPart.Prose(text)
        prose.clear()
    }

    for (line in answer.lines()) {
        val open = fence
        if (open == null) {
            val match = OPENING_FENCE.matchEntire(line)
            if (match != null) {
                flushProse()
                fence = match.groupValues[1]
                language = match.groupValues[2].lowercase()
                code.clear()
            } else {
                prose.append(line).append('\n')
            }
        } else {
            val trimmed = line.trim()
            if (trimmed.length >= open.length && trimmed.all { it == open[0] }) {
                parts += AnswerPart.Code(language, code.toString().trimEnd('\n'), complete = true)
                fence = null
            } else {
                code.append(line).append('\n')
            }
        }
    }
    if (fence != null) parts += AnswerPart.Code(language, code.toString().trimEnd('\n'), complete = false) else flushProse()
    return parts
}

/** What "Ver resultado" can show for a code block. */
internal enum class PreviewKind { HTML, SVG, JAVASCRIPT }

/** A code block shown as a card in the chat and opened full screen, like Gemini's Canvas. */
internal data class CodeCanvas(
    val title: String,
    val languageLabel: String,
    /** Original fenced-code language (py, tsx, cpp, etc.) to match the pack library. */
    val language: String,
    val code: String,
    val complete: Boolean,
    val preview: PreviewKind?,
) {
    val lineCount: Int get() = if (code.isEmpty()) 0 else code.count { it == '\n' } + 1
}

/** Which message, and which of its code blocks, a canvas shows; it follows the block while it is written. */
internal data class CanvasRef(val messageId: String, val codeIndex: Int)

internal object CodeCanvases {
    /** Shorter blocks that cannot be previewed stay inline in the answer. */
    const val MIN_LINES = 8

    /** Origin of the preview page: an https origin lets pages use storage, and `.invalid` never resolves. */
    const val PREVIEW_BASE_URL = "https://canvas.invalid/"

    private val TITLE = Regex("(?is)<title[^>]*>(.*?)</title>")
    private val HEADING = Regex("(?is)<h1[^>]*>(.*?)</h1>")
    private val TAG = Regex("<[^>]+>")
    private val SCRIPT_END = Regex("(?i)</(script)")

    fun isCanvas(part: AnswerPart.Code): Boolean =
        previewKind(part.language, part.code) != null || part.code.lines().size >= MIN_LINES

    fun of(part: AnswerPart.Code): CodeCanvas {
        val preview = previewKind(part.language, part.code)
        return CodeCanvas(
            title = title(part.language, part.code, preview),
            languageLabel = languageLabel(part.language, preview),
            language = part.language,
            code = part.code,
            complete = part.complete,
            preview = preview,
        )
    }

    /** The [codeIndex]-th code block of [answer], as a canvas. */
    fun at(answer: String, codeIndex: Int): CodeCanvas? =
        splitAnswer(answer).filterIsInstance<AnswerPart.Code>().getOrNull(codeIndex)?.let(::of)

    fun previewKind(language: String, code: String): PreviewKind? {
        val start = code.trimStart()
        return when {
            language in setOf("html", "htm", "xhtml") -> PreviewKind.HTML
            language == "svg" -> PreviewKind.SVG
            language in setOf("js", "javascript") -> PreviewKind.JAVASCRIPT
            language.isEmpty() && (start.startsWith("<!doctype", true) || start.startsWith("<html", true)) -> PreviewKind.HTML
            (language.isEmpty() || language == "xml") && start.startsWith("<svg", true) -> PreviewKind.SVG
            else -> null
        }
    }

    /** The page "Ver resultado" loads, or null when the code cannot be previewed. */
    fun previewDocument(canvas: CodeCanvas): String? = when (canvas.preview) {
        PreviewKind.HTML -> canvas.code
        PreviewKind.SVG -> svgPage(canvas.code)
        PreviewKind.JAVASCRIPT -> consolePage(canvas.code)
        null -> null
    }

    private fun title(language: String, code: String, preview: PreviewKind?): String {
        if (preview == PreviewKind.HTML) {
            val found = TITLE.find(code)?.groupValues?.get(1) ?: HEADING.find(code)?.groupValues?.get(1)
            val text = found?.let { decodeEntities(TAG.replace(it, "")).replace(Regex("\\s+"), " ").trim() }
            return text?.takeIf { it.isNotEmpty() }?.take(60) ?: "Página HTML"
        }
        if (preview == PreviewKind.SVG) return "Imagem SVG"
        return if (language.isEmpty()) "Código" else "Código ${languageLabel(language, preview)}"
    }

    private fun languageLabel(language: String, preview: PreviewKind?): String = when (language) {
        "html", "htm", "xhtml" -> "HTML"
        "css" -> "CSS"
        "js", "javascript" -> "JavaScript"
        "ts", "typescript" -> "TypeScript"
        "jsx" -> "JSX"
        "tsx" -> "TSX"
        "py", "python" -> "Python"
        "kt", "kotlin" -> "Kotlin"
        "java" -> "Java"
        "sql" -> "SQL"
        "sh", "bash", "shell", "zsh" -> "Shell"
        "json" -> "JSON"
        "xml" -> "XML"
        "svg" -> "SVG"
        "c" -> "C"
        "cpp", "c++" -> "C++"
        "cs", "csharp" -> "C#"
        "go" -> "Go"
        "rs", "rust" -> "Rust"
        "php" -> "PHP"
        "rb", "ruby" -> "Ruby"
        "swift" -> "Swift"
        "dart" -> "Dart"
        "yaml", "yml" -> "YAML"
        "md", "markdown" -> "Markdown"
        "" -> when (preview) {
            PreviewKind.HTML -> "HTML"
            PreviewKind.SVG -> "SVG"
            else -> "Texto"
        }
        else -> language.replaceFirstChar { it.uppercase() }
    }

    private fun decodeEntities(text: String): String = text
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")

    // The user's code is placed between the parts, so its own indentation is left untouched.
    private fun svgPage(svg: String): String = SVG_PAGE_START + svg + SVG_PAGE_END

    /** Runs JavaScript in a page that prints what it writes to the console. */
    private fun consolePage(script: String): String =
        CONSOLE_PAGE_START + SCRIPT_END.replace(script, "<\\\\/$1") + CONSOLE_PAGE_END

    private val SVG_PAGE_START = """
        <!doctype html><html><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <style>html,body{margin:0;height:100%;background:#fff}body{display:flex;align-items:center;justify-content:center}svg{max-width:100%;height:auto}</style>
        </head><body>
    """.trimIndent() + "\n"

    private const val SVG_PAGE_END = "\n</body></html>"

    private val CONSOLE_PAGE_START = """
        <!doctype html><html><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <style>body{margin:0;background:#0b1020;color:#e2e8f0;font:13px/1.5 monospace}#out{padding:12px;white-space:pre-wrap;word-break:break-word}.err{color:#fb7185}</style>
        </head><body><div id="out"></div><script>
        (function(){var out=document.getElementById('out');
        function show(cls,args){var line=document.createElement('div');if(cls)line.className=cls;
        line.textContent=Array.prototype.map.call(args,function(a){if(typeof a==='string')return a;try{return JSON.stringify(a)}catch(e){return String(a)}}).join(' ');out.appendChild(line);}
        console.log=function(){show('',arguments)};console.info=console.log;
        console.warn=function(){show('err',arguments)};console.error=console.warn;
        window.onerror=function(m){show('err',['Erro: '+m])};window.__show=show;})();
        </script><script>
    """.trimIndent() + "\n"

    private val CONSOLE_PAGE_END = "\n" + """
        </script><script>
        if(!document.getElementById('out').childNodes.length){window.__show('',['(O código rodou sem escrever nada no console.)'])}
        </script></body></html>
    """.trimIndent()
}
