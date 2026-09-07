package com.clipmind.android.export

import com.clipmind.android.data.*
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import org.junit.Assert.*
import org.junit.Test

class LocalExportServiceTest {
    private val preferences = ExportPreferences("# {{title}}\n{{content}}\n{{wikilinks}}", WikiLinkFormat.FILE_NAME_WITH_TITLE, true, true, true)

    @Test fun safeNamesHandleIllegalReservedDuplicateAndBlankValues() {
        assertEquals("a-b-c-d-e-f-g-h-i", safeFileName("a/b:c*d?e\"f<g>h|i"))
        assertEquals("_CON", safeFileName("CON"))
        assertEquals("untitled", safeFileName("..."))
        assertEquals(listOf("Note", "note (2)", "Note (3)"), uniqueFileNames(listOf("Note", "note", "Note")))
    }

    @Test fun escapingProtectsYamlAndCsvStructure() {
        assertEquals("\"a\\\"b\\\\c\\nx\"", yamlQuote("a\"b\\c\nx"))
        assertEquals("\"a,\"\"b\"\"\"", csvEscape("a,\"b\""))
        assertEquals("\\[title\\] \\*bold\\*", escapeMarkdownInline("[title] *bold*"))
    }

    @Test fun markdownExportsOnlyConfirmedRelationsAndEscapesAliases() {
        val cards = listOf(card(1, "First/Note", "tag: one"), card(2, "Second|Note", "tag two"), card(3, "Candidate", "x"))
        val relations = listOf(
            relation(1, 2, RelationStatus.CONFIRMED),
            relation(1, 3, RelationStatus.CANDIDATE),
            relation(2, 3, RelationStatus.REJECTED),
        )
        val files = LocalExportService().markdownFiles(ExportSnapshot(cards, relations), preferences)
        val first = files.first { it.fileName.startsWith("First-Note") }.content
        assertTrue(first.contains("[[Second-Note|Second\\|Note]]"))
        assertFalse(first.contains("Candidate"))
        assertTrue(first.contains("tags: [\"tag: one\"]"))
    }

    @Test fun zipCsvAndTextAreRealNonEmptyExports() {
        val snapshot = ExportSnapshot(listOf(card(1, "Title\nBody, \"quoted\"", "tag")), emptyList())
        ExportFormat.entries.forEach { format ->
            val output = ByteArrayOutputStream()
            LocalExportService().write(format, snapshot, preferences, output)
            assertTrue("$format was empty", output.size() > 10)
            if (format == ExportFormat.OBSIDIAN_ZIP) ZipInputStream(output.toByteArray().inputStream()).use { assertNotNull(it.nextEntry) }
        }
    }

    private fun card(id: Long, text: String, tag: String) = LocalCard(
        id, text, true, 1_700_000_000_000, 1_700_000_000_000, "app:\nsource",
        listOf(TagEntity(id, tag, tag.lowercase(), 0)), sourceUrl = "https://example.test/?a=1",
    )
    private fun relation(source: Long, target: Long, status: RelationStatus) = CardRelationEntity(
        id = source * 10 + target, sourceCardId = source, targetCardId = target,
        relationType = "related", status = status, createdAt = 0, updatedAt = 0,
    )
}
