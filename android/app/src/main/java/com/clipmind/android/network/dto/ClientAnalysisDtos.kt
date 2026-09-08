package com.clipmind.android.network.dto

import com.google.gson.annotations.SerializedName

data class ClientAnalysis(
    @SerializedName("provider") val provider: String,
    @SerializedName("model") val model: String,
    @SerializedName("primary_tag") val primaryTag: String,
    @SerializedName("interpretation") val interpretation: AnalysisInterpretation,
    @SerializedName("books") val books: List<AnalysisBook>,
    @SerializedName("schema_version") val schemaVersion: Int = 1,
    @SerializedName("secondary_tags") val secondaryTags: List<AnalysisTag> = emptyList(),
    @SerializedName("keywords") val keywords: List<String> = emptyList(),
    @SerializedName("articles") val articles: List<ReadingArticle> = emptyList(),
    @SerializedName("value") val value: String = "unrated",
    @SerializedName("value_reason") val valueReason: String = "",
    @SerializedName("questions") val questions: List<String> = emptyList(),
    @SerializedName("warnings") val warnings: List<String> = emptyList(),
)

data class AnalysisInterpretation(
    @SerializedName("summary") val summary: String,
    @SerializedName("insight") val insight: String,
    @SerializedName("action") val action: String,
)

data class AnalysisBook(
    @SerializedName("title") val title: String,
    @SerializedName("author") val author: String,
    @SerializedName("verified") val verified: Boolean = false,
    @SerializedName("openlibrary_key") val openLibraryKey: String? = null,
    @SerializedName("confidence") val confidence: String = "speculative",
    @SerializedName("reason") val reason: String = "",
    @SerializedName("topic") val topic: String = "",
)

data class AnalysisTag(val name: String, val status: String)
data class ReadingArticle(val title: String, val url: String, val summary: String, @SerializedName("checked_at") val checkedAt: String)

internal data class ProviderAnalysis(
    @SerializedName("primary_tag") val primaryTag: String?,
    @SerializedName("interpretation") val interpretation: ProviderInterpretation?,
    @SerializedName("books") val books: List<ProviderBook>?,
)

internal data class ProviderInterpretation(
    @SerializedName("summary") val summary: String?,
    @SerializedName("insight") val insight: String?,
    @SerializedName("action") val action: String?,
)

internal data class ProviderBook(
    @SerializedName("title") val title: String?,
    @SerializedName("author") val author: String?,
)
