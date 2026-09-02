package com.clipmind.android.network.dto

import com.google.gson.annotations.SerializedName

data class CaptureUploadItem(
    @SerializedName("client_capture_id") val clientCaptureId: String,
    @SerializedName("raw_text") val rawText: String,
    @SerializedName("text_sha256") val textSha256: String,
    @SerializedName("source_app") val sourceApp: String?,
    @SerializedName("source_url") val sourceUrl: String?,
    @SerializedName("mode") val mode: String,
    @SerializedName("captured_at") val capturedAt: String,
)

data class CaptureBatchRequest(
    @SerializedName("captures") val captures: List<CaptureUploadItem>,
)

data class AcceptedCapture(
    @SerializedName("client_capture_id") val clientCaptureId: String,
    @SerializedName("capture_id") val captureId: String? = null,
    @SerializedName("card_id") val cardId: String? = null,
    @SerializedName("duplicate") val duplicate: Boolean = false,
)

data class RejectedCapture(
    @SerializedName("client_capture_id") val clientCaptureId: String,
    @SerializedName("code") val code: String,
    @SerializedName("message") val message: String? = null,
)

data class CaptureBatchResponse(
    @SerializedName("accepted") val accepted: List<AcceptedCapture> = emptyList(),
    @SerializedName("rejected") val rejected: List<RejectedCapture> = emptyList(),
)
