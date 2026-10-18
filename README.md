# IA Offline — Android

App Android local-first para baixar ou importar modelos GGUF, validar por inferência real e conversar totalmente no aparelho, com API localhost compatível com o fluxo do próprio app.

## O que já funciona

- Android 13+; arm64-v8a/x86_64.
- Runtime local baseado no binding Android do llama.cpp.
- Catálogo integrado para baixar modelos em GGUF diretamente no app, organizado por empresa (PrismML/Bonsai, DeepSeek, Qwen, Mistral, Meta, Google, Microsoft, IBM, Liquid AI, Ai2, NVIDIA, OpenAI, OpenBMB e JetBrains).
- **Bonsai (PrismML)**: modelos com todos os pesos em 1 bit (Q1_0) ou ternários (Q2_0), que levam a capacidade de modelos grandes para pouca memória:
  - Bonsai 1.7B, 4B e 8B (1 bit, baseados no Qwen3): 250 MB, 570 MB e 1,2 GB.
  - Bonsai 8B Ternário: 2,3 GB, mais preciso que o 1 bit.
  - Bonsai 27B (1 bit, baseado no Qwen3.6 27B, com raciocínio): um modelo da classe 27B em 3,8 GB.
  - Bonsai 2 Ternário 2B, 4B e 9B (baseados no Qwen3.5, com raciocínio): 550 MB, 1,2 GB e 2,6 GB.
  - Bonsai 2 Ternário 27B (baseado no Qwen3.8 27B): 7,6 GB, para celulares de 12 GB; denso e lento, mas muito forte.
