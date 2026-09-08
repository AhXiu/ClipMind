package com.clipmind.android.reading

import org.junit.Assert.*
import org.junit.Test

class LearningSafetyTest {
    @Test fun foregroundAndBackgroundCannotAcquireThePaidTaskGateTogether() {
        assertTrue(LearningTaskGate.tryAcquire())
        try { assertFalse(LearningTaskGate.tryAcquire()) }
        finally { LearningTaskGate.release() }
        assertTrue(LearningTaskGate.tryAcquire())
        LearningTaskGate.release()
    }

    @Test fun notionChunksPreserveSurrogatePairsAndExactOriginal() {
        val source = "a".repeat(1799) + "\uD83D\uDE00" + "b".repeat(3600)
        val chunks = notionChunks(source)
        assertEquals(source, chunks.joinToString(""))
        assertTrue(chunks.all { it.length <= 1800 && !it.last().isHighSurrogate() && !it.first().isLowSurrogate() })
    }
}
