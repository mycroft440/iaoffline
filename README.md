# IA Offline — Android

Aplicativo Android local-first para importar modelos GGUF, validá-los com inferência real, expor uma API local compatível com o formato de chat da OpenAI e usar essa mesma API no chat do aplicativo.

## Escopo atual

- Android 13+ (`minSdk 33`).
- `arm64-v8a` e `x86_64`.
- Modelos `.gguf` compatíveis com `llama.cpp`.
- Pré-validação GGUF antes da cópia.
- Cópia para armazenamento privado.
- Smoke test nativo antes de marcar `VERIFIED`.
- Apenas modelos `VERIFIED` podem ser ativados.
- API em `127.0.0.1:11435` com Bearer token.
- Servidor com `accept()` bloqueante e limite de 8 clientes simultâneos.
- `/v1/health`, `/v1/models`, `/v1/chat/completions` e SSE.
- Perfis de agente e ferramentas locais somente de leitura.
- Histórico, fixar, renomear e ordenar conversas por atividade.
- Tema claro, escuro e sistema.
- Texto, PDF com camada de texto e áudio via reconhecimento on-device quando disponível.

## Limitações declaradas

A primeira versão suporta GGUF e depende da compatibilidade do modelo com o runtime `llama.cpp`, além de memória e armazenamento suficientes no aparelho.

O binding Android `llama.cpp v0.4.0` usa temperatura fixa em `0.3`. A API e o runtime rejeitam `temperature` diferente de `0.3` para não fingir suporte.

PDFs digitalizados ainda exigem OCR. Áudio depende de `SpeechRecognizer.isOnDeviceRecognitionAvailable()`; Whisper local ainda não é fallback.

## Fluxo

```text
GGUF → inspeção → compatibilidade → cópia privada → llama.cpp → smoke test → VERIFIED → API local → chat
```

## Teste completo dentro do app

`IntegrationSelfTest` valida:

1. servidor localhost;
2. `/v1/health`;
3. autenticação Bearer;
4. `/v1/models` + modelo ativo `VERIFIED`;
5. inferência real em `/v1/chat/completions`;
6. streaming SSE até `[DONE]`.

## GitHub Actions

O workflow `.github/workflows/android.yml` configura Java 17, SDK 36, NDK `29.0.13113456`, CMake `3.31.6`, compila o AAR Android do `llama.cpp v0.4.0`, executa testes, gera `app-debug.apk` e publica o artifact `IA-Local-debug`.

## Critério de pronto

```text
instalar APK
→ importar GGUF real
→ smoke test nativo
→ VERIFIED
→ /v1/health
→ /v1/models
→ /v1/chat/completions
→ SSE
→ reiniciar app
→ carregar modelo novamente
→ conversar novamente
```

Uma Action verde prova compilação e testes automatizados. A prova final da IA continua sendo o teste físico com um GGUF real no telefone.
