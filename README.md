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
- Modelos concluídos do catálogo são mantidos na pasta `IAs Offline` na raiz do armazenamento compartilhado do telefone para sobreviver à desinstalação.
- Após reinstalar, os GGUFs persistentes podem ser restaurados sem novo download, com nova validação SHA-256 e inferência real.
- SHA-256 fixado no app para cada GGUF do catálogo antes de qualquer carregamento nativo.
- Importação manual de outros arquivos `.gguf` continua disponível.
- Validação do cabeçalho GGUF, ABI, espaço disponível e estimativa de RAM.
- Um modelo só vira `VERIFIED` e pode ser ativado após uma inferência real no dispositivo.
- API autenticada com health/models/chat/SSE, escutando somente em `127.0.0.1`.
- Servidor limitado a 8 conexões.
- Teste end-to-end dentro do app.
- Editor anatômico de código com parser formal de sintaxe e análise semântica complementar por IA offline.
- SQL usa JSqlParser 5.3; o restante da cobertura formal usa o catálogo Tree-sitter, inclusive gramáticas reconhecidas dinamicamente por nome, extensão ou caminho.

Depois que um modelo foi baixado e instalado, a inferência não depende da internet. Internet é necessária apenas para baixar um modelo do catálogo; modelos importados manualmente podem ser usados sem rede desde o início.

Os modelos baixados pelo catálogo ficam também em `IAs Offline` fora de Downloads. Se o aplicativo for desinstalado, o Android apaga o banco e a cópia privada usada pelo runtime, mas o GGUF dessa pasta pública permanece. Após reinstalar, conceda acesso aos arquivos do aparelho e toque em **Buscar** em **Minhas I.As**. A busca inclui essa pasta e os diretórios antigos em Downloads, importa GGUFs válidos e não baixa os arquivos novamente. Os perfis **Programador** e **Sem censura** aparecem no chat mesmo antes de haver um modelo; a seleção é gerenciada em **Configurações**, acessível pelo menu das três barras no chat.

## Editor de código e diagnósticos

O editor separa sintaxe de análise por IA. Uma linguagem só recebe o estado **Sintaxe válida** depois que o parser formal correspondente conclui sem erros. Se a gramática não puder ser carregada, o estado é **Parser formal indisponível** — o app não presume que o código está correto.

SQL passa pelo JSqlParser. Os perfis comuns mantêm nomes e aliases próprios, mas o registro não fica limitado à lista manual: qualquer gramática reconhecida pelo catálogo da versão instalada do `tree-sitter-language-pack` pode gerar dinamicamente um perfil Tree-sitter. Isso permite reconhecer também linguagens e formatos fora da lista original, inclusive por extensão ou caminho de arquivo.

Para a distribuição Android, `scripts/prepare_tree_sitter_android.sh` recompila o AAR do Tree-sitter com `TSLP_LANGUAGES=all` por padrão, usando as fontes de parsers publicadas e verificadas pela mesma release. O objetivo é embutir no aplicativo o maior conjunto de gramáticas que a versão consegue compilar para Android, em vez de depender apenas do subconjunto reduzido do AAR Maven. Se um build precisar deliberadamente de um conjunto menor, `TREE_SITTER_LANGUAGES` pode receber uma lista separada por vírgulas.

A IA local só é chamada depois que a sintaxe é aceita e não pode retornar diagnósticos da categoria `SYNTAX`; ela complementa com erros de tipo, referência, lógica, segurança e compatibilidade. Ao editar o texto ou trocar a linguagem, a aprovação sintática anterior é invalidada imediatamente.

Para SQL, o parser valida gramática, não existência de tabelas, colunas ou objetos de um banco específico; validação semântica de schema exige conexão ou importação do schema correspondente.

## Instalação

A distribuição para aparelhos deve ser feita pelo APK assinado publicado em **GitHub Releases**, não pelo arquivo ZIP de artefatos do GitHub Actions e não pelo AAB.

Quando a assinatura de release estiver configurada, o APK mais recente ficará disponível diretamente em:

`https://github.com/mycroft440/iaoffline/releases/latest/download/IA-Local.apk`

Requisitos do APK atual:

- Android 13 ou superior (`minSdk 33`).
- CPU `arm64-v8a` ou `x86_64` para o runtime completo do llama.cpp.

Se uma versão debug antiga do aplicativo já estiver instalada, desinstale-a uma única vez antes de instalar a primeira Release assinada. Depois disso, as Releases futuras usam a mesma chave e podem atualizar a instalação normalmente.

## Segurança e armazenamento

Arquivos incompletos continuam no armazenamento privado do aplicativo com extensão `.part`, podem ser retomados e nunca são registrados como modelo. Ao concluir um download do catálogo, o app confere o SHA-256 esperado e salva uma cópia persistente em `IAs Offline` antes de registrar a cópia privada usada pelo runtime. O Android solicita acesso aos arquivos compartilhados antes de iniciar o download, caso ainda não tenha sido concedido.

A desinstalação remove a biblioteca privada, o banco local e os arquivos parciais, mas não remove o GGUF concluído em `IAs Offline`. Depois de reinstalar, **Minhas I.As → Buscar** lê a pasta e também encontra arquivos anteriores em Downloads. Arquivos com nomes do catálogo passam novamente pelo SHA-256 fixado no app; todos os GGUFs passam por leitura estrutural e a ativação requer uma inferência real no aparelho.

Quando o usuário exclui explicitamente uma IA do catálogo dentro do aplicativo, o app remove tanto a cópia privada quanto a cópia persistente acessível em `IAs Offline` e os arquivos antigos em Downloads aos quais tenha acesso. Assim, a persistência protege contra desinstalação do aplicativo, não contra uma ordem explícita de excluir o modelo.

A biblioteca privada e os downloads parciais são excluídos do backup e da transferência de dados do Android para evitar cópias de vários gigabytes. Enquanto uma IA do catálogo estiver instalada, existe uma cópia persistente em `IAs Offline` e uma cópia privada para o runtime, portanto é necessário espaço para ambas.

## Build

Os AARs nativos são preparados antes do build do aplicativo. Para reproduzir o mesmo caminho do CI com cobertura sintática máxima:

```bash
export ANDROID_NDK_HOME=/caminho/para/o/ndk
rustup target add aarch64-linux-android x86_64-linux-android
cargo install cargo-ndk --locked
./scripts/prepare_tree_sitter_android.sh
./scripts/prepare_llama_android.sh
./gradlew :app:testDebugUnitTest :app:assembleDebug --no-daemon
```

O script Tree-sitter usa `all` por padrão. Um conjunto menor pode ser solicitado, por exemplo:

```bash
TREE_SITTER_LANGUAGES=python,html,bash,powershell,php,c,cpp,csharp \
  ./scripts/prepare_tree_sitter_android.sh
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
- Parser sintático não substitui compilador, type checker ou schema real. Um arquivo pode ter sintaxe válida e ainda conter erro de tipo, símbolo inexistente, erro de link, dependência ausente ou erro semântico.
- O modo `TSLP_LANGUAGES=all` maximiza a cobertura, mas aumenta tempo de CI e tamanho do AAR/APK. O build é estrito: se uma gramática anunciada pela release não compilar para Android, a CI deve falhar em vez de publicar silenciosamente um APK que prometa suporte offline inexistente.

Pronto para uso = CI verde + teste físico com pelo menos um GGUF real no aparelho-alvo.
