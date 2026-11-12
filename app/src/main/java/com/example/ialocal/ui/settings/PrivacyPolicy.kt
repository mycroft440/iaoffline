package com.example.ialocal.ui.settings

import com.example.ialocal.AppContact

/** One titled part of the privacy policy. */
data class PrivacySection(val title: String, val paragraphs: List<String>)

/**
 * The privacy policy shown in Configurações › Política de privacidade, available without internet.
 * docs/politica-de-privacidade.md is the same text, published for the Google Play listing:
 * PrivacyPolicyTest fails when one changes without the other.
 */
object PrivacyPolicy {
    const val TITLE = "Política de privacidade do I.A Off-line"
    const val UPDATED = "Última atualização: 12 de novembro de 2026"
    const val INTRO =
        "O I.A Off-line roda modelos de inteligência artificial no próprio aparelho. O aplicativo não " +
            "tem servidor próprio, não exige cadastro e o desenvolvedor não recebe suas conversas, seus " +
            "arquivos nem seus áudios. Esta política explica quais dados ficam no aparelho, quando o " +
            "aplicativo usa a internet e quais dados os anúncios coletam."

    val sections: List<PrivacySection> = listOf(
        PrivacySection(
            "Quem é o responsável",
            listOf(
                "O aplicativo é mantido por um desenvolvedor independente. Contato para dúvidas, pedidos e " +
                    "denúncias: ${AppContact.EMAIL}.",
            ),
        ),
        PrivacySection(
            "Dados que ficam só no seu aparelho",
            listOf(
                "Conversas com as IAs offline, perfis de I.A, preferências, arquivos anexados (PDF e texto) e " +
                    "os modelos baixados ficam guardados no armazenamento do aparelho. As respostas das IAs " +
                    "offline são geradas no aparelho, sem enviar a conversa pela internet.",
                "Áudios: quando você grava ou anexa um áudio, o aplicativo usa o microfone e o reconhecimento " +
                    "de voz do próprio Android, no aparelho, para transformar a fala em texto. O áudio fica na " +
                    "área privada do aplicativo e não é enviado ao desenvolvedor.",
                "Registros de diagnóstico (erros e eventos da IA) ficam no aparelho. Eles só saem dele se " +
                    "você mesmo copiar e enviar os detalhes.",
            ),
        ),
        PrivacySection(
            "Quando o aplicativo usa a internet",
            listOf(
                "Baixar modelos de IA: os arquivos vêm do Hugging Face (huggingface.co).",
                "Baixar a Wikipédia offline: os arquivos vêm do Kiwix (download.kiwix.org), pelo gerenciador " +
                    "de downloads do Android.",
                "Baixar pacotes de linguagem do editor de código: os arquivos vêm do GitHub " +
                    "(raw.githubusercontent.com).",
                "Nesses downloads, o serviço que hospeda o arquivo recebe o seu endereço IP e dados técnicos " +
                    "da conexão, como em qualquer download. O aplicativo não envia suas conversas a esses " +
                    "serviços.",
                "Chat online (Utilizar API): é opcional. Se você cadastrar uma chave de API, as mensagens, o " +
                    "texto dos anexos e as instruções do perfil daquela conversa são enviados ao provedor que " +
                    "você escolheu (por exemplo OpenRouter, OpenAI ou outro endereço que você informar), que os " +
                    "trata conforme a política dele. A chave fica criptografada no aparelho.",
            ),
        ),
        PrivacySection(
            "Anúncios (Google AdMob)",
            listOf(
                "O aplicativo mostra anúncios do Google AdMob. Para isso, o SDK de anúncios do Google coleta " +
                    "e trata dados como o ID de publicidade do aparelho, o endereço IP (que indica a localização " +
                    "aproximada), informações do aparelho e do aplicativo e a sua interação com os anúncios. " +
                    "Esses dados são usados para exibir e medir anúncios, limitar a repetição, prevenir fraude e, " +
                    "quando permitido, personalizar anúncios.",
                "Onde a lei exige (por exemplo no Espaço Econômico Europeu, no Reino Unido e na Suíça), o " +
                    "aplicativo pede o seu consentimento antes de carregar anúncios. Quando essa escolha se " +
                    "aplica à sua região, ela pode ser mudada em Configurações > Privacidade dos anúncios. Em " +
                    "qualquer lugar, você pode redefinir ou excluir o ID de publicidade e desativar a " +
                    "personalização nas configurações do Android (Configurações > Google > Anúncios).",
                "Como o Google usa os dados coletados em aplicativos parceiros: " +
                    "https://policies.google.com/technologies/partner-sites. Política de privacidade do Google: " +
                    "https://policies.google.com/privacy.",
                "O aplicativo não envia suas conversas nem seus arquivos ao Google para anúncios.",
            ),
        ),
        PrivacySection(
            "Permissões do Android",
            listOf(
                "Internet e estado da rede: downloads, chat online opcional e anúncios.",
                "Microfone: só quando você grava um áudio para a IA.",
                "Notificações: progresso de downloads e de respostas em segundo plano.",
                "Acesso a todos os arquivos: usado para guardar os modelos baixados na pasta IAs Offline, que " +
                    "continua no aparelho se o aplicativo for desinstalado, e para encontrar arquivos de modelo " +
                    "(GGUF) já salvos no aparelho. O aplicativo só procura e lê arquivos de modelo; nomes e " +
                    "conteúdos de arquivos não saem do aparelho.",
                "Serviços em primeiro plano e manter o aparelho ativo: para concluir downloads e respostas " +
                    "com o aplicativo em segundo plano.",
            ),
        ),
        PrivacySection(
            "Backup do Android",
            listOf(
                "Se o backup do Android estiver ativado no aparelho, o sistema pode incluir as conversas " +
                    "offline e as preferências do aplicativo no backup da sua conta Google. Modelos, anexos, " +
                    "áudios, chaves de API e o histórico do chat online ficam fora desse backup.",
            ),
        ),
        PrivacySection(
            "Denúncia de respostas",
            listOf(
                "Cada resposta da IA tem um botão para denunciar conteúdo ofensivo. Ele abre o seu aplicativo " +
                    "de e-mail com o motivo, o nome do modelo e o texto da resposta. Esses dados só são enviados " +
                    "se você enviar o e-mail, e são usados apenas para analisar a denúncia.",
            ),
        ),
        PrivacySection(
            "Compartilhamento e venda de dados",
            listOf(
                "O desenvolvedor não vende seus dados e não os compartilha com terceiros. Os únicos dados que " +
                    "saem do aparelho são os descritos acima: os dos anúncios, tratados pelo Google, os dos " +
                    "downloads e, se você usar, os do chat online, tratados pelo provedor escolhido.",
            ),
        ),
        PrivacySection(
            "Guarda e exclusão",
            listOf(
                "Você pode apagar conversas, perfis e modelos dentro do aplicativo. Limpar os dados do " +
                    "aplicativo ou desinstalá-lo remove tudo o que está na área privada dele. Os modelos da pasta " +
                    "IAs Offline continuam no aparelho até você apagar a pasta ou excluir o modelo dentro do " +
                    "aplicativo.",
                "Como o desenvolvedor não guarda dados seus em servidores, não há conta nem dados a excluir " +
                    "do lado dele. E-mails de denúncia ou de contato que você enviar são apagados a seu pedido.",
            ),
        ),
        PrivacySection(
            "Idade",
            listOf(
                "O aplicativo é destinado a maiores de 18 anos e não é direcionado a crianças. As IAs podem " +
                    "gerar respostas incorretas ou inadequadas; confira informações importantes em outras fontes.",
            ),
        ),
        PrivacySection(
            "Seus direitos",
            listOf(
                "Nos termos da Lei Geral de Proteção de Dados (LGPD) e de leis equivalentes, você pode pedir " +
                    "informações, correção ou exclusão de dados pessoais pelo e-mail de contato. Para os dados " +
                    "tratados pelo Google nos anúncios, use também os controles do Google citados acima.",
            ),
        ),
        PrivacySection(
            "Mudanças nesta política",
            listOf(
                "Esta política pode ser atualizada quando o aplicativo mudar. A versão em vigor fica no " +
                    "aplicativo, em Configurações > Política de privacidade, e na página do aplicativo na loja.",
            ),
        ),
    )
}
