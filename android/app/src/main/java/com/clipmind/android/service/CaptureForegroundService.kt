package com.clipmind.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.clipmind.android.ClipMindApp
import com.clipmind.android.domain.CaptureHash
import com.clipmind.android.domain.RecentHashDeduplicator
import com.clipmind.android.shizuku.ClipboardReadResult
import com.clipmind.android.shizuku.ShizukuState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class CaptureForegroundService : Service() {
    companion object {
        const val ACTION_START = "com.clipmind.android.START_CAPTURE"
        const val ACTION_STOP = "com.clipmind.android.STOP_CAPTURE"
        private const val CHANNEL = "capture"
        private const val NOTIFICATION_ID = 1001
        private const val TAG = "ClipMindCapture"

        fun start(context: Context) = context.startForegroundService(Intent(context, CaptureForegroundService::class.java).setAction(ACTION_START))
        fun stop(context: Context) = context.startService(Intent(context, CaptureForegroundService::class.java).setAction(ACTION_STOP))
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null
    private val deduplicator = RecentHashDeduplicator()
    private val app get() = application as ClipMindApp

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startAsForeground(notification(app.container.shizuku.state.value))
        scope.launch {
            app.container.shizuku.state.collectLatest { state ->
                applyPolicy(state)
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(state))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                app.container.settings.setCaptureRequested(false)
                stopPolling()
                stopSelf()
            }
            ACTION_START -> {
                app.container.settings.setCaptureRequested(true)
                applyPolicy(app.container.shizuku.state.value)
            }
        }
        return START_STICKY
    }

    private fun applyPolicy(state: ShizukuState) {
        val action = CaptureServicePolicy.evaluate(app.container.settings.captureRequested.value, state)
        if (!action.keepService) {
            stopPolling()
            return
        }
        if (action.runPolling) startPolling() else stopPolling()
    }

    @Synchronized private fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (true) {
                try {
                    when (val result = app.container.shizuku.readClipboard()) {
                        is ClipboardReadResult.Success -> processClipboardText(result.text)
                        is ClipboardReadResult.Error -> if (
                            result.code == "SHIZUKU_NOT_ACTIVE" || result.code == "BINDER_CALL_FAILED"
                        ) {
                            return@launch
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Log.e(TAG, "event=capture_poll_failed type=${error::class.java.simpleName}")
                }
                delay(1_500)
            }
        }
    }

    private suspend fun processClipboardText(text: String) {
        val hash = CaptureHash.sha256(text)
        val now = System.currentTimeMillis()
        if (deduplicator.isDuplicate(hash, now)) return

        val handling = try {
            handlingFor(app.container.repository.capture(text, null, app.container.settings.mode.value, now))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            handlingForFailure(error)
        }
        if (handling.commitRecentHash) deduplicator.commit(hash, now)
        app.container.captureDiagnostics.record(handling.result, now, text.length)
        Log.i(TAG, "event=capture_processed result=${handling.result.logValue()} chars=${text.length}")
    }

    private fun CaptureProcessingResult.logValue(): String = when (this) {
        is CaptureProcessingResult.Stored -> "STORED state=${state.name} record_id=$id"
        is CaptureProcessingResult.Filtered -> "FILTERED reason=$reason"
        CaptureProcessingResult.Duplicate24H -> "DUPLICATE_24H"
        is CaptureProcessingResult.EncryptionFailed -> "ENCRYPTION_FAILED code=$code"
        is CaptureProcessingResult.ProcessingFailed -> "PROCESSING_FAILED type=$type"
    }

    @Synchronized private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    override fun onDestroy() {
        stopPolling()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startAsForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "剪贴板采集", NotificationManager.IMPORTANCE_LOW))
    }

    private fun notification(state: ShizukuState): Notification = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(android.R.drawable.ic_menu_save)
        .setContentTitle("ClipMind 剪贴板采集")
        .setContentText(when (state) {
            ShizukuState.ACTIVE -> "正在采集纯文本"
            ShizukuState.PERMISSION_REQUIRED -> "等待 Shizuku 授权"
            ShizukuState.BINDER_READY -> "正在连接 Shizuku UserService"
            ShizukuState.DEAD -> "Shizuku 已断开，正在有限重连"
            ShizukuState.UNAVAILABLE -> "等待 Shizuku 启动或重连"
        })
        .setOngoing(true)
        .build()
}
