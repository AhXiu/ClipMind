package com.clipmind.android.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

internal const val CLIPMIND_DATABASE_VERSION = 3
internal const val MIGRATION_1_2_ADD_SERVER_CARD_ID =
    "ALTER TABLE capture_outbox ADD COLUMN serverCardId TEXT"
internal const val MIGRATION_1_2_ADD_SERVER_CARD_STATUS =
    "ALTER TABLE capture_outbox ADD COLUMN serverCardStatus TEXT"
internal const val MIGRATION_1_2_ADD_SERVER_LAST_ERROR =
    "ALTER TABLE capture_outbox ADD COLUMN serverLastError TEXT"
internal const val MIGRATION_2_3_ADD_AI_PROVIDER =
    "ALTER TABLE capture_outbox ADD COLUMN aiProvider TEXT"
internal const val MIGRATION_2_3_ADD_AI_MODEL =
    "ALTER TABLE capture_outbox ADD COLUMN aiModel TEXT"
internal const val MIGRATION_2_3_ADD_ENCRYPTED_CLIENT_ANALYSIS =
    "ALTER TABLE capture_outbox ADD COLUMN encryptedClientAnalysis TEXT"

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

@Database(entities = [CaptureOutboxEntity::class], version = CLIPMIND_DATABASE_VERSION, exportSchema = false)
@TypeConverters(DbConverters::class)
abstract class ClipMindDatabase : RoomDatabase() {
    abstract fun captureOutboxDao(): CaptureOutboxDao

    companion object {
        fun create(context: Context): ClipMindDatabase = Room.databaseBuilder(
            context.applicationContext,
            ClipMindDatabase::class.java,
            "clipmind.db",
        ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build()
    }
}
