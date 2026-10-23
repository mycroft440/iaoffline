# Desempenho de MoE no Android

Em Configurações → Desempenho no aparelho, selecione as quantizações instaladas e execute a comparação. Cada arquivo é carregado separadamente; nunca há um segundo modelo ou decodificação especulativa. Os perfis não alteram os pesos nem os parâmetros das conversas.

- **CPU · páginas do sistema:** especialistas mapeados do GGUF, pré-leitura adaptativa e cache de arquivos do Android.
- **CPU · cache de especialistas:** acrescenta cópias exatas dos especialistas reutilizados, consumidas pelos kernels CPU (inclusive o caminho tiled). Até 512 MiB, usando no máximo metade da margem disponível depois dos pesos fixos, pico de repacking, KV, buffers e maior camada de especialistas. Sem margem ou após uma falha de memória, o cache é desativado.
- **GPU parcial:** experimento ARM64 com Vulkan, até quatro camadas de atenção/pesos compartilhados. Os especialistas continuam na CPU. O orçamento conta a cópia dos pesos compartilhados e mais 256 MiB de buffers. O driver só é carregado quando esse perfil é solicitado. Falhas recuperáveis de carregamento voltam à CPU; uma falha nativa fica no processo isolado da IA e desativa o perfil GPU selecionado para a próxima tentativa.

O cache admite um especialista a partir do segundo uso confirmado, favorece frequência com envelhecimento e mantém protegidas as entradas da camada em execução. O processamento do prompt pode reutilizar entradas, mas não enche o cache. A pré-leitura aprende por camada, liga acima de 75% de acertos e desliga abaixo de 60%, em janelas de 16 observações. Camadas desativadas são amostradas a cada oito oportunidades. Não há modelo auxiliar: a previsão usa o roteador do próprio modelo.

## Comparação no telefone

Cada perfil usa o mesmo texto natural, 128 tokens de prompt e 64 passos de decodificação de uma continuação fixa, três passagens, contexto solicitado de 2.048 tokens. A continuação fixa elimina variação de amostragem; isso mede os kernels e acessos a pesos, não qualidade nem velocidade completa da interface de chat. O teste reutiliza o contexto existente, sem duplicar o KV.

Os resultados mostram a primeira passagem e as duas repetidas, tokens/s do prompt, bytes de armazenamento por token apenas durante a decodificação, acertos do cache ponderados por bytes, capacidade do cache, pico de RSS amostrado a cada 100 ms durante o teste, condição térmica antes/depois e backend efetivo. RSS não é memória total da GPU. Leituras indisponíveis são marcadas como tais. O cache de arquivos do Android não pode ser limpo pelo app: a primeira passagem não é uma medição garantidamente fria. A ordem dos perfis muda na próxima comparação para ajudar a perceber esse viés.

Compare somente execuções com contexto real e condição térmica comparáveis. Repita com o telefone frio; uma quantização menor pode reduzir o I/O e alterar a qualidade. Nenhuma quantização é escolhida automaticamente. “Usar este perfil” salva a execução para o modelo selecionado; a mudança entra no próximo carregamento. GPU indisponível ou sem margem aparece como CPU e não pode ser selecionada a partir daquele resultado. O perfil GPU só pode ser aplicado depois de concordar em pelo menos 95% dos tokens mais prováveis com a CPU, no mesmo contexto e nas três passagens. É uma checagem básica de consistência, não uma garantia de qualidade; valores NaN/+infinito ou amostras incompletas invalidam o teste.

## Validação de desenvolvimento

O patch é aplicado sobre llama.cpp v0.7.0 por `scripts/prepare_llama_android.sh` e `scripts/patch_moe_performance.py`. O build requer `glslc`, `libvulkan-dev` e `spirv-headers` no host Linux; apenas os cabeçalhos são copiados para o cross-build, e `libvulkan.so` vem do NDK. x86_64 continua CPU.

Os testes cobrem limite/evicção/proteção/concor­rência do cache, cancelamento da pré-leitura, orçamento de RAM e dimensões do GGUF. O teste de kernels troca o conteúdo do mapeamento por zeros depois de preencher o cache, comprovando que a operação consome a cópia: F32, Q4_0, Q3_K e Q4_K, lotes de 1 e 16. Testes no host/CI não demonstram ganho de tokens/s nem compatibilidade do driver no S22 Ultra; esses resultados dependem da comparação no próprio aparelho.
