# IA Offline — Android

App Android local-first para importar GGUF, validar por inferência real e conversar via API localhost.

- Android 13+; arm64-v8a/x86_64.
- llama.cpp v0.4.0.
- API autenticada com health/models/chat/SSE.
- Somente modelos VERIFIED são ativados.
- Servidor limitado a 8 conexões.
- Teste end-to-end dentro do app.

Limitações atuais: temperature fixa em 0.3, PDF escaneado sem OCR e áudio dependente de reconhecimento on-device.

Pronto = CI verde + teste físico com GGUF real.
