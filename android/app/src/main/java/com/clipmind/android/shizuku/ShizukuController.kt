package com.clipmind.android.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.Process
import com.clipmind.android.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

class ShizukuController(private val context: Context) {
    companion object { const val PERMISSION_REQUEST_CODE = 41 }

    private val mutableState = MutableStateFlow(ShizukuState.UNAVAILABLE)
    val state: StateFlow<ShizukuState> = mutableState.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val reconnectPolicy = ReconnectBackoffPolicy()
    @Volatile private var remote: IClipboardUserService? = null
    @Volatile private var started = false
    @Volatile private var binding = false
    private var reconnectJob: Job? = null

    private val binderReceived = Shizuku.OnBinderReceivedListener {
        cancelReconnect()
        refreshAndBind()
    }
    private val binderDead = Shizuku.OnBinderDeadListener {
        remote = null
        binding = false
        dispatch(ShizukuEvent.BinderDead)
        scheduleReconnect()
    }
    private val permissionResult = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == PERMISSION_REQUEST_CODE) {
            val granted = grantResult == PackageManager.PERMISSION_GRANTED
            dispatch(ShizukuEvent.PermissionResult(granted))
            if (granted) bindUserService()
        }
    }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            binding = false
            val candidate = IClipboardUserService.Stub.asInterface(service)
            try {
                // Equivalent to UserHandle.myUserId(), computed in the client app process (never in shell UserService).
                candidate.configureUserId(Process.myUid() / 100_000)
                remote = candidate
                cancelReconnect()
                dispatch(ShizukuEvent.ServiceConnected)
            } catch (_: Exception) {
                remote = null
                dispatch(ShizukuEvent.ServiceDisconnected)
                scheduleReconnect()
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            remote = null
            binding = false
            dispatch(ShizukuEvent.ServiceDisconnected)
            scheduleReconnect()
        }
    }
    private val args by lazy {
        Shizuku.UserServiceArgs(ComponentName(context, ClipboardUserService::class.java))
            .daemon(false)
            .processNameSuffix("clipboard")
            .debuggable(BuildConfig.DEBUG)
            .version(1)
    }

    @Synchronized fun start() {
        if (started) return
        started = true
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        Shizuku.addRequestPermissionResultListener(permissionResult)
        refreshAndBind()
    }

    fun requestPermission() {
        if (!Shizuku.pingBinder()) {
            dispatch(ShizukuEvent.RuntimeUnavailable)
            scheduleReconnect()
            return
        }
        Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
    }

    fun reconnect() {
        cancelReconnect()
        refreshAndBind()
        if (!Shizuku.pingBinder()) scheduleReconnect()
    }

    @Synchronized fun stop() {
        if (!started) return
        started = false
        cancelReconnect()
        runCatching { Shizuku.unbindUserService(args, connection, true) }
        Shizuku.removeBinderReceivedListener(binderReceived)
        Shizuku.removeBinderDeadListener(binderDead)
        Shizuku.removeRequestPermissionResultListener(permissionResult)
        remote = null
        binding = false
        mutableState.value = ShizukuState.UNAVAILABLE
    }

    fun readClipboard(): ClipboardReadResult {
        if (mutableState.value != ShizukuState.ACTIVE) return ClipboardReadResult.Error("SHIZUKU_NOT_ACTIVE", mutableState.value.name)
        val bundle = try { remote?.readPrimaryClip() } catch (e: Exception) {
            remote = null
            binding = false
            dispatch(ShizukuEvent.ServiceDisconnected)
            scheduleReconnect()
            return ClipboardReadResult.Error("BINDER_CALL_FAILED", e.javaClass.simpleName)
        } ?: return ClipboardReadResult.Error("SERVICE_NOT_CONNECTED", null)
        return if (bundle.getBoolean("ok")) {
            bundle.getString("text")?.let { ClipboardReadResult.Success(it) }
                ?: ClipboardReadResult.Error("NO_PLAIN_TEXT", null)
        } else ClipboardReadResult.Error(bundle.getString("code") ?: "UNKNOWN", bundle.getString("detail"))
    }

    private fun refreshAndBind() {
        if (!started || !Shizuku.pingBinder()) {
            dispatch(ShizukuEvent.RuntimeUnavailable)
            return
        }
        val granted = try { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED } catch (_: Exception) { false }
        dispatch(ShizukuEvent.BinderReceived(granted))
        if (granted) bindUserService()
    }

    @Synchronized private fun bindUserService() {
        if (!started || binding || remote != null) return
        binding = true
        runCatching { Shizuku.bindUserService(args, connection) }
            .onFailure {
                binding = false
                dispatch(ShizukuEvent.ServiceDisconnected)
                scheduleReconnect()
            }
    }

    @Synchronized private fun scheduleReconnect() {
        if (!started || reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            var attempt = 0
            while (started) {
                val waitMillis = reconnectPolicy.delayForAttempt(attempt++) ?: break
                delay(waitMillis)
                refreshAndBind()
                if (mutableState.value == ShizukuState.ACTIVE || mutableState.value == ShizukuState.PERMISSION_REQUIRED) break
            }
        }
    }

    @Synchronized private fun cancelReconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
    }

    @Synchronized private fun dispatch(event: ShizukuEvent) {
        mutableState.value = ShizukuStateReducer.reduce(mutableState.value, event)
    }
}

sealed interface ClipboardReadResult {
    data class Success(val text: String) : ClipboardReadResult
    data class Error(val code: String, val detail: String?) : ClipboardReadResult
}
