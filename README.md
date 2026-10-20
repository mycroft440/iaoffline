# IA Offline — Android

App Android local-first para baixar ou importar modelos GGUF, validar por inferência real e conversar no aparelho. O botão **Utilizar API** permite também conversar online com OpenRouter, OpenAI ou outros provedores HTTPS compatíveis, enquanto a API localhost continua disponível separadamente.

## O que já funciona

- Android 13+; arm64-v8a/x86_64.
- Runtime local baseado no binding Android do llama.cpp.
- Catálogo integrado para baixar modelos em GGUF diretamente no app, organizado por empresa (PrismML/Bonsai, DeepSeek, Qwen, Mistral, Meta, Google, Microsoft, IBM, Liquid AI, Ai2, NVIDIA, OpenAI, OpenBMB, JetBrains, Ornith AI, inclusionAI, PowerInfer, Sber, Z.ai, Cohere e Poolside), cada uma com seu logo, que também aparece ao lado das respostas no chat.
- **MoE recomendadas em destaque, no topo do catálogo**: todos os modelos MoE com **menos de 14B parâmetros totais** ganham uma seção própria, com prioridade para os que cabem na **RAM livre real** do aparelho e, entre esses, destaque para os **8B**. Para celulares de 8 GB, a compatibilidade é calculada pelo **tamanho do GGUF completo + 768 MiB de memória de trabalho**, não apenas pelos parâmetros ativos. MoE maiores do que a RAM livre continuam visíveis nesta seção para descoberta, mas recebem aviso de que podem não executar no dispositivo. A lista recolhida de recomendações por RAM livre continua completa ao tocar em **Ver recomendadas**.
- O catálogo mantém o destaque **MoE abaixo de 14B** e os demais grupos, mas a seção **Recomendadas para sua RAM livre** fica **oculta por padrão** para poupar rolagem. No começo, um aviso **Dica para escolher sua I.A** explica que, em geral, mais parâmetros demandam mais processamento e sugere MoE menores de 14B ou IAs com RAM estimada de até o **valor de RAM livre medido dinamicamente no aparelho** (não um limite fixo de 5 GB). **Ver recomendadas (N)** revela a mesma lista completa e cartões de antes; **Ocultar recomendadas** volta a recolher. O app acompanha a RAM livre do Android enquanto o catálogo está aberto e recalcula **todas as IAs** cujo GGUF mais contexto (256 MiB) e buffers (512 MiB) cabem na memória atual. Se nenhuma couber, ao abrir a seção mostra as cinco de menor consumo estimado e avisa que podem não caber. A estimativa de RAM não garante execução: mmap pode consumir menos RAM e contextos maiores podem consumir mais.
- Depois vêm os **Modelos MoE que podem rodar no seu telefone** (*mixture of experts*, que usam só uma parte dos parâmetros a cada palavra), com uma frase sobre o que é MoE, separados por empresa; em seguida os **Modelos densos**, também por empresa, e no fim **Outros modelos MoE**, os que pedem mais RAM do que o telefone tem. Os MoE experimentais contam como "podem rodar", porque rodam lendo os pesos do armazenamento: num telefone com menos RAM que a ideal eles aparecem como **Roda lento**, com a RAM ideal no lugar dos requisitos mínimos. O filtro por empresa vale para todas as seções: a faixa de empresas, que desliza para o lado, traz todas as empresas do catálogo com quantos modelos cada uma tem, e o botão **↑ Empresas**, que aparece quando a faixa sai da tela, volta até ela de qualquer ponto da lista.
- MoEs adicionados depois de baixados, conferidos pelo SHA-256 e executados com o llama.cpp do app: SmallThinker 4B-A0.6B (PowerInfer), GigaChat 3.1 10B-A1.8B (Sber), Ling mini 2.0 (inclusionAI), Gemma 4 26B-A4B QAT (Google), GLM-4.7-Flash 30B-A3B (Z.ai), North Mini Code 30B-A3B (Cohere), Laguna XS 2.1 33B-A3B (Poolside), Ornith 1.5 35B-A3B (Ornith AI) e Nemotron 3.5 Lightning 30B-A3B (NVIDIA). O raciocínio do North (marcadores da Cohere) e do Laguna (aberto pelo próprio template) aparece separado da resposta. Modelos da arquitetura `deepseek2` (GigaChat 3, GLM-4.7-Flash e GGUFs importados como DeepSeek V2) carregam sempre com os pesos como estão no arquivo: a versão fixada do llama.cpp reorganiza um tensor da atenção latente para a CPU de um jeito que nenhum backend executa, o que derrubava o processo da IA.
- **Bonsai (PrismML)**: modelos com todos os pesos em 1 bit (Q1_0) ou ternários (Q2_0), que levam a capacidade de modelos grandes para pouca memória:
  - Bonsai 1.7B, 4B e 8B (1 bit, baseados no Qwen3): 250 MB, 570 MB e 1,2 GB.
  - Bonsai 8B Ternário: 2,3 GB, mais preciso que o 1 bit.
  - Bonsai 27B (1 bit, baseado no Qwen3.6 27B, com raciocínio): um modelo da classe 27B em 3,8 GB.
  - Bonsai 2 Ternário 2B, 4B e 9B (baseados no Qwen3.5, com raciocínio): 550 MB, 1,2 GB e 2,6 GB.
  - Bonsai 2 Ternário 27B (baseado no Qwen3.8 27B): 7,6 GB, para celulares de 12 GB; denso e lento, mas muito forte.
