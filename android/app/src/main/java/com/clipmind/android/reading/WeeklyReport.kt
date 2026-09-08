package com.clipmind.android.reading

import com.clipmind.android.data.LocalCard
import com.clipmind.android.knowledge.KnowledgeCard
import com.clipmind.android.knowledge.KnowledgeResult
import com.clipmind.android.knowledge.KnowledgeFailure
import com.clipmind.android.network.CaptureApi
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

data class WeeklyRequest(val cards: List<KnowledgeCard>)
data class WeeklyPlan(val start: LocalDate, val end: LocalDate, val cards: List<LocalCard>, val request: WeeklyRequest)
fun weeklyPlan(cards: List<LocalCard>, now: Long, zone: ZoneId, previous: Boolean): WeeklyPlan {
    val monday = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(if (previous) 1 else 0)
    val end = monday.plusWeeks(1)
    val chosen = cards.filter { it.contentAvailable && it.capturedAt >= monday.atStartOfDay(zone).toInstant().toEpochMilli() && it.capturedAt < end.atStartOfDay(zone).toInstant().toEpochMilli() }
    if (chosen.any { !ReadingContract.safeText(it.content) } || chosen.sumOf { it.content.codePointCount(0,it.content.length) } > 16000) throw KnowledgeFailure("WEEK_OVER_16000_OR_UNSAFE")
    return WeeklyPlan(monday,end,chosen,WeeklyRequest(chosen.map { KnowledgeCard(it.id.toString(),it.contentRevision,it.content) }))
}
fun escapeNote(text: String): String = text.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("[","\\[").replace("]","\\]").replace("#","\\#").replace("`","\\`").replace("\n"," ")
fun validWeek(plan: WeeklyPlan, result: KnowledgeResult): Boolean = runCatching {
    require(result.title.isNotBlank() && result.title.length <= 160 && result.points.size in 1..16 && result.relations.isEmpty())
    val sources = plan.request.cards.associate { it.id to it.text }
    result.points.forEach { point ->
        require(point.kind in setOf("summary","agreement","difference","question") && point.text.isNotBlank() && point.text.length <= 2400 && point.evidence.size in 1..8)
        require(point.evidence.map { it.cardId }.distinct().size == point.evidence.size)
        if (point.kind in setOf("agreement","difference")) require(point.evidence.size >= 2)
        point.evidence.forEach { require(it.quote.isNotBlank() && it.quote.length <= 800 && sources[it.cardId]?.contains(it.quote) == true) }
    }; true
}.getOrDefault(false)
suspend fun generateWeek(api: CaptureApi, auth: String?, plan: WeeklyPlan): String {
    val header = buildString {
        append("# 知识周报｜${plan.start}\n\n统计范围：${plan.start} 至 ${plan.end}（不含）\n\n## 摘抄统计\n共 ${plan.cards.size} 张卡片。\n\n## 高频主题\n")
        plan.cards.flatMap { it.tags.filter { tag -> tag.status == "confirmed" }.map { tag -> tag.name } }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(10).forEach { append("- ${escapeNote(it.key)}：${it.value}\n") }
    }
    if (plan.cards.isEmpty()) return header + "\n本周没有摘抄，不生成阅读主线。\n"
    val response = api.weeklyReading(auth, plan.request)
    val result = response.body()
    if (!response.isSuccessful || result == null || !validWeek(plan,result)) throw KnowledgeFailure("WEEKLY_PROVIDER_OR_CITATIONS_FAILED")
    return header + buildString {
        append("\n## 阅读思考主线\n${escapeNote(result.title)}\n\n## 本周核心观点\n")
        result.points.forEach { point ->
            append("- ${escapeNote(point.text)}\n")
            point.evidence.forEach { e -> append("  > 卡片 ${e.cardId} v${plan.request.cards.first { it.id == e.cardId }.revision}：${escapeNote(e.quote)}\n") }
        }
        append("\n模型归纳需核对。引用为生成时历史快照，删除原卡片不会删除此周报副本。\n")
    }
}
