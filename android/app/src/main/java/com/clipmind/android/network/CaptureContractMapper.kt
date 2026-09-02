package com.clipmind.android.network

import com.clipmind.android.data.CaptureOutboxEntity
import com.clipmind.android.network.dto.CaptureUploadItem
import com.clipmind.android.security.TextCipher
import com.clipmind.android.security.TextCipherException
import java.time.Instant

data class PreparedUpload(
    val entity: CaptureOutboxEntity,
    val item: CaptureUploadItem,
)

data class UploadDecryptionFailure(
    val entity: CaptureOutboxEntity,
    val errorCode: String,
)

data class PreparedUploadBatch(
    val uploads: List<PreparedUpload>,
    val failures: List<UploadDecryptionFailure>,
)

internal fun prepareUploadBatch(
    entities: List<CaptureOutboxEntity>,
    cipher: TextCipher,
): PreparedUploadBatch {
    val uploads = mutableListOf<PreparedUpload>()
    val failures = mutableListOf<UploadDecryptionFailure>()
    entities.forEach { entity ->
        try {
            val plainText = cipher.decrypt(entity.encryptedRawText)
            uploads += PreparedUpload(entity, entity.toUploadDto(plainText))
        } catch (e: TextCipherException) {
            failures += UploadDecryptionFailure(entity, "DECRYPT_${e.error.name}")
        } catch (_: Exception) {
            failures += UploadDecryptionFailure(entity, "DECRYPT_UNEXPECTED_FAILURE")
        }
    }
    return PreparedUploadBatch(uploads, failures)
}

internal fun CaptureOutboxEntity.toUploadDto(plainText: String) = CaptureUploadItem(
    clientCaptureId = clientCaptureId,
    rawText = plainText,
    textSha256 = hash,
    sourceApp = sourceApp,
    sourceUrl = sourceUrl,
    mode = mode.name.lowercase(),
    capturedAt = Instant.ofEpochMilli(capturedAt).toString(),
)
