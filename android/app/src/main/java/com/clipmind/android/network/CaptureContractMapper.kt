package com.clipmind.android.network

import com.clipmind.android.data.CaptureOutboxEntity
import com.clipmind.android.network.dto.CaptureUploadItem
import com.clipmind.android.network.dto.ClientAnalysis
import com.clipmind.android.security.TextCipher
import com.clipmind.android.security.TextCipherException
import com.google.gson.Gson
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
            val analysis = entity.encryptedClientAnalysis?.let {
                Gson().fromJson(cipher.decrypt(it), ClientAnalysis::class.java)
            }
            uploads += PreparedUpload(entity, entity.toUploadDto(plainText, analysis))
        } catch (e: TextCipherException) {
            failures += UploadDecryptionFailure(entity, "DECRYPT_${e.error.name}")
        } catch (_: Exception) {
            failures += UploadDecryptionFailure(entity, "DECRYPT_UNEXPECTED_FAILURE")
        }
    }
    return PreparedUploadBatch(uploads, failures)
}

internal fun CaptureOutboxEntity.toUploadDto(plainText: String, clientAnalysis: ClientAnalysis? = null) = CaptureUploadItem(
    clientCaptureId = clientCaptureId,
    rawText = plainText,
    textSha256 = hash,
    sourceApp = sourceApp,
    sourceUrl = sourceUrl,
    mode = mode.name.lowercase(),
    capturedAt = Instant.ofEpochMilli(capturedAt).toString(),
    clientAnalysis = clientAnalysis,
)
