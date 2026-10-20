package com.example.ialocal.ui.chat

import com.example.ialocal.data.AiModelEntity
import com.example.ialocal.models.ModelCatalog
import java.util.Locale

/**
 * A short identity for the answer header: family/version and total parameter count only.
 * Never confuse total billions (e.g. 27B) with quantization, file size or active MoE experts.
 */
internal fun chatModelLabel(model: AiModelEntity): String {
    val catalog = ModelCatalog.entries.firstOrNull { model.apiModelId.startsWith(it.apiIdPrefix) }
    return if (catalog != null) {
        chatModelLabel(catalog.displayName, catalog.totalParametersBillions)
    } else {
        chatModelLabel(model.name)
    }
}

private val CHAT_PARAMETER_COUNT = Regex("""(?i)(?<![a-z0-9])\d+(?:[.,]\d+)?\s*b(?![a-z0-9])""")
private val CHAT_FILE_EXTENSION = Regex("""(?i)\.gguf$""")
private val CHAT_QUANT_TAIL = Regex(
    """(?i)(?:[-_.\s]+)(?:IQ[1-4][A-Z0-9_-]*|Q[1-8](?:_[A-Z0-9]+)*|BF16|F16|FP16|F32)(?:\.gguf)?$""",
)
private val CHAT_VARIANT_WORDS = Regex(
    """(?i)\b(?:tern[aá]rio|ternary|instruct|reasoning|thinking|preview|pruned|qat|gguf|uncensored|censored|experimental)\b""",
)

/** Also handles manually imported GGUF file names that have no pinned catalog entry. */
internal fun chatModelLabel(rawName: String, totalParametersBillions: Double? = null): String {
    val withoutFile = CHAT_QUANT_TAIL.replace(CHAT_FILE_EXTENSION.replace(rawName.trim(), ""), "")
        .replace('_', ' ')
    val countMatch = CHAT_PARAMETER_COUNT.find(withoutFile)
    val base = if (countMatch != null) {
        withoutFile.substring(0, countMatch.range.last + 1)
    } else {
        withoutFile
    }
    val clean = CHAT_VARIANT_WORDS.replace(base, " ")
        .replace(Regex("""-(?=\d)"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim(' ', '-', '_', '.')
    val label = clean.ifBlank { "Modelo local" }
    if (CHAT_PARAMETER_COUNT.containsMatchIn(label) || totalParametersBillions == null) return label

    val billions = String.format(Locale.ROOT, "%.2f", totalParametersBillions)
        .trimEnd('0').trimEnd('.')
    return "$label ${billions}B"
}
