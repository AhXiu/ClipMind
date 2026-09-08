package com.clipmind.android.reading

import com.clipmind.android.data.LocalCard
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt

enum class Recall(val score: Int, val label: String) { AGAIN(1, "未记住"), HARD(3, "困难"), GOOD(4, "记住了"), EASY(5, "容易") }

object ReviewPolicy {
    fun day(now: Long, zone: ZoneId) = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    /** SM-2 style feedback scheduling, not an alleged universal Ebbinghaus formula. */
    fun next(old: ReviewEntity, rating: Recall, now: Long, zone: ZoneId): ReviewEntity {
        val q = rating.score
        val ease = (old.ease + 0.1 - (5 - q) * (0.08 + (5 - q) * 0.02)).coerceIn(1.3, 3.5)
        val repetitions = if (q < 3) 0 else old.repetitions + 1
        val interval = when { q < 3 -> 1; old.repetitions == 0 -> 1; old.repetitions == 1 -> 6; else -> (old.intervalDays * ease).roundToInt().coerceIn(1, 365) }
        return old.copy(dueAt = day(now, zone).plusDays(interval.toLong()).atStartOfDay(zone).toInstant().toEpochMilli(), intervalDays = interval, repetitions = repetitions, ease = ease, lastReviewDay = day(now, zone).toString())
    }
    fun due(cards: List<LocalCard>, reviews: List<ReviewEntity>, limit: Int, now: Long, zone: ZoneId): List<LocalCard> {
        val rows = reviews.associateBy { it.cardId }; val today = day(now, zone).toString()
        val completed = reviews.count { it.lastReviewDay == today }
        return cards.filter { card -> card.contentAvailable && rows[card.id]?.let { it.lastReviewDay != today && (it.revision != card.contentRevision || it.dueAt <= now) } != false }
            .sortedWith(compareBy<LocalCard> { if (rows[it.id]?.revision == it.contentRevision) 0 else 1 }.thenBy { rows[it.id]?.dueAt ?: 0L }.thenBy { when(it.analysis?.value) { "high" -> 0; "low" -> 2; else -> 1 } }.thenBy { it.capturedAt })
            .take((limit.coerceIn(3, 5) - completed).coerceAtLeast(0))
    }
}