- **Gemma 4 QAT** (E2B, E4B, 12B e 26B-A4B): versões oficiais do Google treinadas para 4 bits, com qualidade próxima da versão completa, no formato Q4_0, o mais rápido nos processadores ARM.
- Outros modelos novos que rodam no celular: Ornith 1.5 9B (sobre o Qwen3.5 9B, perto do Qwen3.6 35B-A3B nos testes do autor), Granite 4.2 3B e 8B (IBM, com raciocínio), MiniCPM5 2B (OpenBMB), Mellum 2.1 12B-A2.5B (JetBrains, especialista em código; MoE com 2,5B ativos), LFM2.5 24B-A2B (Liquid AI, experimental; MoE com 2,45B ativos, quantizado com destilação) e SmallThinker 4B-A0.6B (PowerInfer, MoE feito para rodar no aparelho, 0,6B ativos).
- Download em HTTPS com retomada do arquivo parcial quando o servidor permite `Range`. **Falha transitória de conexão/DNS/servidor: a primeira repetição é imediata; se falhar de novo, o download tenta automaticamente a cada 30 segundos**, mesmo com a tela fechada, enquanto o serviço em primeiro plano permanecer ativo. Não há mais o limite antigo de quatro tentativas; a fila e o progresso continuam preservados.
- Fila de downloads: é possível escolher várias IAs enquanto outra baixa; elas baixam uma de cada vez e a próxima começa assim que a anterior é instalada (ou encontra uma falha definitiva que exige correção). **Minhas I.As** mostra cada download com o progresso e as ações Pausar, Continuar, Tentar novamente e Remover; a fila sobrevive ao fechamento do app e retoma dos arquivos parciais.
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
- **Uma thread por núcleo rápido**: o llama.cpp usa uma thread para cada núcleo rápido que o processo da IA pode usar no momento, até 8, em vez de no máximo 4 (todos os núcleos menos 2), como no binding original. Os núcleos de eficiência (Cortex-A53, A55, A510 e A520, reconhecidos pelo número da peça em `/proc/cpuinfo`) só completam até as 4 threads de antes, porque cada operação espera a thread mais lenta terminar. Num Snapdragon 8 Gen 3 são 6 threads; num Snapdragon 8 Elite ou Dimensity 9300/9400, 8; celulares com até 4 núcleos rápidos continuam com 4. Ler a mensagem, o histórico e as instruções depende do número de núcleos, então esses aparelhos passam a usar de 1,5 a 2 vezes mais núcleos nessa etapa. O número é conferido de novo antes de cada bloco e de cada palavra: quando o Android deixa o processo com menos núcleos (em segundo plano ou desligando núcleos), as threads diminuem junto, em vez de uma delas ficar sem núcleo e atrasar todas. O log da IA mostra as threads escolhidas ao carregar o modelo (etapa `MODEL_LOAD`).
- **Uma thread nativa só para a IA**: todas as chamadas ao llama.cpp rodam na mesma thread (`llama-native`). Antes, cada chamada podia cair numa thread diferente do pool de E/S; no arm64 cada uma virava a raiz de uma equipe própria de threads do OpenMP, e a equipe anterior continuava girando por 200 ms nos mesmos núcleos enquanto a nova calculava (no começo de cada turno do histórico, por exemplo).
- As chamadas nativas longas (carregar o modelo, ler o prompt, escrever cada palavra) deixaram de ser `@FastNative`: com essa marcação o coletor de lixo esperava a chamada terminar para pausar o processo da IA, e as outras threads dele (o progresso da resposta, o pedido para parar) ficavam travadas enquanto isso.
- Enquanto a IA responde, a mensagem é salva e redesenhada até 5 vezes por segundo, não a cada token: com respostas longas, salvar o texto inteiro a cada token deixava a tela sem responder.
- **Modo seguro automático**: o app marca quando está carregando um modelo ou respondendo com ele. Se o Android o fechar por falta de memória (ou o motor nativo falhar) nesse momento, os próximos carregamentos daquele modelo usam menos memória: pesos lidos do arquivo e metade do contexto na primeira vez, o menor contexto (2K) se acontecer de novo. Assim o mesmo modelo não derruba o app repetidamente. O nível volta ao normal se o modelo for apagado e baixado de novo.
- Embeddings por camada (a tabela que ocupa quase metade do Gemma 4 E2B/E4B) ficam no arquivo e só as linhas das palavras em uso são lidas, alguns KB por palavra. Medido no llama.cpp com a mesma resposta e a mesma velocidade: o Gemma 4 E2B QAT caiu de 4,3 GB para 2,5 GB de RAM e o E4B QAT de 7,2 GB para 5,0 GB (de 5,1 GB para 2,9 GB no modo de pouca memória). O app desconta essa parte do arquivo ao decidir o modo de memória e o tamanho do contexto.
- Em segundo plano, quando o aparelho está com pouca memória livre, o app libera o modelo ocioso em vez de esperar o Android fechar o app; a próxima mensagem carrega o modelo de novo. Um modelo carregando ou respondendo não é liberado.
- RAM mostrada pelo app: usada, livre (MemAvailable do kernel) e quanto o próprio app ocupa, atualizadas a cada 2 segundos. Até o Android 15, a "memória disponível" que o sistema entrega aos apps soma o cache de arquivos, inclusive o de um modelo mapeado, e parecia sobrar mais RAM do que havia. As exigências do catálogo são comparadas com a RAM anunciada do celular (um aparelho de 12 GB informa cerca de 11 GB ao sistema).
- Cache de contexto (KV) em Q4_0, cerca de um quarto da memória do F16, quando o modelo suporta flash attention; senão o app usa F16 com um contexto menor. O llama.cpp aplica uma rotação de Hadamard ao cache quantizado, o que deixa a perda de qualidade do Q4_0 quase nula.
- **Conversas contínuas**: o modelo fica carregado entre as mensagens e, quando a próxima mensagem continua a mesma conversa, o app aproveita o contexto que já está na memória e processa só a mensagem nova. Antes, cada mensagem recarregava o modelo e relia todo o histórico. Ao abrir outra conversa, editar ou regenerar uma mensagem, o contexto é remontado.
- **Primeira resposta mais rápida**: ao abrir uma conversa, a IA selecionada é carregada e lê as instruções do perfil e o histórico da conversa em segundo plano, enquanto a pessoa escreve; ao enviar, só a mensagem nova falta ser lida. Isso para sozinho se uma mensagem for enviada antes, e não acontece enquanto houver respostas em andamento. Além disso, o processo da IA guarda no cache do app (`prompt-cache`, um arquivo por modelo) o prompt de sistema já processado, até a linha da data, que fica no fim do prompt: nas próximas aberturas do app, inclusive em outros dias, ele é restaurado em cerca de um segundo em vez de ser lido de novo (cerca de mil tokens, que no processador de um celular levam de um a vários minutos). Se o arquivo não servir (outro modelo, outro tipo de cache, arquivo danificado), o prompt é lido normalmente e o arquivo é regravado. Um modelo testado logo antes de responder continua carregado para a resposta, em vez de ser liberado e carregado de novo.
- **Conversa salva no armazenamento**: ao fim de cada resposta, quando ler a conversa de novo levaria mais de 10 segundos (estimado pela velocidade com que o modelo leu a última mensagem), o processo da IA grava no cache do app (`session-cache`) o que o modelo guarda dela (o cache KV e, nos modelos híbridos, o estado recorrente), junto com os turnos já formatados. Se o modelo for liberado ou o Android encerrar o processo da IA, voltar à mesma conversa restaura esse estado lendo um arquivo, em vez de reler todo o histórico, o que numa conversa longa com um modelo grande leva minutos no processador do celular. Só a última conversa respondida fica salva (o arquivo tem o tamanho do contexto em uso, de dezenas a centenas de MB). A gravação começa 1,5 s depois da resposta, para não atrasar a volta seguinte quando a IA usa uma ferramenta. Um estado que não serve para a próxima mensagem (outro dia, outro perfil, conversa editada) ou que não cabe no contexto carregado (outro tipo de cache, contexto menor) é ignorado, e a conversa é lida como antes. Apagar o modelo apaga a conversa salva com ele.
- O histórico vai para o modelo como turnos de chat de verdade, formatados pelo template do próprio modelo (do jeito que ele foi treinado), e não mais colado como texto no prompt de sistema. As respostas anteriores entram sem o raciocínio, que só ocuparia contexto.
- O contexto é o maior que cabe na memória do aparelho (até o limite de treino do modelo): 40% da RAM menos o tamanho do modelo, entre 256 MB e 2 GB para o cache. Num celular de 12 GB, um modelo 4B usa o contexto completo de 32K e um 8B fica em torno de 13K.
- Modelos MoE grandes (15B ou mais no total, poucos bilhões ativos por palavra), como Gemma 4 26B-A4B, Qwen3-Coder 30B-A3B, Qwen3.6 35B-A3B, Nemotron 3 Nano 30B-A3B, gpt-oss 20B e os MoEs maiores adicionados acima (inclusive o Ling mini 2.0, com 16B no total e só 1,4B ativos), aparecem como **Experimental – lento**.
- **MoE maior que a RAM**: quando um modelo MoE não cabe no limite de memória da IA (o app descobre pelo próprio GGUF, inclusive em modelos importados), os especialistas ficam no arquivo e só os escolhidos para cada palavra são lidos. Assim que o roteador de uma camada escolhe seus especialistas (8 de 128 no Gemma 4 26B-A4B, cerca de 21 MB), o app pede todos ao sistema de uma vez, numa thread própria: o armazenamento os lê em paralelo enquanto o processador já começa a conta, em vez de lê-los aos poucos, a cada falha de página. O restante dos pesos (atenção, especialista compartilhado e saída: 2,4 GB no Gemma 4 26B-A4B, lidos a cada palavra) fica na RAM, reempacotado para o processador, quando cabem duas cópias dele; senão fica no arquivo, como antes. O arquivo também deixou de ser lido inteiro ao carregar (12,7 GB no Gemma 4 26B-A4B), o que só enchia o cache com o fim dele. O contexto continua com 384 MB. Os especialistas lidos ocupam o cache de arquivos que o sistema já usava; a parte fixa reempacotada ocupa na RAM o mesmo que já ocupava no cache, mas o Android deixa de descartá-la e relê-la. Medido num PC com o Gemma 4 26B-A4B UD-Q3_K_M, a memória limitada aos 6,8 GB que a IA recebe num celular de 12 GB e o disco limitado para se comportar como o armazenamento de um celular: a resposta passou de 1,1 para 2,2–2,6 tokens por segundo, a leitura de uma mensagem curta de 20–28 s para 3–10 s e o carregamento de 62 s para 1,4 s. Num celular os números absolutos mudam; o log `SPEED` mostra os do aparelho.
- **Especialistas lidos inteiros e uma camada antes**: os "4B ativos" de um MoE mudam a cada palavra e em cada camada, então o modelo inteiro precisa estar ao alcance; o que cabe na RAM é a parte fixa e um cache dos especialistas mais usados, e o resto vem do armazenamento. Duas mudanças aceleram essa leitura:
  - O pedido ao sistema (`MADV_WILLNEED`) lia só o começo de cada especialista: o kernel atende no máximo a janela de leitura antecipada do dispositivo (tipicamente 256–512 KB), e o resto vinha aos poucos, a cada falha de página. Um especialista ocupa cerca de 0,5 MB por tensor no Qwen3.6 35B-A3B, 2 MB no Nemotron 3 Nano e 4,4 MB no gpt-oss 20B; agora cada um é pedido em pedaços de 128 KB, todos de uma vez.
  - Enquanto o processador calcula uma camada, a thread de leitura prevê os especialistas da próxima: aplica o roteador dela ao estado da palavra nesta camada (que muda pouco de uma camada para a outra) e pede ao armazenamento os que ele escolheria, com uma camada inteira de antecedência. Cada previsão é conferida com o que a camada escolhe de fato; os previstos só são lidos antes enquanto pelo menos 60% acertam (conferido a cada 256 previsões), porque um erro lê especialistas que ninguém usa. O log `SPEED` mostra a porcentagem de acerto de cada resposta.
