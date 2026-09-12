package com.clipmind.android.network

import com.clipmind.android.data.AiDefaults
import com.clipmind.android.security.ProviderApiKeyStore
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

sealed interface ModelListResult {
    data class Success(val models: List<String>) : ModelListResult
    data class Failure(val code: String, val httpStatus: Int? = null) : ModelListResult
}

fun interface ProviderModelLoader {
    suspend fun listModels(provider: String, apiKey: String): ModelListResult
}

/** Account-scoped metadata only. Never follows server URLs, redirects, or retries. */
class ProviderModelClient(http: OkHttpClient = OkHttpClient()) : ProviderModelLoader {
    private val client = http.newBuilder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS).build()

    override suspend fun listModels(provider: String, apiKey: String): ModelListResult = withContext(Dispatchers.IO) {
        if (AiDefaults.modelsEndpoint(provider) == null) return@withContext ModelListResult.Failure("MODEL_LIST_UNSUPPORTED")
        if (apiKey.isBlank()) return@withContext ModelListResult.Failure("BYOK_KEY_MISSING")
        if (!ProviderApiKeyStore.validApiKey(apiKey)) return@withContext ModelListResult.Failure("BYOK_AUTH")
        try {
            withTimeoutOrNull(45_000) { fetchPages(provider, apiKey) } ?: ModelListResult.Failure("BYOK_NETWORK")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            ModelListResult.Failure("BYOK_NETWORK")
        } catch (_: RuntimeException) {
            ModelListResult.Failure("MODEL_LIST_INVALID")
        }
    }

    private suspend fun fetchPages(provider: String, apiKey: String): ModelListResult {
        val models = linkedSetOf<String>()
        val cursors = mutableSetOf<String>()
        var cursor: String? = null
        var itemCount = 0
        var byteCount = 0L
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45)
        repeat(MAX_PAGES) { index ->
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) return ModelListResult.Failure("BYOK_NETWORK")
            val call = client.newCall(modelListRequest(provider, apiKey, cursor, index + 1))
            call.timeout().timeout(minOf(remaining, TimeUnit.SECONDS.toNanos(15)), TimeUnit.NANOSECONDS)
            val page = call.awaitProviderResponse().use { response ->
                if (!response.isSuccessful) {
                    val failure = providerHttpError(response)
                    return ModelListResult.Failure(failure.code.name, failure.httpStatus)
                }
                val source = response.body?.source() ?: return ModelListResult.Failure("MODEL_LIST_INVALID")
                source.request(MAX_PAGE_BYTES + 1)
                byteCount += source.buffer.size
                if (source.buffer.size > MAX_PAGE_BYTES || byteCount > MAX_TOTAL_BYTES) return ModelListResult.Failure("MODEL_LIST_LIMIT")
                parsePage(provider, source.readUtf8(), index + 1, itemCount)
            }
            itemCount += page.itemCount
            if (itemCount > MAX_ITEMS) return ModelListResult.Failure("MODEL_LIST_LIMIT")
            models += page.models.filterNot { it.contains(apiKey) }
            val next = page.nextCursor ?: return ModelListResult.Success(models.sorted())
            if (page.itemCount == 0 || !validCursor(next) || next.contains(apiKey) || !cursors.add(next)) return ModelListResult.Failure("MODEL_LIST_INVALID")
            cursor = next
        }
        return ModelListResult.Failure("MODEL_LIST_LIMIT")
    }

    internal fun modelListRequest(provider: String, apiKey: String, cursor: String? = null, page: Int = 1): Request {
        val url = requireNotNull(AiDefaults.modelsEndpoint(provider)).toHttpUrl().newBuilder()
        when (provider) {
            "anthropic" -> { url.addQueryParameter("limit", "100"); cursor?.let { url.addQueryParameter("after_id", it) } }
            "gemini" -> { url.addQueryParameter("pageSize", "100"); cursor?.let { url.addQueryParameter("pageToken", it) } }
            "qwen" -> url.addQueryParameter("page_no", page.toString()).addQueryParameter("page_size", "100")
                .addQueryParameter("providers", "qwen").addQueryParameter("capabilities", "TG")
                .addQueryParameter("features", "structured-output")
        }
        return ProviderChatProtocol.authorize(Request.Builder().url(url.build()).get(), provider, apiKey).build()
    }

    private data class Page(val models: List<String>, val itemCount: Int, val nextCursor: String?)

    private fun parsePage(provider: String, raw: String, page: Int, previousCount: Int): Page {
        val root = JsonParser.parseString(raw).asJsonObject
        val entries = when (provider) {
            "gemini" -> root.getAsJsonArray("models")
            "qwen" -> {
                check(root.get("success")?.asBoolean != false)
                root.getAsJsonObject("output").getAsJsonArray("models")
            }
            else -> root.getAsJsonArray("data")
        }
        check(entries != null && entries.size() <= MAX_ITEMS)
        val models = entries.mapNotNull { element ->
            val entry = element.asJsonObject
            val id = when (provider) {
                "gemini" -> entry.string("name")?.removePrefix("models/")
                "qwen" -> entry.string("model")
                else -> entry.string("id")
            }
            requireNotNull(id).takeIf { validCatalogId(it) && isTextCandidate(provider, it, entry) }
        }
        val next = when (provider) {
            "anthropic" -> if (root.get("has_more")?.asBoolean == true) requireNotNull(root.string("last_id")) else null
            "gemini" -> root.string("nextPageToken")?.takeIf { it.isNotEmpty() }
            "qwen" -> {
                val output = root.getAsJsonObject("output")
                val total = output.get("total").asInt
                check(total in 0..MAX_ITEMS && output.get("page_no").asInt == page)
                if (previousCount + entries.size() < total) (page + 1).toString() else null
            }
            else -> { check(root.get("has_more")?.asBoolean != true); null }
        }
        return Page(models, entries.size(), next)
    }

    private fun isTextCandidate(provider: String, id: String, entry: JsonObject): Boolean {
        val name = id.lowercase(java.util.Locale.ROOT)
        if (listOf("embedding", "rerank", "moderation", "realtime", "audio", "tts", "transcrib", "image", "robotics").any { it in name }) return false
        return when (provider) {
            "openai" -> (name.startsWith("gpt-") || name.startsWith("chatgpt-") || Regex("^o[1-9](?:-|$)").containsMatchIn(name)) &&
                listOf("codex", "-pro", "instruct", "search").none { it in name }
            "anthropic" -> name.startsWith("claude-")
            "gemini" -> name.startsWith("gemini-") && entry.getAsJsonArray("supportedGenerationMethods")
                ?.any { it.asString == "generateContent" } == true
            "kimi" -> name.startsWith("kimi-") || name.startsWith("moonshot-")
            "deepseek" -> name.startsWith("deepseek-")
            "qwen" -> name.startsWith("qwen")
            "openrouter" -> {
                val outputs = entry.getAsJsonObject("architecture")?.getAsJsonArray("output_modalities")
                val parameters = entry.getAsJsonArray("supported_parameters")
                (outputs == null || outputs.all { it.asString == "text" }) &&
                    (parameters == null || parameters.any { it.asString == "response_format" })
            }
            else -> false
        }
    }

    private fun JsonObject.string(field: String): String? = get(field)
        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
    private fun validCursor(value: String): Boolean = value.isNotBlank() && value.length <= 2048 && value.none(Char::isISOControl)
    private fun validCatalogId(value: String): Boolean = AiDefaults.validModel(value) &&
        value.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:/@+\\-]{0,199}"))

    companion object {
        private const val MAX_PAGES = 20
        private const val MAX_ITEMS = 5000
        private const val MAX_PAGE_BYTES = 2_097_152L
        private const val MAX_TOTAL_BYTES = 10_485_760L
    }
}
