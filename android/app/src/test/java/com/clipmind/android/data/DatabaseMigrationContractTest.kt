package com.clipmind.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DatabaseMigrationContractTest {
    @Test fun databaseVersionAndMigrationChainAreExplicit() {
        assertEquals(4, CLIPMIND_DATABASE_VERSION)
        assertEquals(1, MIGRATION_1_2.startVersion)
        assertEquals(2, MIGRATION_1_2.endVersion)
        assertEquals(2, MIGRATION_2_3.startVersion)
        assertEquals(3, MIGRATION_2_3.endVersion)
        assertEquals(3, MIGRATION_3_4.startVersion)
        assertEquals(4, MIGRATION_3_4.endVersion)
    }

    @Test fun v3DataIsCopiedBeforeLegacyTableIsRemoved() {
        val cardCopy = MIGRATION_3_4_STATEMENTS.indexOfFirst { it.startsWith("INSERT INTO local_cards") }
        val metadataCopy = MIGRATION_3_4_STATEMENTS.indexOfFirst { it.startsWith("INSERT INTO sync_metadata") }
        val drop = MIGRATION_3_4_STATEMENTS.indexOf("DROP TABLE capture_outbox")
        assertTrue(cardCopy >= 0 && metadataCopy > cardCopy && drop > metadataCopy)
    }

    @Test fun v3MigrationPreservesCiphertextAndAllRemoteAiState() {
        val cardCopy = MIGRATION_3_4_STATEMENTS.first { it.startsWith("INSERT INTO local_cards") }
        val metadataCopy = MIGRATION_3_4_STATEMENTS.first { it.startsWith("INSERT INTO sync_metadata") }
        assertTrue("encryptedRawText" in cardCopy)
        listOf(
            "state", "retryCount", "nextRetryAt", "lastErrorCode", "serverCardId",
            "serverCardStatus", "serverLastError", "aiProvider", "aiModel", "encryptedClientAnalysis",
        ).forEach { assertTrue("Missing $it", it in metadataCopy) }
        assertTrue("Discarded captures must become soft-deleted cards", "state = 'DISCARDED'" in cardCopy)
    }
}
