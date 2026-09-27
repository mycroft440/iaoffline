package com.example.ialocal.agent.tools

import java.util.Locale

/** Turns what a model wrote as a tool call into text a JSON parser accepts. */
internal object ToolCallText {
    /**
     * The JSON object in [output], without a markdown fence around it, or null. Line breaks and
     * tabs written raw inside strings (models do it in long texts, such as a PDF's content) are
     * escaped, since JSON only allows them as \n and \t.
     */
    fun jsonObject(output: String): String? {
        val ticks = "```"
        val clean = output.trim().removePrefix(ticks + "json").removePrefix(ticks).removeSuffix(ticks).trim()
        val start = clean.indexOf('{')
        val end = clean.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return escapeControlCharsInStrings(clean.substring(start, end + 1))
    }

    fun escapeControlCharsInStrings(json: String): String {
        val out = StringBuilder(json.length)
        var inString = false
        var escaped = false
        for (char in json) {
            if (inString) {
                when {
                    escaped -> { escaped = false; out.append(char) }
                    char == '\\' -> { escaped = true; out.append(char) }
                    char == '"' -> { inString = false; out.append(char) }
                    char == '\n' -> out.append("\\n")
                    char == '\r' -> out.append("\\r")
                    char == '\t' -> out.append("\\t")
                    char < ' ' -> out.append(String.format(Locale.ROOT, "\\u%04x", char.code))
                    else -> out.append(char)
                }
            } else {
                if (char == '"') inString = true
                out.append(char)
            }
        }
        return out.toString()
    }
}
