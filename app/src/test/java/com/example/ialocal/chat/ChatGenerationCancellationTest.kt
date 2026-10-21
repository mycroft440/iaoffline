package com.example.ialocal.chat

import com.example.ialocal.runtime.GenerationStage.Kind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatGenerationCancellationTest {
    @Test
    fun stopBeforeFirstTokenCanAbortNativePreparation() {
        listOf(
            null,
            Kind.QUEUED,
            Kind.STARTING_AI,
            Kind.VERIFYING,
            Kind.WAITING_FOR_AI,
            Kind.LOADING_MODEL,
            Kind.RESTORING_INSTRUCTIONS,
            Kind.READING_INSTRUCTIONS,
            Kind.READING_HISTORY,
            Kind.CONTEXT_REUSED,
            Kind.CONSULTING_WIKIPEDIA,
            Kind.READING_MESSAGE,
            Kind.USING_TOOL,
        ).forEach { stage ->
            assertTrue("Stopping during $stage should interrupt blocking work", shouldInterruptNativeWork(true, stage))
        }
    }

    @Test
    fun streamingAnswerUsesExistingCooperativeCancellation() {
        assertFalse(shouldInterruptNativeWork(true, Kind.WRITING))
        assertFalse(shouldInterruptNativeWork(true, Kind.REASONING))
    }

    @Test
    fun idleConversationMustNotStopSharedAiProcess() {
        Kind.entries.forEach { stage ->
            assertFalse(shouldInterruptNativeWork(false, stage))
        }
        assertFalse(shouldInterruptNativeWork(false, null))
    }
}
