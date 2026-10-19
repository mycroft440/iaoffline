package com.example.ialocal.ui.models

import com.example.ialocal.models.CatalogModel
import com.example.ialocal.models.ModelProvider

/** One company's models in the download catalog, in the MoE section or in the dense one. */
internal data class CatalogGroup(
    val provider: ModelProvider,
    val moe: Boolean,
    val models: List<CatalogModel>,
)

/** Order of the companies in the download catalog. */
internal val CATALOG_PROVIDER_ORDER = listOf(
    ModelProvider.PRISMML,
    ModelProvider.DEEPSEEK,
    ModelProvider.ALIBABA,
    ModelProvider.MISTRAL,
    ModelProvider.META,
    ModelProvider.GOOGLE,
    ModelProvider.MICROSOFT,
    ModelProvider.IBM,
    ModelProvider.LIQUID,
    ModelProvider.AI2,
    ModelProvider.NVIDIA,
    ModelProvider.OPENAI,
    ModelProvider.OPENBMB,
    ModelProvider.JETBRAINS,
    ModelProvider.ORNITH,
)

/**
 * The download catalog's groups: the MoE models first, in their own highlighted section, then the
 * dense ones. Both sections keep the models together by company, in [providerOrder]; with
 * [onlyProvider], only that company's models are listed.
 */
internal fun catalogGroups(
    catalog: List<CatalogModel>,
    providerOrder: List<ModelProvider> = CATALOG_PROVIDER_ORDER,
    onlyProvider: ModelProvider? = null,
): List<CatalogGroup> = listOf(true, false).flatMap { moe ->
    providerOrder
        .filter { onlyProvider == null || it == onlyProvider }
        .mapNotNull { provider ->
            catalog.filter { it.provider == provider && it.isMoe == moe }
                .takeIf { it.isNotEmpty() }
                ?.let { CatalogGroup(provider, moe, it) }
        }
}
