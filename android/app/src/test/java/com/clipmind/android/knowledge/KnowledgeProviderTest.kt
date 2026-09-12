package com.clipmind.android.knowledge

import com.clipmind.android.data.AiCaptureConfiguration
import com.clipmind.android.data.AiMode
import com.clipmind.android.network.CaptureApi
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

class KnowledgeProviderTest {
    @Test fun allByokProvidersShareRequestRulesAndPreserveEvidence() = runBlocking {
        val api = Proxy.newProxyInstance(CaptureApi::class.java.classLoader, arrayOf(CaptureApi::class.java)) { _, _, _ ->
            error("BYOK must not invoke backend generation")
        } as CaptureApi
        val input = KnowledgeRequest(listOf(KnowledgeCard("1", 1, "Transactions ensure consistency."), KnowledgeCard("2", 1, "Logs enable recovery.")))
        val result = KnowledgeResult("Database design", listOf(KnowledgePoint("summary", "Two mechanisms.", listOf(
            Evidence("1", "ensure consistency"), Evidence("2", "enable recovery"),
        ))), emptyList())
        AiMode.entries.filter { it.isByok }.forEach { mode ->
            val http = OkHttpClient.Builder().addInterceptor { chain ->
                val body = JsonParser.parseString(Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8()).asJsonObject
                if (mode != AiMode.BYOK_ARK) assertFalse(body.has("temperature"))
                assertEquals("Bearer ${mode.providerId}-key", chain.request().header("Authorization"))
                val envelope = Gson().toJson(mapOf("choices" to listOf(mapOf("message" to mapOf("content" to Gson().toJson(result))))))
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(envelope.toResponseBody()).build()
            }.build()
            val response = KnowledgeClient(api, http).generate(input, AiCaptureConfiguration(mode, "test-model"), "${mode.providerId}-key", null)
            assertEquals(mode.providerId, response.provider)
            assertEquals(result, response.result)
            assertTrue(KnowledgeContract.validResponse(input, response))
        }
    }
}
