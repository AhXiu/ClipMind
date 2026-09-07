package com.clipmind.android.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Embedded
import androidx.room.Junction
import androidx.room.Relation
import androidx.room.ColumnInfo

enum class RelationStatus { CANDIDATE, CONFIRMED, REJECTED, STALE }

@Entity(
    tableName = "local_cards",
    indices = [Index(value = ["clientCaptureId"], unique = true), Index("hash"), Index("deletedAt", "capturedAt")],
)
data class LocalCardEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val clientCaptureId: String,
    val encryptedContent: String,
    val hash: String,
    val sourceApp: String?,
    val sourceUrl: String?,
    val mode: CaptureMode,
    val capturedAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
    @ColumnInfo(defaultValue = "1") val contentRevision: Long = 1,
)

@Entity(
    tableName = "sync_metadata",
    foreignKeys = [ForeignKey(
        entity = LocalCardEntity::class,
        parentColumns = ["id"],
        childColumns = ["cardId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("uploadState", "nextRetryAt"), Index("serverCardId")],
)
data class SyncMetadataEntity(
    @PrimaryKey val cardId: Long,
    val uploadState: OutboxState,
    val retryCount: Int = 0,
    val nextRetryAt: Long = 0,
    val updatedAt: Long,
    val lastErrorCode: String? = null,
    val serverCardId: String? = null,
    val serverCardStatus: String? = null,
    val serverLastError: String? = null,
    val aiProvider: String? = null,
    val aiModel: String? = null,
    val encryptedClientAnalysis: String? = null,
    val encryptedServerAnalysis: String? = null,
)

@Entity(tableName = "tags", indices = [Index(value = ["normalizedName"], unique = true)])
data class TagEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val normalizedName: String,
    val createdAt: Long,
)

@Entity(
    tableName = "card_tag_refs",
    primaryKeys = ["cardId", "tagId"],
    foreignKeys = [
        ForeignKey(entity = LocalCardEntity::class, parentColumns = ["id"], childColumns = ["cardId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = TagEntity::class, parentColumns = ["id"], childColumns = ["tagId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("tagId")],
)
data class CardTagRefEntity(val cardId: Long, val tagId: Long)

@Entity(
    tableName = "card_relations",
    foreignKeys = [
        ForeignKey(entity = LocalCardEntity::class, parentColumns = ["id"], childColumns = ["sourceCardId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = LocalCardEntity::class, parentColumns = ["id"], childColumns = ["targetCardId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [
        Index(value = ["sourceCardId", "targetCardId", "relationType"], unique = true),
        Index("targetCardId"),
        Index("status"),
    ],
)
data class CardRelationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceCardId: Long,
    val targetCardId: Long,
    val relationType: String,
    val status: RelationStatus = RelationStatus.CANDIDATE,
    val createdAt: Long,
    val updatedAt: Long,
    val encryptedEvidence: String? = null,
    @ColumnInfo(defaultValue = "0") val sourceRevision: Long = 0,
    @ColumnInfo(defaultValue = "0") val targetRevision: Long = 0,
    @ColumnInfo(defaultValue = "'manual'") val origin: String = "manual",
)

@Entity(tableName = "knowledge_notes")
data class KnowledgeNoteEntity(
    @PrimaryKey val id: String,
    val encryptedPayload: String,
    val createdAt: Long,
)

data class LocalCardWithTags(
    @Embedded val card: LocalCardEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "id",
        associateBy = Junction(CardTagRefEntity::class, parentColumn = "cardId", entityColumn = "tagId"),
    )
    val tags: List<TagEntity>,
    @Relation(parentColumn = "id", entityColumn = "cardId")
    val sync: SyncMetadataEntity? = null,
)
