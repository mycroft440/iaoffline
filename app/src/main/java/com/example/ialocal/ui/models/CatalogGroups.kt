package com.example.ialocal.ui.models

import com.example.ialocal.models.CatalogModel
import com.example.ialocal.models.ModelProvider

/** Where a company's models go in the download catalog. */
internal enum class CatalogSection {
    /** Mixture-of-experts models this phone can run, first and highlighted. */
    MOE,
    DENSE,
    /** Mixture-of-experts models that need more RAM than the phone has, at the end. */
    MOE_NEEDS_MORE_RAM,
}

/** One company's models in one section of the download catalog. */
internal data class CatalogGroup(
    val provider: ModelProvider,
    val section: CatalogSection,
    val models: List<CatalogModel>,
) {
    val moe: Boolean get() = section != CatalogSection.DENSE
}

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
    ModelProvider.INCLUSION_AI,
    ModelProvider.POWERINFER,
    ModelProvider.ZAI,
    ModelProvider.COHERE,
    ModelProvider.POOLSIDE,
    ModelProvider.SBER,
    ModelProvider.ARCEE,
)

/** Minimum native overhead: 256 MiB of KV cache + 512 MiB for compute and runtime. */
internal const val CATALOG_WORKING_MEMORY_BYTES = 768L * 1024 * 1024

/**
 * Planning estimate for a MoE with its GGUF weights in RAM (without disk paging).
 * The active-parameter count measures compute, not resident weights. This estimate assumes the
 * entire GGUF may become resident and only the minimum context; it is not a measured RSS/peak.
 * Larger contexts, repacked tensors and device-specific buffers can use more, while mmap can keep
 * only some experts resident and trade RAM for slower storage reads.
 */
internal fun estimatedFullMoeRamBytes(model: CatalogModel): Long =
    model.approximateSizeBytes + CATALOG_WORKING_MEMORY_BYTES

/**
 * Catalog grouping by the RAM recommended for the phone. Large MoE models are still listed
 * because mmap may allow their experts to be paged from storage; actual loading is not guaranteed.
 */
internal fun runsOnPhone(model: CatalogModel, deviceRamBytes: Long): Boolean =
    model.isExperimental || model.recommendedRamBytes <= deviceRamBytes

/**
 * The download catalog's groups: the MoE models this phone can run first, in their own highlighted
 * section, then the dense ones and, last, the MoE models that need more RAM than [deviceRamBytes].
 * Each section keeps the models together by company, in [providerOrder]; with [onlyProvider], only
 * that company's models are listed.
 */
internal fun catalogGroups(
    catalog: List<CatalogModel>,
    providerOrder: List<ModelProvider> = CATALOG_PROVIDER_ORDER,
    onlyProvider: ModelProvider? = null,
    deviceRamBytes: Long = Long.MAX_VALUE,
): List<CatalogGroup> = CatalogSection.entries.flatMap { section ->
    providerOrder
        .filter { onlyProvider == null || it == onlyProvider }
        .mapNotNull { provider ->
            catalog.filter { it.provider == provider && sectionOf(it, deviceRamBytes) == section }
                .takeIf { it.isNotEmpty() }
                ?.let { CatalogGroup(provider, section, it) }
        }
}

private fun sectionOf(model: CatalogModel, deviceRamBytes: Long): CatalogSection = when {
    !model.isMoe -> CatalogSection.DENSE
    runsOnPhone(model, deviceRamBytes) -> CatalogSection.MOE
    else -> CatalogSection.MOE_NEEDS_MORE_RAM
}

/** The models recommended for the RAM free right now. */
internal data class CatalogRecommendation(
    val models: List<CatalogModel>,
    /** False when nothing fits the free RAM: [models] are then the lightest ones. */
    val fitFreeRam: Boolean,
)

/**
 * Up to [limit] models for the phone as it is now: the largest ones (the more capable) whose file,
 * context and working memory fit [freeRamBytes], one per company so the list is not four versions
 * of the same model. Experimental MoE models (slow, from storage) and the [installedIds] are left
 * out. When nothing fits, the lightest models the phone can run are suggested instead.
 */
internal fun recommendedModels(
    catalog: List<CatalogModel>,
    freeRamBytes: Long,
    deviceRamBytes: Long,
    installedIds: Set<String> = emptySet(),
    limit: Int = 3,
): CatalogRecommendation {
    val candidates = catalog.filter {
        it.id !in installedIds && !it.isExperimental && it.recommendedRamBytes <= deviceRamBytes
    }
    val fitting = candidates.filter { it.approximateSizeBytes + CATALOG_WORKING_MEMORY_BYTES <= freeRamBytes }
    return if (fitting.isNotEmpty()) {
        CatalogRecommendation(
            models = fitting.sortedByDescending { it.approximateSizeBytes }.distinctBy { it.provider }.take(limit),
            fitFreeRam = true,
        )
    } else {
        CatalogRecommendation(
            models = candidates.sortedBy { it.approximateSizeBytes }.distinctBy { it.provider }.take(limit),
            fitFreeRam = false,
        )
    }
}
