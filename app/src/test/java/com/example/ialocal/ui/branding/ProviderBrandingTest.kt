package com.example.ialocal.ui.branding

import com.example.ialocal.models.ModelProvider
import org.junit.Assert.assertEquals
import org.junit.Test

class ProviderBrandingTest {
    @Test
    fun everyCompanyHasItsOwnLogo() {
        // The logo identifies the company in the catalog and next to its model's replies in the chat.
        val logos = ModelProvider.entries.map { it.logoRes() }
        assertEquals(logos.size, logos.toSet().size)
    }
}
