package com.example.ialocal.runtime

import com.example.ialocal.ai.AiChatMessage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatSessionTest {
    private val session = ChatSession(
        modelId = "m1",
        key = "sistema",
        conversation = listOf(AiChatMessage("user", "Meu nome é Ana.")),
        reply = "<think>O usuário disse o nome.</think>\n\nPrazer, Ana!",
        tokens = 900,
    )

    @Test
    fun continuesWhenTheRequestIsTheSameConversationPlusTheAnswer() {
        // The app stores the answer without reasoning and with timing marks.
        val request = listOf(
            AiChatMessage("user", "Meu nome é Ana."),
            AiChatMessage("assistant", "Prazer, Ana!"),
        )
        assertTrue(session.continuesWith("m1", "sistema", request))
    }

    @Test
    fun startsOverWhenAnythingElseChanged() {
        val request = listOf(AiChatMessage("user", "Meu nome é Ana."), AiChatMessage("assistant", "Prazer, Ana!"))
        assertFalse(session.continuesWith("m2", "sistema", request))
        assertFalse(session.continuesWith("m1", "outro sistema", request))
        // The user edited an earlier message, or the answer was regenerated.
        assertFalse(session.continuesWith("m1", "sistema", listOf(AiChatMessage("user", "Meu nome é Bia."), request[1])))
        assertFalse(session.continuesWith("m1", "sistema", listOf(request[0], AiChatMessage("assistant", "Olá!"))))
        // Same request again (regenerate) or another conversation.
        assertFalse(session.continuesWith("m1", "sistema", listOf(request[0])))
    }

    @Test
    fun aLatePrefillDoesNotReplaceTheAnswerItWasPreparing() {
        val answered = listOf(AiChatMessage("user", "Meu nome é Ana."), AiChatMessage("assistant", "Prazer,  Ana!"))
        // After the answer, with the question not yet answered, or before the question was saved.
        assertTrue(session.holds("m1", "sistema", answered))
        assertTrue(session.holds("m1", "sistema", listOf(AiChatMessage("user", "Meu nome é Ana."))))
        assertTrue(session.holds("m1", "sistema", emptyList()))
        val longer = session.copy(conversation = answered + AiChatMessage("user", "E você?"), reply = "Sou uma IA.")
        assertFalse(longer.holds("m1", "sistema", emptyList()))
        // Another conversation, model or system prompt is not.
        assertFalse(session.holds("m1", "sistema", listOf(AiChatMessage("user", "Meu nome é Bia."))))
        assertFalse(session.holds("m2", "sistema", answered))
        assertFalse(session.holds("m1", "outro sistema", answered))
    }

    @Test
    fun startsOverWhenTheContextWouldOverflow() {
        assertTrue(session.hasRoomFor(latestUserChars = 300, maxOutputTokens = 2048, contextTokens = 8192))
        // 900 tokens in use + a long message + half of a 4096 context reserved for the answer.
        assertFalse(session.hasRoomFor(latestUserChars = 3000, maxOutputTokens = 8192, contextTokens = 4096))
        assertFalse(session.copy(tokens = 3500).hasRoomFor(latestUserChars = 300, maxOutputTokens = 1024, contextTokens = 4096))
    }

    @Test
    fun theTextOfAttachmentsDoesNotStopTheConversation() {
        // Sent with the whole attachment, rebuilt from the database with an excerpt of it.
        val sent = session.copy(conversation = listOf(AiChatMessage("user", "Resuma.\n\n[Contexto dos anexos]\nrelatorio.pdf: texto inteiro")))
        val request = listOf(
            AiChatMessage("user", "Resuma.\n\n[Contexto persistido dos anexos]\nrelatorio.pdf:\ntexto"),
            AiChatMessage("assistant", "Prazer, Ana!"),
        )
        assertTrue(sent.continuesWith("m1", "sistema", request))
        // The user's own text still counts.
        assertFalse(sent.continuesWith("m1", "sistema", listOf(AiChatMessage("user", "Traduza."), request[1])))
    }

    @Test
    fun turnsLeftOutOfTheContextAreSkipped() {
        val older = listOf(AiChatMessage("user", "Oi"), AiChatMessage("assistant", "Olá!"))
        val trimmed = session.copy(skipped = 2)
        val request = older + listOf(AiChatMessage("user", "Meu nome é Ana."), AiChatMessage("assistant", "Prazer, Ana!"))
        assertTrue(trimmed.continuesWith("m1", "sistema", request))
        assertFalse(session.continuesWith("m1", "sistema", request))
        assertTrue(trimmed.holds("m1", "sistema", older + AiChatMessage("user", "Meu nome é Ana.")))
    }

    @Test
    fun dropsWholeTurnsUntilAQuarterOfTheContextIsFree() {
        val turns = (1..6).flatMap { listOf(AiChatMessage("user", "p".repeat(3000)), AiChatMessage("assistant", "r".repeat(3000))) } +
            AiChatMessage("user", "última")
        val full = session.copy(conversation = turns, tokens = 7_800)
        assertFalse(full.hasRoomFor(latestUserChars = 30, maxOutputTokens = 2048, contextTokens = 8192))
        val drop = full.turnsToDrop(latestUserChars = 30, maxOutputTokens = 2048, contextTokens = 8192)
        assertTrue(drop > 0)
        assertTrue(drop % 2 == 0)
        val trimmed = full.dropped(drop, tokens = 7_800 - drop / 2 * 2_016)
        assertTrue(trimmed.hasRoomFor(latestUserChars = 30, maxOutputTokens = 2048, contextTokens = 8192))
        assertTrue(trimmed.conversation.first().role == "user")
        // The latest turn always stays.
        assertTrue(session.turnsToDrop(latestUserChars = 30, maxOutputTokens = 2048, contextTokens = 1024) == 0)
    }

    @Test
    fun continuingKeepsLessRoomForTheAnswerThanTheWholeBudget() {
        assertTrue(ChatSession.continuationReserve(8192, 32_768) == 4096)
        assertTrue(ChatSession.continuationReserve(8192, 8192) == 2048)
        assertTrue(ChatSession.continuationReserve(8192, 2048) == 1024)
        assertTrue(ChatSession.continuationReserve(512, 32_768) == 512)
    }

    @Test
    fun answersAreComparedWithoutReasoningAndWhitespace() {
        assertTrue(ChatSession.sameAnswer("Prazer,  Ana!\n\n<!--nexus_response_ms:10-->", "<think>x</think>Prazer, Ana!"))
        assertFalse(ChatSession.sameAnswer("Prazer, Ana!", "Olá, Ana!"))
    }
}