- **Velocidade no chat**: embaixo de cada resposta, ao lado de **Copiar** e **Regenerar**, a nota de tempo mostra também a velocidade em que o modelo escreveu ("⚡ 12s · 2,6 tokens/s" em vez de "⚡ Respondeu em 12s", do mesmo tamanho, para caber na mesma linha). É a medição do próprio llama.cpp, só do tempo escrevendo (sem carregar o modelo nem ler a mensagem), somada entre as voltas quando a IA usa ferramentas, e fica gravada com a resposta. Respostas antigas ou interrompidas continuam com "Respondeu em".
- Cada resposta registra no log da IA (etapa `SPEED`, também no Logcat como `IALocal/SPEED`) a velocidade de leitura da mensagem e da resposta em tokens/s, quantos MB vieram do armazenamento por token e quantas vezes a IA esperou uma leitura do armazenamento; num MoE maior que a RAM, também quantos especialistas previstos uma camada antes estavam certos.
- O gpt-oss 20B da OpenAI responde no formato "harmony"; o app converte o canal `analysis` em raciocínio expansível e o canal `final` em resposta.
- Modelos cujo formato de conversa não está entre os embutidos no llama.cpp (como o Gemma 4) usam o template Jinja do próprio GGUF, em vez de derrubar o app. O raciocínio do Gemma 4 (`<|channel>thought … <channel|>`) aparece como raciocínio expansível.
- Um modelo só vira `VERIFIED` e pode ser ativado após uma inferência real no dispositivo.
- No chat, cada resposta mostra o logo da empresa do modelo que a escreveu (DeepSeek, Mistral, Google…), gravado junto com a resposta: trocar de modelo no meio da conversa não muda o logo das respostas anteriores. Empresas sem logo próprio no app e modelos desconhecidos aparecem com um ícone genérico.
- **Canvas de código no chat**, como o do Gemini: o código que a IA escreve aparece na conversa como um card com título, linguagem, número de linhas e botão **Abrir** (blocos curtos que não rodam, como um comando de terminal, continuam no texto). **Abrir** mostra o código em tela cheia com duas abas: **Código**, com números de linha, e **Ver resultado**, que roda HTML (sites), SVG e JavaScript (com o console na tela) no navegador do próprio Android, sem internet, a não ser que a página carregue algo de fora. O título do card vem do `<title>` da página. Enquanto a IA escreve, o card mostra as linhas já escritas e o canvas aberto acompanha o código. O perfil **Programador** pede sites num único arquivo HTML, com CSS e JavaScript embutidos, para o resultado abrir completo.
- A tela inicial usa o mesmo visual do catálogo **Baixar I.As offline**: no centro, o botão **Iniciar chat com I.A**; abaixo, **Utilizar API** (com estado de conexão a serviços externos); depois **Minhas I.As** num resumo **uniforme em duas colunas**, com ícone, nome, tamanho do arquivo GGUF e ponto de estado (**Pronta**, **Testando**, **Erro**, **Não testada**) em cartões pequenos. Não há mais três linhas grandes seguidas de chips. Aparecem até **8 IAs** (ativa primeiro, depois mais recentes); quando há mais, aparece **+N outras IAs instaladas · Ver todas**. O botão **Ver todas baixadas** abre a biblioteca completa, com tamanho, estado de verificação, possíveis erros e ações de conversa e desinstalação. Tocar numa IA do resumo abre conversa; se o teste falhou, leva para a tela detalhada. Abaixo fica **Baixar modelos offline**.
- **Utilizar API** abre um **chat online próprio**, semelhante ao chat offline, com OpenRouter, OpenAI ou APIs HTTPS compatíveis com `/v1/chat/completions`. Possui busca e escolha de modelo, **histórico online pesquisável e persistido em arquivo separado**, nova conversa, edição e reenvio de mensagens, **Refazer**, **Copiar**, leitura em voz alta, **Parar**, perfis padrão e personalizados do app, documentos PDF/texto e áudios transcritos no aparelho, respostas em streaming e **Canvas** de código com prévia HTML/SVG/JavaScript e acesso à biblioteca de linguagens. Arquivos sem texto extraível e imagens não são enviados como anexo visual; o app informa a limitação. A chave do provedor é criptografada com AndroidKeyStore e os dados sensíveis de API e histórico online não vão ao backup automático. O provedor recebe o conteúdo enviado explicitamente e pode cobrar por uso. Os históricos online e offline **não se misturam**; o app só compartilha os perfis escolhidos. O chat online usa a conexão de internet e não exige instalar GGUF.
- **API local separada**: a tela **Modelos e API** mantém o servidor localhost autenticado com health/models/chat/SSE, escutando somente em `127.0.0.1`, para ferramentas locais e integrações existentes.
- Servidor limitado a 8 conexões.
- Teste end-to-end dentro do app.
- Editor anatômico de código com parser formal de sintaxe e análise semântica complementar por IA offline.
- SQL usa JSqlParser 5.3; o restante da cobertura formal usa o catálogo Tree-sitter, inclusive gramáticas reconhecidas dinamicamente por nome, extensão ou caminho.

