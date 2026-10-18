package com.example.ialocal.ui.chat

/** Lines of reasoning shown while the model thinks, before the user opens the whole of it. */
internal const val REASONING_PREVIEW_LINES = 2

/**
 * The last [REASONING_PREVIEW_LINES] non-blank lines of the reasoning, at most [maxChars] of them
 * from the end, so a long reasoning is not laid out in full only to show its end.
 */
internal fun reasoningTail(reasoning: String, maxChars: Int = 400): String {
    val tail = reasoning.lineSequence()
        .map { it.trimEnd() }
        .filter { it.isNotBlank() }
        .toList()
        .takeLast(REASONING_PREVIEW_LINES)
        .joinToString("\n")
    return if (tail.length <= maxChars) tail else tail.takeLast(maxChars).trimStart()
}
