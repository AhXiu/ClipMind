package com.clipmind.android.shizuku

import org.junit.Assert.assertEquals
import org.junit.Test

class ClipboardDiagnosticUiStateTest {
    @Test fun successExposesOnlyCharacterCount() {
        val state = ClipboardReadResult.Success("private clipboard text").toDiagnosticUiState()
        assertEquals(ClipboardDiagnosticUiState.Success(22), state)
    }

    @Test fun failurePreservesCodeAndSafeDiagnosticDetail() {
        val state = ClipboardReadResult.Error(
            "ALL_SIGNATURES_FAILED",
            "(String,AttributionSource,int):SecurityException",
        ).toDiagnosticUiState()
        assertEquals(
            ClipboardDiagnosticUiState.Failed(
                "ALL_SIGNATURES_FAILED",
                "(String,AttributionSource,int):SecurityException",
            ),
            state,
        )
    }
}