- **Gemma 4 QAT** (E2B, E4B e 12B): versões oficiais do Google treinadas para 4 bits, com qualidade próxima da versão completa, no formato Q4_0, o mais rápido nos processadores ARM.
- Outros modelos novos que rodam no celular: Ornith 1.5 9B (sobre o Qwen3.5 9B, perto do Qwen3.6 35B-A3B nos testes do autor), Granite 4.2 3B e 8B (IBM, com raciocínio), MiniCPM5 2B (OpenBMB), Mellum 2.1 12B-A2.5B (JetBrains, especialista em código; MoE com 2,5B ativos) e LFM2.5 24B-A2B (Liquid AI, experimental; MoE com 2,45B ativos, quantizado com destilação).
- Download em HTTPS com retomada de arquivo parcial quando o servidor permite `Range`.
- Fila de downloads: é possível escolher várias IAs enquanto outra baixa; elas baixam uma de cada vez e a próxima começa assim que a anterior é instalada (ou falha). **Minhas I.As** mostra cada download com o progresso e as ações Pausar, Continuar, Tentar novamente e Remover; a fila sobrevive ao fechamento do app e retoma dos arquivos parciais.
- Modelos concluídos do catálogo são mantidos na pasta `IAs Offline` na raiz do armazenamento compartilhado do telefone para sobreviver à desinstalação.
- Após reinstalar, os GGUFs persistentes podem ser restaurados sem novo download, com nova validação SHA-256 e inferência real.
- SHA-256 fixado no app para cada GGUF do catálogo antes de qualquer carregamento nativo.
- Importação manual de outros arquivos `.gguf` continua disponível.
- Validação do cabeçalho GGUF, ABI, espaço disponível e estimativa de RAM.
- Modelos grandes para a RAM do aparelho (arquivo acima de 30% da memória total, como 8B em celulares de 12 GB) carregam em modo de pouca memória: pesos mapeados do arquivo, sem a cópia reempacotada em RAM, e contexto de 4096 tokens. É um pouco mais lento, mas evita que o Android feche o app.
- Se o app for fechado inesperadamente, na abertura seguinte ele mostra o motivo registrado pelo Android (falha nativa, falta de memória, erro) com detalhes para copiar. Fechamentos normais do Android em segundo plano, com o app parado, não são mostrados como falha.
- **A IA roda num processo separado** (`:ai`, com o `InferenceService`): o modelo, o contexto e o motor nativo ficam fora do processo do app. Quando falta memória, o Android encerra esse processo (o maior) em vez do app: a resposta em andamento termina com uma mensagem explicando o que houve, o modelo passa a usar menos memória e as telas e a conversa continuam abertas. O processo da IA tem a mesma prioridade e o mesmo agendamento de CPU do app.
- **Limite de memória da IA**: a IA pode usar a RAM livre (ou o que o Android libera fechando apps em segundo plano) menos 0,5 GB para o sistema e o app; com 8 GB livres, 7,5 GB. Pesos, contexto e memória de trabalho são planejados dentro desse limite: sem espaço para a cópia reempacotada, os pesos ficam no arquivo; o contexto encolhe para caber. Um modelo denso que nem assim cabe não é carregado, com uma mensagem dizendo quanto precisa e quanto há, em vez de travar o celular lendo os pesos do armazenamento a cada palavra.
- O texto é processado em blocos de 256 tokens em vez de 512, o que corta pela metade a memória de trabalho do llama.cpp (de 568 para 285 MB no Gemma 4 E4B) sem perder velocidade nas medições.
- Enquanto a IA responde, a mensagem é salva e redesenhada até 5 vezes por segundo, não a cada token: com respostas longas, salvar o texto inteiro a cada token deixava a tela sem responder.
- **Modo seguro automático**: o app marca quando está carregando um modelo ou respondendo com ele. Se o Android o fechar por falta de memória (ou o motor nativo falhar) nesse momento, os próximos carregamentos daquele modelo usam menos memória: pesos lidos do arquivo e metade do contexto na primeira vez, o menor contexto (2K) se acontecer de novo. Assim o mesmo modelo não derruba o app repetidamente. O nível volta ao normal se o modelo for apagado e baixado de novo.
- Embeddings por camada (a tabela que ocupa quase metade do Gemma 4 E2B/E4B) ficam no arquivo e só as linhas das palavras em uso são lidas, alguns KB por palavra. Medido no llama.cpp com a mesma resposta e a mesma velocidade: o Gemma 4 E2B QAT caiu de 4,3 GB para 2,5 GB de RAM e o E4B QAT de 7,2 GB para 5,0 GB (de 5,1 GB para 2,9 GB no modo de pouca memória). O app desconta essa parte do arquivo ao decidir o modo de memória e o tamanho do contexto.
- Em segundo plano, quando o aparelho está com pouca memória livre, o app libera o modelo ocioso em vez de esperar o Android fechar o app; a próxima mensagem carrega o modelo de novo. Um modelo carregando ou respondendo não é liberado.
- RAM mostrada pelo app: usada, livre (MemAvailable do kernel) e quanto o próprio app ocupa, atualizadas a cada 2 segundos. Até o Android 15, a "memória disponível" que o sistema entrega aos apps soma o cache de arquivos, inclusive o de um modelo mapeado, e parecia sobrar mais RAM do que havia. As exigências do catálogo são comparadas com a RAM anunciada do celular (um aparelho de 12 GB informa cerca de 11 GB ao sistema).
- Cache de contexto (KV) em Q4_0, cerca de um quarto da memória do F16, quando o modelo suporta flash attention; senão o app usa F16 com um contexto menor. O llama.cpp aplica uma rotação de Hadamard ao cache quantizado, o que deixa a perda de qualidade do Q4_0 quase nula.
- **Conversas contínuas**: o modelo fica carregado entre as mensagens e, quando a próxima mensagem continua a mesma conversa, o app aproveita o contexto que já está na memória e processa só a mensagem nova. Antes, cada mensagem recarregava o modelo e relia todo o histórico. Ao abrir outra conversa, editar ou regenerar uma mensagem, o contexto é remontado.
- O histórico vai para o modelo como turnos de chat de verdade, formatados pelo template do próprio modelo (do jeito que ele foi treinado), e não mais colado como texto no prompt de sistema. As respostas anteriores entram sem o raciocínio, que só ocuparia contexto.
- O contexto é o maior que cabe na memória do aparelho (até o limite de treino do modelo): 40% da RAM menos o tamanho do modelo, entre 256 MB e 2 GB para o cache. Num celular de 12 GB, um modelo 4B usa o contexto completo de 32K e um 8B fica em torno de 13K.
- Modelos MoE grandes (15B ou mais no total, poucos bilhões ativos por palavra), como Gemma 4 26B-A4B, Qwen3-Coder 30B-A3B, Qwen3.6 35B-A3B, Nemotron 3 Nano 30B-A3B e gpt-oss 20B, aparecem como **Experimental – lento**: rodam sempre em modo de pouca memória, lendo os pesos do armazenamento, com 384 MB para o contexto.
- O gpt-oss 20B da OpenAI responde no formato "harmony"; o app converte o canal `analysis` em raciocínio expansível e o canal `final` em resposta.
- Modelos cujo formato de conversa não está entre os embutidos no llama.cpp (como o Gemma 4) usam o template Jinja do próprio GGUF, em vez de derrubar o app. O raciocínio do Gemma 4 (`<|channel>thought … <channel|>`) aparece como raciocínio expansível.
- Um modelo só vira `VERIFIED` e pode ser ativado após uma inferência real no dispositivo.
- API autenticada com health/models/chat/SSE, escutando somente em `127.0.0.1`.
- Servidor limitado a 8 conexões.
- Teste end-to-end dentro do app.
- Editor anatômico de código com parser formal de sintaxe e análise semântica complementar por IA offline.
- SQL usa JSqlParser 5.3; o restante da cobertura formal usa o catálogo Tree-sitter, inclusive gramáticas reconhecidas dinamicamente por nome, extensão ou caminho.

