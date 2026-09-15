package com.example.ialocal.ui.codeeditor

import io.xberg.tslp.android.TreeSitterLanguagePack
import java.util.Locale

enum class SyntaxBackend {
    TREE_SITTER,
    SQL_JSQLPARSER,
    NONE,
}

data class CodeLanguageProfile(
    val id: String,
    val displayName: String,
    val aliases: Set<String>,
    val extensions: Set<String>,
    val analysisHint: String,
    val lineCommentTokens: Set<String> = setOf("//"),
    val supportsBlockComments: Boolean = true,
    val syntaxBackend: SyntaxBackend = SyntaxBackend.TREE_SITTER,
    val treeSitterCandidates: List<String> = listOf(id),
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
        syntaxBackend: SyntaxBackend = SyntaxBackend.TREE_SITTER,
        treeSitterCandidates: List<String> = listOf(id),
    ) = CodeLanguageProfile(
        id = id,
        displayName = displayName,
        aliases = aliases + id + displayName.lowercase(),
        extensions = extensions,
        analysisHint = analysisHint,
        lineCommentTokens = lineCommentTokens,
        supportsBlockComments = supportsBlockComments,
        syntaxBackend = syntaxBackend,
        treeSitterCandidates = treeSitterCandidates,
    )

    val profiles: List<CodeLanguageProfile> = listOf(
        profile("kotlin", "Kotlin", setOf("kt", "kts"), setOf("kt", "kts"), "Kotlin/JVM moderno, null-safety, coroutines e APIs Android quando aplicável."),
        profile("java", "Java", setOf("jdk"), setOf("java"), "Java moderno, checagem de tipos, generics, exceções e APIs JVM/Android."),
        profile("python", "Python", setOf("py", "python3"), setOf("py"), "Python 3.x: nomes, tipos óbvios, exceções e erros de execução previsíveis.", setOf("#"), false),
        profile("javascript", "JavaScript", setOf("js", "node", "ecmascript"), setOf("js", "mjs", "cjs"), "ECMAScript moderno: escopo, promises, módulos e APIs comuns."),
        profile("typescript", "TypeScript", setOf("ts"), setOf("ts", "tsx"), "TypeScript moderno: tipos, narrowing, generics, módulos e JSX/TSX quando presente.", treeSitterCandidates = listOf("typescript", "tsx")),
        profile("html", "HTML", setOf("html5"), setOf("html", "htm"), "HTML5: estrutura, tags, atributos, nesting, acessibilidade estrutural e referências inválidas.", setOf(), false),
        profile("css", "CSS", setOf("css3"), setOf("css"), "CSS moderno: seletores, propriedades, valores e regras de cascata evidentes.", setOf(), true),
        profile("json", "JSON", setOf("jsonc"), setOf("json"), "JSON estrito: estrutura e tipos válidos.", setOf(), false),
        profile("xml", "XML", setOf("xhtml"), setOf("xml", "xhtml"), "XML bem-formado: tags, atributos, entidades e hierarquia.", setOf(), false),
        profile("yaml", "YAML", setOf("yml"), setOf("yaml", "yml"), "YAML moderno: mapeamentos, listas, anchors e escalares.", setOf("#"), false),
        profile("bash", "Bash", setOf("shell", "sh", "bash shell"), setOf("sh", "bash"), "Bash: quoting, expansão, pipelines, condicionais, loops, funções e comandos POSIX/GNU comuns.", setOf("#"), false, treeSitterCandidates = listOf("bash", "shell")),
        profile("powershell", "PowerShell", setOf("pwsh", "ps1"), setOf("ps1", "psm1", "psd1"), "PowerShell 7+: pipelines, cmdlets, quoting, escopos e objetos .NET.", setOf("#"), true),
        profile("c", "C", setOf("c99", "c11", "c17", "c23"), setOf("c", "h"), "C moderno: tipos, ponteiros, memória, UB óbvio e headers."),
        profile("cpp", "C++", setOf("c++", "cplusplus", "cpp23"), setOf("cpp", "cc", "cxx", "hpp", "hh", "hxx"), "C++ moderno: tipos, templates, RAII, lifetime, STL e erros de compilação."),
        profile("csharp", "C#", setOf("c#", "cs", "dotnet"), setOf("cs"), "C# moderno/.NET: nullable, LINQ, async/await e tipos.", treeSitterCandidates = listOf("c_sharp", "csharp", "c-sharp")),
        profile("go", "Go", setOf("golang"), setOf("go"), "Go moderno: tipos, interfaces, goroutines, errors e imports."),
        profile("rust", "Rust", setOf("rs"), setOf("rs"), "Rust estável: borrow checker, lifetimes, traits, ownership, tipos e macros usuais."),
        profile("swift", "Swift", setOf("swiftui"), setOf("swift"), "Swift moderno: optionals, tipos, protocolos, concurrency e SwiftUI quando aplicável."),
        profile("dart", "Dart", setOf("flutter"), setOf("dart"), "Dart moderno: null-safety, async, tipos e APIs Flutter quando aplicável."),
        profile("php", "PHP", setOf("php8"), setOf("php", "phtml"), "PHP 8+: tipos, namespaces, exceptions e APIs comuns."),
        profile("ruby", "Ruby", setOf("rb"), setOf("rb", "rake"), "Ruby moderno: blocos, escopo, exceptions e APIs comuns.", setOf("#"), false),
        profile("lua", "Lua", setOf("lua5"), setOf("lua"), "Lua 5.x: tabelas, escopo, closures e APIs padrão.", setOf("--"), false),
        profile(
            "sql",
            "SQL",
            setOf("postgresql", "mysql", "sqlite", "tsql"),
            setOf("sql"),
            "SQL: aliases, joins, agregações, escopo de colunas e dialeto declarado quando reconhecível.",
            setOf("--"),
            true,
            syntaxBackend = SyntaxBackend.SQL_JSQLPARSER,
            treeSitterCandidates = emptyList(),
        ),
        profile("r", "R", setOf("rscript"), setOf("r"), "R moderno: vetorização, fatores/data frames, pacotes e erros de runtime previsíveis.", setOf("#"), false),
        profile("scala", "Scala", setOf("scala3"), setOf("scala", "sc"), "Scala 3: tipos, implicits/givens, collections, pattern matching e JVM."),
        profile("groovy", "Groovy", setOf("gradle groovy"), setOf("groovy", "gradle"), "Groovy/JVM e Gradle quando aplicável: closures e DSLs."),
        profile("perl", "Perl", setOf("pl", "perl5"), setOf("pl", "pm"), "Perl 5: sigils, regex, escopo e módulos.", setOf("#"), false),
        profile("haskell", "Haskell", setOf("hs"), setOf("hs", "lhs"), "Haskell moderno: tipos, pattern matching, typeclasses e pureza."),
        profile("elixir", "Elixir", setOf("ex", "exs"), setOf("ex", "exs"), "Elixir: pattern matching, pipelines, OTP, tipos óbvios e módulos.", setOf("#"), false),
        profile("erlang", "Erlang", setOf("erl"), setOf("erl", "hrl"), "Erlang/OTP: pattern matching, processos e módulos.", setOf("%"), false),
        profile("clojure", "Clojure", setOf("clj", "cljs"), setOf("clj", "cljs", "cljc", "edn"), "Clojure: namespaces, aridade e collections.", setOf(";"), false),
        profile("fsharp", "F#", setOf("f#", "fs"), setOf("fs", "fsx"), "F#/.NET: tipos, pattern matching, computation expressions e módulos.", treeSitterCandidates = listOf("fsharp", "f_sharp")),
        profile("vbnet", "VB.NET", setOf("visual basic", "vb"), setOf("vb"), "Visual Basic .NET: tipos, LINQ, escopo e APIs .NET.", setOf("'"), false, treeSitterCandidates = listOf("vbnet", "visual_basic", "vb")),
        profile("solidity", "Solidity", setOf("sol"), setOf("sol"), "Solidity moderno: tipos, visibilidade, storage/memory/calldata, eventos, modifiers e erros de contrato."),
        profile("objectivec", "Objective-C", setOf("objc", "objective-c"), setOf("m", "mm", "h"), "Objective-C moderno: ARC, mensagens, nullability e APIs Apple.", treeSitterCandidates = listOf("objective_c", "objc", "objective-c")),
        profile("assembly", "Assembly", setOf("asm", "x86 asm", "arm asm"), setOf("asm", "s"), "Assembly: registradores, operandos, labels e arquitetura indicada.", setOf(";", "#"), false, treeSitterCandidates = listOf("asm", "assembly", "nasm")),
        profile("dockerfile", "Dockerfile", setOf("docker"), setOf("dockerfile"), "Dockerfile: instruções, estágios, COPY/RUN, ARG/ENV e erros de build previsíveis.", setOf("#"), false),
        profile("makefile", "Makefile", setOf("make", "gnu make"), setOf("mk", "makefile"), "GNU Make: targets, recipes, tabs, variáveis, prerequisites e expansão.", setOf("#"), false, treeSitterCandidates = listOf("make", "makefile")),
        profile("gradlekts", "Gradle Kotlin DSL", setOf("gradle kotlin", "build.gradle.kts"), setOf("gradle.kts", "kts"), "Gradle Kotlin DSL: plugins, dependencies, tasks e APIs Gradle.", treeSitterCandidates = listOf("kotlin")),
        profile("markdown", "Markdown", setOf("md", "gfm"), setOf("md", "markdown"), "Markdown/GFM: estrutura e links/referências; reporte somente problemas concretos, não estilo.", setOf(), false),
        profile("toml", "TOML", setOf("cargo toml"), setOf("toml"), "TOML: tabelas, arrays, chaves, tipos e duplicações inválidas.", setOf("#"), false),
        profile("ini", "INI", setOf("cfg", "properties"), setOf("ini", "cfg", "properties"), "INI/properties: seções, chaves, valores, duplicações e estrutura.", setOf("#", ";"), false, treeSitterCandidates = listOf("ini", "properties")),
    )

    fun find(nameOrAlias: String): CodeLanguageProfile = find(nameOrAlias, ::resolveTreeSitterGrammar)

    internal fun find(
        nameOrAlias: String,
        treeSitterResolver: (String) -> String?,
    ): CodeLanguageProfile {
        val raw = nameOrAlias.trim()
        val normalized = raw.lowercase().removePrefix(".")

        profiles.firstOrNull { profile ->
            normalized == profile.id ||
                normalized == profile.displayName.lowercase() ||
                normalized in profile.aliases ||
                normalized in profile.extensions
        }?.let { return it }

        val resolvedGrammar = treeSitterResolver(raw)
            ?: treeSitterResolver(normalized)

        if (resolvedGrammar != null) {
            val canonical = resolvedGrammar.trim().lowercase()
            return CodeLanguageProfile(
                id = canonical,
                displayName = humanizeGrammarName(canonical),
                aliases = setOf(normalized).filter { it.isNotBlank() }.toSet(),
                extensions = emptySet(),
                analysisHint = "Linguagem reconhecida dinamicamente pela gramática Tree-sitter '$canonical'. " +
                    "Após a sintaxe formal ser aceita, reporte somente erros semânticos concretos.",
                lineCommentTokens = emptySet(),
                supportsBlockComments = false,
                syntaxBackend = SyntaxBackend.TREE_SITTER,
                treeSitterCandidates = listOf(canonical),
            )
        }

        return CodeLanguageProfile(
            id = normalized.ifBlank { "unknown" },
            displayName = nameOrAlias.ifBlank { "Desconhecida" },
            aliases = emptySet(),
            extensions = emptySet(),
            analysisHint = "Analise estritamente a linguagem declarada pelo usuário e reporte apenas erros concretos.",
            syntaxBackend = SyntaxBackend.NONE,
            treeSitterCandidates = emptyList(),
        )
    }

    /**
     * Every name/alias reported by the Tree-sitter package is accepted dynamically. Curated
     * profiles remain first so SQL keeps JSqlParser and common languages retain friendly names.
     */
    val supportedDisplayNames: List<String>
        get() = (profiles.map { it.displayName } + treeSitterCatalog)
            .distinctBy { it.lowercase() }
            .sortedBy { it.lowercase() }

    val catalogEntryCount: Int get() = supportedDisplayNames.size

    private val treeSitterCatalog: List<String> by lazy {
        runCatching { TreeSitterLanguagePack.availableLanguages() }
            .getOrDefault(emptyList())
            .filter { it.isNotBlank() }
    }

    private val treeSitterCatalogSet: Set<String> by lazy {
        treeSitterCatalog.map { it.lowercase() }.toSet()
    }

    private fun resolveTreeSitterGrammar(input: String): String? {
        val value = input.trim()
        if (value.isBlank()) return null

        val normalized = value.lowercase().removePrefix(".")
        val extension = normalized.substringAfterLast('.', normalized)
        val candidates = buildList {
            runCatching { TreeSitterLanguagePack.detectLanguageFromPath(value) }
                .getOrNull()
                ?.let(::add)
            runCatching { TreeSitterLanguagePack.detectLanguageFromExtension(extension) }
                .getOrNull()
                ?.let(::add)
            add(normalized)
        }.map { it.trim().lowercase() }.filter { it.isNotBlank() }.distinct()

        return candidates.firstOrNull { candidate ->
            candidate in treeSitterCatalogSet ||
                runCatching { TreeSitterLanguagePack.hasLanguage(candidate) }.getOrDefault(false)
        }
    }

    private fun humanizeGrammarName(name: String): String = name
        .replace('_', ' ')
        .replace('-', ' ')
        .split(' ')
        .filter { it.isNotBlank() }
        .joinToString(" ") { token ->
            token.replaceFirstChar { char ->
                if (char.isLowerCase()) char.titlecase(Locale.ROOT) else char.toString()
            }
        }
        .ifBlank { name }
}
