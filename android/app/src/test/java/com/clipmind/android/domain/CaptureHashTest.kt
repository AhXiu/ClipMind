package com.clipmind.android.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureHashTest {
    @Test fun normalizationMakesEquivalentTextHashEqual() {
        assertEquals(CaptureHash.sha256(" hello   world\n"), CaptureHash.sha256("hello world"))
    }
    @Test fun differentTextHasDifferentHash() = assertNotEquals(CaptureHash.sha256("abcdefgh"), CaptureHash.sha256("abcdefgi"))
    @Test fun deduplicatesWithinWindowOnly() {
        val dedup = RecentHashDeduplicator(1000)
        assertFalse(dedup.isDuplicate("a", 1000))
        assertTrue(dedup.isDuplicate("a", 1500))
        assertFalse(dedup.isDuplicate("a", 2600))
    }
}
