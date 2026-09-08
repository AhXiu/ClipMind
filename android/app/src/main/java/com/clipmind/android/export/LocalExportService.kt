package com.clipmind.android.export

import com.clipmind.android.data.CardRelationEntity
import com.clipmind.android.data.ExportPreferences
import com.clipmind.android.data.LocalCard
import com.clipmind.android.data.WikiLinkFormat
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class ExportSnapshot(val cards: List<LocalCard>, val confirmedRelations: List<CardRelationEntity>, val documents: List<MarkdownFile> = emptyList(), val relationEvidence: Map<Long,com.clipmind.android.knowledge.RelationEvidence> = emptyMap())
enum class ExportFormat(val mimeType: String, val extension: String) {
    OBSIDIAN_ZIP("application/zip", "zip"), CSV("text/csv", "csv"), PLAIN_TEXT("text/plain", "txt")
}

data class MarkdownFile(val fileName: String, val content: String)

class LocalExportService {
    fun write(format: ExportFormat, snapshot: ExportSnapshot, preferences: ExportPreferences, output: OutputStream) {
        when (format) {
            ExportFormat.OBSIDIAN_ZIP -> writeZip(snapshot, preferences, output)
            ExportFormat.CSV -> output.write(renderCsv(snapshot.cards).toByteArray(StandardCharsets.UTF_8))
            ExportFormat.PLAIN_TEXT -> output.write(renderPlainText(snapshot.cards).toByteArray(StandardCharsets.UTF_8))
        }
    }

    fun markdownFiles(snapshot: ExportSnapshot, preferences: ExportPreferences): List<MarkdownFile> {
        val names = uniqueFileNames(snapshot.cards.map { safeFileName(titleFor(it)) })
        val namesById = snapshot.cards.mapIndexed { index, card -> card.id to names[index] }.toMap()
        return snapshot.cards.mapIndexed { index, card ->
            val links = snapshot.confirmedRelations.asSequence()
                .filter { it.status == com.clipmind.android.data.RelationStatus.CONFIRMED }
                .filter { it.sourceCardId == card.id || it.targetCardId == card.id }
                .mapNotNull { relation ->
                    val otherId = if (relation.sourceCardId == card.id) relation.targetCardId else relation.sourceCardId
                    namesById[otherId]?.let { target ->
                        val targetCard = snapshot.cards.first { it.id == otherId }
                        val link = when (preferences.wikiLinkFormat) {
                            WikiLinkFormat.FILE_NAME -> "[[$target]]"
                            WikiLinkFormat.FILE_NAME_WITH_TITLE -> "[[$target|${escapeWikiAlias(titleFor(targetCard))}]]"
                        }
                        val group = when(relation.relationType) { "supports" -> "相似观点"; "contradicts", "extends", "example" -> "对立／互补视角"; else -> "同主题" }
                        val evidence = snapshot.relationEvidence[relation.id]
                        val summary = if (relation.sourceCardId == card.id) evidence?.targetSummary else evidence?.sourceSummary
                        "$group：$link · 卡片 $otherId · 摘要：${escapeMarkdownInline(summary ?: titleFor(targetCard))} · ${escapeMarkdownInline(evidence?.reason.orEmpty())}"
                    }
                }.distinct().joinToString("\n")
            MarkdownFile("${names[index]}.md", renderMarkdown(card, links, preferences))
        }
    }

    private fun writeZip(snapshot: ExportSnapshot, preferences: ExportPreferences, output: OutputStream) {
        ZipOutputStream(output.buffered()).use { zip ->
            (markdownFiles(snapshot, preferences) + snapshot.documents).forEach { file ->
                zip.putNextEntry(ZipEntry(file.fileName))
                zip.write(file.content.toByteArray(StandardCharsets.UTF_8))
                zip.closeEntry()
            }
        }
    }
}

