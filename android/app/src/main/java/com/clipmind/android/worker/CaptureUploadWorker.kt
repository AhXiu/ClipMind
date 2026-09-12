package com.clipmind.android.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.clipmind.android.ClipMindApp
import com.clipmind.android.data.CaptureOutboxDao
import com.clipmind.android.data.CaptureOutboxEntity
import com.clipmind.android.network.BatchRequestMetadata
import com.clipmind.android.network.ByokAnalysisResult
import com.clipmind.android.network.dto.AcceptedCapture
import com.clipmind.android.network.dto.CaptureBatchRequest
import com.clipmind.android.network.dto.CaptureBatchResponse
import com.clipmind.android.network.prepareUploadBatch
import com.clipmind.android.security.TextCipherException
import com.google.gson.Gson
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import com.clipmind.android.domain.LocalSafetyFilter
import com.clipmind.android.domain.FilterResult

class CaptureUploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = uploadMutex.withLock { performUpload() }

    private suspend fun performUpload(): Result {
        val app = applicationContext as ClipMindApp
        val container = app.container
        // The current batch endpoint always initiates AI processing and requires plaintext.
        // AI-off therefore means no upload at all; this is safer than pretending the protocol supports storage-only sync.
        if (!container.settings.aiEnabled.value) return Result.success()
        val dao = container.database.captureOutboxDao()
        val now = System.currentTimeMillis()
        dao.recoverStaleUploads(now - 10 * 60 * 1000L, now)
        val candidates = dao.eligibleBatch(now, 50)
        if (candidates.isEmpty()) return Result.success()

        var retry = false
        for (candidate in candidates) {
            if (!container.settings.aiEnabled.value) break
            if (dao.claim(candidate.id, candidate.clientCaptureId, System.currentTimeMillis()) != 1) continue
            if (uploadOne(candidate, dao, container) == Result.retry()) retry = true
        }
        return if (retry || dao.eligibleBatch(System.currentTimeMillis(), 1).isNotEmpty()) Result.retry() else Result.success()
    }

    private suspend fun uploadOne(
        candidate: CaptureOutboxEntity,
        dao: CaptureOutboxDao,
        container: com.clipmind.android.AppContainer,
    ): Result {
        // Recheck the current encrypted snapshot before either the provider or backend sees it.
        val text = try { container.textCipher.decrypt(candidate.encryptedRawText) }
        catch (e: TextCipherException) {
            dao.markDecryptionFailed(listOf(candidate.id), System.currentTimeMillis(), "DECRYPT_${e.error.name}")
            return Result.success()
        }
        if (container.safetyFilter.evaluate(text, candidate.sourceApp, container.settings.minimumCaptureLength.value) != FilterResult.Allowed) {
            dao.markRejected(listOf(candidate.clientCaptureId), System.currentTimeMillis(), "LOCAL_SAFETY_REJECT")
            return Result.success()
        }
        val analysisReady = prepareClientAnalysis(listOf(candidate), dao, container)
        if (analysisReady.isEmpty()) return Result.retry()
        if (!container.settings.aiEnabled.value) {
            markRetry(dao, listOf(candidate), "AI_PAUSED")
            return Result.success()
        }
        val prepared = prepareUploadBatch(analysisReady, container.textCipher)
        prepared.failures.groupBy { it.errorCode }.forEach { (code, failures) ->
            dao.markDecryptionFailed(failures.map { it.entity.id }, System.currentTimeMillis(), code)
        }
        if (prepared.uploads.isEmpty()) return Result.success()

        val batch = prepared.uploads.map { it.entity }
        val clientIds = batch.map { it.clientCaptureId }
        val idempotencyKey = BatchRequestMetadata.idempotencyKey(clientIds)
        val authorization = BatchRequestMetadata.authorizationHeader(container.tokenStore.readToken().orEmpty())
        return try {
            val response = if (candidate.serverCardId != null) container.api.analyzeAgain(
                authorization, idempotencyKey, candidate.serverCardId, prepared.uploads.single().item,
            ) else container.api.upload(
                authorization = authorization,
                idempotencyKey = idempotencyKey,
                request = CaptureBatchRequest(prepared.uploads.map { it.item }),
            )
            if (response.isSuccessful) {
                val result = handleSuccessfulResponse(dao, batch, response.body())
                acceptedCardMappings(batch, response.body() ?: CaptureBatchResponse()).forEach { accepted ->
                    accepted.cardId?.let { container.cardRepository.refreshServerCard(candidate.id, it) }
                }
                result
            } else if (response.code() in setOf(400, 401, 403, 404, 422)) {
                dao.markRejected(clientIds, System.currentTimeMillis(), "HTTP_${response.code()}")
                Result.success()
            } else {
                markRetry(dao, batch, "HTTP_${response.code()}")
                Result.retry()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            markRetry(dao, batch, "NETWORK_${e.javaClass.simpleName}")
            Result.retry()
        }
    }

    private suspend fun prepareClientAnalysis(
        candidates: List<CaptureOutboxEntity>,
        dao: CaptureOutboxDao,
        container: com.clipmind.android.AppContainer,
    ): List<CaptureOutboxEntity> {
        val ready = mutableListOf<CaptureOutboxEntity>()
        for (entity in candidates) {
            if (!shouldInvokeClientAnalysis(entity)) {
                ready += entity
                continue
            }
            val key = container.apiKeyStore.readForAuthorization(entity.aiProvider.orEmpty())
            if (key == null) {
                markAnalysisError(dao, entity, "BYOK_KEY_MISSING")
                continue
            }
            val model = entity.aiModel.orEmpty()
            if (model.isBlank()) {
                markAnalysisError(dao, entity, "BYOK_MODEL_MISSING")
                continue
            }
            val plainText = try {
                container.textCipher.decrypt(entity.encryptedRawText)
            } catch (e: TextCipherException) {
                dao.markDecryptionFailed(listOf(entity.id), System.currentTimeMillis(), "DECRYPT_${e.error.name}")
                continue
            }
            when (val result = container.clientAnalyzer.analyze(entity.aiProvider!!, model, key, plainText)) {
                is ByokAnalysisResult.Failure -> markAnalysisError(dao, entity, result.code.name)
                is ByokAnalysisResult.Success -> {
                    val encrypted = try {
                        container.textCipher.encrypt(Gson().toJson(result.analysis))
                    } catch (e: TextCipherException) {
                        markAnalysisError(dao, entity, "BYOK_ENCRYPTION")
                        continue
                    }
                    if (dao.cacheClientAnalysis(entity.id, encrypted, System.currentTimeMillis()) == 1) {
                        ready += entity.copy(encryptedClientAnalysis = encrypted)
                    }
                }
            }
        }
        return ready
    }

    private suspend fun markAnalysisError(dao: CaptureOutboxDao, entity: CaptureOutboxEntity, code: String) {
        val now = System.currentTimeMillis()
        if (com.clipmind.android.network.requiresByokConfigurationChange(code)) {
            dao.markRejected(listOf(entity.clientCaptureId), now, code)
            return
        }
        dao.markAnalysisError(entity.id, now, now + ANALYSIS_RETRY_DELAY_MS, code)
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
        val acceptedMappings = acceptedCardMappings(batch, body)
        val acceptedIds = acceptedMappings.map { it.clientCaptureId }.toSet()
        val succeededAt = System.currentTimeMillis()
        acceptedMappings.forEach { accepted -> dao.markSucceeded(accepted.clientCaptureId, accepted.cardId, succeededAt) }

        val rejected = body.rejected.filter { it.clientCaptureId in batchByClientId && it.clientCaptureId !in acceptedIds }
        val terminalRejectedIds = rejected.filter { it.code.equals("filtered_reject", ignoreCase = true) }
            .map { it.clientCaptureId }.toSet()
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

    private companion object {
        val uploadMutex = Mutex()
        const val ANALYSIS_RETRY_DELAY_MS = 6 * 60 * 60 * 1000L
    }
}

internal fun shouldInvokeClientAnalysis(entity: CaptureOutboxEntity): Boolean =
    entity.aiProvider != null && entity.encryptedClientAnalysis == null

internal fun acceptedCardMappings(
    batch: List<CaptureOutboxEntity>,
    body: CaptureBatchResponse,
): List<AcceptedCapture> {
    val expectedClientIds = batch.mapTo(mutableSetOf()) { it.clientCaptureId }
    return body.accepted.filter { it.clientCaptureId in expectedClientIds }
}
