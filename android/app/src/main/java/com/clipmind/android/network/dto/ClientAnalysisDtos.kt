package com.clipmind.android.network.dto

import com.google.gson.annotations.SerializedName

data class ClientAnalysis(
    @SerializedName("provider") val provider: String,
    @SerializedName("model") val model: String,
    @SerializedName("primary_tag") val primaryTag: String,
    @SerializedName("interpretation") val interpretation: AnalysisInterpretation,
    @SerializedName("books") val books: List<AnalysisBook>,
)

data class AnalysisInterpretation(
    @SerializedName("summary") val summary: String,
    @SerializedName("insight") val insight: String,
    @SerializedName("action") val action: String,
)

data class AnalysisBook(
    @SerializedName("title") val title: String,
    @SerializedName("author") val author: String,
)

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
