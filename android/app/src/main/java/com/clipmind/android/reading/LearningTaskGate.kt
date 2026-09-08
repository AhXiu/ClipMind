package com.clipmind.android.reading

import kotlinx.coroutines.sync.Mutex

// UI and WorkManager share one process. Do not queue paid work behind an older consent snapshot.
object LearningTaskGate {
    private val mutex = Mutex()
    fun tryAcquire(): Boolean = mutex.tryLock()
    fun release() = mutex.unlock()
}
