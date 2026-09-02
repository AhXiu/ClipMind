package com.clipmind.android.shizuku

class ReconnectBackoffPolicy(
    private val maxAttempts: Int = 5,
    private val initialDelayMillis: Long = 1_000,
    private val maxDelayMillis: Long = 30_000,
) {
    init {
        require(maxAttempts >= 0)
        require(initialDelayMillis > 0)
        require(maxDelayMillis >= initialDelayMillis)
    }

    fun delayForAttempt(attempt: Int): Long? {
        if (attempt !in 0 until maxAttempts) return null
        var delay = initialDelayMillis
        repeat(attempt) { delay = (delay * 2).coerceAtMost(maxDelayMillis) }
        return delay
    }
}
