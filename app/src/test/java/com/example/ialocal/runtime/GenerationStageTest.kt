package com.example.ialocal.runtime

import com.example.ialocal.runtime.GenerationStage.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GenerationStageTest {
    @Test
    fun everyStageCrossesTheProcessBoundaryUnchanged() {
        Kind.entries.forEach { kind ->
            val stage = GenerationStage(kind, detail = "Gemma 4 E4B", done = 2, total = 5)
            assertEquals(stage, GenerationStage.decode(stage.encode()))
        }
        val withoutDetail = GenerationStage(Kind.READING_MESSAGE)
        assertEquals(withoutDetail, GenerationStage.decode(withoutDetail.encode()))
    }

    @Test
    fun unknownStagesAreIgnored() {
        assertNull(GenerationStage.decode("""{"kind":"SOMETHING_NEW"}"""))
        assertNull(GenerationStage.decode("not json"))
    }
}
