# IA Offline — Android

App Android local-first para importar GGUF, validar por inferência real e conversar via API localhost.

- Android 10+ (API 29); arm64-v8a/x86_64.
- llama.cpp v0.4.0 com patch reproduzível para contexto nativo dinâmico.
- Contexto calibrado por aparelho + modelo: o app testa alocações em um processo separado e persiste o maior valor comprovadamente estável antes de tentar o próximo.
- Meta de 32k tokens; modelos que declaram mais contexto podem ser testados acima disso.
- Em Android 11+, mortes do processo de calibração usam `ApplicationExitInfo` quando disponível para registrar LOW_MEMORY/crash/sinal; no Android 10 a morte é detectada pelo Binder, sem atribuir uma causa que o sistema não expõe.
- API autenticada com health/models/chat/SSE.
- Somente modelos VERIFIED são ativados.
- Servidor limitado a 8 conexões.
- Teste end-to-end dentro do app.

Limitações atuais: temperature fixa em 0.3, PDF escaneado sem OCR e áudio dependente de reconhecimento on-device.

Pronto = CI verde + teste físico com GGUF real.
