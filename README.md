# IA Offline — Android

Aplicativo Android local-first para importar modelos GGUF, validá-los com inferência real, expor uma API local e usar essa mesma API no chat do aplicativo.

## Escopo atual

- Android 13+ (`minSdk 33`).
- `arm64-v8a` e `x86_64`.
- Modelos `.gguf` compatíveis com `llama.cpp`.
- Pré-validação GGUF, cópia privada e smoke test nativo.
- Somente modelos `VERIFIED` podem ser ativados.
- API em `127.0.0.1:11435` com Bearer token.
- `/v1/health`, `/v1/models`, `/v1/chat/completions` e streaming SSE.
- Servidor com `accept()` bloqueante e limite de 8 clientes simultâneos.
- Agentes com ferramentas locais somente de leitura.
- Histórico, fixar, renomear, ordenação por atividade e temas claro/escuro/sistema.
- Texto, PDF com camada de texto e áudio on-device quando disponível.

## Limitações declaradas

A primeira versão suporta GGUF e depende da compatibilidade do modelo com `llama.cpp`, RAM e armazenamento disponíveis.

O binding Android `llama.cpp v0.4.0` usa temperatura fixa em `0.3`; a API rejeita valores diferentes para não fingir suporte.

PDFs digitalizados ainda exigem OCR. Áudio depende de reconhecimento on-device do Android; Whisper local ainda não é fallback.

## Fluxo

```text
GGUF → inspeção → compatibilidade → cópia → llama.cpp → smoke test → VERIFIED → API local → chat
```

## Teste completo

`IntegrationSelfTest` valida localhost, health, Bearer token, modelo ativo `VERIFIED`, inferência HTTP e SSE até `[DONE]`.

## GitHub Actions

O workflow configura Java 17, SDK 36, NDK `29.0.13113456`, CMake `3.31.6`, compila o AAR do `llama.cpp v0.4.0`, executa testes, gera `app-debug.apk` e publica `IA-Local-debug`.

## Critério de pronto

```text
instalar APK
→ importar GGUF real
→ smoke test
→ VERIFIED
→ health/models/chat/SSE
→ reiniciar app
→ carregar modelo novamente
→ conversar novamente
```

Uma Action verde prova compilação e testes automatizados. A prova final da IA exige teste físico com um GGUF real.
