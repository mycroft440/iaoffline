package com.example.ialocal.models

enum class ModelProvider(val sectionLabel: String) {
    GOOGLE("Modelos do Google"),
    ALIBABA("Modelos da Alibaba"),
    META("Modelos da Meta"),
}

/** A curated GGUF that can be downloaded directly by the app. */
data class CatalogModel(
    val id: String,
    val displayName: String,
    val provider: ModelProvider,
    val repository: String,
    val fileName: String,
    val quantization: String,
    val approximateSizeBytes: Long,
    val sha256: String,
    val recommendedRamBytes: Long,
    val description: String,
) {
    val downloadUrl: String
        get() = "https://huggingface.co/$repository/resolve/main/$fileName?download=true"

    val apiIdPrefix: String
        get() = "local-catalog-$id-"
}

/**
 * Curated for Android: one practical quantization per model, with a known SHA-256.
 * Keeping the list in code avoids trusting remote metadata to decide what binary the app executes.
 */
object ModelCatalog {
    val entries: List<CatalogModel> = listOf(
        CatalogModel(
            id = "gemma3-1b-it-q4-k-m",
            displayName = "Gemma 3 1B",
            provider = ModelProvider.GOOGLE,
            repository = "ggml-org/gemma-3-1b-it-GGUF",
            fileName = "gemma-3-1b-it-Q4_K_M.gguf",
            quantization = "Q4_K_M",
            approximateSizeBytes = 806_000_000L,
            sha256 = "8ccc5cd1f1b3602548715ae25a66ed73fd5dc68a210412eea643eb20eb75a135",
            recommendedRamBytes = 2_000_000_000L,
            description = "Modelo compacto do Google para conversa local, indicado para aparelhos com pouca RAM.",
        ),
        CatalogModel(
            id = "qwen3-0.6b-q4-k-m",
            displayName = "Qwen3 0.6B",
            provider = ModelProvider.ALIBABA,
            repository = "Qwen/Qwen3-0.6B-GGUF",
            fileName = "Qwen3-0.6B-Q4_K_M.gguf",
            quantization = "Q4_K_M",
            approximateSizeBytes = 397_000_000L,
            sha256 = "b0638f08417a2d3c8652760462eb5407c6e30173cf9608ad0820757a281eea0e",
            recommendedRamBytes = 1_500_000_000L,
            description = "Mais leve e rápido. Recomendado para testar o app e para aparelhos com pouca RAM.",
        ),
        CatalogModel(
            id = "qwen3-1.7b-q4-k-m",
            displayName = "Qwen3 1.7B",
            provider = ModelProvider.ALIBABA,
            repository = "ggml-org/Qwen3-1.7B-GGUF",
            fileName = "Qwen3-1.7B-Q4_K_M.gguf",
            quantization = "Q4_K_M",
            approximateSizeBytes = 1_280_000_000L,
            sha256 = "d2387ca2dbfee2ffabce7120d3770dadca0b293052bc2f0e138fdc940d9bc7b5",
            recommendedRamBytes = 3_000_000_000L,
            description = "Bom equilíbrio entre tamanho, velocidade e qualidade para celulares intermediários.",
        ),
        CatalogModel(
            id = "qwen3-4b-q4-k-m",
            displayName = "Qwen3 4B",
            provider = ModelProvider.ALIBABA,
            repository = "ggml-org/Qwen3-4B-GGUF",
            fileName = "Qwen3-4B-Q4_K_M.gguf",
            quantization = "Q4_K_M",
            approximateSizeBytes = 2_500_000_000L,
            sha256 = "ab27b9bfa375a178d6cba48f3ad892b94b7739659dcc7aae8058ce0ffed6b328",
            recommendedRamBytes = 5_000_000_000L,
            description = "Qualidade maior, indicado para aparelhos com boa quantidade de RAM disponível.",
        ),
        CatalogModel(
            id = "qwen3-8b-q4-k-m",
            displayName = "Qwen3 8B",
            provider = ModelProvider.ALIBABA,
            repository = "Qwen/Qwen3-8B-GGUF",
            fileName = "Qwen3-8B-Q4_K_M.gguf",
            quantization = "Q4_K_M",
            approximateSizeBytes = 5_030_000_000L,
            sha256 = "d98cdcbd03e17ce47681435b5150e34c1417f50b5c0019dd560e4882c5745785",
            recommendedRamBytes = 8_000_000_000L,
            description = "Modelo maior. Baixe apenas em aparelhos com bastante armazenamento e RAM.",
        ),
        CatalogModel(
            id = "qwen3.8-27b-q4-k-m",
            displayName = "Qwen3.8 27B",
            provider = ModelProvider.ALIBABA,
            repository = "ggml-org/Qwen3.8-27B-GGUF",
            fileName = "Qwen3.8-27B-Q4_K_M.gguf",
            quantization = "Q4_K_M",
            approximateSizeBytes = 19_000_000_000L,
            sha256 = "31629f53165ab6a7dad8c9847dcfd1fdf55829dac1e6e748f4a68581b0033d34",
            recommendedRamBytes = 32_000_000_000L,
            description = "Opção avançada e muito pesada. No app atual é usada em texto; exige aparelho excepcional, muito armazenamento e RAM.",
        ),
        CatalogModel(
            id = "llama3.2-1b-instruct-q4-k-m",
            displayName = "Llama 3.2 1B",
            provider = ModelProvider.META,
            repository = "bartowski/Llama-3.2-1B-Instruct-GGUF",
            fileName = "Llama-3.2-1B-Instruct-Q4_K_M.gguf",
            quantization = "Q4_K_M",
            approximateSizeBytes = 807_694_464L,
            sha256 = "6f85a640a97cf2bf5b8e764087b1e83da0fdb51d7c9fab7d0fece9385611df83",
            recommendedRamBytes = 2_000_000_000L,
            description = "Modelo compacto da Meta, apropriado para conversa offline em aparelhos móveis.",
        ),
    )

    fun requireById(id: String): CatalogModel =
        requireNotNull(entries.firstOrNull { it.id == id }) { "Modelo do catálogo não encontrado: $id" }
}
