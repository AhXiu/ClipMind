package com.clipmind.android.shizuku

class UserIdConfiguration {
    @Volatile private var configuredUserId: Int? = null

    fun configure(userId: Int) {
        require(userId >= 0) { "userId must be non-negative" }
        configuredUserId = userId
    }

    fun getOrNull(): Int? = configuredUserId
}
