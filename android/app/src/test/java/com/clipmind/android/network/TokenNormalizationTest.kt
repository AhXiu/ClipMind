package com.clipmind.android.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TokenNormalizationTest {
    @Test fun blankTokenOmitsAuthorizationHeader() {
        assertNull(BatchRequestMetadata.authorizationHeader("  "))
    }

    @Test fun rawTokenGetsExactlyOneBearerPrefix() {
        assertEquals("Bearer abc", BatchRequestMetadata.authorizationHeader(" abc "))
        assertEquals("bearer abc", BatchRequestMetadata.authorizationHeader(" bearer abc "))
    }
}
