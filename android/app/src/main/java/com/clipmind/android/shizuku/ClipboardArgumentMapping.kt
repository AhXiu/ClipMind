package com.clipmind.android.shizuku

/** Pure description of supported IClipboard arguments, kept separate for local JVM tests. */
internal enum class ClipboardArgumentKind {
    CALLING_PACKAGE,
    NULL_STRING,
    USER_ID,
    ZERO_INT,
    FALSE_BOOLEAN,
    ATTRIBUTION_SOURCE,
}

internal fun mapClipboardParameterTypes(typeNames: List<String>): List<ClipboardArgumentKind>? {
    var stringIndex = 0
    var intIndex = 0
    return typeNames.map { typeName ->
        when (typeName) {
            "java.lang.String" -> if (stringIndex++ == 0) {
                ClipboardArgumentKind.CALLING_PACKAGE
            } else {
                ClipboardArgumentKind.NULL_STRING
            }
            "int", "java.lang.Integer" -> if (intIndex++ == 0) {
                ClipboardArgumentKind.USER_ID
            } else {
                ClipboardArgumentKind.ZERO_INT
            }
            "boolean", "java.lang.Boolean" -> ClipboardArgumentKind.FALSE_BOOLEAN
            "android.content.AttributionSource" -> ClipboardArgumentKind.ATTRIBUTION_SOURCE
            // Do not guess values for OEM-specific object parameters.
            else -> return null
        }
    }
}
