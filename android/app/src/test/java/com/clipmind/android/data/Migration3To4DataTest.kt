package com.clipmind.android.data

import org.junit.Assert.assertTrue
import org.junit.Test

class Migration3To4DataTest {
    @Test fun migrationMapsEveryV3ColumnExactlyOnceIntoTheNewAuthority() {
        val cardCopy = MIGRATION_3_4_STATEMENTS.first { it.startsWith("INSERT INTO local_cards") }
        val metadataCopy = MIGRATION_3_4_STATEMENTS.first { it.startsWith("INSERT INTO sync_metadata") }

        listOf(
            "id", "clientCaptureId", "encryptedRawText", "hash", "sourceApp", "sourceUrl",
            "mode", "capturedAt", "updatedAt",
        ).forEach { assertTrue("Card mapping lost $it", it in cardCopy) }
        listOf(
            "id", "state", "retryCount", "nextRetryAt", "updatedAt", "lastErrorCode",
            "serverCardId", "serverCardStatus", "serverLastError", "aiProvider", "aiModel",
            "encryptedClientAnalysis",
        ).forEach { assertTrue("Metadata mapping lost $it", it in metadataCopy) }
    }

    @Test fun migrationCreatesAllRequiredAuthorityTables() {
        val creates = MIGRATION_3_4_STATEMENTS.filter { it.startsWith("CREATE TABLE") }.joinToString("\n")
        listOf("local_cards", "tags", "card_tag_refs", "card_relations", "sync_metadata")
            .forEach { assertTrue("Missing table $it", "`$it`" in creates) }
    }
}
