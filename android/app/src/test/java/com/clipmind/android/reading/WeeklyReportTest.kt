package com.clipmind.android.reading

import com.clipmind.android.data.LocalCard
import com.clipmind.android.knowledge.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class WeeklyReportTest {
    private fun card(id: Long,time: String) = LocalCard(id,"Exact original quotation",true,Instant.parse(time).toEpochMilli(),0,null,emptyList())
    @Test fun usesLocalMondayInclusiveAndNextMondayExclusive() {
        val now = Instant.parse("2026-09-08T08:00:00Z").toEpochMilli()
        val plan = weeklyPlan(listOf(card(1,"2026-09-06T16:00:00Z"),card(2,"2026-09-06T15:59:59Z"),card(3,"2026-09-13T16:00:00Z")),now,ZoneId.of("Asia/Shanghai"),false)
        assertEquals(listOf(1L),plan.cards.map { it.id })
        val good = KnowledgeResult("Theme",listOf(KnowledgePoint("summary","Idea",listOf(Evidence("1","original quotation")))),emptyList())
        assertTrue(validWeek(plan,good))
        assertFalse(validWeek(plan,good.copy(points=listOf(KnowledgePoint("summary","Idea",listOf(Evidence("999","original quotation")))))))
        assertFalse(validWeek(plan,good.copy(points=listOf(KnowledgePoint("agreement","Idea",listOf(Evidence("1","original quotation")))))))
    }
}
