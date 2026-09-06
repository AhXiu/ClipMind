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

sealed interface ServerCardOperationResult {
    data class Success(val card: ServerCard) : ServerCardOperationResult
    data class Failure(val errorCode: String) : ServerCardOperationResult
}

class CardRepository(
    private val api: CaptureApi,
    private val dao: CaptureOutboxDao,
    private val tokenStore: TokenStore,
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
        try {
            val response = request(BatchRequestMetadata.authorizationHeader(tokenStore.readToken().orEmpty()))
            if (!response.isSuccessful) {
                val code = classifyServerCardHttpError(response.code())
                dao.updateServerCardError(id, cardId, code, System.currentTimeMillis())
                return@withContext ServerCardOperationResult.Failure(code)
            }
            val card = response.body()
            if (card == null) {
                val code = "NETWORK_EMPTY_RESPONSE"
                dao.updateServerCardError(id, cardId, code, System.currentTimeMillis())
                return@withContext ServerCardOperationResult.Failure(code)
            }
            dao.updateServerCard(id, cardId, card.status, card.lastError, System.currentTimeMillis())
            ServerCardOperationResult.Success(card)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (throwable: Throwable) {
            val code = classifyServerCardNetworkError(throwable)
            dao.updateServerCardError(id, cardId, code, System.currentTimeMillis())
            ServerCardOperationResult.Failure(code)
        }
    }
}

internal fun classifyServerCardHttpError(statusCode: Int): String = "HTTP_$statusCode"

internal fun classifyServerCardNetworkError(throwable: Throwable): String = when (throwable) {
    is IOException -> "NETWORK_IO"
    else -> "NETWORK_UNEXPECTED"
}
