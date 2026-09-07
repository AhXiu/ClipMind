package com.clipmind.android.network

import com.clipmind.android.network.dto.CaptureBatchRequest
import com.clipmind.android.network.dto.CaptureBatchResponse
import com.clipmind.android.network.dto.ServerCard
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path

interface CaptureApi {
    @POST("v1/knowledge:synthesize")
    suspend fun synthesize(
        @Header("Authorization") authorization: String?,
        @Body request: com.clipmind.android.knowledge.KnowledgeRequest,
    ): Response<com.clipmind.android.knowledge.KnowledgeResponse>
    @GET("v1/cards/{cardId}/versions")
    suspend fun getVersions(
        @Header("Authorization") authorization: String?,
        @Path("cardId") cardId: String,
    ): Response<List<com.clipmind.android.network.dto.ServerCardVersion>>

    @POST("v1/cards/{cardId}/analyses")
    suspend fun analyzeAgain(
        @Header("Authorization") authorization: String?,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Path("cardId") cardId: String,
        @Body request: com.clipmind.android.network.dto.CaptureUploadItem,
    ): Response<CaptureBatchResponse>
    @GET("health")
    suspend fun health(): Response<com.clipmind.android.network.dto.HealthResponse>

    @POST("v1/captures:batch")
    suspend fun upload(
        @Header("Authorization") authorization: String?,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body request: CaptureBatchRequest,
    ): Response<CaptureBatchResponse>

    @GET("v1/cards/{cardId}")
    suspend fun getCard(
        @Header("Authorization") authorization: String?,
        @Path("cardId") cardId: String,
    ): Response<ServerCard>

    @POST("v1/cards/{cardId}/confirm")
    suspend fun confirmCard(
        @Header("Authorization") authorization: String?,
        @Path("cardId") cardId: String,
    ): Response<ServerCard>
}
