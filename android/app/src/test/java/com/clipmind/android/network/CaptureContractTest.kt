package com.clipmind.android.network

import com.clipmind.android.data.CaptureMode
import com.clipmind.android.data.CaptureOutboxEntity
import com.clipmind.android.data.OutboxState
import com.clipmind.android.network.dto.CaptureBatchRequest
import com.clipmind.android.network.dto.CaptureBatchResponse
import com.clipmind.android.security.FakeTextCipher
import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureContractTest {
    private val gson = Gson()

    @Test fun requestUsesBackendFieldNamesRfc3339AndLowercaseMode() {
        val entity = CaptureOutboxEntity(
            id = 1,
            clientCaptureId = "client-1",
            encryptedRawText = FakeTextCipher().encrypt("original text"),
            hash = "abc123",
            sourceApp = "notes.app",
            sourceUrl = "https://source.example/item",
            mode = CaptureMode.AUTO,
            state = OutboxState.READY,
            capturedAt = 0,
            updatedAt = 0,
        )
        val upload = prepareUploadBatch(listOf(entity), FakeTextCipher()).uploads.single().item
        val json = JsonParser.parseString(gson.toJson(CaptureBatchRequest(listOf(upload))))
        val item = json.asJsonObject.getAsJsonArray("captures")[0].asJsonObject

        assertEquals("client-1", item["client_capture_id"].asString)
        assertEquals("original text", item["raw_text"].asString)
        assertEquals("abc123", item["text_sha256"].asString)
        assertEquals("notes.app", item["source_app"].asString)
        assertEquals("https://source.example/item", item["source_url"].asString)
        assertEquals("auto", item["mode"].asString)
        assertEquals("1970-01-01T00:00:00Z", item["captured_at"].asString)
        assertFalse(item.has("clientCaptureId"))
        assertFalse(item.has("hash"))
    }

    @Test fun responseParsesAcceptedAndRejectedObjects() {
        val response = gson.fromJson(
            """{
              "accepted":[{"client_capture_id":"c1","capture_id":"cap1","card_id":"card1","duplicate":true}],
              "rejected":[{"client_capture_id":"c2","code":"filtered_reject","message":"filtered"}]
            }""".trimIndent(),
            CaptureBatchResponse::class.java,
        )
        assertEquals("c1", response.accepted.single().clientCaptureId)
        assertEquals("cap1", response.accepted.single().captureId)
        assertEquals("card1", response.accepted.single().cardId)
        assertTrue(response.accepted.single().duplicate)
        assertEquals("c2", response.rejected.single().clientCaptureId)
        assertEquals("filtered_reject", response.rejected.single().code)
        assertEquals("filtered", response.rejected.single().message)
    }

    @Test fun idempotencyKeyIsStableForOrderedClientIds() {
        val first = BatchRequestMetadata.idempotencyKey(listOf("c3", "c1", "c2"))
        val retry = BatchRequestMetadata.idempotencyKey(listOf("c2", "c3", "c1"))
        assertEquals(first, retry)
        assertEquals(78, first.length)
        assertTrue(first.startsWith("capture-batch-"))
        assertFalse(first == BatchRequestMetadata.idempotencyKey(listOf("c1", "c2")))
    }

    @Test fun authorizationCanBeOmittedAndBearerIsNormalized() {
        assertNull(BatchRequestMetadata.authorizationHeader("  "))
        assertEquals("Bearer token", BatchRequestMetadata.authorizationHeader("token"))
        assertEquals("Bearer existing", BatchRequestMetadata.authorizationHeader("Bearer existing"))
    }
}
