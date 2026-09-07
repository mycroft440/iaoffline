# IA Offline — Android

Aplicativo Android local-first para importar modelos GGUF, validá-los com inferência real, expor uma API local e usar essa mesma API no chat do aplicativo.

- Android 13+ (`minSdk 33`).
- `arm64-v8a` e `x86_64`.
- GGUF + llama.cpp v0.4.0.
- API localhost autenticada, chat completions e SSE.
- Apenas modelos `VERIFIED` são ativados.
- Servidor local com limite de conexões.
- Teste de integração end-to-end dentro do app.

O binding atual usa `temperature=0.3` fixa. PDF digitalizado ainda exige OCR e áudio depende de reconhecimento on-device do Android.

Critério final: Action verde + teste físico com GGUF real no telefone.
