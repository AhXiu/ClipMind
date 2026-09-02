package com.clipmind.android.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.clipmind.android.ClipMindApp
import com.clipmind.android.data.CaptureOutboxDao
import com.clipmind.android.data.CaptureOutboxEntity
import com.clipmind.android.network.BatchRequestMetadata
import com.clipmind.android.network.dto.CaptureBatchRequest
import com.clipmind.android.network.dto.CaptureBatchResponse
import com.clipmind.android.network.prepareUploadBatch
import kotlin.math.min

class CaptureUploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as ClipMindApp
        val dao = app.container.database.captureOutboxDao()
        val now = System.currentTimeMillis()
        dao.recoverStaleUploads(now - 10 * 60 * 1000L, now)
        val candidates = dao.eligibleBatch(now, 50)
        if (candidates.isEmpty()) return Result.success()

        val prepared = prepareUploadBatch(candidates, app.container.textCipher)
        prepared.failures.groupBy { it.errorCode }.forEach { (code, failures) ->
            dao.markDecryptionFailed(failures.map { it.entity.id }, System.currentTimeMillis(), code)
        }
        if (prepared.uploads.isEmpty()) return Result.success()

        val batch = prepared.uploads.map { it.entity }
        dao.markUploading(batch.map { it.id }, now)
        val clientIds = batch.map { it.clientCaptureId }
        val idempotencyKey = BatchRequestMetadata.idempotencyKey(clientIds)
        val authorization = BatchRequestMetadata.authorizationHeader(app.container.tokenStore.readToken().orEmpty())
        return try {
            val response = app.container.api.upload(
                authorization = authorization,
                idempotencyKey = idempotencyKey,
                request = CaptureBatchRequest(prepared.uploads.map { it.item }),
            )
            if (response.isSuccessful) {
                handleSuccessfulResponse(dao, batch, response.body())
            } else {
                markRetry(dao, batch, "HTTP_${response.code()}")
                Result.retry()
            }
        } catch (e: Exception) {
            markRetry(dao, batch, "NETWORK_${e.javaClass.simpleName}")
            Result.retry()
        }
    }

    private suspend fun handleSuccessfulResponse(
        dao: CaptureOutboxDao,
        batch: List<CaptureOutboxEntity>,
        body: CaptureBatchResponse?,
    ): Result {
        if (body == null) {
            markRetry(dao, batch, "EMPTY_RESPONSE")
            return Result.retry()
        }
        val batchByClientId = batch.associateBy { it.clientCaptureId }
        val acceptedIds = body.accepted.map { it.clientCaptureId }.filter(batchByClientId::containsKey).toSet()
        if (acceptedIds.isNotEmpty()) dao.markSucceeded(acceptedIds.toList(), System.currentTimeMillis())

        val rejected = body.rejected
            .filter { it.clientCaptureId in batchByClientId && it.clientCaptureId !in acceptedIds }
        val terminalRejectedIds = rejected
            .filter { it.code.equals("filtered_reject", ignoreCase = true) }
            .map { it.clientCaptureId }
            .toSet()
        if (terminalRejectedIds.isNotEmpty()) {
            dao.markRejected(terminalRejectedIds.toList(), System.currentTimeMillis(), "filtered_reject")
        }

        var hasRetryable = false
        rejected.filterNot { it.clientCaptureId in terminalRejectedIds }
            .groupBy { it.code.ifBlank { "UNKNOWN_REJECTION" } }
            .forEach { (code, responses) ->
                val items = responses.mapNotNull { batchByClientId[it.clientCaptureId] }
                if (items.isNotEmpty()) {
                    hasRetryable = true
                    markRetry(dao, items, "REJECTED_$code")
                }
            }

        val explicitlyHandled = acceptedIds + terminalRejectedIds + rejected.map { it.clientCaptureId }
        val missing = batch.filterNot { it.clientCaptureId in explicitlyHandled }
        if (missing.isNotEmpty()) {
            hasRetryable = true
            markRetry(dao, missing, "SERVER_NOT_CONFIRMED")
        }
        return if (hasRetryable) Result.retry() else Result.success()
    }

    private suspend fun markRetry(dao: CaptureOutboxDao, items: List<CaptureOutboxEntity>, code: String) {
        val maxRetry = items.maxOfOrNull { it.retryCount + 1 } ?: 1
        val delay = min(6 * 60 * 60 * 1000L, 30_000L * (1L shl min(maxRetry - 1, 10)))
        val now = System.currentTimeMillis()
        dao.markRetryable(items.map { it.id }, now, now + delay, code)
    }
}
