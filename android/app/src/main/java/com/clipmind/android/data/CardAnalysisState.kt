package com.clipmind.android.data

import com.clipmind.android.network.ClientAnalysisRejection

enum class CardAnalysisState(val label: String) {
    LOCAL("仅本地"), CONFIRM("待确认上传"), QUEUED("等待处理"), RUNNING("正在分析"),
    COMPLETE("分析完成"), FAILED("处理失败"), UNREADABLE("内容无法解密"), DISCARDED("已丢弃"),
}

fun SyncMetadataEntity?.analysisState(): CardAnalysisState = when {
    this == null -> CardAnalysisState.LOCAL
    uploadState == OutboxState.DECRYPTION_FAILED -> CardAnalysisState.UNREADABLE
    uploadState == OutboxState.DISCARDED -> CardAnalysisState.DISCARDED
    uploadState == OutboxState.LOCAL_ONLY -> CardAnalysisState.LOCAL
    uploadState == OutboxState.PENDING_CONFIRMATION -> CardAnalysisState.CONFIRM
    uploadState == OutboxState.REJECTED -> CardAnalysisState.FAILED
    ClientAnalysisRejection.fromStored(lastErrorCode) != null -> CardAnalysisState.FAILED
    encryptedClientAnalysis != null || encryptedServerAnalysis != null -> CardAnalysisState.COMPLETE
    serverCardStatus == "ai_failed" || lastErrorCode != null -> CardAnalysisState.FAILED
    serverCardStatus in setOf("ai_succeeded", "awaiting_confirm", "published", "syncing", "synced") -> CardAnalysisState.COMPLETE
    uploadState == OutboxState.UPLOADING || serverCardStatus == "ai_running" -> CardAnalysisState.RUNNING
    else -> CardAnalysisState.QUEUED
}

fun SyncMetadataEntity?.syncLabel(): String = when {
    this == null || uploadState == OutboxState.LOCAL_ONLY -> "尚未上传"
    serverLastError != null -> "云端操作失败，可刷新重试"
    ClientAnalysisRejection.fromStored(lastErrorCode) != null -> "分析结果被后端拒绝，需处理"
    uploadState == OutboxState.RETRYABLE_ERROR -> "上传待重试"
    uploadState == OutboxState.REJECTED -> "上传被拒绝"
    uploadState == OutboxState.UPLOADING -> "正在处理上传任务"
    serverCardStatus == "synced" -> "Obsidian 已同步"
    serverCardStatus == "syncing" -> "Obsidian 同步中"
    serverCardStatus == "awaiting_confirm" -> "待确认写入 Obsidian"
    uploadState == OutboxState.SUCCEEDED -> "已上传"
    else -> "尚未上传"
}
