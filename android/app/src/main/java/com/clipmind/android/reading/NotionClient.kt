package com.clipmind.android.reading

import com.clipmind.android.knowledge.KnowledgeFailure
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import java.util.concurrent.TimeUnit

data class NotionPlan(val page: String, val title: String, val text: String)
class NotionClient {
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).callTimeout(25,TimeUnit.SECONDS).build()
    suspend fun send(plan: NotionPlan, key: String): String = withContext(Dispatchers.IO) {
        require(UUID.fromString(plan.page).toString().equals(plan.page,true))
        require(plan.text.length <= 100_000 && plan.title.length <= 100)
        val children = notionChunks(plan.text).map { text -> mapOf("object" to "block", "type" to "paragraph", "paragraph" to mapOf("rich_text" to listOf(mapOf("type" to "text","text" to mapOf("content" to text))))) }
        val payload = Gson().toJson(mapOf("parent" to mapOf("page_id" to plan.page), "properties" to mapOf("title" to mapOf("type" to "title","title" to listOf(mapOf("type" to "text","text" to mapOf("content" to plan.title))))) ,"children" to children))
        val request = Request.Builder().url("https://api.notion.com/v1/pages").header("Authorization","Bearer $key").header("Notion-Version","2022-06-28").post(payload.toRequestBody("application/json".toMediaType())).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw KnowledgeFailure("NOTION_HTTP_${response.code}")
            val source = response.body?.source() ?: throw KnowledgeFailure("NOTION_EMPTY_RESPONSE")
            source.request(1_048_577); if (source.buffer.size > 1_048_576) throw KnowledgeFailure("NOTION_RESPONSE_LIMIT")
            val id = JsonParser.parseString(source.readUtf8()).asJsonObject.get("id")?.asString ?: throw KnowledgeFailure("NOTION_INVALID_RESPONSE")
            UUID.fromString(id).toString()
        }
    }
}

internal fun notionChunks(text: String): List<String> = buildList {
    var start=0
    while(start<text.length) {
        var end=(start+1800).coerceAtMost(text.length)
        if(end<text.length && text[end-1].isHighSurrogate()) end--
        add(text.substring(start,end));start=end
    }
}
