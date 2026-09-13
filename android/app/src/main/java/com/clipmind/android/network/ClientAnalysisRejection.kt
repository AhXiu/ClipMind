package com.clipmind.android.network

import com.clipmind.android.network.dto.RejectedCapture

enum class ClientAnalysisRejection(val errorCode: String) {
    UNKNOWN("REJECTED_invalid_client_analysis"),
    PROVIDER("REJECTED_CLIENT_ANALYSIS_PROVIDER"),
    MODEL("REJECTED_CLIENT_ANALYSIS_MODEL"),
    TAG("REJECTED_CLIENT_ANALYSIS_TAG"),
    SUMMARY("REJECTED_CLIENT_ANALYSIS_SUMMARY"),
    INSIGHT("REJECTED_CLIENT_ANALYSIS_INSIGHT"),
    ACTION("REJECTED_CLIENT_ANALYSIS_ACTION"),
    BOOKS("REJECTED_CLIENT_ANALYSIS_BOOKS");

    companion object {
        fun fromStored(code: String?): ClientAnalysisRejection? = entries.firstOrNull { it.errorCode.equals(code, ignoreCase = true) }

        /** Match only fixed contract messages; never persist the server's free-form message. */
        fun fromResponse(rejection: RejectedCapture): ClientAnalysisRejection? {
            if (!rejection.code.equals("invalid_client_analysis", ignoreCase = true)) return null
            return when (rejection.message) {
                "client_analysis.provider must be ark or openrouter",
                "client_analysis.provider is unsupported" -> PROVIDER
                "client_analysis.model must be non-empty and at most 200 characters" -> MODEL
                "client_analysis.primary_tag is invalid" -> TAG
                "client_analysis.interpretation.summary must be non-empty and at most 2000 characters" -> SUMMARY
                "client_analysis.interpretation.insight must be non-empty and at most 4000 characters" -> INSIGHT
                "client_analysis.interpretation.action must be non-empty and at most 2000 characters" -> ACTION
                "client_analysis.books must contain at most 10 items",
                "client_analysis book title must be non-empty and at most 300 characters",
                "client_analysis book author must be at most 200 characters" -> BOOKS
                else -> UNKNOWN
            }
        }
    }
}
