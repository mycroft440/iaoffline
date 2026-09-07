# IA Offline — Android

Aplicativo Android local-first para importar modelos GGUF, validá-los com inferência real, expor uma API local compatível com o formato de chat da OpenAI e usar essa mesma API no chat do aplicativo.

## Escopo atual

- Android 13+ (`minSdk 33`).
- `arm64-v8a` e `x86_64`, conforme o binding Android do `llama.cpp` usado pelo projeto.
- Modelos `.gguf` compatíveis com `llama.cpp`.
- Importação com pré-validação do cabeçalho GGUF antes da cópia.
- Cópia para armazenamento privado do app.
- Smoke test nativo antes de marcar um modelo como `VERIFIED`.
- Apenas modelos `VERIFIED` podem ser ativados.
- API local em `127.0.0.1:11435` protegida por Bearer token.
- Loop de `accept()` bloqueante e despacho de cada cliente em coroutine separada.
- Limite de 8 conexões simultâneas para evitar exaustão de recursos.
- `GET /v1/health`.
- `GET /v1/models`.
- `POST /v1/chat/completions`.
- Streaming SSE com `stream: true`.
- Perfis de agente e ferramentas locais somente de leitura.
- Histórico, fixar, renomear e ordenar conversas por atividade.
- Tema claro, escuro e sistema.
- Anexos de texto e PDFs com texto extraível.
- Transcrição de áudio usando reconhecimento on-device do Android quando o aparelho disponibiliza esse recurso.

## Limitações declaradas

O projeto **não promete que qualquer arquivo de IA funcionará**. A primeira versão suporta GGUF e depende da compatibilidade do modelo com o runtime `llama.cpp`, além de memória e armazenamento suficientes no aparelho.

O binding Android `llama.cpp v0.4.0` usado neste projeto possui temperatura de sampling fixa em `0.3`. Por isso, a API rejeita explicitamente `temperature` diferente de `0.3` em vez de aceitar um valor que seria ignorado.

PDFs digitalizados sem camada de texto ainda exigem OCR. A transcrição de áudio depende de `SpeechRecognizer.isOnDeviceRecognitionAvailable()`; Whisper local ainda não é o fallback desta versão.

## Fluxo de importação

```text
Arquivo .gguf
    ↓
GgufInspector
    ↓
DeviceCompatibilityChecker
    ↓
Cópia privada
    ↓
Revalidação da cópia
    ↓
llama.cpp Android runtime
    ↓
Smoke test real
    ↓
VERIFIED
    ↓
Modelo ativo
    ↓
127.0.0.1:11435/v1/chat/completions
    ↓
Chat do aplicativo
```

## Teste completo dentro do app

O projeto inclui `IntegrationSelfTest`, que valida o mesmo caminho usado em produção:

1. servidor localhost;
2. `GET /v1/health`;
3. rejeição sem Bearer token;
4. `GET /v1/models` e modelo ativo `VERIFIED`;
5. inferência real via `POST /v1/chat/completions`;
6. streaming SSE até `[DONE]`.

O teste só pode ser aprovado depois que um GGUF real tiver sido importado e verificado no aparelho.

## GitHub Actions

O workflow `.github/workflows/android.yml`:

1. configura Java 17;
2. instala Android SDK 36, NDK `29.0.13113456` e CMake `3.31.6`;
3. baixa `llama.cpp v0.4.0`;
4. compila o AAR Android oficial;
5. executa testes unitários;
6. compila `app-debug.apk`;
7. publica o APK como artifact `IA-Local-debug`.

O workflow usa SDK 36 porque tanto o app quanto o binding Android pinado compilam contra essa API.

## API local

### Health

```http
GET /v1/health
```

Não requer Bearer token para facilitar diagnóstico local.

### Modelos

```http
GET /v1/models
Authorization: Bearer <chave-local>
```

### Chat

```http
POST /v1/chat/completions
Authorization: Bearer <chave-local>
Content-Type: application/json
```

Exemplo:

```json
{
  "model": "local-modelo-id",
  "messages": [
    {"role": "user", "content": "Responda somente: FUNCIONOU"}
  ],
  "temperature": 0.3,
  "max_tokens": 64,
  "stream": true
}
```

## Critério de pronto para o núcleo de IA

O núcleo só deve ser considerado validado depois desta sequência em um Android físico:

```text
instalar APK
→ importar GGUF real
→ pré-validação aprovada
→ smoke test nativo aprovado
→ status VERIFIED
→ /v1/health 200
→ /v1/models mostra modelo ativo
→ /v1/chat/completions gera texto
→ SSE entrega tokens e [DONE]
→ fechar e abrir o app
→ modelo continua registrado
→ carregar e conversar novamente
```

Até essa prova física, uma compilação verde no GitHub Actions significa que o projeto **compila e passa nos testes automatizados**, não que todo GGUF funcionará em qualquer telefone.
