package com.clipmind.android.ui

internal fun searchSnippet(text: String, query: String): String {
    val needle = query.trim()
    val match = if (needle.isEmpty()) -1 else text.indexOf(needle, ignoreCase = true)
    if (match < 0) return text
    var start = (match - 32).coerceAtLeast(0)
    var end = (match + needle.length + 100).coerceAtMost(text.length)
    if (start > 0 && text[start].isLowSurrogate()) start--
    if (end < text.length && text[end - 1].isHighSurrogate()) end++
    return (if (start > 0) "..." else "") + text.substring(start, end) + if (end < text.length) "..." else ""
}
