package com.clipmind.android.network

import com.clipmind.android.network.dto.CaptureBatchRequest
import com.clipmind.android.network.dto.CaptureBatchResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

interface CaptureApi {
    @POST("v1/captures:batch")
    suspend fun upload(
        @Header("Authorization") authorization: String?,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body request: CaptureBatchRequest,
    ): Response<CaptureBatchResponse>
}
