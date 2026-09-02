package com.clipmind.android.shizuku

import android.content.ClipData
import android.content.Context
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
        val service = getClipboardBinder() ?: return error("CLIPBOARD_SERVICE_MISSING", null)
        val iface = getClipboardInterface(service) ?: return error("CLIPBOARD_INTERFACE_UNAVAILABLE", null)
        val candidates = iface.javaClass.methods.filter { it.name == "getPrimaryClip" }.sortedBy { it.parameterCount }
        if (candidates.isEmpty()) return error("SIGNATURE_NOT_FOUND", iface.javaClass.name)

        val failures = mutableListOf<String>()
        for (method in candidates) {
            val args = argumentsFor(method, userId)
            if (args == null) {
                failures += "unsupported:${method.parameterTypes.joinToString { it.simpleName }}"
                continue
            }
            try {
                val clip = method.invoke(iface, *args) as? ClipData ?: return error("EMPTY_CLIP", null)
                if (clip.itemCount == 0) return error("EMPTY_CLIP", null)
                val item = clip.getItemAt(0)
                // Reject URI/Intent-only clips; coercion can resolve content providers unexpectedly.
                val text = item.text?.toString() ?: return error("NO_PLAIN_TEXT", null)
                return Bundle().apply { putBoolean("ok", true); putString("text", text) }
            } catch (t: Throwable) {
                val cause = (t as? InvocationTargetException)?.targetException ?: t
                failures += "${method.parameterTypes.joinToString { it.simpleName }}:${cause.javaClass.simpleName}"
            }
        }
        return error("ALL_SIGNATURES_FAILED", failures.joinToString("; ").take(1000))
    }

    private fun getClipboardBinder(): IBinder? = try {
        val manager = Class.forName("android.os.ServiceManager")
        manager.getMethod("getService", String::class.java).invoke(null, Context.CLIPBOARD_SERVICE) as? IBinder
    } catch (_: Throwable) { null }

    private fun getClipboardInterface(binder: IBinder): Any? = try {
        val stub = Class.forName("android.content.IClipboard\$Stub")
        stub.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
    } catch (_: Throwable) { null }

    /** Explicitly supports known primitive/String attribution variants; unknown object types are not guessed. */
    private fun argumentsFor(method: Method, userId: Int): Array<Any?>? {
        var stringIndex = 0
        var intIndex = 0
        val args = arrayOfNulls<Any?>(method.parameterCount)
        val callerPackage = if (Process.myUid() == Process.SHELL_UID) "com.android.shell" else context.packageName
        for ((index, type) in method.parameterTypes.withIndex()) {
            args[index] = when {
                type == String::class.java -> if (stringIndex++ == 0) callerPackage else null
                type == Int::class.javaPrimitiveType || type == Int::class.javaObjectType -> if (intIndex++ == 0) userId else 0
                type == Boolean::class.javaPrimitiveType || type == Boolean::class.javaObjectType -> false
                else -> return null
            }
        }
        return args
    }

    private fun error(code: String, detail: String?) = Bundle().apply {
        putBoolean("ok", false)
        putString("code", code)
        putString("detail", detail)
    }

    fun destroy() = Unit
    fun exit() = Process.killProcess(Process.myPid())
}
