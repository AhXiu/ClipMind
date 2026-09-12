package com.clipmind.android.reading

import com.clipmind.android.data.LocalCard
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class ReviewPolicyTest {
    @Test fun skippingTodayDoesNotCountAsRecallOrChangeLearningParameters() {
        val old = ReviewEntity(1, 1, now, 6, 2, 2.3, "2026-09-01")
        val skipped = ReviewPolicy.skip(old, now, zone)
        assertEquals(old.copy(dueAt = skipped.dueAt), skipped)
        assertTrue(ReviewPolicy.due(listOf(card(1)), listOf(skipped), 3, now, zone).isEmpty())
        assertEquals(1, ReviewPolicy.due(listOf(card(1)), listOf(skipped), 3, skipped.dueAt, zone).size)
        assertEquals(3, ReviewPolicy.due((1L..10L).map { card(it) }, listOf(skipped), 3, now, zone).size)
    }

    @Test fun skippingUsesCalendarDayAcrossDaylightSaving() {
        val midnight = Instant.parse("2026-03-08T05:00:00Z").toEpochMilli()
        assertEquals(23 * 60 * 60 * 1000L, ReviewPolicy.skip(ReviewEntity(1, 1, midnight), midnight, ZoneId.of("America/New_York")).dueAt - midnight)
    }
    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = Instant.parse("2026-09-08T08:00:00Z").toEpochMilli()
    private fun card(id: Long, revision: Long = 1) = LocalCard(id,"Some source text",true,id,now,null,emptyList(),contentRevision=revision)
    @Test fun goodRecallUsesOneThenSixDaysAndFeedbackChangesEase() {
        val first = ReviewPolicy.next(ReviewEntity(1,1,now),Recall.GOOD,now,zone)
        assertEquals(1,first.intervalDays); assertEquals(1,first.repetitions)
        val second = ReviewPolicy.next(first,Recall.GOOD,first.dueAt,zone)
        assertEquals(6,second.intervalDays)
        assertEquals("2026-09-08",first.lastReviewDay)
        val failed = ReviewPolicy.next(second,Recall.AGAIN,second.dueAt,zone)
        assertEquals(0,failed.repetitions); assertEquals(1,failed.intervalDays)
        assertTrue(failed.ease < second.ease)
    }
    @Test fun dailyBudgetIncludesCompletedReviewsAndAvoidsDuplicateCard() {
        val reviews = listOf(ReviewEntity(1,1,now,lastReviewDay="2026-09-08"),ReviewEntity(2,1,now,lastReviewDay="2026-09-08"))
        val due = ReviewPolicy.due((1L..20).map { card(it) },reviews,5,now,zone)
        assertEquals(3,due.size); assertTrue(due.none { it.id <= 2 })
    }
    @Test fun overdueCardsPrecedeNewAndEditsInvalidateFutureSchedule() {
        val reviews = listOf(ReviewEntity(99,1,now-1),ReviewEntity(100,1,now+100000000))
        val due = ReviewPolicy.due((1L..10).map { card(it) } + card(99) + card(100,2),reviews,5,now,zone)
        assertEquals(99L,due.first().id)
        assertEquals(1,ReviewPolicy.due(listOf(card(100,2)),reviews,5,now,zone).size)
        assertTrue(ReviewPolicy.due(listOf(card(100)),reviews,5,now,zone).isEmpty())
    }
    @Test fun nextDayIsCalendarBasedAcrossDaylightSaving() {
        val ny = ZoneId.of("America/New_York")
        val midnight = Instant.parse("2026-03-08T05:00:00Z").toEpochMilli()
        val next = ReviewPolicy.next(ReviewEntity(1,1,midnight),Recall.GOOD,midnight,ny)
        assertEquals(23*60*60*1000L,next.dueAt-midnight)
    }
}
