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
 *
 * The system part only changes once a day, so the next message of a conversation can continue
 * the loaded context; the excerpts go with the message they answer.
 */
class AnswerGuidance(
    private val wikipedia: OfflineWikipedia? = null,
    private val clock: () -> ZonedDateTime = { ZonedDateTime.now() },
) {
    fun systemSuffix(): String = baseSuffix(clock())

    /** Excerpts for the latest user message, or "" when there is nothing to add. */
    suspend fun turnSuffix(messages: List<AiChatMessage>): String {
        val question = messages.lastOrNull { it.role.equals("user", ignoreCase = true) }?.content ?: return ""
        return wikipedia?.contextFor(question).orEmpty()
    }

    companion object {
        private val DATE_FORMAT = DateTimeFormatter.ofPattern(
            "EEEE, d 'de' MMMM 'de' yyyy",
            Locale.forLanguageTag("pt-BR"),
        )

        fun baseSuffix(now: ZonedDateTime): String = buildString {
            append("\n\nData de hoje no aparelho: ").append(DATE_FORMAT.format(now)).append(". ")
            append("Para a hora exata, use a ferramenta current_time.")
            append("\nBoas práticas: responda no idioma do usuário. Se não souber um fato ou não tiver certeza, ")
            append("diga isso em vez de inventar nomes, datas ou números. Para contas, use a ferramenta calculate. ")
            append("Em problemas de várias etapas, resolva passo a passo e confira o resultado antes da resposta final.")
        }
    }
}
