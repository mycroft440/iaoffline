package com.example.ialocal.models

/**
 * Curated GGUF downloads shown inside the app.
 *
 * Keep this list intentionally small: every entry should be a single-file GGUF that can be
 * consumed by the current llama.cpp runtime. URLs point directly to the model file so Android's
 * DownloadManager can handle multi-gigabyte transfers without sending the user to a browser.
 */
data class CatalogModel(
    val id: String,
    val name: String,
    val variant: String,
    val description: String,
    val fileName: String,
    val approximateSizeBytes: Long,
    val downloadUrl: String,
    val source: String,
    val license: String,
    val recommended: Boolean = false,
)

object ModelCatalog {
    val items: List<CatalogModel> = listOf(
        CatalogModel(
            id = "qwen38-27b-iq4-xs",
            name = "Qwen3.8 27B",
            variant = "IQ4_XS · mais leve",
            description = "Menor uso de armazenamento e RAM entre as opções recomendadas de 4 bits.",
            fileName = "Qwen3.8-27B-IQ4_XS.gguf",
            approximateSizeBytes = 15_570_000_000L,
            downloadUrl = "https://huggingface.co/bartowski/Qwen3.8-27B-GGUF/resolve/main/Qwen3.8-27B-IQ4_XS.gguf?download=true",
            source = "bartowski/Qwen3.8-27B-GGUF",
            license = "Apache-2.0",
        ),
        CatalogModel(
            id = "qwen38-27b-q4-k-s",
            name = "Qwen3.8 27B",
            variant = "Q4_K_S · equilibrado",
            description = "Boa qualidade com economia de espaço; indicado quando o Q4_K_M fica apertado.",
            fileName = "Qwen3.8-27B-Q4_K_S.gguf",
            approximateSizeBytes = 16_710_000_000L,
            downloadUrl = "https://huggingface.co/bartowski/Qwen3.8-27B-GGUF/resolve/main/Qwen3.8-27B-Q4_K_S.gguf?download=true",
            source = "bartowski/Qwen3.8-27B-GGUF",
            license = "Apache-2.0",
        ),
        CatalogModel(
            id = "qwen38-27b-q4-k-m",
            name = "Qwen3.8 27B",
            variant = "Q4_K_M · recomendado",
            description = "Opção padrão de boa qualidade para aparelhos com memória e espaço suficientes.",
            fileName = "Qwen3.8-27B-Q4_K_M.gguf",
            approximateSizeBytes = 17_770_000_000L,
            downloadUrl = "https://huggingface.co/bartowski/Qwen3.8-27B-GGUF/resolve/main/Qwen3.8-27B-Q4_K_M.gguf?download=true",
            source = "bartowski/Qwen3.8-27B-GGUF",
            license = "Apache-2.0",
            recommended = true,
        ),
    )

    fun byId(id: String): CatalogModel? = items.firstOrNull { it.id == id }
}
