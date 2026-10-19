package com.example.ialocal.chat

import com.example.ialocal.runtime.GenerationStage

/** One step of an answer: when it started and, once the next one began, when it ended. */
data class ProgressStep(
    val stage: GenerationStage,
    val startedAt: Long,
    val endedAt: Long? = null,
) {
    fun durationMs(now: Long): Long = ((endedAt ?: now) - startedAt).coerceAtLeast(0L)
}

/**
 * The steps an answer went through before its text showed up, the current one last. A report of
 * the same step (the next turn read, more tokens written) updates it instead of adding another.
 */
data class GenerationProgress(
    val steps: List<ProgressStep> = emptyList(),
    /** When the first step started, kept for the total wait after old steps are dropped. */
    val startedAt: Long? = null,
) {
    val current: ProgressStep? get() = steps.lastOrNull()
    val finished: List<ProgressStep> get() = steps.dropLast(1)

    fun advance(stage: GenerationStage, now: Long): GenerationProgress {
        val last = steps.lastOrNull()
        if (last != null && last.stage.kind == stage.kind && last.stage.detail == stage.detail) {
            return copy(steps = steps.dropLast(1) + last.copy(stage = stage))
        }
        val closed = steps.map { if (it.endedAt == null) it.copy(endedAt = now) else it }
        return GenerationProgress(
            steps = (closed + ProgressStep(stage, now)).takeLast(MAX_STEPS),
            startedAt = startedAt ?: now,
        )
    }

    companion object {
        /** The chat shows the latest steps; older ones are dropped. */
        const val MAX_STEPS = 8
    }
}
