package com.clipmind.android.data

import com.clipmind.android.network.BatchRequestMetadata
import com.clipmind.android.network.CaptureApi
import com.clipmind.android.network.dto.ServerCard
import com.clipmind.android.security.TokenStore
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.Response
import com.clipmind.android.security.TextCipher
import com.clipmind.android.network.dto.ClientAnalysis
import com.google.gson.Gson

sealed interface ServerCardOperationResult {
    data class Success(val card: ServerCard) : ServerCardOperationResult
    data class Failure(val errorCode: String) : ServerCardOperationResult
}

class CardRepository(
    private val api: CaptureApi,
    private val dao: CaptureOutboxDao,
    private val tokenStore: TokenStore,
    private val cipher: TextCipher,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun refreshServerCard(id: Long, cardId: String): ServerCardOperationResult =
        execute(id, cardId) { authorization -> api.getCard(authorization, cardId) }

    suspend fun confirmServerCard(id: Long, cardId: String): ServerCardOperationResult =
        execute(id, cardId) { authorization -> api.confirmCard(authorization, cardId) }

    private suspend fun execute(
        id: Long,
        cardId: String,
        request: suspend (String?) -> Response<ServerCard>,
    ): ServerCardOperationResult = withContext(ioDispatcher) {
        val taskId = dao.serverReadTask(id, cardId)
            ?: return@withContext ServerCardOperationResult.Failure("STALE_ANALYSIS_TASK")
        try {
            val response = request(BatchRequestMetadata.authorizationHeader(tokenStore.readToken().orEmpty()))
            if (!response.isSuccessful) {
                val code = classifyServerCardHttpError(response.code())
                dao.updateServerCardError(id, cardId, taskId, code, System.currentTimeMillis())
                return@withContext ServerCardOperationResult.Failure(code)
            }
            val card = response.body()
            if (card == null || card.id != cardId) {
                val code = "NETWORK_EMPTY_RESPONSE"
                dao.updateServerCardError(id, cardId, taskId, code, System.currentTimeMillis())
                return@withContext ServerCardOperationResult.Failure(code)
            }
            val analysis = if (card.activeVersionId != null) {
                val versions = api.getVersions(BatchRequestMetadata.authorizationHeader(tokenStore.readToken().orEmpty()), cardId)
                val version = versions.body()?.firstOrNull { it.id == card.activeVersionId && it.cardId == cardId }
                if (!versions.isSuccessful || version == null || version.interpretation.summary.isBlank() ||
                    version.interpretation.insight.isBlank() || version.interpretation.action.isBlank()) {
                    dao.updateServerCardError(id, cardId, taskId, "ANALYSIS_RESULT_UNAVAILABLE", System.currentTimeMillis())
                    return@withContext ServerCardOperationResult.Failure("ANALYSIS_RESULT_UNAVAILABLE")
                }
                cipher.encrypt(Gson().toJson(ClientAnalysis(version.provider, version.model, version.primaryTag, version.interpretation, version.books.orEmpty().filter { it.verified }, version.schemaVersion,version.secondaryTags.orEmpty(),version.keywords.orEmpty())))
            } else null
            if (dao.updateServerCard(id, cardId, taskId, card.status, card.lastError, analysis, System.currentTimeMillis()) != 1) {
                return@withContext ServerCardOperationResult.Failure("STALE_ANALYSIS_TASK")
            }
            ServerCardOperationResult.Success(card)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (throwable: Throwable) {
            val code = classifyServerCardNetworkError(throwable)
            dao.updateServerCardError(id, cardId, taskId, code, System.currentTimeMillis())
            ServerCardOperationResult.Failure(code)
        }
    }
}

internal fun classifyServerCardHttpError(statusCode: Int): String = "HTTP_$statusCode"

internal fun classifyServerCardNetworkError(throwable: Throwable): String = when (throwable) {
    is IOException -> "NETWORK_IO"
    else -> "NETWORK_UNEXPECTED"
}
