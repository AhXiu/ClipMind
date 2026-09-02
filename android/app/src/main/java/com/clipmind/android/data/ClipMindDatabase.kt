package com.clipmind.android.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(entities = [CaptureOutboxEntity::class], version = 1, exportSchema = false)
@TypeConverters(DbConverters::class)
abstract class ClipMindDatabase : RoomDatabase() {
    abstract fun captureOutboxDao(): CaptureOutboxDao

    companion object {
        fun create(context: Context): ClipMindDatabase = Room.databaseBuilder(
            context.applicationContext,
            ClipMindDatabase::class.java,
            "clipmind.db",
        ).build()
    }
}
