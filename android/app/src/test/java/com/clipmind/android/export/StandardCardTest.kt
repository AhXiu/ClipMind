package com.clipmind.android.export

import com.clipmind.android.data.*
import com.clipmind.android.network.dto.*
import com.clipmind.android.reading.BookAttribution
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import org.junit.Assert.*
import org.junit.Test

class StandardCardTest {
    private val original = "  Original {{tags}}\n```\nquoted passage with at least twenty characters\n  "
    private val analysis = ClientAnalysis("ark", "model", "认知", AnalysisInterpretation("meaning", "application", "reflection"), listOf(AnalysisBook("Real book", "Author", true, "/works/OL1W", "semantic", "reason")), schemaVersion = 2)
    private val card = LocalCard(1, original, true, 0, 0, null, emptyList(), analysis = analysis)
    private val preferences = ExportPreferences("{{standard_card}}", WikiLinkFormat.FILE_NAME, false, false, false)

    @Test fun standardTemplatePreservesLiteralSourceAndRequiresCurrentBookEvidence() {
        val rendered = renderMarkdown(card, "", preferences)
        assertTrue(rendered.contains("````text\n$original\n````"))
        assertTrue(rendered.contains("1. 核心释义：meaning"))
        assertTrue(rendered.contains("2. 场景应用：application"))
        assertTrue(rendered.contains("3. 认知启发：reflection"))
        assertFalse(rendered.contains("高确定"))
        val proof = BookAttribution(1, 1, "/works/OL1W", "page 12", "quoted passage with at least twenty characters", 0)
        assertTrue(standardCard(card.copy(bookSources = listOf(proof)), "").contains("高确定（人工出处核对）"))
        assertFalse(standardCard(card.copy(contentRevision = 2, bookSources = listOf(proof)), "").contains("高确定"))
        assertFalse(standardCard(card.copy(analysis = analysis.copy(books = listOf(AnalysisBook("Invented", "Unknown")))), "").contains("Invented"))
    }

    @Test fun placeholdersWithinSourceAreNotRecursivelyExpanded() {
        val output = renderMarkdown(card, "RELATION", preferences.copy(markdownTemplate = "{{content}}\n{{wikilinks}}"))
        assertTrue(output.startsWith(original))
        assertTrue(output.contains("{{tags}}"))
        assertTrue(output.endsWith("RELATION\n"))
    }
    @Test fun articleReadingValueAndEvidenceSurviveExportWithEscaping() {
        val article = ReadingArticle("Article", "https://sspai.com/post/1", "Summary", "today", "extends", "[unsafe](link)", "Article evidence with context.", "Original source evidence.")
        val rendered = standardCard(card.copy(analysis = analysis.copy(articles = listOf(article))), "")
        assertTrue(rendered.contains("阅读价值（AI 推论，补充）"))
        assertTrue(rendered.contains("\\[unsafe\\]"))
        assertTrue(rendered.contains("摘抄依据：Original source evidence."))
        assertTrue(rendered.contains("文章依据：Article evidence with context."))
    }

    @Test fun zipIncludesIndependentRawAndWeeklyDocuments() {
        val documents = listOf(MarkdownFile("raw/capture.json", "{\"text\":\"original\"}"), MarkdownFile("reflections/weekly.md", "# Weekly"))
        val output = ByteArrayOutputStream()
        LocalExportService().write(ExportFormat.OBSIDIAN_ZIP, ExportSnapshot(listOf(card), emptyList(), documents), preferences, output)
        val actual = mutableMapOf<String, String>()
        ZipInputStream(output.toByteArray().inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                actual[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
            }
        }
        documents.forEach { assertEquals(it.content, actual[it.fileName]) }
        assertEquals(3, actual.size)
    }
}
