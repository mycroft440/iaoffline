package com.example.ialocal.runtime

/** How much RAM the AI may trade for speed (Settings › Desempenho da IA). */
enum class PerformanceLevel {
    /** Decided per model from the device RAM and how much of the model gains from it. */
    AUTO,
    /** Weights always read from the model file: the least RAM, the slowest reading. */
    ECONOMY,
    /** The rules used before this setting existed. */
    BALANCED,
    /** An optimized copy of the weights in RAM whenever it fits. */
    MAX,
}

/** A setting that is decided per device and model, or forced off or on. */
enum class TuningSwitch { AUTO, OFF, ON }

/**
 * The performance choices made in Settings, sent to the AI process. Each one is turned off for a
 * model that already made the AI process close for lack of memory ([ModelCrashGuard]).
 */
data class RuntimeTuning(
    val performance: PerformanceLevel = PerformanceLevel.AUTO,
    /** More memory for the conversation, so long chats are not read again from the start. */
    val longContext: TuningSwitch = TuningSwitch.AUTO,
    /**
     * "Refazer" and edits of the last message continue the loaded context instead of reading the
     * conversation again; models with recurrent layers need a copy of their state for it.
     */
    val rewind: TuningSwitch = TuningSwitch.AUTO,
    /** Larger micro-batches when a mixture of experts reads a long message; ON is the maximum. */
    val longPromptMoe: TuningSwitch = TuningSwitch.AUTO,
    /** Conversations kept on storage, ready to continue without being read again. */
    val readyConversations: Int = DEFAULT_READY_CONVERSATIONS,
    /** Models whose optimized copy of the weights made the AI process close; they load without it. */
    val performanceBlocked: Set<String> = emptySet(),
) {
    companion object {
        const val DEFAULT_READY_CONVERSATIONS = 5
        val READY_CONVERSATION_CHOICES = listOf(1, 3, 5, 10)
    }
}
