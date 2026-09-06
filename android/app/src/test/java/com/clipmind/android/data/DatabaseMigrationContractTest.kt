package com.clipmind.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DatabaseMigrationContractTest {
    @Test fun databaseVersionAndMigrationAreExplicitAndNonDestructive() {
        assertEquals(2, CLIPMIND_DATABASE_VERSION)
        assertEquals(1, MIGRATION_1_2.startVersion)
        assertEquals(2, MIGRATION_1_2.endVersion)
        val statements = listOf(
            MIGRATION_1_2_ADD_SERVER_CARD_ID,
            MIGRATION_1_2_ADD_SERVER_CARD_STATUS,
            MIGRATION_1_2_ADD_SERVER_LAST_ERROR,
        )
        assertTrue(statements.all { it.startsWith("ALTER TABLE capture_outbox ADD COLUMN") })
        assertTrue(statements.all { it.endsWith(" TEXT") })
        assertFalse(statements.any { "DROP" in it.uppercase() || "DELETE" in it.uppercase() })
    }
}