- Amostragem recomendada pelo próprio modelo: o runtime lê temperatura, top-k, top-p, min-p e penalidade de repetição gravados no GGUF (`general.sampling.*`) em vez de usar sempre 0,3, e aplica a penalidade de presença que o cartão do modelo pede (Bonsai 2). Modelos que raciocinam e modelos de poucos bits entram menos em repetição assim, sem custo de memória.

Depois que um modelo foi baixado e instalado, a inferência não depende da internet. Internet é necessária apenas para baixar um modelo do catálogo; modelos importados manualmente podem ser usados sem rede desde o início.

Os anúncios usam a rede quando há conexão, mas não interferem na inferência local. Sem conexão ou sem anúncio disponível, o espaço do banner desaparece e o anúncio de download não aparece.

Os modelos baixados pelo catálogo ficam também em `IAs Offline` fora de Downloads. Se o aplicativo for desinstalado, o Android apaga o banco e a cópia privada usada pelo runtime, mas o GGUF dessa pasta pública permanece. Após reinstalar, conceda acesso aos arquivos do aparelho e toque em **Buscar** em **Minhas I.As**. A busca inclui essa pasta e os diretórios antigos em Downloads, importa GGUFs válidos e não baixa os arquivos novamente. Os pré-perfis **Programador** e **Sem censura**, e os perfis criados pelo usuário, aparecem no seletor de perfil do chat mesmo antes de haver um modelo e são gerenciados em **Configurações → Perfis de I.A**, acessível pelo menu das três barras no chat.

