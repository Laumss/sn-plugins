package me.laumss.notipal.net

import android.os.Looper
import android.util.Log
import java.lang.reflect.Method

internal object PluginIdentity {
    private const val TAG = "PluginIdentity"

    const val PLUGIN_ID = "19uq35yjd3tr3zjr"

    private class Api(val instance: Any, val get: Method?, val set: Method)

    private val api: Api? by lazy {
        try {
            val cls = Class.forName("android.os.PluginSecurityManager")
            val instance = cls.getMethod("getInstance").invoke(null)
                ?: throw IllegalStateException("getInstance returned null")
            val get = runCatching { cls.getMethod("getCurrentPluginId") }.getOrNull()
            val set = cls.getMethod("setCurrentPluginId", String::class.java)
            Api(instance, get, set)
        } catch (e: Throwable) {

            Log.i(TAG, "no PluginSecurityManager: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    @Volatile private var failureLogged = false

    private val setHere = ThreadLocal<Boolean>()

    fun claim() {
        if (Looper.myLooper() == Looper.getMainLooper()) return
        val a = api ?: return
        try {
            val getter = a.get
            if (getter == null) {
                if (setHere.get() == true) return
                a.set.invoke(a.instance, PLUGIN_ID)
                setHere.set(true)
                return
            }
            val current = getter.invoke(a.instance) as? String
            if (current.isNullOrEmpty() || current == PLUGIN_ID) return
            a.set.invoke(a.instance, PLUGIN_ID)
            Log.i(TAG, "thread '${Thread.currentThread().name}' was plugin $current, now Notipal")
        } catch (e: Throwable) {
            if (!failureLogged) {
                failureLogged = true
                val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
                Log.w(TAG, "cannot set the thread's plugin id: ${cause.javaClass.simpleName}: ${cause.message}")
            }
        }
    }
}
