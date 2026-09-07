# IA Offline — Android

Aplicativo Android local-first para importar modelos GGUF, validar por inferência real, expor uma API local e usar a mesma API no chat.

- Android 13+.
- arm64-v8a/x86_64.
- GGUF + llama.cpp v0.4.0.
- `/v1/health`, `/v1/models`, `/v1/chat/completions` e SSE.
- Apenas modelos `VERIFIED` são ativados.
- Servidor localhost autenticado e limitado a 8 clientes simultâneos.
- Teste end-to-end dentro do app.

Limitações atuais: temperature fixa em 0.3 no binding, PDF escaneado sem OCR e áudio dependente do reconhecedor on-device do Android.

Critério final: GitHub Actions verde e teste físico com GGUF real.
