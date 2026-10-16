package com.example.ialocal.agent

import com.example.ialocal.ai.AiChatMessage
import com.example.ialocal.knowledge.OfflineWikipedia
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Cheap ways to make a small local model answer better without a bigger model, more memory or
 * extra generations: the current date (models only know their training cutoff), a few rules that
 * curb the usual mistakes of small models (inventing facts, doing arithmetic in their head) and
 * short excerpts of the offline Wikipedia articles the question names, so facts come from the
 * encyclopedia instead of from the weights. It adds a few hundred prompt tokens at most.
 */
class AnswerGuidance(
    private val wikipedia: OfflineWikipedia? = null,
    private val clock: () -> ZonedDateTime = { ZonedDateTime.now() },
) {
    suspend fun systemSuffix(messages: List<AiChatMessage>): String {
        val question = messages.lastOrNull { it.role.equals("user", ignoreCase = true) }?.content
        val knowledge = question?.let { wikipedia?.contextFor(it) }.orEmpty()
        return baseSuffix(clock()) + knowledge
    }

    companion object {
        private val DATE_FORMAT = DateTimeFormatter.ofPattern(
            "EEEE, d 'de' MMMM 'de' yyyy, HH:mm",
            Locale.forLanguageTag("pt-BR"),
        )

        fun baseSuffix(now: ZonedDateTime): String = buildString {
            append("\n\nData e hora atuais do aparelho: ").append(DATE_FORMAT.format(now)).append('.')
            append("\nBoas práticas: responda no idioma do usuário. Se não souber um fato ou não tiver certeza, ")
            append("diga isso em vez de inventar nomes, datas ou números. Para contas, use a ferramenta calculate. ")
            append("Em problemas de várias etapas, resolva passo a passo e confira o resultado antes da resposta final.")
        }
    }
}
