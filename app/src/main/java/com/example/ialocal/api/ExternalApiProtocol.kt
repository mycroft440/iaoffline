package com.example.ialocal.api

import java.net.URI
import org.json.JSONObject

/**
 * Never send a bearer token over plaintext HTTP, even for a custom API.
 * An API root like https://openrouter.ai/api/v1 is normalized without a trailing slash.
 */
fun remoteApiBaseUrl(raw: String): String {
    val value = raw.trim().trimEnd('/')
    val parsed = try { URI(value) } catch (_: Exception) { throw IllegalArgumentException("Informe uma URL HTTPS válida.") }
    require(parsed.scheme.equals("https", ignoreCase = true) && !parsed.host.isNullOrBlank() &&
        parsed.rawUserInfo == null && parsed.rawFragment == null && parsed.rawQuery == null) {
        "A API externa exige HTTPS e uma URL sem usuário, senha ou parâmetros."
    }
    return value
}

/** Parse a single OpenAI-compatible SSE event; null means metadata or final marker. */
fun remoteApiEventContent(line: String): String? {
    if (!line.startsWith("data:")) return null
    val data = line.removePrefix("data:").trim()
    if (data.isEmpty() || data == "[DONE]") return null
    val json = JSONObject(data)
    json.optJSONObject("error")?.let {
        throw IllegalStateException(it.optString("message").ifBlank { "Erro na API externa." })
    }
    val delta = json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")
    val content = delta?.opt("content")
    return when (content) {
        is String -> content.takeIf { it.isNotEmpty() }
        else -> null
    }
}

fun remoteApiErrorMessage(status: Int, response: String): String {
    val error = runCatching {
        val json = JSONObject(response)
        json.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
            ?: json.optString("message").takeIf { it.isNotBlank() }
    }.getOrNull()
    return error?.take(400) ?: when (status) {
        401, 403 -> "Chave de API inválida ou sem autorização (HTTP $status)."
        402 -> "A conta do provedor não tem créditos suficientes (HTTP 402)."
        429 -> "Limite de requisições atingido (HTTP 429). Tente novamente."
        else -> "O provedor respondeu com HTTP $status."
    }
}
