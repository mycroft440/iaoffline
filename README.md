# IA Offline — Android

App Android local-first para baixar ou importar modelos GGUF, validar por inferência real e conversar totalmente no aparelho, com API localhost compatível com o fluxo do próprio app.

## O que já funciona

- Android 13+; arm64-v8a/x86_64.
- Runtime local baseado no binding Android do llama.cpp.
- Catálogo integrado para baixar modelos Qwen3 em GGUF diretamente no app:
  - Qwen3 0.6B Q4_K_M
  - Qwen3 1.7B Q4_K_M
  - Qwen3 4B Q4_K_M
  - Qwen3 8B Q4_K_M
- Download em HTTPS com retomada de arquivo parcial quando o servidor permite `Range`.
- SHA-256 fixado no app para cada GGUF do catálogo antes de qualquer carregamento nativo.
- Importação manual de outros arquivos `.gguf` continua disponível.
- Validação do cabeçalho GGUF, ABI, espaço disponível e estimativa de RAM.
- Um modelo só vira `VERIFIED` e pode ser ativado após uma inferência real no dispositivo.
- API autenticada com health/models/chat/SSE, escutando somente em `127.0.0.1`.
- Servidor limitado a 8 conexões.
- Teste end-to-end dentro do app.

Depois que um modelo foi baixado e instalado, a inferência não depende da internet. Internet é necessária apenas para baixar um modelo do catálogo; modelos importados manualmente podem ser usados sem rede desde o início.

## Segurança e armazenamento

Os downloads são gravados no armazenamento privado do aplicativo. Arquivos incompletos usam a extensão `.part`, podem ser retomados e não são registrados como modelo. Ao concluir, o app confere o SHA-256 esperado, relê o GGUF e só então executa o teste de inferência.

As pastas de modelos e downloads são excluídas do backup e da transferência de dados do Android para evitar cópias de vários gigabytes.

## Build

O AAR do binding Android do llama.cpp é gerado antes do build do aplicativo:

```bash
./scripts/prepare_llama_android.sh
gradle :app:testDebugUnitTest :app:assembleDebug --no-daemon
```

O workflow de CI executa essas etapas automaticamente.

## Limitações atuais

- Temperature fixa em 0.3 pelo binding Android utilizado atualmente.
- Contexto efetivo limitado a 8192 tokens no runtime Android atual.
- Modelos grandes podem não caber na RAM de todos os aparelhos; o app mostra uma recomendação e ainda exige um teste real antes de ativar.
- PDF escaneado sem OCR.
- Áudio dependente de reconhecimento on-device.

Pronto para uso = CI verde + teste físico com pelo menos um GGUF real no aparelho-alvo.
