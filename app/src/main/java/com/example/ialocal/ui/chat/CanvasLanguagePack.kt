package com.example.ialocal.ui.chat

import com.example.ialocal.ui.codeeditor.CodeLanguagePackDescriptor
import com.example.ialocal.ui.codeeditor.CodeLanguagePackRepository
import com.example.ialocal.ui.codeeditor.CodeLanguageRegistry

/**
 * Resolve the optional *language guidance pack* for a Canvas, not a native compiler.
 * HTML, SVG and JavaScript previews work in the WebView without downloading any pack.
 */
internal fun canvasLanguagePack(canvas: CodeCanvas): CodeLanguagePackDescriptor? {
    if (canvas.preview != null || canvas.language.isBlank()) return null
    val id = when (canvas.language.lowercase().trim()) {
        "tsx" -> "typescript"
        "jsx" -> "javascript"
        "zsh" -> "bash"
        else -> CodeLanguageRegistry.find(canvas.language).id
    }
    return CodeLanguagePackRepository.AVAILABLE_PACKS.firstOrNull { it.id == id }
}

/** Returns a recommendation only if the exact code language has an available, missing pack. */
internal fun missingCanvasLanguagePack(
    canvas: CodeCanvas,
    installedIds: Set<String>,
): CodeLanguagePackDescriptor? =
    canvasLanguagePack(canvas)?.takeUnless { it.id in installedIds }
