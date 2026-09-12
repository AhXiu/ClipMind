package com.clipmind.android.ui

import com.clipmind.android.data.AiMode
import com.clipmind.android.network.ByokAnalysisResult
import com.clipmind.android.network.ByokErrorCode
import com.clipmind.android.network.FixedProviderClient
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class AiConnectionFeedbackTest {
    @Test fun catalogFailuresAndUnsupportedDiscoveryNeverClaimALiveList() {
        val unsupported = modelCatalogLabel(ModelCatalogUiState("glm", ModelCatalogStatus.UNSUPPORTED), AiMode.BYOK_GLM)
        assertTrue(unsupported.contains("手动填写"))
        val network = modelCatalogLabel(ModelCatalogUiState("openai", ModelCatalogStatus.FAILED, errorCode = "BYOK_NETWORK"), AiMode.BYOK_OPENAI)
        assertTrue(network.contains("未发送生成请求"))
        val empty = modelCatalogLabel(ModelCatalogUiState("openai", ModelCatalogStatus.LOADED), AiMode.BYOK_OPENAI)
        assertTrue(empty.contains("未返回"))
    }

    @Test fun kimiK3NotFoundKeepsTheSelectedModelAndShowsSafeHttpFeedback() = runBlocking {
        val testKey = "fake-kimi-key-for-offline-test"
        val cases = listOf(
            "<html>not found</html>" to ByokErrorCode.BYOK_NOT_FOUND,
            """{"error":{"code":"model_not_found","message":"$testKey"}}""" to ByokErrorCode.BYOK_MODEL_UNAVAILABLE,
        )
        cases.forEach { (body, expectedCode) ->
            var calls = 0
            val http = OkHttpClient.Builder().addInterceptor { chain ->
                calls++
                val request = chain.request()
                assertEquals("https://api.moonshot.cn/v1/chat/completions", request.url.toString())
                assertEquals("Bearer $testKey", request.header("Authorization"))
                val json = JsonParser.parseString(Buffer().also { request.body!!.writeTo(it) }.readUtf8()).asJsonObject
                assertEquals("kimi-k3", json.get("model").asString)
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                    .code(404).message("Not Found").body(body.toResponseBody()).build()
            }.build()

            val result = FixedProviderClient(http).analyze(AiMode.BYOK_KIMI.providerId, "kimi-k3", testKey, "test excerpt")
            assertEquals(ByokAnalysisResult.Failure(expectedCode, 404), result)
            val failure = result as ByokAnalysisResult.Failure
            val label = aiConnectionLabel(AiConnectionUiState.Failed(failure.code.name, failure.httpStatus))
            assertTrue(label.contains("HTTP 404"))
            assertTrue(label.contains("账号不可访问"))
            assertFalse(label.contains("模型未配置、已下线"))
            assertFalse(label.contains("尚未选择模型"))
            assertFalse(label.contains(testKey))
            assertEquals(1, calls)
        }
    }

    @Test fun missingConfigurationAndHttpNotFoundHaveDifferentGuidance() {
        val missing = aiConnectionLabel(AiConnectionUiState.Failed("BYOK_MODEL_MISSING"))
        val notFound = aiConnectionLabel(AiConnectionUiState.Failed("BYOK_NOT_FOUND", 404))
        assertTrue(missing.contains("尚未选择模型"))
        assertTrue(notFound.contains("HTTP 404"))
        assertTrue(notFound.contains("账号不可访问"))
        assertFalse(notFound.contains("尚未选择模型"))
    }

    @Test fun kimiAccessConditionsAreSpecificToTheDirectK3Model() {
        val hint = kimiAccessNotice(AiMode.BYOK_KIMI, "kimi-k3")!!
        assertTrue(hint.contains("充值"))
        assertTrue(hint.contains("赠送代金券不可用于 K3"))
        assertTrue(hint.contains("勿仅凭测试失败重复充值"))
        assertNull(kimiAccessNotice(AiMode.BYOK_KIMI, "kimi-k2.6"))
        assertNull(kimiAccessNotice(AiMode.BYOK_OPENROUTER, "moonshotai/kimi-k3"))
    }

    @Test fun permissionErrorDoesNotAssertAnInvalidApiKey() {
        val hint = aiConnectionLabel(AiConnectionUiState.Failed("BYOK_PERMISSION", 403))
        assertTrue(hint.contains("HTTP 403"))
        assertTrue(hint.contains("访问权限"))
        assertTrue(hint.contains("不代表 Key 一定无效"))
    }
}
