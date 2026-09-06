package com.clipmind.android.worker

import com.clipmind.android.data.CaptureMode
import com.clipmind.android.data.CaptureOutboxEntity
import com.clipmind.android.data.OutboxState
import com.clipmind.android.network.dto.AcceptedCapture
import com.clipmind.android.network.dto.CaptureBatchResponse
import org.junit.Assert.assertEquals
import org.junit.Test

class AcceptedCardMappingTest {
    @Test fun preservesCardIdForEachAcceptedClientCapture() {
        val batch = listOf(entity("client-1"), entity("client-2"))
        val response = CaptureBatchResponse(accepted = listOf(
            AcceptedCapture("client-2", "capture-2", "card-2"),
            AcceptedCapture("unknown", "capture-x", "card-x"),
            AcceptedCapture("client-1", "capture-1", "card-1"),
        ))

        val mappings = acceptedCardMappings(batch, response).associate { it.clientCaptureId to it.cardId }

        assertEquals(mapOf("client-1" to "card-1", "client-2" to "card-2"), mappings)
    }

    private fun entity(clientId: String) = CaptureOutboxEntity(
        clientCaptureId = clientId,
        encryptedRawText = "encrypted",
        hash = clientId,
        sourceApp = null,
        sourceUrl = null,
        mode = CaptureMode.CONFIRM,
        state = OutboxState.UPLOADING,
        capturedAt = 0,
        updatedAt = 0,
    )
}
