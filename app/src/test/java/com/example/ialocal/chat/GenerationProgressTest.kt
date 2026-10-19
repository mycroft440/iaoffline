package com.example.ialocal.chat

import com.example.ialocal.runtime.GenerationStage
import com.example.ialocal.runtime.GenerationStage.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GenerationProgressTest {
    @Test
    fun aNewStepEndsTheCurrentOne() {
        val progress = GenerationProgress()
            .advance(GenerationStage(Kind.STARTING_AI), now = 1_000)
            .advance(GenerationStage(Kind.LOADING_MODEL, "Gemma 4 E4B", total = 3_870), now = 1_800)

        assertEquals(listOf(Kind.STARTING_AI), progress.finished.map { it.stage.kind })
        assertEquals(800L, progress.finished.single().durationMs(now = 9_999))
        assertEquals(Kind.LOADING_MODEL, progress.current?.stage?.kind)
        assertNull(progress.current?.endedAt)
        assertEquals(2_200L, progress.current?.durationMs(now = 4_000))
        assertEquals(1_000L, progress.startedAt)
    }

    @Test
    fun progressWithinAStepUpdatesItInPlace() {
        val progress = GenerationProgress()
            .advance(GenerationStage(Kind.READING_HISTORY, done = 1, total = 3), now = 100)
            .advance(GenerationStage(Kind.READING_HISTORY, done = 2, total = 3), now = 400)
            .advance(GenerationStage(Kind.READING_HISTORY, done = 3, total = 3), now = 700)

        assertEquals(1, progress.steps.size)
        assertEquals(3, progress.current?.stage?.done)
        // The step keeps the time it started, so its duration covers every turn read.
        assertEquals(100L, progress.current?.startedAt)
    }

    @Test
    fun theSameKindWithAnotherDetailIsAnotherStep() {
        val progress = GenerationProgress()
            .advance(GenerationStage(Kind.USING_TOOL, "calculate"), now = 0)
            .advance(GenerationStage(Kind.USING_TOOL, "current_time"), now = 50)

        assertEquals(listOf("calculate", "current_time"), progress.steps.map { it.stage.detail })
    }

    @Test
    fun onlyTheLatestStepsAreKeptButTheTotalCountsFromTheFirst() {
        var progress = GenerationProgress()
        repeat(GenerationProgress.MAX_STEPS + 3) { index ->
            progress = progress.advance(GenerationStage(Kind.USING_TOOL, "tool$index"), now = index * 10L)
        }

        assertEquals(GenerationProgress.MAX_STEPS, progress.steps.size)
        assertEquals("tool${GenerationProgress.MAX_STEPS + 2}", progress.current?.stage?.detail)
        assertEquals(0L, progress.startedAt)
    }
}
