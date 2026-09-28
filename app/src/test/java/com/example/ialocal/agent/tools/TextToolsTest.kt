package com.example.ialocal.agent.tools

import com.example.ialocal.files.TextFiles
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** Tool calls as a 9B model (Qwen3.5 9B Q3_K_M) actually wrote them, applied to the files. */
class TextToolsTest {
    private fun arguments(answer: String): JSONObject =
        JSONObject(requireNotNull(ToolCallText.jsonObject(answer))).getJSONObject("arguments")

    private fun edit(text: String, answer: String): String =
        TextFiles.edit(text, TextTools.parseOperations(arguments(answer).optJSONArray("operations"))).text

    @Test
    fun replaceAndAppend() {
        val answer = """{"tool":"txt_edit","arguments":{"id":"7f3c2a","file_name":"compras editado.txt","operations":[{"op":"replace","find":"café","replace":"chá"},{"op":"append","text":"açúcar"}]}}"""
        assertEquals("arroz\nfeijão\nchá\nleite\naçúcar\n", edit("arroz\nfeijão\ncafé\nleite\n", answer))
    }

    @Test
    fun deleteALineAfterASentence() {
        val answer = "Para remover essa linha, vou usar a ferramenta `txt_edit` com a operação `delete_lines` especificando a linha 3.\n\n" +
            """{"tool":"txt_edit","arguments":{"id":"5e6f70","file_name":"tarefas_editado.txt","operations":[{"op":"delete_lines","lines":"3"}]}}"""
        val tasks = "Comprar pão\nPagar a conta de luz\nLigar para o Pedro sobre o orçamento\nRevisar o contrato\nEnviar relatório\nMarcar dentista\n"
        assertEquals("Comprar pão\nPagar a conta de luz\nRevisar o contrato\nEnviar relatório\nMarcar dentista\n", edit(tasks, answer))
    }

    @Test
    fun createWithEscapedLineBreaks() {
        val answer = """{"tool":"txt_create","arguments":{"file_name":"anotações da reunião.txt","content":"orçamento\nprazos\nequipe"}}"""
        assertEquals("orçamento\nprazos\nequipe\n", TextFiles.created(arguments(answer).getString("content")))
    }

    @Test
    fun numbersAreAcceptedForLinesAndMissingArgumentsExplainThemselves() {
        val operations = JSONObject("""{"operations":[{"op":"delete_lines","lines":2},{"op":"insert_lines","after_line":"0","text":"topo"}]}""")
        assertEquals("topo\na\nc\n", TextFiles.edit("a\nb\nc\n", TextTools.parseOperations(operations.optJSONArray("operations"))).text)
        val error = assertThrows(IllegalArgumentException::class.java) {
            TextTools.parseOperations(JSONObject("""{"operations":[{"op":"insert_lines","text":"x"}]}""").optJSONArray("operations"))
        }
        assertEquals("Em insert_lines, informe 'after_line' (0 para o início).", error.message)
    }
}
