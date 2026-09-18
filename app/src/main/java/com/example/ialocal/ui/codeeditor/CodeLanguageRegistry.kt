package com.example.ialocal.ui.codeeditor

data class CodeLanguageProfile(
    val id: String,
    val displayName: String,
    val aliases: Set<String>,
    val extensions: Set<String>,
    val analysisHint: String,
)

object CodeLanguageRegistry {
    private fun profile(
        id: String,
        displayName: String,
        aliases: Set<String>,
        extensions: Set<String>,
        analysisHint: String,
    ) = CodeLanguageProfile(
        id = id,
        displayName = displayName,
        aliases = aliases + id + displayName.lowercase(),
        extensions = extensions,
        analysisHint = analysisHint,
    )

    val profiles: List<CodeLanguageProfile> = listOf(
        profile("kotlin", "Kotlin", setOf("kt", "kts", "gradle kotlin"), setOf("kt", "kts", "gradle.kts"), "Kotlin/JVM moderno, null-safety, coroutines e Android quando aplicável."),
        profile("java", "Java", setOf("jdk"), setOf("java"), "Java moderno, tipos, generics, exceções e APIs JVM/Android."),
        profile("python", "Python", setOf("py", "python3"), setOf("py"), "Python 3 moderno, escopo, exceções, typing e biblioteca padrão."),
        profile("javascript", "JavaScript", setOf("js", "node", "ecmascript"), setOf("js", "mjs", "cjs"), "ECMAScript moderno, módulos, promises e APIs JavaScript usuais."),
        profile("typescript", "TypeScript", setOf("ts"), setOf("ts", "tsx"), "TypeScript moderno, tipos, narrowing, generics, módulos e TSX."),
        profile("html", "HTML", setOf("html5"), setOf("html", "htm"), "HTML5, estrutura, semântica, atributos e acessibilidade."),
        profile("css", "CSS", setOf("css3"), setOf("css"), "CSS moderno, seletores, propriedades, layout e cascata."),
        profile("json", "JSON", setOf("jsonc"), setOf("json"), "JSON e estruturas de dados serializadas."),
        profile("yaml", "YAML", setOf("yml"), setOf("yaml", "yml"), "YAML moderno, mapeamentos, listas, anchors e escalares."),
        profile("bash", "Bash", setOf("shell", "sh", "bash shell"), setOf("sh", "bash"), "Bash/POSIX shell, quoting, pipelines, expansão e comandos usuais."),
        profile("powershell", "PowerShell", setOf("pwsh", "ps1"), setOf("ps1", "psm1", "psd1"), "PowerShell 7+, pipelines, cmdlets, objetos .NET e escopos."),
        profile("c", "C", setOf("c11", "c17", "c23"), setOf("c", "h"), "C moderno, tipos, ponteiros, memória, headers e comportamento indefinido."),
        profile("cpp", "C++", setOf("c++", "cplusplus", "cpp23"), setOf("cpp", "cc", "cxx", "hpp", "hh", "hxx"), "C++ moderno, RAII, templates, STL, lifetime e tipos."),
        profile("csharp", "C#", setOf("c#", "cs", "dotnet"), setOf("cs"), "C# moderno/.NET, nullable, LINQ, async/await e tipos."),
        profile("go", "Go", setOf("golang"), setOf("go"), "Go moderno, interfaces, goroutines, errors, módulos e imports."),
        profile("rust", "Rust", setOf("rs"), setOf("rs"), "Rust estável, ownership, borrowing, lifetimes, traits e tipos."),
        profile("sql", "SQL", setOf("postgresql", "mysql", "sqlite", "tsql"), setOf("sql"), "SQL e dialetos comuns; preserve o dialeto indicado pelo código ou pedido."),
        profile("swift", "Swift", setOf("swiftui"), setOf("swift"), "Swift moderno, optionals, protocolos, concurrency e SwiftUI quando aplicável."),
        profile("dart", "Dart", setOf("flutter"), setOf("dart"), "Dart moderno, null-safety, async, tipos e Flutter quando aplicável."),
        profile("php", "PHP", setOf("php8"), setOf("php", "phtml"), "PHP 8+, tipos, namespaces, exceptions e APIs comuns."),
        profile("ruby", "Ruby", setOf("rb"), setOf("rb", "rake"), "Ruby moderno, blocos, escopo, exceptions e APIs comuns."),
        profile("lua", "Lua", setOf("lua5"), setOf("lua"), "Lua 5.x, tabelas, escopo, closures e biblioteca padrão."),
        profile("dockerfile", "Dockerfile", setOf("docker"), setOf("dockerfile"), "Dockerfile, estágios, COPY/RUN, ARG/ENV e contexto de build."),
        profile("makefile", "Makefile", setOf("make", "gnu make"), setOf("mk", "makefile"), "GNU Make, targets, recipes, variáveis e expansão."),
        profile("markdown", "Markdown", setOf("md", "gfm"), setOf("md", "markdown"), "Markdown/GFM, estrutura, links e blocos de código."),
        profile("toml", "TOML", setOf("cargo toml"), setOf("toml"), "TOML, tabelas, arrays, chaves e tipos."),
    )

    fun find(nameOrAlias: String): CodeLanguageProfile {
        val raw = nameOrAlias.trim()
        val normalized = raw.lowercase().removePrefix(".")
        val filename = normalized.substringAfterLast('/')
        val extension = filename.substringAfterLast('.', filename)

        return profiles.firstOrNull { profile ->
            normalized == profile.id ||
                normalized == profile.displayName.lowercase() ||
                normalized in profile.aliases ||
                normalized in profile.extensions ||
                extension in profile.extensions
        } ?: CodeLanguageProfile(
            id = normalized.ifBlank { "unknown" },
            displayName = raw.ifBlank { "Desconhecida" },
            aliases = emptySet(),
            extensions = emptySet(),
            analysisHint = "Preserve a linguagem declarada pelo usuário e altere somente o trecho solicitado.",
        )
    }

    val supportedDisplayNames: List<String>
        get() = profiles.map { it.displayName }.sortedBy { it.lowercase() }
}
