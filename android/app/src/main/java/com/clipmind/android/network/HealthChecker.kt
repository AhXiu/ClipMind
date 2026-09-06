package com.clipmind.android.network

import com.clipmind.android.network.dto.HealthResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.Response
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

sealed interface HealthCheckResult {
    data object Success : HealthCheckResult
    data class Failure(val reason: String) : HealthCheckResult
}

class HealthChecker(private val api: CaptureApi) {
    suspend fun check(): HealthCheckResult = withContext(Dispatchers.IO) {
        try {
            mapHealthResponse(api.health())
        } catch (e: CancellationException) {
            throw e
        } catch (_: UnknownHostException) {
            HealthCheckResult.Failure("无法解析服务器地址")
        } catch (_: SocketTimeoutException) {
            HealthCheckResult.Failure("连接超时")
        } catch (_: IOException) {
            HealthCheckResult.Failure("网络连接失败")
        } catch (_: Exception) {
            HealthCheckResult.Failure("连接检查异常")
        }
    }
}

internal fun mapHealthResponse(response: Response<HealthResponse>): HealthCheckResult {
    if (!response.isSuccessful) {
        return HealthCheckResult.Failure("服务器返回 HTTP ${response.code()}")
    }
    return if (response.body()?.status == "ok") {
        HealthCheckResult.Success
    } else {
        HealthCheckResult.Failure("服务器健康状态无效")
    }
}
