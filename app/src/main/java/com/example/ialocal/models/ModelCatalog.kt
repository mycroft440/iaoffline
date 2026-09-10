package com.example.ialocal.models

/**
 * Curated GGUF downloads shown inside the app.
 *
 * Keep this list intentionally small: every entry must be a real, single-file GGUF that can be
 * consumed directly by the current llama.cpp runtime. Multi-part GGUFs are intentionally excluded
 * until the downloader can atomically manage all shards.
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
            id = "qwen38-27b-q4-k-m",
            name = "Qwen3.8 27B",
            variant = "Q4_K_M · 19 GB",
            description = "Modelo de 27B parâmetros. Exige bem mais RAM e armazenamento; o contexto será calibrado automaticamente para o aparelho.",
            fileName = "Qwen3.8-27B-Q4_K_M.gguf",
            approximateSizeBytes = 19_000_000_000L,
            downloadUrl = "https://huggingface.co/ggml-org/Qwen3.8-27B-GGUF/resolve/main/Qwen3.8-27B-Q4_K_M.gguf?download=true",
            source = "ggml-org/Qwen3.8-27B-GGUF",
            license = "Apache-2.0",
        ),
        CatalogModel(
            id = "qwen3-8b-q4-k-m",
            name = "Qwen3 8B",
            variant = "Q4_K_M · recomendado",
            description = "Opção de 8B disponível para celular. Boa qualidade com cerca de 5 GB de armazenamento.",
            fileName = "Qwen3-8B-Q4_K_M.gguf",
            approximateSizeBytes = 5_030_000_000L,
            downloadUrl = "https://huggingface.co/Qwen/Qwen3-8B-GGUF/resolve/main/Qwen3-8B-Q4_K_M.gguf?download=true",
            source = "Qwen/Qwen3-8B-GGUF",
            license = "Apache-2.0",
            recommended = true,
        ),
        CatalogModel(
            id = "gemma4-e4b-it-q4-0",
            name = "Gemma 4 E4B",
            variant = "Q4_0 · 4,59 GB",
            description = "Gemma 4 instruído em quantização oficial de 4 bits. É uma das opções mais práticas do catálogo para execução local em celular.",
            fileName = "gemma-4-E4B-it-Q4_0.gguf",
            approximateSizeBytes = 4_590_000_000L,
            downloadUrl = "https://huggingface.co/ggml-org/gemma-4-E4B-it-GGUF/resolve/main/gemma-4-E4B-it-Q4_0.gguf?download=true",
            source = "ggml-org/gemma-4-E4B-it-GGUF",
            license = "Apache-2.0",
            recommended = true,
        ),
        CatalogModel(
            id = "llama4-scout-iq1-s",
            name = "Llama 4 Scout",
            variant = "UD-IQ1_S · 32,5 GB",
            description = "Versão de arquivo único do Llama 4 Scout. É muito grande para a maioria dos celulares; use apenas em aparelhos com armazenamento e RAM excepcionais.",
            fileName = "Llama-4-Scout-17B-16E-Instruct-UD-IQ1_S.gguf",
            approximateSizeBytes = 32_500_000_000L,
            downloadUrl = "https://huggingface.co/ggml-org/Llama-4-Scout-17B-16E-Instruct-GGUF/resolve/main/Llama-4-Scout-17B-16E-Instruct-UD-IQ1_S.gguf?download=true",
            source = "ggml-org/Llama-4-Scout-17B-16E-Instruct-GGUF",
            license = "Llama 4 Community License",
        ),
    )

    fun byId(id: String): CatalogModel? = items.firstOrNull { it.id == id }
}