**Notificações de respostas e retorno à conversa.** Quando a IA local gera uma resposta, o serviço de primeiro plano mostra **I.A offline respondendo**, com botão **Parar**; tocar na notificação abre diretamente a conversa que está recebendo a resposta. O chat conectado a OpenRouter/OpenAI tem um serviço próprio de primeiro plano com **I.A por API respondendo**, também com **Parar** e toque para retornar ao histórico online exato. O controlador remoto fica no escopo do aplicativo: sair da tela da API não cancela seu streaming. No Android 13+, o app solicita a permissão de notificações uma vez; se for recusada, o Android pode ocultar a notificação da gaveta. O botão **Voltar** do chat local e o retorno do chat API voltam para a página anterior (normalmente o menu inicial), em vez de criar outra conversa vazia. **Iniciar chat com I.A** seleciona a última IA escolhida explicitamente; se ela não estiver disponível, tenta o modelo da conversa recente e depois a IA instalada ativa/única.

**Teste automático de novas IAs.** Downloads do catálogo continuam com verificação real de inferência no serviço de downloads, mesmo em segundo plano. Os modelos GGUF importados por arquivo ou pela busca do armazenamento agora agendam seu próprio teste de inferência em serviço de primeiro plano; esses testes aguardam quando outro download ou resposta está usando a IA, e pendências ficam salvas se o Android encerrar o processo. Após interrupções repetidas do processo, o app deixa a IA com erro para revisão manual, sem reiniciar infinitamente. Uma IA permanece instalada caso não passe no teste, e pode ser retestada pelo usuário. O Android ainda pode finalizar os serviços sob pressão crítica de memória.

As respostas da I.A continuam sendo geradas quando o app vai para segundo plano, quando a tela é desligada ou quando o usuário sai da conversa. No máximo duas conversas são respondidas ao mesmo tempo; uma mensagem enviada de uma terceira conversa aparece como **Na fila** e é respondida na ordem de chegada assim que uma das outras termina. Enquanto houver resposta ou mensagens na fila, um **serviço em primeiro plano** (com `foregroundServiceType=specialUse`) mostra a notificação **Gerando resposta…** e mantém o processo do app com prioridade elevada. O processo separado da IA é vinculado com `BIND_IMPORTANT`. Um bloqueio parcial da CPU (`PARTIAL_WAKE_LOCK`) é **renovado a cada cinco minutos** com prazo de segurança de quinze minutos para sustentar respostas longas com a tela bloqueada; é liberado ao terminar. **Parar** na conversa cancela a resposta e sua fila pendente; **Parar** na notificação interrompe todas as gerações e esvazia as filas. O serviço é encerrado quando não há mais gerações. Essa proteção reduz encerramentos evitáveis, mas **o Android ainda pode matar processos se faltar RAM, se o usuário forçar a parada ou por restrições do fabricante**. Gerações interrompidas por morte completa do processo não podem continuar do ponto exato; não fazemos um reinício oculto da IA.

Antes da primeira palavra, o chat mostra exatamente o que a resposta está esperando, etapa por etapa, com o tempo de cada uma: na fila (outras conversas sendo respondidas), testando a IA no primeiro uso, iniciando o processo da IA, aguardando a IA terminar outra resposta ou a preparação feita enquanto a pessoa escrevia, carregando o modelo na memória (com o tamanho), restaurando ou lendo as instruções do perfil, lendo a conversa (mensagem 3 de 8), conversa já na memória, consultando a Wikipédia offline, lendo a mensagem, usando uma ferramenta (calculadora, data e hora…) e escrevendo a resposta (com os tokens já escritos) quando o texto ainda está retido por poder ser uma chamada de ferramenta. As etapas concluídas ficam listadas acima da atual, com o tempo total. O processo da IA envia cada etapa ao app pelo mesmo canal das palavras, e a API local só as inclui (`{"ia_stage": …}`) quando o pedido traz `"ia_stages": true`, como faz o chat do app; outros clientes recebem o streaming de sempre.

Modelos que raciocinam mostram o raciocínio enquanto ele é gerado, num bloco **Raciocínio** que exibe só as duas últimas linhas escritas (tocar em **Mostrar tudo** abre o raciocínio inteiro); o tempo de **Raciocinando…** conta a partir do início do raciocínio, sem o carregamento antes dele; ao terminar, o bloco se recolhe e exibe quanto tempo o modelo raciocinou, e cada resposta mostra o tempo total (**Respondeu em …**). O seletor **DeepThink** aparece nos modelos em que o raciocínio pode ser ligado e desligado. Desligado, a resposta sai bem mais rápido, o que pesa muito no celular:
  - Qwen3 e Nemotron Nano v2: o app envia `/no_think`; começam com o DeepThink desligado.
  - Qwen3.5, 3.6 e 3.8, Bonsai 2 e Bonsai 27B, Ornith, MiniCPM5 e Granite 4.2: a resposta é pré-preenchida com um bloco de raciocínio vazio, como os templates desses modelos fazem; começam com o DeepThink ligado, como o próprio modelo.
  - Gemma 4: o app desliga o `enable_thinking` do template; começa ligado.

  Modelos que sempre raciocinam (DeepSeek R1, gpt-oss, Mellum, variantes *Reasoning* e *Think*) não têm o seletor. Todos os perfis pedem respostas de até 8192 tokens, um contexto nativo inteiro; o modelo para antes quando termina, e se passar da janela de contexto o llama.cpp desloca o contexto (mantendo o prompt de sistema) em vez de cortar a resposta. No prompt, no máximo metade do contexto é reservada para a resposta, para o histórico continuar cabendo.

## Respostas melhores sem modelo maior

Além da amostragem recomendada pelo modelo, cada conversa recebe técnicas que custam poucos tokens de prompt e nenhuma memória extra:

- **Data de hoje** no fim do prompt de sistema (a hora exata vem da ferramenta `current_time`): os modelos só conhecem a data do treino. O prompt de sistema só muda uma vez por dia, para não impedir a continuação da conversa, e só nessa última linha, para o resto continuar salvo já processado.
- **Regras curtas contra erros típicos de modelos pequenos**: responder no idioma do usuário, admitir quando não sabe em vez de inventar nomes, datas e números, e resolver problemas de várias etapas passo a passo.
- **Calculadora** (`calculate`): contas exatas com precedência, potências, funções e constantes, em vez de aritmética "de cabeça".
- **Wikipedia Offline**: quando instalada, os agentes podem procurar (`wikipedia_search`) e ler (`wikipedia_read`) artigos, e, com a consulta automática ligada, perguntas que citam uma pessoa, lugar ou assunto recebem o começo do artigo correspondente (no máximo dois artigos, ~650 caracteres cada).

