package com.clipmind.android.ui

import com.clipmind.android.network.ClientAnalysisRejection

internal fun clientAnalysisFailureMessage(code: String): String? {
    val reason = ClientAnalysisRejection.fromStored(code) ?: return null
    val detail = when (reason) {
        ClientAnalysisRejection.UNKNOWN -> "后端拒绝客户端分析结果，可能是服务商支持、字段校验或版本不一致；旧错误码无法区分具体原因。"
        ClientAnalysisRejection.PROVIDER -> "后端不接受本次服务商标识，请确认后端已更新并支持该服务商；不是模型厂商的 Key 认证失败。"
        ClientAnalysisRejection.MODEL -> "后端拒绝模型字段：模型 ID 必须非空且不超过 200 个字符。"
        ClientAnalysisRejection.TAG -> "后端拒绝一级分类，请核对客户端与后端的分类规则是否一致。"
        ClientAnalysisRejection.SUMMARY -> "后端拒绝核心释义：内容必须非空且不超过 2000 个字符。"
        ClientAnalysisRejection.INSIGHT -> "后端拒绝场景应用：内容必须非空且不超过 4000 个字符。"
        ClientAnalysisRejection.ACTION -> "后端拒绝认知启发：内容必须非空且不超过 2000 个字符。"
        ClientAnalysisRejection.BOOKS -> "后端拒绝书籍字段：最多 10 本，书名非空且不超过 300 字，作者不超过 200 字。"
    }
    return "$detail 原文和已有分析仍保留在本机；同一提交会回放旧拒绝，不能靠反复重试解决。"
}