internal fun renderMarkdown(card: LocalCard, wikiLinks: String, preferences: ExportPreferences): String {
    val frontmatter = buildList {
        if (preferences.includeTags) add("tags: [${card.tags.joinToString(", ") { yamlQuote(it.name) }}]")
        if (preferences.includeTime) add("captured_at: ${yamlQuote(Instant.ofEpochMilli(card.capturedAt).toString())}")
        if (preferences.includeSource) {
            add("source_app: ${yamlQuote(card.sourceApp.orEmpty())}")
            card.sourceUrl?.let { add("source_url: ${yamlQuote(it)}") }
        }
    }.takeIf { it.isNotEmpty() }?.joinToString("\n", prefix = "---\n", postfix = "\n---\n\n").orEmpty()
    val replacements = mapOf("standard_card" to standardCard(card,wikiLinks), "title" to escapeMarkdownInline(titleFor(card)), "content" to card.content,
        "wikilinks" to wikiLinks,"captured_at" to Instant.ofEpochMilli(card.capturedAt).toString(),"source_app" to card.sourceApp.orEmpty(),"source_url" to card.sourceUrl.orEmpty(),"tags" to card.tags.joinToString(", ") { it.name })
    val body = Regex("\\{\\{([a-z_]+)\\}\\}").replace(preferences.markdownTemplate) { match -> replacements[match.groupValues[1]] ?: match.value }
    return frontmatter + body.trimEnd() + "\n"
}

internal fun safeFileName(value: String): String {
    val cleaned = value.replace(Regex("[\\\\/:*?\"<>|\\p{Cc}]"), "-")
        .replace(Regex("\\s+"), " ").trim().trim('.').take(80).trim().trim('.')
    val candidate = cleaned.ifBlank { "untitled" }
    return if (candidate.uppercase(Locale.ROOT) in WINDOWS_RESERVED_NAMES) "_$candidate" else candidate
}

internal fun uniqueFileNames(baseNames: List<String>): List<String> {
    val used = mutableSetOf<String>()
    return baseNames.map { base ->
        var candidate = base; var suffix = 2
        while (!used.add(candidate.lowercase(Locale.ROOT))) candidate = "$base ($suffix)".also { suffix++ }
        candidate
    }
}

internal fun yamlQuote(value: String): String = "\"" + value
    .replace("\\", "\\\\").replace("\"", "\\\"")
    .replace("\r", "\\r").replace("\n", "\\n") + "\""

internal fun csvEscape(value: String): String = "\"${value.replace("\"", "\"\"")}\""
internal fun escapeMarkdownInline(value: String): String = buildString {
    value.replace("\n", " ").forEach { character ->
        if (character in "\\`*_{}[]<>#|" ) append('\\')
        append(character)
    }
}
internal fun renderCsv(cards: List<LocalCard>): String = buildString {
    append("id,content,tags,captured_at,source_app,source_url\r\n")
    cards.forEach { card ->
        append(listOf(card.id.toString(), card.content, card.tags.joinToString(";") { it.name }, Instant.ofEpochMilli(card.capturedAt).toString(), card.sourceApp.orEmpty(), card.sourceUrl.orEmpty()).joinToString(",", transform = ::csvEscape))
        append("\r\n")
    }
}

internal fun renderPlainText(cards: List<LocalCard>): String = cards.joinToString("\n\n---\n\n", postfix = if (cards.isEmpty()) "" else "\n") { card ->
    "${titleFor(card)}\n${Instant.ofEpochMilli(card.capturedAt)}\n${card.content}"
}

private fun titleFor(card: LocalCard): String = card.content.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(80) ?: "card-${card.id}"
private fun escapeWikiAlias(value: String) = value.replace("|", "\\|").replace("]", "\\]").replace("\n", " ")
private val WINDOWS_RESERVED_NAMES = (listOf("CON", "PRN", "AUX", "NUL") + (1..9).map { "COM$it" } + (1..9).map { "LPT$it" }).toSet()
