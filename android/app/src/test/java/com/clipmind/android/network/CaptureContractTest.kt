package com.clipmind.android.network

import com.clipmind.android.data.CaptureMode
import com.clipmind.android.data.CaptureOutboxEntity
import com.clipmind.android.data.OutboxState
import com.clipmind.android.data.AiDefaults
import com.clipmind.android.network.dto.CaptureBatchRequest
import com.clipmind.android.network.dto.CaptureBatchResponse
import com.clipmind.android.network.dto.AnalysisBook
import com.clipmind.android.network.dto.AnalysisInterpretation
import com.clipmind.android.network.dto.ClientAnalysis
import com.clipmind.android.network.dto.ServerCard
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

    @Test fun everyProviderPreservesItsValidatedAnalysisThroughEncryptionAndUploadSerialization() {
        val cipher = FakeTextCipher()
        val raw = """{"primary_tag":"技术","interpretation":{"summary":"summary","insight":"insight","action":"action"},"books":[{"title":"title","author":"author"}]}"""
        AiDefaults.providerIds.forEach { provider ->
            val result = FixedProviderClient().parseResponse(providerEnvelope(provider, raw), provider, "test-model") as ByokAnalysisResult.Success
            val entity = CaptureOutboxEntity(clientCaptureId = "task-$provider", encryptedRawText = cipher.encrypt("original"),
                hash = "hash", sourceApp = null, sourceUrl = null, mode = CaptureMode.CONFIRM, state = OutboxState.READY,
                capturedAt = 0, updatedAt = 0, aiProvider = provider, aiModel = "test-model",
                encryptedClientAnalysis = cipher.encrypt(gson.toJson(result.analysis)))
            val batch = prepareUploadBatch(listOf(entity), cipher)
            assertTrue(batch.failures.isEmpty())
            val json = JsonParser.parseString(gson.toJson(batch.uploads.single().item)).asJsonObject.getAsJsonObject("client_analysis")
            assertEquals(setOf("provider", "model", "primary_tag", "interpretation", "books", "schema_version"), json.keySet())
            assertEquals(provider, json["provider"].asString)
            assertEquals("test-model", json["model"].asString)
            assertEquals("技术", json["primary_tag"].asString)
            assertEquals(2, json["schema_version"].asInt)
            assertEquals(JsonParser.parseString(raw).asJsonObject["interpretation"], json["interpretation"])
            assertEquals(JsonParser.parseString(raw).asJsonObject["books"], json["books"])
        }
    }

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
        assertEquals(java.security.MessageDigest.getInstance("SHA-256").digest("original text".toByteArray()).joinToString("") { "%02x".format(it) }, item["text_sha256"].asString)
        assertEquals("notes.app", item["source_app"].asString)
        assertEquals("https://source.example/item", item["source_url"].asString)
        assertEquals("auto", item["mode"].asString)
        assertEquals("1970-01-01T00:00:00Z", item["captured_at"].asString)
        assertFalse(item.has("clientCaptureId"))
        assertFalse(item.has("hash"))
    }

    @Test fun clientAnalysisUsesSharedSnakeCaseContractAndServerOmitsIt() {
        val cipher = FakeTextCipher()
        val analysis = ClientAnalysis(
            "openrouter", "vendor/model", "认知",
            AnalysisInterpretation("摘要", "洞察", "行动"),
            listOf(AnalysisBook("书", "作者")),
        )
        val base = CaptureOutboxEntity(
            clientCaptureId = "c", encryptedRawText = cipher.encrypt("text"), hash = "h",
            sourceApp = null, sourceUrl = null, mode = CaptureMode.AUTO, state = OutboxState.READY,
            capturedAt = 0, updatedAt = 0,
        )
        val byok = base.copy(encryptedClientAnalysis = cipher.encrypt(gson.toJson(analysis)))
        val byokJson = JsonParser.parseString(gson.toJson(prepareUploadBatch(listOf(byok), cipher).uploads.single().item)).asJsonObject
        assertEquals("openrouter", byokJson.getAsJsonObject("client_analysis")["provider"].asString)
        assertEquals("认知", byokJson.getAsJsonObject("client_analysis")["primary_tag"].asString)
        assertTrue(byokJson.getAsJsonObject("client_analysis").has("interpretation"))
        assertTrue(byokJson.getAsJsonObject("client_analysis").has("books"))
        assertFalse(byokJson.getAsJsonObject("client_analysis").getAsJsonArray("books")[0].asJsonObject.has("verified"))
        val serverJson = JsonParser.parseString(gson.toJson(prepareUploadBatch(listOf(base), cipher).uploads.single().item)).asJsonObject
        assertFalse(serverJson.has("client_analysis"))
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

    @Test fun serverCardParsesBackendFieldsAndStatus() {
        val card = gson.fromJson(
            """{"id":"card-1","status":"awaiting_confirm","active_version_id":"version-2","last_error":"AI_TIMEOUT"}""",
            ServerCard::class.java,
        )

        assertEquals("card-1", card.id)
        assertEquals("awaiting_confirm", card.status)
        assertEquals("version-2", card.activeVersionId)
        assertEquals("AI_TIMEOUT", card.lastError)
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
