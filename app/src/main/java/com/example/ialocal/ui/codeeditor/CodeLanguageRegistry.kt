package com.example.ialocal.ui.codeeditor

data class CodeLanguageProfile(
    val id: String,
    val displayName: String,
    val aliases: Set<String>,
    val extensions: Set<String>,
    val analysisHint: String,
    val lineCommentTokens: Set<String> = setOf("//"),
    val supportsBlockComments: Boolean = true,
    val deterministicSyntaxParser: Boolean = false,
)

object CodeLanguageRegistry {
    private fun profile(
        id: String,
        displayName: String,
        aliases: Set<String>,
        extensions: Set<String>,
        analysisHint: String,
        lineCommentTokens: Set<String> = setOf("//"),
        supportsBlockComments: Boolean = true,
        deterministicSyntaxParser: Boolean = false,
    ) = CodeLanguageProfile(
        id = id,
        displayName = displayName,
        aliases = aliases + id + displayName.lowercase(),
        extensions = extensions,
        analysisHint = analysisHint,
        lineCommentTokens = lineCommentTokens,
        supportsBlockComments = supportsBlockComments,
        deterministicSyntaxParser = deterministicSyntaxParser,
    )

    val profiles: List<CodeLanguageProfile> = listOf(
        profile("kotlin", "Kotlin", setOf("kt", "kts"), setOf("kt", "kts"), "Kotlin/JVM moderno, null-safety, coroutines e APIs Android quando aplicável."),
        profile("java", "Java", setOf("jdk"), setOf("java"), "Java moderno, checagem de tipos, generics, exceções e APIs JVM/Android."),
        profile("python", "Python", setOf("py", "python3"), setOf("py"), "Python 3.x: sintaxe, indentação, nomes, tipos óbvios, exceções e erros de execução previsíveis.", setOf("#"), false),
        profile("javascript", "JavaScript", setOf("js", "node", "ecmascript"), setOf("js", "mjs", "cjs"), "ECMAScript moderno: sintaxe, escopo, promises, módulos e APIs comuns."),
        profile("typescript", "TypeScript", setOf("ts"), setOf("ts", "tsx"), "TypeScript moderno: sintaxe, tipos, narrowing, generics, módulos e JSX/TSX quando presente."),
        profile("html", "HTML", setOf("html5"), setOf("html", "htm"), "HTML5: estrutura, tags, atributos, nesting, acessibilidade estrutural e referências inválidas.", setOf(), false, deterministicSyntaxParser = true),
        profile("css", "CSS", setOf("css3"), setOf("css"), "CSS moderno: sintaxe, seletores, propriedades, valores e regras de cascata evidentes.", setOf(), true),
        profile("json", "JSON", setOf("jsonc"), setOf("json"), "JSON estrito: aspas, vírgulas, chaves, arrays, tipos e estrutura válida.", setOf(), false),
        profile("xml", "XML", setOf("xhtml"), setOf("xml", "xhtml"), "XML bem-formado: tags, atributos, entidades e hierarquia.", setOf(), false),
        profile("yaml", "YAML", setOf("yml"), setOf("yaml", "yml"), "YAML moderno: indentação, mapeamentos, listas, anchors e escalares.", setOf("#"), false),
        profile("bash", "Bash", setOf("shell", "sh", "bash shell"), setOf("sh", "bash"), "Bash: quoting, expansão, pipelines, condicionais, loops, funções e comandos POSIX/GNU comuns.", setOf("#"), false),
        profile("powershell", "PowerShell", setOf("pwsh", "ps1"), setOf("ps1", "psm1", "psd1"), "PowerShell 7+: parser, pipelines, cmdlets, quoting, escopos e objetos .NET.", setOf("#"), true),
        profile("c", "C", setOf("c99", "c11", "c17", "c23"), setOf("c", "h"), "C moderno: sintaxe, tipos, ponteiros, memória, UB óbvio e headers."),
        profile("cpp", "C++", setOf("c++", "cplusplus", "cpp23"), setOf("cpp", "cc", "cxx", "hpp", "hh", "hxx"), "C++ moderno: tipos, templates, RAII, lifetime, STL e erros de compilação."),
        profile("csharp", "C#", setOf("c#", "cs", "dotnet"), setOf("cs"), "C# moderno/.NET: sintaxe, nullable, LINQ, async/await e tipos."),
        profile("go", "Go", setOf("golang"), setOf("go"), "Go moderno: sintaxe, tipos, interfaces, goroutines, errors e imports."),
        profile("rust", "Rust", setOf("rs"), setOf("rs"), "Rust estável: borrow checker, lifetimes, traits, ownership, tipos e macros usuais."),
        profile("swift", "Swift", setOf("swiftui"), setOf("swift"), "Swift moderno: optionals, tipos, protocolos, concurrency e SwiftUI quando aplicável."),
        profile("dart", "Dart", setOf("flutter"), setOf("dart"), "Dart moderno: null-safety, async, tipos e APIs Flutter quando aplicável."),
        profile("php", "PHP", setOf("php8"), setOf("php", "phtml"), "PHP 8+: sintaxe, tipos, namespaces, exceptions e APIs comuns."),
        profile("ruby", "Ruby", setOf("rb"), setOf("rb", "rake"), "Ruby moderno: sintaxe, blocos, escopo, exceptions e APIs comuns.", setOf("#"), false),
        profile("lua", "Lua", setOf("lua5"), setOf("lua"), "Lua 5.x: sintaxe, tabelas, escopo, closures e APIs padrão.", setOf("--"), false),
        profile("sql", "SQL", setOf("postgresql", "mysql", "sqlite", "tsql"), setOf("sql"), "SQL: sintaxe, aliases, joins, agregações, escopo de colunas e dialeto declarado quando reconhecível.", setOf("--"), true, deterministicSyntaxParser = true),
        profile("r", "R", setOf("rscript"), setOf("r"), "R moderno: sintaxe, vetorização, fatores/data frames, pacotes e erros de runtime previsíveis.", setOf("#"), false),
        profile("scala", "Scala", setOf("scala3"), setOf("scala", "sc"), "Scala 3: tipos, implicits/givens, collections, pattern matching e JVM."),
        profile("groovy", "Groovy", setOf("gradle groovy"), setOf("groovy", "gradle"), "Groovy/JVM e Gradle quando aplicável: sintaxe dinâmica, closures e DSLs."),
        profile("perl", "Perl", setOf("pl", "perl5"), setOf("pl", "pm"), "Perl 5: sintaxe, sigils, regex, escopo e módulos.", setOf("#"), false),
        profile("haskell", "Haskell", setOf("hs"), setOf("hs", "lhs"), "Haskell moderno: tipos, pattern matching, typeclasses e pureza."),
        profile("elixir", "Elixir", setOf("ex", "exs"), setOf("ex", "exs"), "Elixir: pattern matching, pipelines, OTP, tipos óbvios e módulos.", setOf("#"), false),
        profile("erlang", "Erlang", setOf("erl"), setOf("erl", "hrl"), "Erlang/OTP: sintaxe, pattern matching, processos e módulos.", setOf("%"), false),
        profile("clojure", "Clojure", setOf("clj", "cljs"), setOf("clj", "cljs", "cljc", "edn"), "Clojure: forms, parênteses, namespaces, aridade e collections.", setOf(";"), false),
        profile("fsharp", "F#", setOf("f#", "fs"), setOf("fs", "fsx"), "F#/.NET: tipos, pattern matching, computation expressions e módulos."),
        profile("vbnet", "VB.NET", setOf("visual basic", "vb"), setOf("vb"), "Visual Basic .NET: sintaxe, tipos, LINQ, escopo e APIs .NET.", setOf("'"), false),
        profile("solidity", "Solidity", setOf("sol"), setOf("sol"), "Solidity moderno: tipos, visibilidade, storage/memory/calldata, eventos, modifiers e erros de contrato."),
        profile("objectivec", "Objective-C", setOf("objc", "objective-c"), setOf("m", "mm", "h"), "Objective-C moderno: sintaxe, ARC, mensagens, nullability e APIs Apple."),
        profile("assembly", "Assembly", setOf("asm", "x86 asm", "arm asm"), setOf("asm", "s"), "Assembly: sintaxe, registradores, operandos, labels e arquitetura indicada.", setOf(";", "#"), false),
        profile("dockerfile", "Dockerfile", setOf("docker"), setOf("dockerfile"), "Dockerfile: instruções, estágios, COPY/RUN, ARG/ENV e erros de build previsíveis.", setOf("#"), false),
        profile("makefile", "Makefile", setOf("make", "gnu make"), setOf("mk", "makefile"), "GNU Make: targets, recipes, tabs, variáveis, prerequisites e expansão.", setOf("#"), false),
        profile("gradlekts", "Gradle Kotlin DSL", setOf("gradle kotlin", "build.gradle.kts"), setOf("gradle.kts"), "Gradle Kotlin DSL: sintaxe Kotlin, plugins, dependencies, tasks e APIs Gradle."),
        profile("markdown", "Markdown", setOf("md", "gfm"), setOf("md", "markdown"), "Markdown/GFM: estrutura e links/referências; reporte somente problemas concretos, não estilo.", setOf(), false),
        profile("toml", "TOML", setOf("cargo toml"), setOf("toml"), "TOML: tabelas, arrays, chaves, tipos e duplicações inválidas.", setOf("#"), false),
        profile("ini", "INI", setOf("cfg", "properties"), setOf("ini", "cfg", "properties"), "INI/properties: seções, chaves, valores, duplicações e estrutura.", setOf("#", ";"), false),
    )

    fun find(nameOrAlias: String): CodeLanguageProfile {
        val normalized = nameOrAlias.trim().lowercase().removePrefix(".")
        return profiles.firstOrNull { profile ->
            normalized == profile.id ||
                normalized == profile.displayName.lowercase() ||
                normalized in profile.aliases ||
                normalized in profile.extensions
        } ?: CodeLanguageProfile(
            id = normalized.ifBlank { "unknown" },
            displayName = nameOrAlias.ifBlank { "Desconhecida" },
            aliases = emptySet(),
            extensions = emptySet(),
            analysisHint = "Analise estritamente a linguagem declarada pelo usuário e reporte apenas erros concretos.",
        )
    }

    val supportedDisplayNames: List<String> get() = profiles.map { it.displayName }
}
