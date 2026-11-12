package com.example.ialocal.ui.settings

import com.example.ialocal.AppContact
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacyPolicyTest {
    /** The policy published for the store listing; tests run from the module or from the repository root. */
    private val published: String? = generateSequence(File("").absoluteFile) { it.parentFile }
        .take(4)
        .map { File(it, "docs/politica-de-privacidade.md") }
        .firstOrNull { it.isFile }
        ?.readText()

    @Test
    fun theAppAndThePublishedPolicySayTheSame() {
        val text = requireNotNull(published) { "docs/politica-de-privacidade.md não foi encontrado" }
        assertTrue(text.contains("# ${PrivacyPolicy.TITLE}"))
        assertTrue(text.contains(PrivacyPolicy.UPDATED))
        assertTrue(text.contains(PrivacyPolicy.INTRO))
        PrivacyPolicy.sections.forEachIndexed { index, section ->
            assertTrue(section.title, text.contains("## ${index + 1}. ${section.title}"))
            section.paragraphs.forEach { paragraph -> assertTrue(paragraph, text.contains(paragraph)) }
        }
        // Nothing published that the app does not show.
        assertEquals(PrivacyPolicy.sections.size, Regex("^## ", RegexOption.MULTILINE).findAll(text).count())
    }

    @Test
    fun namesTheContactTheAdsAndThePermissionsThatNeedExplaining() {
        val all = (listOf(PrivacyPolicy.INTRO) + PrivacyPolicy.sections.flatMap { it.paragraphs }).joinToString("\n")
        assertTrue(all.contains(AppContact.EMAIL))
        assertTrue(all.contains("AdMob"))
        assertTrue(all.contains("ID de publicidade"))
        assertTrue(all.contains("https://policies.google.com/technologies/partner-sites"))
        assertTrue(all.contains("Microfone"))
        assertTrue(all.contains("Acesso a todos os arquivos"))
    }
}
