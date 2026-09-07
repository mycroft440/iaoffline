# IA Offline — Android

Aplicativo Android local-first para importar modelos GGUF, validar a inferência no aparelho, expor uma API local compatível com chat completions e usar o modelo no próprio chat ou como agente.

## Caminho crítico implementado

```text
Selecionar .gguf
      ↓
Pré-validar GGUF + aparelho
      ↓
Copiar para armazenamento privado
      ↓
Carregar via llama.cpp Android
      ↓
Smoke test de inferência real
      ↓
Status VERIFIED
      ↓
Modelo ativo
      ↓
127.0.0.1:11435
      ↓
/v1/chat/completions
      ↓
Chat do aplicativo
```

Um arquivo importado não é tratado como funcional apenas por existir no banco. O app mantém estados de verificação (`IMPORTED`, `VERIFYING`, `VERIFIED`, `ERROR`) e só permite ativar como modelo de uso após uma inferência real gerar texto.

## Teste completo da integração

A tela **Modelos e API** possui **Executar teste completo**. O teste percorre o mesmo caminho usado pelo app:

1. Confirma o servidor em `127.0.0.1`.
2. Valida `GET /v1/health`.
3. Confirma que um endpoint protegido rejeita requisição sem Bearer (`401`).
4. Valida `GET /v1/models` com a chave e exige um modelo ativo `VERIFIED`.
5. Executa uma inferência real por `POST /v1/chat/completions`.
6. Executa uma segunda geração por SSE e exige conteúdo + `[DONE]`.

O resultado mostra exatamente qual etapa passou ou falhou e registra eventos no log de diagnóstico do app.

## Chat

- Histórico persistente com Room.
- Nova conversa, renomear, fixar/desafixar e excluir.
- Fixadas no topo; demais conversas ordenadas por `updatedAt`.
- Tema claro, escuro e seguir o sistema.
- Envio de arquivos e gravação/importação de áudio.
- Respostas por streaming com cancelamento.
- O próprio chat chama a API localhost; não contorna o gateway.

## Arquivos e áudio

- TXT, Markdown, JSON e CSV entram como contexto textual.
- PDF com camada de texto é extraído para contexto usando PDFBox Android.
- O texto processado do anexo fica persistido para continuar disponível nas mensagens seguintes da conversa.
- Áudio usa reconhecimento on-device do Android quando disponível no aparelho.
- Um fallback com Whisper/whisper.cpp ainda pode ser adicionado para aparelhos sem reconhecimento on-device adequado.

## Modelos de IA

- Importação `.gguf` pelo seletor de documentos do Android.
- Leitura de metadados GGUF antes da cópia: versão, arquitetura, tensors, contexto e chat template quando presentes.
- Verificação de ABI, espaço e estimativa conservadora de RAM.
- Armazenamento privado do modelo.
- Smoke test nativo real antes de marcar `VERIFIED`.
- Biblioteca, ativação, nova verificação, unload de RAM e exclusão.
- Primeiro alvo: Android ARM64/API 33+ com modelos GGUF compatíveis com o `llama.cpp` fixado pelo build.

## API local

O servidor escuta somente no loopback:

```text
http://127.0.0.1:11435
```

Endpoints principais:

```text
GET  /health
GET  /v1/health
GET  /v1/models
POST /v1/chat/completions
GET  /v1/agents
POST /v1/agents/run
```

Exceto health, os endpoints exigem:

```text
Authorization: Bearer <chave-local>
```

### Chat completo

```bash
curl http://127.0.0.1:11435/v1/chat/completions \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer SUA_CHAVE' \
  -d '{
    "model": "local-seu-modelo-xxxxxxxx",
    "stream": false,
    "messages": [{"role":"user","content":"Olá!"}],
    "max_tokens": 128
  }'
```

### Streaming SSE

Envie `"stream": true`. A resposta usa `text/event-stream`, envia chunks compatíveis com `chat.completion.chunk` e termina com:

```text
data: [DONE]
```

## Agentes e ferramentas

Cada modelo recebe um perfil de agente com nome, system prompt, temperatura e limite de tokens. O agente possui um loop de ferramentas limitado e, por segurança, as ferramentas iniciais são somente leitura:

```text
list_recent_conversations
search_conversations
read_conversation
current_time
```

A execução de ferramenta é registrada em diagnóstico. Ferramentas destrutivas ou permissões amplas do aparelho ainda não são concedidas automaticamente a modelos importados.

## Runtime GGUF

A inferência usa o binding Android oficial do `ggml-org/llama.cpp`, fixado em `v0.4.0` pelo workflow.

Antes de compilar o app, o script:

```bash
./scripts/prepare_llama_android.sh
```

baixa o `llama.cpp`, compila o módulo Android oficial e gera:

```text
app/libs/llama-android.aar
```

O AAR é artefato de build e não fica versionado no repositório.

## GitHub Actions

`.github/workflows/android.yml` executa:

1. Java 17.
2. Android SDK 36/37, NDK e CMake.
3. Gradle 9.6.
4. Build do binding Android `llama.cpp v0.4.0`.
5. Testes unitários.
6. `assembleDebug`.
7. Upload de `app-debug.apk` como artifact.

O workflow roda em push/PR para `main` e também pode ser disparado manualmente.

## Requisitos atuais

- Android 13 / API 33 ou superior.
- Aparelho compatível com o ABI fornecido pelo binding Android.
- RAM e armazenamento suficientes para o GGUF escolhido.
- JDK 17 para build.
- compileSdk 37 / targetSdk 36.

## Arquitetura

```text
UI Chat / Modelos
       |
       v
LocalApiAiGateway
       |
       v
127.0.0.1:11435
       |
       +--> /v1/health
       +--> /v1/models
       +--> /v1/chat/completions (JSON/SSE)
       +--> /v1/agents/run
       |
       v
AiOrchestrator
       |
       +--> AgentToolRegistry
       |
       v
LlamaCppRuntime
       |
       v
GGUF privado
```

## Limitações restantes

- O primeiro APK ainda precisa ser compilado pelo GitHub Actions e validado em aparelho físico com um GGUF real.
- Reconhecimento de áudio depende do serviço on-device disponível no Android; Whisper local ainda não é fallback universal.
- PDFs escaneados sem camada de texto ainda precisam de OCR.
- O binding atual não expõe todos os parâmetros de sampling do contrato HTTP; por exemplo, `temperature` pode não controlar efetivamente a implementação nativa fixada.
- O servidor vive no processo do aplicativo; um foreground service será necessário para mantê-lo disponível de forma prolongada em segundo plano.
- Ferramentas de agente com ações destrutivas, acesso a outros apps ou permissões sensíveis ainda exigem uma camada de autorização explícita.

## Próximas validações

1. GitHub Actions verde.
2. Baixar o APK artifact.
3. Instalar em Android ARM64/API 33+.
4. Importar um GGUF pequeno e quantizado.
5. Confirmar status `VERIFIED`.
6. Executar **Teste completo da integração**.
7. Conversar normalmente pelo chat e validar reinicialização/troca de modelo.
