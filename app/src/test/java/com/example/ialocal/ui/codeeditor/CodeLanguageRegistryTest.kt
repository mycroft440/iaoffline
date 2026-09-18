package com.example.ialocal.ui.codeeditor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeLanguageRegistryTest {
    @Test
    fun commonAliasesResolveToCuratedProfiles() {
        assertEquals("python", CodeLanguageRegistry.find("py").id)
        assertEquals("javascript", CodeLanguageRegistry.find("node").id)
        assertEquals("cpp", CodeLanguageRegistry.find("cplusplus").id)
        assertEquals("csharp", CodeLanguageRegistry.find("cs").id)
        assertEquals("bash", CodeLanguageRegistry.find("shell").id)
    }

    @Test
    fun fileExtensionResolvesLanguageWithoutParserDependency() {
        assertEquals("kotlin", CodeLanguageRegistry.find("src/MainActivity.kt").id)
        assertEquals("typescript", CodeLanguageRegistry.find("ui/page.tsx").id)
        assertEquals("python", CodeLanguageRegistry.find("tools/build.py").id)
    }

    @Test
    fun unknownLanguageRemainsUsableByTheEditor() {
        val profile = CodeLanguageRegistry.find("zig")

        assertEquals("zig", profile.id)
        assertEquals("zig", profile.displayName)
        assertTrue(profile.analysisHint.isNotBlank())
    }
}
