package com.clipmind.android.data

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Test

class ServerCardErrorClassificationTest {
    @Test fun httpErrorsContainOnlyStatusCode() {
        assertEquals("HTTP_401", classifyServerCardHttpError(401))
        assertEquals("HTTP_503", classifyServerCardHttpError(503))
    }

    @Test fun networkErrorsUseFixedSanitizedCategories() {
        assertEquals("NETWORK_IO", classifyServerCardNetworkError(IOException("secret response body")))
        assertEquals("NETWORK_UNEXPECTED", classifyServerCardNetworkError(IllegalStateException("token-value")))
    }
}
