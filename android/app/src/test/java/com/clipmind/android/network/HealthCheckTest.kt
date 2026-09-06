package com.clipmind.android.network

import com.clipmind.android.network.dto.HealthResponse
import com.clipmind.android.ui.ConnectionUiState
import com.clipmind.android.ui.toConnectionUiState
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

class HealthCheckTest {
    @Test fun okHealthMapsToConnectedUi() {
        val result = mapHealthResponse(Response.success(HealthResponse("ok")))
        assertEquals(ConnectionUiState.Connected, result.toConnectionUiState())
    }

    @Test fun invalidStatusMapsToReadableFailure() {
        val ui = mapHealthResponse(Response.success(HealthResponse("degraded"))).toConnectionUiState()
        assertTrue(ui is ConnectionUiState.Failed)
        assertEquals("服务器健康状态无效", (ui as ConnectionUiState.Failed).reason)
    }

    @Test fun httpErrorMapsToReadableFailureWithoutResponseBody() {
        val ui = mapHealthResponse(
            Response.error<HealthResponse>(503, "secret details".toResponseBody()),
        ).toConnectionUiState()
        assertEquals(ConnectionUiState.Failed("服务器返回 HTTP 503"), ui)
    }
}