- Amostragem recomendada pelo próprio modelo: o runtime lê temperatura, top-k, top-p, min-p e penalidade de repetição gravados no GGUF (`general.sampling.*`) em vez de usar sempre 0,3, e aplica a penalidade de presença que o cartão do modelo pede (Bonsai 2). Modelos que raciocinam e modelos de poucos bits entram menos em repetição assim, sem custo de memória.

Depois que um modelo foi baixado e instalado, a inferência não depende da internet. Internet é necessária apenas para baixar um modelo do catálogo; modelos importados manualmente podem ser usados sem rede desde o início.

Os anúncios usam a rede quando há conexão, mas não interferem na inferência local. Sem conexão ou sem anúncio disponível, o espaço do banner desaparece e o anúncio de download não aparece.

Os modelos baixados pelo catálogo ficam também em `IAs Offline` fora de Downloads. Se o aplicativo for desinstalado, o Android apaga o banco e a cópia privada usada pelo runtime, mas o GGUF dessa pasta pública permanece. Após reinstalar, conceda acesso aos arquivos do aparelho e toque em **Buscar** em **Minhas I.As**. A busca inclui essa pasta e os diretórios antigos em Downloads, importa GGUFs válidos e não baixa os arquivos novamente. Os perfis **Programador** e **Sem censura** aparecem no seletor de perfil do chat mesmo antes de haver um modelo e também são gerenciados em **Configurações**, acessível pelo menu das três barras no chat.

As respostas da I.A continuam sendo geradas quando o app vai para segundo plano, quando a tela é desligada ou quando o usuário sai da conversa. No máximo duas conversas são respondidas ao mesmo tempo; uma mensagem enviada de uma terceira conversa aparece como **Na fila** e é respondida na ordem de chegada assim que uma das outras termina. Enquanto houver uma resposta ou mensagens na fila, um serviço em primeiro plano mostra a notificação **Gerando resposta…** (com a ação **Parar**) e mantém a CPU ativa; ele é encerrado automaticamente quando a geração termina.

Modelos que raciocinam mostram o raciocínio enquanto ele é gerado, num bloco **Raciocínio** que pode ser expandido ou recolhido; ao terminar, o bloco se recolhe e exibe quanto tempo o modelo raciocinou, e cada resposta mostra o tempo total (**Respondeu em …**). O seletor **DeepThink** aparece nos modelos em que o raciocínio pode ser ligado e desligado. Desligado, a resposta sai bem mais rápido, o que pesa muito no celular:
  - Qwen3 e Nemotron Nano v2: o app envia `/no_think`; começam com o DeepThink desligado.
  - Qwen3.5, 3.6 e 3.8, Bonsai 2 e Bonsai 27B, Ornith, MiniCPM5 e Granite 4.2: a resposta é pré-preenchida com um bloco de raciocínio vazio, como os templates desses modelos fazem; começam com o DeepThink ligado, como o próprio modelo.
  - Gemma 4: o app desliga o `enable_thinking` do template; começa ligado.

  Modelos que sempre raciocinam (DeepSeek R1, gpt-oss, Mellum, variantes *Reasoning* e *Think*) não têm o seletor. Todos os perfis pedem respostas de até 8192 tokens, um contexto nativo inteiro; o modelo para antes quando termina, e se passar da janela de contexto o llama.cpp desloca o contexto (mantendo o prompt de sistema) em vez de cortar a resposta. No prompt, no máximo metade do contexto é reservada para a resposta, para o histórico continuar cabendo.

