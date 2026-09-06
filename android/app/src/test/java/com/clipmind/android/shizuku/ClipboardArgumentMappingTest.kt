package com.clipmind.android.shizuku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClipboardArgumentMappingTest {
    @Test fun mapsAndroid14AttributionSignatureWithoutChangingExistingRules() {
        val mapped = mapClipboardParameterTypes(listOf(
            "java.lang.String",
            "android.content.AttributionSource",
            "int",
            "int",
            "boolean",
            "java.lang.String",
        ))

        assertEquals(listOf(
            ClipboardArgumentKind.CALLING_PACKAGE,
            ClipboardArgumentKind.ATTRIBUTION_SOURCE,
            ClipboardArgumentKind.USER_ID,
            ClipboardArgumentKind.ZERO_INT,
            ClipboardArgumentKind.FALSE_BOOLEAN,
            ClipboardArgumentKind.NULL_STRING,
        ), mapped)
    }

    @Test fun rejectsUnknownOemObjectInsteadOfGuessing() {
        assertNull(mapClipboardParameterTypes(listOf(
            "java.lang.String",
            "com.meizu.clipboard.UnknownCallerIdentity",
            "int",
        )))
    }
}
