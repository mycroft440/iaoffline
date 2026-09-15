# IA Offline — Android

App Android local-first para baixar ou importar modelos GGUF, validar por inferência real e conversar totalmente no aparelho, com API localhost compatível com o fluxo do próprio app.

## O que já funciona

- Android 13+; arm64-v8a/x86_64.
- Runtime local baseado no binding Android do llama.cpp.
- Catálogo integrado para baixar modelos em GGUF diretamente no app:
  - Qwen3 0.6B Q4_K_M
  - Qwen3 1.7B Q4_K_M
  - Qwen3 4B Q4_K_M
  - Qwen3 8B Q4_K_M
  - Qwen3.8 27B Q4_K_M (opção avançada, ~19 GB; texto no app atual)
- Download em HTTPS com retomada de arquivo parcial quando o servidor permite `Range`.
- SHA-256 fixado no app para cada GGUF do catálogo antes de qualquer carregamento nativo.
- Importação manual de outros arquivos `.gguf` continua disponível.
- Validação do cabeçalho GGUF, ABI, espaço disponível e estimativa de RAM.
- Um modelo só vira `VERIFIED` e pode ser ativado após uma inferência real no dispositivo.
- API autenticada com health/models/chat/SSE, escutando somente em `127.0.0.1`.
- Servidor limitado a 8 conexões.
- Teste end-to-end dentro do app.

Depois que um modelo foi baixado e instalado, a inferência não depende da internet. Internet é necessária apenas para baixar um modelo do catálogo; modelos importados manualmente podem ser usados sem rede desde o início.

## Instalação

A distribuição para aparelhos deve ser feita pelo APK assinado publicado em **GitHub Releases**, não pelo arquivo ZIP de artefatos do GitHub Actions e não pelo AAB.

Quando a assinatura de release estiver configurada, o APK mais recente ficará disponível diretamente em:

`https://github.com/mycroft440/iaoffline/releases/latest/download/IA-Local.apk`

Requisitos do APK atual:

- Android 13 ou superior (`minSdk 33`).
- CPU `arm64-v8a` ou `x86_64` para o runtime completo do llama.cpp.

Se uma versão debug antiga do aplicativo já estiver instalada, desinstale-a uma única vez antes de instalar a primeira Release assinada. Depois disso, as Releases futuras usam a mesma chave e podem atualizar a instalação normalmente.

## Segurança e armazenamento

Os downloads são gravados no armazenamento privado do aplicativo. Arquivos incompletos usam a extensão `.part`, podem ser retomados e não são registrados como modelo. Ao concluir, o app confere o SHA-256 esperado, relê o GGUF e só então executa o teste de inferência.

As pastas de modelos e downloads são excluídas do backup e da transferência de dados do Android para evitar cópias de vários gigabytes.

## Build

O AAR do binding Android do llama.cpp é gerado antes do build do aplicativo:

```bash
./scripts/prepare_llama_android.sh
gradle :app:testDebugUnitTest :app:assembleDebug --no-daemon
```

O workflow de CI executa essas etapas automaticamente. O `versionCode` e o `versionName` dos builds de CI são derivados do número da execução do GitHub Actions, evitando que versões novas continuem usando `versionCode = 1`.

### Assinatura persistente das Releases

O APK público é construído como `release` e só é publicado quando estes quatro GitHub Actions Secrets estão configurados no repositório:

- `ANDROID_KEYSTORE_BASE64`: conteúdo Base64 do arquivo de keystore.
- `ANDROID_KEY_ALIAS`: alias da chave.
- `ANDROID_KEYSTORE_PASSWORD`: senha do keystore.
- `ANDROID_KEY_PASSWORD`: senha da chave.

Uma chave pode ser criada localmente com o `keytool` do JDK, por exemplo:

```bash
keytool -genkeypair -v \
  -keystore ia-local-release.jks \
  -alias ia-local \
  -keyalg RSA \
  -keysize 4096 \
  -validity 10000
```

Depois, converta o arquivo para Base64 e salve o resultado em `ANDROID_KEYSTORE_BASE64`. Em Linux/GNU:

```bash
base64 -w 0 ia-local-release.jks
```

A chave privada não deve ser adicionada ao Git. O `.gitignore` bloqueia extensões comuns de keystore.

Se os Secrets ainda não estiverem configurados, o CI continua executando testes e o build debug, mas **não publica** uma Release com assinatura efêmera. Isso evita voltar ao problema de cada execução produzir um APK incompatível com a atualização anterior.

## Limitações atuais

- Temperature fixa em 0.3 pelo binding Android utilizado atualmente.
- Contexto efetivo limitado a 8192 tokens no runtime Android atual.
- Modelos grandes podem não caber na RAM de todos os aparelhos; o app mostra uma recomendação e ainda exige um teste real antes de ativar.
- O Qwen3.8 é multimodal na origem, mas esta integração usa apenas texto; suporte a imagem exigiria integrar o projetor multimodal correspondente.
- PDF escaneado sem OCR.
- Áudio dependente de reconhecimento on-device.

Pronto para uso = CI verde + teste físico com pelo menos um GGUF real no aparelho-alvo.
