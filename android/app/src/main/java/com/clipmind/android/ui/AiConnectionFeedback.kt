package com.clipmind.android.ui

import com.clipmind.android.data.AiMode

internal fun modelCatalogLabel(state: ModelCatalogUiState, mode: AiMode): String = when (state.status) {
    ModelCatalogStatus.IDLE -> "尚未获取模型目录，请确认获取；不会使用本地旧目录冒充实时结果。"
    ModelCatalogStatus.LOADING -> "正在从当前服务商获取模型目录…"
    ModelCatalogStatus.LOADED -> if (state.models.isEmpty()) "请求成功，但未返回适用于当前文本接口的模型；可核对权限或手动输入。" else "已从服务商获取 ${state.models.size} 个文本模型候选。"
    ModelCatalogStatus.UNSUPPORTED -> if (mode == AiMode.BYOK_ARK) "方舟使用账号内的 ep-... 部署 ID，不能用公共模型名代替；请手动填写。" else "尚未接入该服务商已确认可用的模型列表 API，请按官方控制台手动填写模型 ID。"
    ModelCatalogStatus.FAILED -> when (state.errorCode) {
        "MODEL_LIST_INVALID" -> "模型目录格式异常，未使用旧目录或部分结果，请手动重试。"
        "MODEL_LIST_LIMIT" -> "模型目录超过安全大小或分页上限，未展示不完整结果。"
        "BYOK_NETWORK" -> "获取模型目录时网络失败或超时；未发送生成请求，请手动重试。"
        "BYOK_NOT_FOUND" -> "模型目录接口返回 HTTP 404，请核对该接口是否支持及账号权限；不代表模型已下线。"
        else -> aiConnectionLabel(AiConnectionUiState.Failed(state.errorCode.orEmpty(), state.httpStatus))
    }
}

internal fun kimiAccessNotice(mode: AiMode, model: String): String? =
    if (mode == AiMode.BYOK_KIMI && model == "kimi-k3")
        "K3 需在 Kimi 开放平台充值后解锁，注册赠送代金券不可用于 K3；聊天会员不等于 API 权限。请先在官方控制台核对访问条件，勿仅凭测试失败重复充值。"
    else null

internal fun aiConnectionLabel(state: AiConnectionUiState): String = when (state) {
    AiConnectionUiState.Idle -> "尚未测试"
    AiConnectionUiState.Checking -> "正在测试"
    AiConnectionUiState.Success -> "连接与结构化输出校验通过"
    is AiConnectionUiState.Failed -> {
        val explanation = when (state.errorCode) {
            "BYOK_AUTH" -> "认证失败，请检查当前服务商的 API Key"
            "BYOK_PERMISSION" -> "当前账号无此请求的访问权限，请核对平台、模型权限与 API 开通条件；不代表 Key 一定无效"
            "BYOK_KEY_MISSING" -> "请先保存当前服务商的 Key"
            "BYOK_MODEL_MISSING" -> "尚未选择模型，请先保存模型 ID"
            "BYOK_MODEL_UNAVAILABLE" -> "该模型不存在或当前账号不可访问，请核对模型 ID 与 API 权限；不能据此认定模型已下线"
            "BYOK_NOT_FOUND" -> "接口或模型未找到，也可能是当前账号不可访问；请核对下方接口、模型 ID 与 API 权限，不能仅凭 404 判断模型下线"
            "BYOK_QUOTA" -> "API 可用额度不足，请在服务商控制台核对余额、赠送额度适用范围与模型开通条件"
            "BYOK_RATE_LIMIT" -> "请求受限，请在服务商控制台核对限速与额度后手动重试"
            "BYOK_VALIDATION" -> "模型参数或输出不兼容，请核对模型 ID"
            "BYOK_JSON" -> "返回内容不是有效的结构化结果"
            "BYOK_NETWORK" -> "网络失败或超时；已接受的请求仍可能计费"
            "AI_DISABLED" -> "AI 已关闭"
            else -> "服务请求失败，请检查配置后手动重试"
        }
        state.httpStatus?.let { "HTTP $it · $explanation" } ?: explanation
    }
}
