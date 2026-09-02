package com.clipmind.android.network

import java.security.MessageDigest

object BatchRequestMetadata {
    fun idempotencyKey(clientCaptureIds: Collection<String>): String {
        val canonicalIds = clientCaptureIds.sorted().joinToString("\n")
        return "capture-batch-" + MessageDigest.getInstance("SHA-256")
            .digest(canonicalIds.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    fun authorizationHeader(token: String): String? {
        val trimmed = token.trim()
        if (trimmed.isEmpty()) return null
        return if (trimmed.startsWith("Bearer ", ignoreCase = true)) trimmed else "Bearer $trimmed"
    }
}
