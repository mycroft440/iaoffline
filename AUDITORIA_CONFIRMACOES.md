# Auditoria de confirmações — IA Offline

## Editor de código: status de validação

O editor distingue suporte catalogado de validação sintática determinística. Ter um perfil de linguagem não significa possuir um compilador/parser dedicado.

### SQL

SQL possui validação sintática local determinística com JSqlParser 5.3. A análise usa `parseStatements` com `withAllowComplexParsing(true)` para garantir que uma falha no parsing simples seja repetida pelo parser complexo e, persistindo, propagada como erro. O diagnóstico é marcado como `LOCAL`, categoria `SYNTAX`, severidade `ERROR`, sem depender da IA.

Testes de regressão cobrem:

- consulta SQL válida sem erro de sintaxe;
- `FORM` no lugar de `FROM` rejeitado como erro;
- erro no segundo statement de um script SQL rejeitado;
- expressão SQL incompleta rejeitada.

O parser SQL valida gramática. Ele não valida se tabelas/colunas existem, pois isso depende do schema do banco. Erros de schema exigem conexão ou importação explícita do schema.

### Demais linguagens

HTML tem validação estrutural local de tags, além de delimitadores. Python, PowerShell, Bash e os demais perfis ainda não devem ser apresentados como compiladores/parsers completos: atualmente recebem checagens estruturais locais e análise complementar da IA, salvo quando um parser dedicado for integrado futuramente.

### Critério de pronto

A alteração só deve ser considerada validada para distribuição após o workflow da PR concluir testes unitários, lint e geração dos artefatos Android com sucesso.
