package com.example.ialocal.ui.codeeditor

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class CodeLanguagePackDescriptor(
    val id: String,
    val displayName: String,
    val summary: String,
)

data class InstalledCodeLanguagePack(
    val id: String,
    val displayName: String,
    val prompt: String,
)

class CodeLanguagePackRepository(context: Context) {
    private val directory = File(context.applicationContext.filesDir, DIRECTORY_NAME).apply { mkdirs() }
    private val _installedIds = MutableStateFlow(scanInstalledIds())
    val installedIds: StateFlow<Set<String>> = _installedIds.asStateFlow()

    suspend fun download(id: String): InstalledCodeLanguagePack = withContext(Dispatchers.IO) {
        val descriptor = requireNotNull(AVAILABLE_PACKS.firstOrNull { it.id == id }) {
            "Linguagem desconhecida: " + id
        }
        val url = RAW_BASE_URL + "/" + descriptor.id + ".json"
        val payload = downloadText(url)
        val pack = parseAndValidate(payload, descriptor)

        val destination = fileFor(descriptor.id)
        val temporary = File(directory, descriptor.id + ".tmp")
        try {
            temporary.writeText(payload, Charsets.UTF_8)
            if (destination.exists() && !destination.delete()) {
                error("Não foi possível substituir o pacote instalado.")
            }
            check(temporary.renameTo(destination)) {
                "Não foi possível concluir a instalação do pacote."
            }
        } finally {
            temporary.delete()
        }

        _installedIds.value = scanInstalledIds()
        pack
    }

    suspend fun remove(id: String) = withContext(Dispatchers.IO) {
        val descriptor = AVAILABLE_PACKS.firstOrNull { it.id == id } ?: return@withContext
        val file = fileFor(descriptor.id)
        if (file.exists() && !file.delete()) {
            error("Não foi possível remover " + descriptor.displayName + ".")
        }
        _installedIds.value = scanInstalledIds()
    }

    suspend fun guidanceFor(languageInput: String): InstalledCodeLanguagePack? = withContext(Dispatchers.IO) {
        val profile = CodeLanguageRegistry.find(languageInput)
        val descriptor = AVAILABLE_PACKS.firstOrNull { it.id == profile.id } ?: return@withContext null
        val file = fileFor(descriptor.id)
        if (!file.isFile) return@withContext null

        runCatching {
            parseAndValidate(file.readText(Charsets.UTF_8), descriptor)
        }.getOrNull()
    }

    fun isInstalled(languageInput: String): Boolean {
        val id = CodeLanguageRegistry.find(languageInput).id
        return id in installedIds.value
    }

    private fun scanInstalledIds(): Set<String> =
        AVAILABLE_PACKS.mapNotNull { descriptor ->
            descriptor.id.takeIf { fileFor(it).isFile }
        }.toSet()

    private fun fileFor(id: String): File = File(directory, id + ".json")

    private fun downloadText(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.instanceFollowRedirects = true
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("User-Agent", "IA-Offline-Android")

        return try {
            val status = connection.responseCode
            require(status in 200..299) {
                "O repositório respondeu HTTP " + status + "."
            }
            val declaredLength = connection.contentLengthLong
            require(declaredLength <= MAX_PACK_BYTES || declaredLength < 0) {
                "O pacote excede o limite permitido."
            }
            connection.inputStream.use { input ->
                readLimited(input).toString(Charsets.UTF_8)
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun readLimited(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0

        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= MAX_PACK_BYTES) {
                "O pacote excede o limite permitido."
            }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun parseAndValidate(
        raw: String,
        descriptor: CodeLanguagePackDescriptor,
    ): InstalledCodeLanguagePack {
        val json = JSONObject(raw)
        require(json.optInt("schemaVersion") == PACK_SCHEMA_VERSION) {
            "Versão de pacote incompatível."
        }
        require(json.optString("id") == descriptor.id) {
            "O pacote recebido não corresponde à linguagem solicitada."
        }
        val displayName = json.optString("displayName").trim()
        val prompt = json.optString("prompt").trim()
        require(displayName.isNotBlank()) { "Pacote sem nome." }
        require(prompt.length in MIN_PROMPT_CHARS..MAX_PROMPT_CHARS) {
            "Conteúdo do pacote inválido."
        }

        return InstalledCodeLanguagePack(
            id = descriptor.id,
            displayName = displayName,
            prompt = prompt,
        )
    }

    companion object {
        val AVAILABLE_PACKS: List<CodeLanguagePackDescriptor> = listOf(
            CodeLanguagePackDescriptor("kotlin", "Kotlin", "Kotlin/JVM, Android, null-safety e coroutines"),
            CodeLanguagePackDescriptor("java", "Java", "Java moderno, JVM, tipos e exceções"),
            CodeLanguagePackDescriptor("python", "Python", "Python 3, typing, escopo e biblioteca padrão"),
            CodeLanguagePackDescriptor("javascript", "JavaScript", "ECMAScript, módulos, promises e runtime"),
            CodeLanguagePackDescriptor("typescript", "TypeScript", "Tipos, narrowing, generics, módulos e TSX"),
            CodeLanguagePackDescriptor("c", "C", "Ponteiros, memória, headers e C moderno"),
            CodeLanguagePackDescriptor("cpp", "C++", "RAII, STL, templates, lifetime e C++ moderno"),
            CodeLanguagePackDescriptor("csharp", "C#", ".NET, nullable, LINQ e async/await"),
            CodeLanguagePackDescriptor("go", "Go", "Interfaces, goroutines, errors e módulos"),
            CodeLanguagePackDescriptor("rust", "Rust", "Ownership, borrowing, lifetimes e traits"),
            CodeLanguagePackDescriptor("sql", "SQL", "SQL e dialetos comuns"),
            CodeLanguagePackDescriptor("bash", "Bash", "Shell, quoting, pipelines e expansão"),
            CodeLanguagePackDescriptor("html", "HTML", "HTML5, semântica, atributos e acessibilidade"),
            CodeLanguagePackDescriptor("css", "CSS", "Seletores, layout, propriedades e cascata"),
            CodeLanguagePackDescriptor("json", "JSON", "Estruturas JSON e serialização"),
        )

        private const val DIRECTORY_NAME = "code-language-packs"
        private const val RAW_BASE_URL =
            "https://raw.githubusercontent.com/mycroft440/iaoffline/main/language-packs"
        private const val PACK_SCHEMA_VERSION = 1
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 20_000
        private const val MAX_PACK_BYTES = 128 * 1024
        private const val MIN_PROMPT_CHARS = 40
        private const val MAX_PROMPT_CHARS = 60_000
    }
}