## Configurações e ferramentas offline

As **Configurações** são um menu de botões, e cada um abre a própria tela com as opções daquela parte:

- **Aparência**: o tema do app (seguir o sistema, claro ou escuro).
- **Perfis de I.A**: os pré-perfis **Programador** e **Sem censura** e os perfis criados pelo usuário, com as instruções de cada um. Em **Criar novo perfil** o usuário dá um nome, uma descrição opcional e as instruções que a IA deve seguir; o perfil ganha um agente em cada IA instalada e pode ser editado ou excluído depois. Ao excluir um perfil, as conversas que o usavam continuam com o Programador.
- **Achar I.As no armazenamento**: busca GGUFs já salvos no aparelho e mostra a lista das I.As encontradas antes de importá-las. Cada uma mostra o que está acontecendo com ela (na fila, verificando, importando com a porcentagem copiada, importada, já instalada, inválida ou com falha). **Cancelar** interrompe a busca: a cópia em andamento é apagada e as I.As já importadas continuam instaladas.
- **Baixar compiladores**: os pacotes de linguagem do editor de código (veja abaixo).
- **Baixar Wikipedia Offline**: edições da Wikipédia publicadas pelo [Kiwix](https://kiwix.org) no formato ZIM, para os agentes consultarem sem internet. Há resumos dos 50 mil artigos essenciais em português (~150 MB), resumos de todos os artigos em português (~1,7 GB), os artigos essenciais completos (~1 GB), a Wikipédia em português completa sem imagens (~7 GB) e resumos em inglês. Quem escolhe o que baixar é o usuário: nada é baixado sem tocar em **Baixar** numa edição e confirmar o diálogo (que mostra o tamanho, o espaço livre e a opção de baixar apenas pelo Wi-Fi), o app não atualiza as edições sozinho e os agentes só leem as edições já instaladas. Ao confirmar, o app baixa a edição mais recente, usando o gerenciador de downloads do Android (continua em segundo plano e retoma após quedas de rede), confere o arquivo com o SHA-256 publicado pelo Kiwix e o guarda na pasta do app em `Android/data`.

O leitor de ZIM é do próprio app: ele procura artigos por título com buscas binárias nos índices do arquivo e descomprime (zstd) só o trecho necessário, então consultar a Wikipédia completa usa quase nada de RAM. Não há busca de texto completo: os artigos são encontrados pelo título, a partir do assunto da pergunta.

## Editor de código e diagnósticos

**Canvas → Ver resultado e biblioteca de linguagens.** O Canvas permite abrir **Ver resultado** também para Python, Kotlin, C++, TypeScript e outros blocos de código. HTML, SVG e JavaScript continuam com suas prévias reais via WebView. Para linguagens sem execução integrada, um aviso central explica a limitação. Quando existe um pacote de suporte à linguagem na biblioteca e ele não está instalado (por exemplo, Python), aparece **Baixar compiladores**, que abre a biblioteca diretamente na linguagem solicitada, destacando seu cartão. O download é iniciado de acordo com a regra de anúncio recompensado para compiladores. Se o pacote já está instalado, a tela apenas explica que o Canvas ainda não executa a linguagem. **Esses pacotes JSON são instruções especializadas para a IA, não compiladores ou interpretadores executáveis** — instalá-los não faz o Canvas executar Python nem outras linguagens nativas. Para linguagens desconhecidas sem pacote, não aparece sugestão enganosa.

O editor separa sintaxe de análise por IA. Uma linguagem só recebe o estado **Sintaxe válida** depois que o parser formal correspondente conclui sem erros. Se a gramática não puder ser carregada, o estado é **Parser formal indisponível** — o app não presume que o código está correto.

SQL passa pelo JSqlParser. Os perfis comuns mantêm nomes e aliases próprios, mas o registro não fica limitado à lista manual: qualquer gramática reconhecida pelo catálogo da versão instalada do `tree-sitter-language-pack` pode gerar dinamicamente um perfil Tree-sitter. Isso permite reconhecer também linguagens e formatos fora da lista original, inclusive por extensão ou caminho de arquivo.

Para a distribuição Android, `scripts/prepare_tree_sitter_android.sh` recompila o AAR do Tree-sitter com `TSLP_LANGUAGES=all` por padrão, usando as fontes de parsers publicadas e verificadas pela mesma release. O objetivo é embutir no aplicativo o maior conjunto de gramáticas que a versão consegue compilar para Android, em vez de depender apenas do subconjunto reduzido do AAR Maven. Se um build precisar deliberadamente de um conjunto menor, `TREE_SITTER_LANGUAGES` pode receber uma lista separada por vírgulas.

A IA local só é chamada depois que a sintaxe é aceita e não pode retornar diagnósticos da categoria `SYNTAX`; ela complementa com erros de tipo, referência, lógica, segurança e compatibilidade. Ao editar o texto ou trocar a linguagem, a aprovação sintática anterior é invalidada imediatamente.

Para SQL, o parser valida gramática, não existência de tabelas, colunas ou objetos de um banco específico; validação semântica de schema exige conexão ou importação do schema correspondente.

## Instalação

A distribuição para aparelhos deve ser feita pelo APK assinado publicado em **GitHub Releases**, não pelo arquivo ZIP de artefatos do GitHub Actions e não pelo AAB.

Quando a assinatura de release estiver configurada, o APK mais recente ficará disponível diretamente em:

`https://github.com/mycroft440/iaoffline/releases/latest/download/IA-Local.apk`

Enquanto a assinatura não estiver configurada, o APK de teste é o artefato **IA-Local-debug-0.1.N.apk** de cada execução do GitHub Actions, em que `N` é o número da execução. Ele é enviado como o próprio APK, sem ZIP (`archive: false`): o download já é o arquivo para instalar, sem nada para descompactar. Execuções anteriores entregavam um ZIP, e os ZIPs sem compressão (até o PR #46) aparecem como corrompidos no celular, porque o leitor de ZIP do Android recusa uma entrada sem compressão com descritor de dados, como o Actions os gerava.

O código (dex) vai comprimido dentro do APK, como as bibliotecas nativas: guardado sem compressão, ele ocupava 87 dos 116 MB do APK, e o download fica com cerca de metade disso. Se a instalação, mesmo com o app desinstalado, terminar em "Como o pacote parece ser inválido, o app não foi instalado", o mais provável é que o arquivo tenha chegado danificado ao celular: o instalador ainda mostra o nome e o ícone, que ficam no fim do arquivo, mas a assinatura cobre o arquivo inteiro e não confere. Compare o tamanho do arquivo baixado (Meus Arquivos → Detalhes) com o tamanho indicado no resumo da execução do Actions e baixe de novo, de preferência pelo Wi-Fi, se for diferente.

Todo APK debug é assinado com a mesma chave, `app/debug.keystore`, que fica no repositório, e o CI falha se o APK sair com outra. Antes, cada execução do Actions criava a própria chave de debug, e o Android recusava o APK novo como atualização do app instalado. Quem tem instalado um APK debug anterior a essa mudança precisa desinstalá-lo uma única vez; os APKs seguintes atualizam a instalação. Instale sempre o APK de número mais alto: o Android recusa, como "pacote inválido", um APK mais antigo que o instalado. A senha dessa chave é pública, como a da chave de debug do próprio Android, por isso ela nunca assina a Release.

O APK debug não é *debuggable*: como app depurável, o Android roda o código com as otimizações desligadas e ignora os perfis de inicialização das bibliotecas, e as telas em Compose (o catálogo mais que todas) engasgavam ao rolar. Os anúncios de teste, o `BuildConfig` e a chave continuam os do debug.

Requisitos do APK atual:

- Android 13 ou superior (`minSdk 33`).
- CPU `arm64-v8a` ou `x86_64` para o runtime completo do llama.cpp.

Se uma versão debug antiga do aplicativo já estiver instalada, desinstale-a uma única vez antes de instalar a primeira Release assinada. Depois disso, as Releases futuras usam a mesma chave e podem atualizar a instalação normalmente.

## Segurança e armazenamento

Falhas de rede são recuperadas automaticamente: a primeira nova tentativa começa imediatamente após o erro; as seguintes ocorrem a cada 30 segundos, com a mensagem de retomada na tela e na notificação. A retomada automática não exige outro anúncio, não apaga os bytes já salvos e pode ser interrompida pelas ações **Pausar** e **Encerrar**. Erros que não melhoram com nova conexão (SHA-256 inválido, falta de espaço, permissão negada, arquivo ausente/HTTP permanente) são exibidos como erro, sem retentativas infinitas. Se o Android limitar ou encerrar o serviço de dados em primeiro plano, o progresso salvo pode ser continuado depois.

Arquivos incompletos continuam no armazenamento privado do aplicativo com extensão `.part`, podem ser retomados e nunca são registrados como modelo. Ao concluir um download do catálogo, o app confere o SHA-256 esperado e salva uma cópia persistente em `IAs Offline` antes de registrar a cópia privada usada pelo runtime. O Android solicita acesso aos arquivos compartilhados antes de iniciar o download, caso ainda não tenha sido concedido.

A desinstalação remove a biblioteca privada, o banco local e os arquivos parciais, mas não remove o GGUF concluído em `IAs Offline`. Depois de reinstalar, **Minhas I.As → Buscar** lê a pasta e também encontra arquivos anteriores em Downloads. Arquivos com nomes do catálogo passam novamente pelo SHA-256 fixado no app; todos os GGUFs passam por leitura estrutural e a ativação requer uma inferência real no aparelho.

Quando o usuário exclui explicitamente uma IA do catálogo dentro do aplicativo, o app remove tanto a cópia privada quanto a cópia persistente acessível em `IAs Offline` e os arquivos antigos em Downloads aos quais tenha acesso. Assim, a persistência protege contra desinstalação do aplicativo, não contra uma ordem explícita de excluir o modelo.

A biblioteca privada e os downloads parciais são excluídos do backup e da transferência de dados do Android para evitar cópias de vários gigabytes. Enquanto uma IA do catálogo estiver instalada, existe uma cópia persistente em `IAs Offline` e uma cópia privada para o runtime, portanto é necessário espaço para ambas.

## Build

### Anúncios AdMob

**Download de I.As (primeira Free + demais recompensadas).** A primeira IA recomendada para a RAM livre é destacada com o selo **FREE · GRÁTIS** e botão verde **Baixar GRÁTIS**. O download é iniciado imediatamente, e só depois o aplicativo tenta mostrar um intersticial comum. Se não houver anúncio, continua sem interrupção. O mesmo modelo mantém o selo Free na lista da empresa e na tela **Modelos e API**. As demais I.As só iniciam o download ao receber o callback `onUserEarnedReward` de um anúncio recompensado que a pessoa aceitou assistir.

- O catálogo continua calculando **todas** as recomendações que cabem na RAM livre, mas só mostra seus cartões após tocar em **Ver recomendadas**. Se nenhuma couber, a primeira da lista das cinco mais leves aparece Free, mesmo que não caiba na RAM disponível naquele momento.
- O anúncio recompensado só autoriza o download após a recompensa; fechar antes não o inicia. **Continuar** ou **Tentar novamente** num download parcial não exige novo anúncio.
- Quando o AdMob não pode mostrar anúncio, o aplicativo libera a ação sem publicidade. Criativos recompensados podem ser vídeos ou outros formatos elegíveis; não são necessariamente só vídeos.
- O intersticial comum da IA Free só é exibido se já estiver preparado e o limite entre anúncios permitir; não impede o download.

**Compiladores, Wikipédia e criação de perfis (anúncios recompensados).** Cada pacote de linguagem/compilador do editor de código e **cada edição da Wikipédia** pede recompensa para iniciar seu próprio download. A Wikipédia continua pedindo confirmação de tamanho e da opção de baixar só por Wi-Fi antes do anúncio. Cada **novo perfil personalizado** exige uma recompensa, validando antes o nome e as instruções; editar, excluir ou usar perfis existentes não requer anúncio. Modelos e recursos já instalados continuam utilizáveis sem anúncios adicionais.

**Preparo dos anúncios nas telas.** Ao entrar em **Wikipédia**, **Baixar compiladores** ou **Perfis de I.A**, o aplicativo prepara um anúncio recompensado, mas somente **uma vez por intervalo de 30 minutos compartilhado entre as três telas**, persistindo o horário entre aberturas do aplicativo. Isso **não exibe um anúncio automaticamente** ao abrir uma tela. Ações escolhidas pelo usuário podem buscar e exibir anúncios recompensados a qualquer momento, mesmo durante esse intervalo. O controle de 30 minutos limita apenas o pré-carregamento motivado pela entrada nas telas.

**Anúncio de abertura (tela cheia).** Aparece quando a pessoa volta ao app (pelo ícone ou pelos recentes), com estas regras (`AppOpenAdPacing`):

- Nunca nas duas primeiras aberturas do app; a partir da terceira.
- No máximo um a cada 4 horas, e nunca até 3 minutos depois do anúncio de download.
- Só quando nenhuma IA está na memória (nem carregando, nem respondendo): o vídeo do anúncio tiraria RAM do modelo.
- Voltar de uma tela que o próprio app abriu (seletor de arquivos, configurações do Android para a permissão de armazenamento, a página de um anúncio) não conta como abertura.
- Na primeira abertura depois de o Android encerrar o app, o anúncio ainda não está carregado e não aparece. Ele é carregado só quando a próxima volta pode mostrá-lo, para não pedir anúncios que ninguém vê.

**Anúncios nativos no catálogo.** Em **Baixar I.As offline**, um card de anúncio com o visual do catálogo (selo **Anúncio**, ícone, título, imagem ou vídeo e botão) aparece depois da 2ª empresa e a cada 4 empresas seguintes (`CatalogAdSlots`), nunca depois da última. Cada card é carregado e montado uma vez por visita à tela: rolar para longe e voltar não pede outro anúncio nem monta o card de novo. Com o filtro de uma empresa só, não há card.

**Banners.** Há um banner no fim da tela inicial, dois no catálogo **Baixar I.As offline** (antes e depois da lista) e dois em **Minhas I.As** (antes e depois da lista, mesmo sem modelos instalados). O **Histórico de conversas** e as **Configurações** têm um banner fixo embaixo; no **Canvas de código** o banner fica no fim do conteúdo, para nunca ficar colado ao teclado. No chat, um banner aparece dentro da resposta da IA a cada 2, depois 4, depois 3 mensagens do usuário, repetindo o ciclo (mensagens 2, 6, 9, 11, 15, 18…); ele fica antes do primeiro bloco de código ou depois do primeiro parágrafo. Sem conexão, o banner não ocupa espaço e o app tenta carregá-lo de novo quando a rede volta. O banner só é montado quando a tela termina de abrir, e os do catálogo e de **Minhas I.As** são mantidos durante a visita: rolar para longe e voltar mostra o mesmo anúncio, sem criar outro WebView (o que travava a lista e a fazia pular quando o anúncio novo chegava). O build `debug` usa exclusivamente os IDs de teste do Google.

**IDs de teste (configuração atual).** Enquanto `ADMOB_APP_ID` não estiver configurado, a release também usa os IDs de teste do Google, então todos os anúncios já funcionam e mostram o selo "Test Ad". Anúncios de teste não geram receita e não pedem o formulário de consentimento. Os IDs usados:

| Uso | ID de teste do Google |
| --- | --- |
| Aplicativo | `ca-app-pub-3940256099942544~3347511713` |
| Banner adaptável | `ca-app-pub-3940256099942544/9214589741` |
| Intersticial (Wikipédia, vídeo e imagem) | `ca-app-pub-3940256099942544/1033173712` |
| Recompensado (I.As, Wikipédia, compiladores e perfis) | `ca-app-pub-3940256099942544/5224354917` |
| Nativo | `ca-app-pub-3940256099942544/2247696110` |
| Abertura do app | `ca-app-pub-3940256099942544/9257395921` |

Para trocar pelos anúncios reais (que geram receita) em um build `release`, configure os valores abaixo no ambiente de build. Os dois primeiros vão juntos e desligam os IDs de teste; cada um dos outros liga o formato correspondente (sem ele, a release real não mostra aquele formato):

```bash
export ADMOB_APP_ID='ca-app-pub-XXXXXXXXXXXXXXXX~YYYYYYYYYY'
export ADMOB_BANNER_AD_UNIT_ID='ca-app-pub-XXXXXXXXXXXXXXXX/YYYYYYYYYY'
export ADMOB_INTERSTITIAL_AD_UNIT_ID='ca-app-pub-XXXXXXXXXXXXXXXX/ZZZZZZZZZZ'   # IA Free
export ADMOB_REWARDED_AD_UNIT_ID='ca-app-pub-XXXXXXXXXXXXXXXX/RRRRRRRRRR'     # I.As extras, Wikipédia, compiladores e perfis
export ADMOB_NATIVE_AD_UNIT_ID='ca-app-pub-XXXXXXXXXXXXXXXX/NNNNNNNNNN'         # cards do catálogo
export ADMOB_APP_OPEN_AD_UNIT_ID='ca-app-pub-XXXXXXXXXXXXXXXX/AAAAAAAAAA'       # abertura do app
```

Use os IDs da sua conta AdMob (no AdMob, crie blocos dos tipos **Recompensado**, **Intersticial**, **Nativo avançado** e **Abertura de app**). No GitHub Actions, cadastre os mesmos valores como secrets com os mesmos nomes; o workflow os passa apenas ao build da release. Configure também a mensagem de privacidade em **Privacy & messaging** no AdMob: a release consulta o consentimento antes de carregar anúncios e mostra a opção de gerenciá-lo em **Configurações** quando exigida. Teste cada formato e o fluxo de consentimento em um aparelho antes de publicar o APK.

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
