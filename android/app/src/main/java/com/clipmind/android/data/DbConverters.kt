package com.clipmind.android.data

import androidx.room.TypeConverter

class DbConverters {
    @TypeConverter fun toMode(value: String): CaptureMode = CaptureMode.valueOf(value)
    @TypeConverter fun fromMode(value: CaptureMode): String = value.name
    @TypeConverter fun toState(value: String): OutboxState = OutboxState.valueOf(value)
    @TypeConverter fun fromState(value: OutboxState): String = value.name
    @TypeConverter fun toRelationStatus(value: String): RelationStatus = RelationStatus.valueOf(value)
    @TypeConverter fun fromRelationStatus(value: RelationStatus): String = value.name
}
