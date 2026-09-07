package com.clipmind.android.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

internal const val CLIPMIND_DATABASE_VERSION = 4
internal const val MIGRATION_1_2_ADD_SERVER_CARD_ID = "ALTER TABLE capture_outbox ADD COLUMN serverCardId TEXT"
internal const val MIGRATION_1_2_ADD_SERVER_CARD_STATUS = "ALTER TABLE capture_outbox ADD COLUMN serverCardStatus TEXT"
internal const val MIGRATION_1_2_ADD_SERVER_LAST_ERROR = "ALTER TABLE capture_outbox ADD COLUMN serverLastError TEXT"
internal const val MIGRATION_2_3_ADD_AI_PROVIDER = "ALTER TABLE capture_outbox ADD COLUMN aiProvider TEXT"
internal const val MIGRATION_2_3_ADD_AI_MODEL = "ALTER TABLE capture_outbox ADD COLUMN aiModel TEXT"
internal const val MIGRATION_2_3_ADD_ENCRYPTED_CLIENT_ANALYSIS = "ALTER TABLE capture_outbox ADD COLUMN encryptedClientAnalysis TEXT"

internal val MIGRATION_3_4_STATEMENTS = listOf(
    "CREATE TABLE IF NOT EXISTS `local_cards` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `clientCaptureId` TEXT NOT NULL, `encryptedContent` TEXT NOT NULL, `hash` TEXT NOT NULL, `sourceApp` TEXT, `sourceUrl` TEXT, `mode` TEXT NOT NULL, `capturedAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `deletedAt` INTEGER)",
    "CREATE UNIQUE INDEX IF NOT EXISTS `index_local_cards_clientCaptureId` ON `local_cards` (`clientCaptureId`)",
    "CREATE INDEX IF NOT EXISTS `index_local_cards_hash` ON `local_cards` (`hash`)",
    "CREATE INDEX IF NOT EXISTS `index_local_cards_deletedAt_capturedAt` ON `local_cards` (`deletedAt`, `capturedAt`)",
    "CREATE TABLE IF NOT EXISTS `sync_metadata` (`cardId` INTEGER NOT NULL, `uploadState` TEXT NOT NULL, `retryCount` INTEGER NOT NULL, `nextRetryAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `lastErrorCode` TEXT, `serverCardId` TEXT, `serverCardStatus` TEXT, `serverLastError` TEXT, `aiProvider` TEXT, `aiModel` TEXT, `encryptedClientAnalysis` TEXT, PRIMARY KEY(`cardId`), FOREIGN KEY(`cardId`) REFERENCES `local_cards`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
    "CREATE INDEX IF NOT EXISTS `index_sync_metadata_uploadState_nextRetryAt` ON `sync_metadata` (`uploadState`, `nextRetryAt`)",
    "CREATE INDEX IF NOT EXISTS `index_sync_metadata_serverCardId` ON `sync_metadata` (`serverCardId`)",
    "CREATE TABLE IF NOT EXISTS `tags` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `normalizedName` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)",
    "CREATE UNIQUE INDEX IF NOT EXISTS `index_tags_normalizedName` ON `tags` (`normalizedName`)",
    "CREATE TABLE IF NOT EXISTS `card_tag_refs` (`cardId` INTEGER NOT NULL, `tagId` INTEGER NOT NULL, PRIMARY KEY(`cardId`, `tagId`), FOREIGN KEY(`cardId`) REFERENCES `local_cards`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`tagId`) REFERENCES `tags`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
    "CREATE INDEX IF NOT EXISTS `index_card_tag_refs_tagId` ON `card_tag_refs` (`tagId`)",
    "CREATE TABLE IF NOT EXISTS `card_relations` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `sourceCardId` INTEGER NOT NULL, `targetCardId` INTEGER NOT NULL, `relationType` TEXT NOT NULL, `status` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, FOREIGN KEY(`sourceCardId`) REFERENCES `local_cards`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`targetCardId`) REFERENCES `local_cards`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
    "CREATE UNIQUE INDEX IF NOT EXISTS `index_card_relations_sourceCardId_targetCardId_relationType` ON `card_relations` (`sourceCardId`, `targetCardId`, `relationType`)",
    "CREATE INDEX IF NOT EXISTS `index_card_relations_targetCardId` ON `card_relations` (`targetCardId`)",
    "CREATE INDEX IF NOT EXISTS `index_card_relations_status` ON `card_relations` (`status`)",
    "INSERT INTO local_cards (id, clientCaptureId, encryptedContent, hash, sourceApp, sourceUrl, mode, capturedAt, updatedAt, deletedAt) SELECT id, clientCaptureId, encryptedRawText, hash, sourceApp, sourceUrl, mode, capturedAt, updatedAt, CASE WHEN state = 'DISCARDED' THEN updatedAt ELSE NULL END FROM capture_outbox",
    "INSERT INTO sync_metadata (cardId, uploadState, retryCount, nextRetryAt, updatedAt, lastErrorCode, serverCardId, serverCardStatus, serverLastError, aiProvider, aiModel, encryptedClientAnalysis) SELECT id, state, retryCount, nextRetryAt, updatedAt, lastErrorCode, serverCardId, serverCardStatus, serverLastError, aiProvider, aiModel, encryptedClientAnalysis FROM capture_outbox",
    "DROP TABLE capture_outbox",
)

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(MIGRATION_1_2_ADD_SERVER_CARD_ID)
        db.execSQL(MIGRATION_1_2_ADD_SERVER_CARD_STATUS)
        db.execSQL(MIGRATION_1_2_ADD_SERVER_LAST_ERROR)
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(MIGRATION_2_3_ADD_AI_PROVIDER)
        db.execSQL(MIGRATION_2_3_ADD_AI_MODEL)
        db.execSQL(MIGRATION_2_3_ADD_ENCRYPTED_CLIENT_ANALYSIS)
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        MIGRATION_3_4_STATEMENTS.forEach(db::execSQL)
    }
}

@Database(
    entities = [LocalCardEntity::class, SyncMetadataEntity::class, TagEntity::class, CardTagRefEntity::class, CardRelationEntity::class],
    version = CLIPMIND_DATABASE_VERSION,
    exportSchema = false,
)
@TypeConverters(DbConverters::class)
abstract class ClipMindDatabase : RoomDatabase() {
    abstract fun captureOutboxDao(): CaptureOutboxDao
    abstract fun localCardDao(): LocalCardDao

    companion object {
        fun create(context: Context): ClipMindDatabase = Room.databaseBuilder(
            context.applicationContext, ClipMindDatabase::class.java, "clipmind.db",
        ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build()
    }
}
