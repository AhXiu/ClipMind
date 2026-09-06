package com.clipmind.android.shizuku

import android.content.AttributionSource
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/** Runs in Shizuku's UserService process. It exposes only one fixed clipboard operation. */
class ClipboardUserService(private val context: Context) : IClipboardUserService.Stub() {
    private val userIdConfiguration = UserIdConfiguration()

    override fun configureUserId(userId: Int) {
        userIdConfiguration.configure(userId)
    }

    override fun readPrimaryClip(): Bundle {
        val userId = userIdConfiguration.getOrNull() ?: return error("USER_ID_NOT_CONFIGURED", null)
        val reflectionFailures = mutableListOf<String>()
        val service = getClipboardBinder()
        if (service == null) {
            reflectionFailures += "CLIPBOARD_SERVICE_MISSING"
        } else {
            val iface = getClipboardInterface(service)
            if (iface == null) {
                reflectionFailures += "CLIPBOARD_INTERFACE_UNAVAILABLE"
            } else {
                val candidates = iface.javaClass.methods
                    .filter { it.name == "getPrimaryClip" }
                    .sortedBy { it.parameterCount }
                if (candidates.isEmpty()) {
                    reflectionFailures += "SIGNATURE_NOT_FOUND:${iface.javaClass.name}"
                } else {
                    for (method in candidates) {
                        val args = argumentsFor(method, userId)
                        if (args == null) {
                            reflectionFailures += "unsupported:${method.signatureDescription()}"
                            continue
                        }
                        try {
                            return clipResult(method.invoke(iface, *args) as? ClipData)
                        } catch (t: Throwable) {
                            val cause = (t as? InvocationTargetException)?.targetException ?: t
                            reflectionFailures += "${method.signatureDescription()}:${cause.javaClass.simpleName}"
                        }
                    }
                }
            }
        }

        // The public API is attempted only with a shell package context whose attribution UID/package
        // match the actual Shizuku UserService process. It is never called with the app package here.
        val fallback = readWithPublicClipboardManager()
        if (fallback != null) return fallback
        reflectionFailures += "public_fallback:unsafe_or_failed"

        val primaryCode = when {
            reflectionFailures.any { it.startsWith("CLIPBOARD_INTERFACE_UNAVAILABLE") } -> "CLIPBOARD_INTERFACE_UNAVAILABLE"
            reflectionFailures.any { it.startsWith("SIGNATURE_NOT_FOUND") } -> "SIGNATURE_NOT_FOUND"
            reflectionFailures.any { it.startsWith("CLIPBOARD_SERVICE_MISSING") } -> "CLIPBOARD_SERVICE_MISSING"
            else -> "ALL_SIGNATURES_FAILED"
        }
        return error(primaryCode, reflectionFailures.joinToString("; ").take(1000))
    }

    private fun clipResult(clip: ClipData?): Bundle {
        if (clip == null || clip.itemCount == 0) return error("EMPTY_CLIP", null)
        val item = clip.getItemAt(0)
        // Reject URI/Intent-only clips; coercion can resolve content providers unexpectedly.
        val text = item.text?.toString() ?: return error("NO_PLAIN_TEXT", null)
        return Bundle().apply { putBoolean("ok", true); putString("text", text) }
    }

    private fun getClipboardBinder(): IBinder? = try {
        val manager = Class.forName("android.os.ServiceManager")
        manager.getMethod("getService", String::class.java).invoke(null, Context.CLIPBOARD_SERVICE) as? IBinder
    } catch (_: Throwable) { null }

    private fun getClipboardInterface(binder: IBinder): Any? = try {
        val stub = Class.forName("android.content.IClipboard\$Stub")
        stub.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
    } catch (_: Throwable) { null }

    /** Supports known primitive/String/AttributionSource variants; unknown object types are not guessed. */
    private fun argumentsFor(method: Method, userId: Int): Array<Any?>? {
        val kinds = mapClipboardParameterTypes(method.parameterTypes.map { it.name }) ?: return null
        val callerPackage = if (Process.myUid() == Process.SHELL_UID) SHELL_PACKAGE else context.packageName
        val attributionSource by lazy { createAttributionSource(callerPackage) }
        return Array(method.parameterCount) { index ->
            when (kinds[index]) {
                ClipboardArgumentKind.CALLING_PACKAGE -> callerPackage
                ClipboardArgumentKind.NULL_STRING -> null
                ClipboardArgumentKind.USER_ID -> userId
                ClipboardArgumentKind.ZERO_INT -> 0
                ClipboardArgumentKind.FALSE_BOOLEAN -> false
                ClipboardArgumentKind.ATTRIBUTION_SOURCE -> attributionSource ?: return null
            }
        }
    }

    private fun createAttributionSource(callerPackage: String): AttributionSource? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        return runCatching {
            AttributionSource.Builder(Process.myUid())
                .setPackageName(callerPackage)
                .build()
        }.getOrNull()
    }

    private fun readWithPublicClipboardManager(): Bundle? {
        if (Process.myUid() != Process.SHELL_UID) return null
        return runCatching {
            val shellContext = context.createPackageContext(SHELL_PACKAGE, Context.CONTEXT_RESTRICTED)
            if (shellContext.packageName != SHELL_PACKAGE) return null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val attribution = shellContext.attributionSource
                if (attribution.uid != Process.SHELL_UID || attribution.packageName != SHELL_PACKAGE) return null
            }
            clipResult(shellContext.getSystemService(ClipboardManager::class.java).primaryClip)
        }.getOrNull()
    }

    private fun Method.signatureDescription() = parameterTypes.joinToString(prefix = "(", postfix = ")") { it.simpleName }

    private fun error(code: String, detail: String?) = Bundle().apply {
        putBoolean("ok", false)
        putString("code", code)
        putString("detail", detail)
    }

    fun destroy() = Unit
    fun exit() = Process.killProcess(Process.myPid())

    private companion object {
        const val SHELL_PACKAGE = "com.android.shell"
    }
}
