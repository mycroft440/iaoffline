package com.example.ialocal.ui.chat

import com.example.ialocal.runtime.GenerationStage
import com.example.ialocal.runtime.GenerationStage.Kind
import java.util.Locale

/**
 * What the chat says about a step of the answer: while it runs ("Carregando Gemma 4 E4B na
 * memória (3,8 GB)") and, once the next one started, what it did ("Gemma 4 E4B carregada").
 */
internal fun stageText(stage: GenerationStage, finished: Boolean): String {
    val detail = stage.detail.orEmpty()
    return when (stage.kind) {
        Kind.QUEUED ->
            if (finished) "Esperou outras conversas serem respondidas"
            else "Na fila: aguardando outras conversas serem respondidas"
        Kind.VERIFYING ->
            if (finished) "${modelOr(detail)} testada no aparelho"
            else "Testando ${modelOr(detail)} no aparelho antes do primeiro uso"
        Kind.STARTING_AI ->
            if (finished) "Processo da IA iniciado" else "Iniciando o processo da IA"
        Kind.WAITING_FOR_AI -> when (detail) {
            GenerationStage.BUSY_PREFILL ->
                if (finished) "Preparação feita enquanto você escrevia concluída"
                else "Terminando a preparação feita enquanto você escrevia"
            GenerationStage.BUSY_VERIFY ->
                if (finished) "Teste de outra IA concluído" else "Aguardando o teste de outra IA terminar"
            GenerationStage.BUSY_LOAD ->
                if (finished) "Carregamento anterior concluído" else "Aguardando a IA terminar de carregar um modelo"
            else ->
                if (finished) "Resposta de outra conversa concluída"
                else "Aguardando a IA terminar a resposta de outra conversa"
        }
        Kind.LOADING_MODEL ->
            if (finished) "${modelOr(detail)} carregada na memória"
            else "Carregando ${modelOr(detail)} na memória" + stage.total.takeIf { it > 0 }?.let { " (${formatMegabytes(it)})" }.orEmpty()
        Kind.RESTORING_INSTRUCTIONS ->
            if (finished) "Instruções do perfil restauradas" else "Restaurando as instruções do perfil já processadas"
        Kind.READING_INSTRUCTIONS ->
            if (finished) "Instruções do perfil lidas e salvas"
            else "Lendo as instruções do perfil pela primeira vez neste modelo"
        Kind.READING_HISTORY ->
            if (finished) "Conversa lida (${messagesText(stage.total)})"
            else "Lendo a conversa: mensagem ${stage.done} de ${stage.total}"
        Kind.CONTEXT_REUSED ->
            if (detail == GenerationStage.REUSED_PREFILL) "Conversa já lida enquanto você escrevia"
            else "Conversa já está na memória da IA"
        Kind.CONSULTING_WIKIPEDIA ->
            if (finished) "Wikipédia offline consultada" else "Consultando a Wikipédia offline"
        Kind.READING_MESSAGE -> when {
            detail == GenerationStage.MESSAGE_TOOL_RESULT ->
                if (finished) "Resultado da ferramenta lido" else "Lendo o resultado da ferramenta"
            finished -> "Mensagem lida"
            else -> "Lendo a sua mensagem"
        }
        Kind.WRITING ->
            if (finished) "Resposta escrita (${tokensText(stage.done)})"
            else "Escrevendo a resposta: ${tokensText(stage.done)}"
        Kind.USING_TOOL ->
            if (finished) "Ferramenta usada: ${toolName(detail)}" else "Usando a ferramenta: ${toolName(detail)}"
        Kind.REASONING -> if (finished) "Raciocinou" else "Raciocinando"
        // Kept with the reply instead of listed as a step; worded like one all the same.
        Kind.ANSWER_SPEED -> "Resposta escrita (${tokensText(stage.done)})"
    }
}

private fun modelOr(name: String) = name.ifBlank { "a IA" }

private fun messagesText(count: Int) = if (count == 1) "1 mensagem" else "$count mensagens"

private fun tokensText(count: Int) =
    if (count == 1) "1 token" else String.format(Locale.getDefault(), "%,d tokens", count)

private fun formatMegabytes(megabytes: Int): String =
    if (megabytes >= 1024) String.format(Locale.getDefault(), "%.1f GB", megabytes / 1024.0)
    else "$megabytes MB"

private fun toolName(name: String): String = when (name) {
    "calculate" -> "calculadora"
    "current_time" -> "data e hora"
    "wikipedia_search" -> "busca na Wikipédia"
    "wikipedia_read" -> "leitura da Wikipédia"
    "list_recent_conversations" -> "conversas recentes"
    "search_conversations" -> "busca nas conversas"
    "read_conversation" -> "leitura de conversa"
    else -> name.ifBlank { "ferramenta" }
}
