# Auditoria de confirmações — IA Offline

## Editor de código: validação sintática formal

O editor agora separa de forma rígida **sintaxe** de **análise semântica por IA**.

Uma mensagem de `Sintaxe válida` só pode aparecer depois que um parser formal conclui a análise sem erros. A IA local não pode declarar sintaxe válida ou inválida e qualquer diagnóstico `SYNTAX` devolvido pelo modelo é descartado defensivamente.

Estados possíveis da validação:

- `IDLE`: código ainda não validado ou alterado depois da última validação;
- `CHECKING`: parser formal em execução;
- `VALID`: parser formal concluiu sem erro sintático;
- `INVALID`: parser formal encontrou erro sintático;
- `PARSER_UNAVAILABLE`: a gramática/parser não pôde ser carregada; neste caso o app não afirma que o código está correto.

### SQL

SQL possui validação sintática determinística com JSqlParser 5.3. A análise usa `parseStatements` com parsing complexo habilitado para que falhas reais da gramática sejam propagadas como erro. O diagnóstico é marcado como `LOCAL`, categoria `SYNTAX`, sem depender da IA.

Testes de regressão cobrem:

- consulta SQL válida sem erro de sintaxe;
- `FORM` no lugar de `FROM` rejeitado;
- erro no segundo statement de um script SQL rejeitado;
- expressão SQL incompleta rejeitada.

O parser SQL valida gramática. Existência de tabelas, colunas, funções e permissões depende do schema/dialeto do banco e exige uma futura camada semântica de schema.

### HTML, Python e demais linguagens catalogadas

Os demais perfis registrados usam Tree-sitter por meio do pacote Android `io.xberg.tslp.android:tree-sitter-language-pack-android:1.15.12`.

O motor solicita diagnósticos formais da árvore sintática e converte erros/missing nodes em `CodeIssue` de categoria `SYNTAX`, com linha e coluna. Os spans do Tree-sitter são convertidos de offsets UTF-8 para o trecho exibido pelo editor.

Perfis cobertos pelo registro: Kotlin, Java, Python, JavaScript, TypeScript, HTML, CSS, JSON, XML, YAML, Bash/Shell, PowerShell, C, C++, C#, Go, Rust, Swift, Dart, PHP, Ruby, Lua, R, Scala, Groovy, Perl, Haskell, Elixir, Erlang, Clojure, F#, VB.NET, Solidity, Objective-C, Assembly, Dockerfile, Makefile, Gradle Kotlin DSL, Markdown, TOML e INI. SQL usa o parser dedicado descrito acima.

Cada perfil Tree-sitter possui pelo menos um identificador de gramática candidato. O runtime também tenta resolver a linguagem pelas extensões conhecidas. Se nenhum identificador puder ser resolvido pelo pacote instalado, o estado é obrigatoriamente `PARSER_UNAVAILABLE`.

### Remoção das heurísticas

O antigo verificador genérico de delimitadores/aspas e o parser HTML simplificado foram removidos do caminho autoritativo. Essas heurísticas poderiam produzir falso positivo em construções legítimas de linguagens diferentes. O parser formal é agora a única autoridade para sintaxe.

### IA local

A IA só é chamada depois de `VALID`. O prompt e o parser de resposta proíbem/descartam `SYNTAX`; ela pode complementar somente com `TYPE`, `REFERENCE`, `LOGIC`, `SECURITY` e `COMPATIBILITY`.

Ao editar o código ou trocar a linguagem, o estado sintático volta imediatamente para `IDLE`, evitando exibir uma aprovação obtida para uma versão anterior do texto.

### Critério de pronto

A alteração só deve ser considerada validada para distribuição após o workflow da PR concluir testes unitários, lint e geração dos APK/AAB com sucesso. Além disso, no aparelho, uma linguagem só recebe selo de sintaxe válida se a gramática correspondente for resolvida e o parser terminar sem erros.
