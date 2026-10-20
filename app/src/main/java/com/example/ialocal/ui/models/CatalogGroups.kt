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

/** Minimum planning estimate: complete quantized GGUF, minimum KV cache, and work buffers. */
internal fun estimatedCatalogRamBytes(model: CatalogModel): Long =
    model.approximateSizeBytes + CATALOG_WORKING_MEMORY_BYTES

/** How many least-RAM-hungry alternatives to show if nothing fits the currently free RAM. */
internal const val LOW_RAM_FALLBACK_MODEL_COUNT = 5

/** Recommendations recalculated using the memory Android currently reports as available. */
internal data class CatalogRecommendation(
    val models: List<CatalogModel>,
    /** False means none fits now: [models] are the lightest alternatives. */
    val fitFreeRam: Boolean,
    /** Only the first recommendation is offered as Free in the catalog. */
    val freeDownloadModelId: String? get() = models.firstOrNull()?.id
)

/**
 * Recommends ALL catalog models whose full-resident minimum estimate fits in [freeRamBytes].
 * Never drop models because of vendor, an arbitrary maximum, installed state, active MoE parameter
 * count, or the marketed RAM capacity. This is a planning estimate, not proven runtime support.
 * When none fit, show the five lowest estimated-RAM models as alternatives.
 */
internal fun recommendedModels(
    catalog: List<CatalogModel>,
    freeRamBytes: Long,
): CatalogRecommendation {
    val available = freeRamBytes.coerceAtLeast(0L)
    val fits = catalog.filter { estimatedCatalogRamBytes(it) <= available }
        .sortedWith(compareByDescending<CatalogModel> { estimatedCatalogRamBytes(it) }.thenBy { it.id })
    if (fits.isNotEmpty()) return CatalogRecommendation(models = fits, fitFreeRam = true)
    val smallest = catalog.sortedWith(compareBy<CatalogModel> { estimatedCatalogRamBytes(it) }.thenBy { it.id })
        .take(LOW_RAM_FALLBACK_MODEL_COUNT)
    return CatalogRecommendation(models = smallest, fitFreeRam = false)
}
