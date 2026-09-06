package com.clipmind.android.worker

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

fun interface ImmediateUploadScheduler {
    fun schedule()
}

class WorkManagerImmediateUploadScheduler(context: Context) : ImmediateUploadScheduler {
    private val appContext = context.applicationContext

    override fun schedule() = UploadScheduler.scheduleImmediate(appContext)
}

object UploadScheduler {
    private const val PERIODIC_WORK_NAME = "capture-upload"
    private const val IMMEDIATE_WORK_NAME = "capture-upload-immediate"

    private val connectedConstraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<CaptureUploadWorker>(15, TimeUnit.MINUTES)
            .setConstraints(connectedConstraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    fun scheduleImmediate(context: Context) {
        val request = OneTimeWorkRequestBuilder<CaptureUploadWorker>()
            .setConstraints(connectedConstraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            IMMEDIATE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }
}
