# IA Offline — Android

App Android local-first para importar GGUF, validar o modelo com inferência real e conversar através de uma API local.

- Android 13+; arm64-v8a/x86_64.
- llama.cpp v0.4.0.
- API localhost autenticada com health/models/chat/SSE.
- Somente modelos VERIFIED são ativados.
- Servidor limitado a 8 conexões simultâneas.
- Teste de integração end-to-end no app.

Limitações: temperature 0.3 fixa no binding; PDF escaneado sem OCR; áudio depende de reconhecimento on-device.

Pronto = CI verde + teste físico com GGUF real.