## Respostas melhores sem modelo maior

Além da amostragem recomendada pelo modelo, cada conversa recebe técnicas que custam poucos tokens de prompt e nenhuma memória extra:

- **Data de hoje** no prompt de sistema (a hora exata vem da ferramenta `current_time`): os modelos só conhecem a data do treino. O prompt de sistema só muda uma vez por dia, para não impedir a continuação da conversa.
- **Regras curtas contra erros típicos de modelos pequenos**: responder no idioma do usuário, admitir quando não sabe em vez de inventar nomes, datas e números, e resolver problemas de várias etapas passo a passo.
- **Calculadora** (`calculate`): contas exatas com precedência, potências, funções e constantes, em vez de aritmética "de cabeça".
- **Wikipedia Offline**: quando instalada, os agentes podem procurar (`wikipedia_search`) e ler (`wikipedia_read`) artigos, e, com a consulta automática ligada, perguntas que citam uma pessoa, lugar ou assunto recebem o começo do artigo correspondente (no máximo dois artigos, ~650 caracteres cada).

## Ferramentas offline

Em **Configurações → Ferramentas offline** há duas entradas:

- **Baixar compiladores**: os pacotes de linguagem do editor de código (veja abaixo).
- **Wikipedia Offline**: edições da Wikipédia publicadas pelo [Kiwix](https://kiwix.org) no formato ZIM, para os agentes consultarem sem internet. Há resumos dos 50 mil artigos essenciais em português (~150 MB), resumos de todos os artigos em português (~1,7 GB), os artigos essenciais completos (~1 GB), a Wikipédia em português completa sem imagens (~7 GB) e resumos em inglês. Quem escolhe o que baixar é o usuário: nada é baixado sem tocar em **Baixar** numa edição e confirmar o diálogo (que mostra o tamanho, o espaço livre e a opção de baixar apenas pelo Wi-Fi), o app não atualiza as edições sozinho e os agentes só leem as edições já instaladas. Ao confirmar, o app baixa a edição mais recente, usando o gerenciador de downloads do Android (continua em segundo plano e retoma após quedas de rede), confere o arquivo com o SHA-256 publicado pelo Kiwix e o guarda na pasta do app em `Android/data`.

O leitor de ZIM é do próprio app: ele procura artigos por título com buscas binárias nos índices do arquivo e descomprime (zstd) só o trecho necessário, então consultar a Wikipédia completa usa quase nada de RAM. Não há busca de texto completo: os artigos são encontrados pelo título, a partir do assunto da pergunta.

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

### Anúncios AdMob

**Anúncio de download (intersticial em tela cheia, vídeo ou imagem).** Ao tocar em **Instalar I.A** no catálogo, o download entra na fila e um anúncio em tela cheia aparece. O download continua em segundo plano enquanto o anúncio passa, então a pessoa não perde tempo esperando. É o formato que mais rende sem pedir nada ao usuário, e o início de um download é a pausa natural que as regras do AdMob pedem para intersticiais. Detalhes:

- O anúncio é carregado quando o catálogo abre, para aparecer na hora; um anúncio carregado vale por uma hora e é trocado antes disso.
- No máximo um a cada 3 minutos (`DownloadAdPacing.MIN_GAP_MS`): quem escolhe várias IAs seguidas vê um só anúncio. O limite sobrevive ao fechamento do app.
- Só novos downloads mostram o anúncio; **Continuar** e **Tentar novamente** não.
- Não aparece enquanto uma resposta está sendo gerada: o player de vídeo tiraria RAM do modelo e poderia fazer o Android encerrar o processo da IA no meio da resposta.
- Sem conexão, sem consentimento ou sem anúncio carregado, o download começa normalmente, sem anúncio.

Para ganhar o máximo com esse anúncio, crie no AdMob um bloco do tipo **Intersticial** com os dois tipos de anúncio ligados (**Display** e **Vídeo**): os dois competem no leilão e ganha o que paga mais. Mais redes disputando cada exibição aumentam o valor pago: a mediação com lances (bidding) do AdMob aceita redes como Meta Audience Network, AppLovin, Unity Ads e Liftoff, mas cada uma precisa do seu adaptador no app (uma dependência `com.google.ads.mediation:…` no `app/build.gradle.kts`), que ainda não foi adicionado.

**Banners.** Há um banner no fim da tela inicial, dois no catálogo **Baixar I.As offline** (antes e depois da lista) e dois em **Minhas I.As** (antes e depois da lista, mesmo sem modelos instalados). No chat, um banner aparece dentro da resposta da IA a cada 2, depois 4, depois 3 mensagens do usuário, repetindo o ciclo (mensagens 2, 6, 9, 11, 15, 18…); ele fica antes do primeiro bloco de código ou depois do primeiro parágrafo. Sem conexão, o banner não ocupa espaço e o app tenta carregá-lo de novo quando a rede volta. O build `debug` usa exclusivamente os IDs de teste do Google.

**IDs de teste (configuração atual).** Enquanto `ADMOB_APP_ID` não estiver configurado, a release também usa os IDs de teste do Google, então os banners e o anúncio de download já funcionam e mostram o selo "Test Ad". Anúncios de teste não geram receita e não pedem o formulário de consentimento. Os IDs usados:

| Uso | ID de teste do Google |
| --- | --- |
| Aplicativo | `ca-app-pub-3940256099942544~3347511713` |
| Banner adaptável | `ca-app-pub-3940256099942544/9214589741` |
| Intersticial (vídeo e imagem) | `ca-app-pub-3940256099942544/1033173712` |

Para trocar pelos anúncios reais (que geram receita) em um build `release`, configure os valores abaixo no ambiente de build. Os dois primeiros vão juntos e desligam os IDs de teste; sem o terceiro, a release mostra os banners reais mas não o anúncio de download:

```bash
export ADMOB_APP_ID='ca-app-pub-XXXXXXXXXXXXXXXX~YYYYYYYYYY'
export ADMOB_BANNER_AD_UNIT_ID='ca-app-pub-XXXXXXXXXXXXXXXX/YYYYYYYYYY'
export ADMOB_INTERSTITIAL_AD_UNIT_ID='ca-app-pub-XXXXXXXXXXXXXXXX/ZZZZZZZZZZ'
```

Use os IDs da sua conta AdMob. No GitHub Actions, cadastre os mesmos valores como secrets `ADMOB_APP_ID`, `ADMOB_BANNER_AD_UNIT_ID` e `ADMOB_INTERSTITIAL_AD_UNIT_ID`; o workflow os passa apenas ao build da release. Configure também a mensagem de privacidade em **Privacy & messaging** no AdMob: a release consulta o consentimento antes de carregar anúncios e mostra a opção de gerenciá-lo em **Configurações** quando exigida. Teste os banners, o anúncio de download e o fluxo de consentimento em um aparelho antes de publicar o APK.

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

- O binding Android não aceita temperature por requisição: vale a recomendada no GGUF do modelo ou, sem ela, 0.3.
- Contexto efetivo limitado a 8192 tokens no runtime Android atual.
- Modelos grandes podem não caber na RAM de todos os aparelhos; o app mostra uma recomendação e ainda exige um teste real antes de ativar.
- O Qwen3.8 é multimodal na origem, mas esta integração usa apenas texto; suporte a imagem exigiria integrar o projetor multimodal correspondente.
- PDF escaneado sem OCR.
- Áudio dependente de reconhecimento on-device.
- Parser sintático não substitui compilador, type checker ou schema real. Um arquivo pode ter sintaxe válida e ainda conter erro de tipo, símbolo inexistente, erro de link, dependência ausente ou erro semântico.
- O modo `TSLP_LANGUAGES=all` maximiza a cobertura, mas aumenta tempo de CI e tamanho do AAR/APK. O build é estrito: se uma gramática anunciada pela release não compilar para Android, a CI deve falhar em vez de publicar silenciosamente um APK que prometa suporte offline inexistente.

Pronto para uso = CI verde + teste físico com pelo menos um GGUF real no aparelho-alvo.
