package com.clipmind.android.ui

import com.clipmind.android.network.ClientAnalysisRejection
import org.junit.Assert.*
import org.junit.Test

class ClientAnalysisFeedbackTest {
    @Test fun distinguishesBackendProviderSupportFromProviderAuthentication() {
        val message = clientAnalysisFailureMessage(ClientAnalysisRejection.PROVIDER.errorCode)!!
        assertTrue(message.contains("后端已更新"))
        assertTrue(message.contains("不是模型厂商的 Key 认证失败"))
        assertTrue(message.contains("保留在本机"))
    }

    @Test fun legacyErrorDoesNotInventAFieldCause() {
        val message = clientAnalysisFailureMessage("Rejected_invalid_client_analysis")!!
        assertTrue(message.contains("无法区分具体原因"))
        assertTrue(message.contains("同一提交会回放旧拒绝"))
        assertNull(clientAnalysisFailureMessage("NETWORK_IO"))
    }
}
