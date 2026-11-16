package me.laumss.notipal.common

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.WritableArray
import com.facebook.react.bridge.WritableMap
import com.facebook.react.modules.core.DeviceEventManagerModule
import me.laumss.notipal.BuildConfig
import java.util.concurrent.atomic.AtomicBoolean

fun interface Cancelable {
    fun cancel()
}

object Main {
    private const val TAG = "Main"
    val handler = Handler(Looper.getMainLooper())

    val isMain: Boolean get() = Looper.myLooper() == Looper.getMainLooper()

    fun post(block: () -> Unit) {
        handler.post(block)
    }

    fun assertMain() {
        if (isMain) return
        val error = IllegalStateException("not on main thread: ${Thread.currentThread().name}")
        if (BuildConfig.DEV_BUILD) throw error
        Log.e(TAG, "assertMain failed", error)
    }
}

class Timers {
    private val pending = LinkedHashSet<Runnable>()

    fun after(ms: Long, block: () -> Unit): Cancelable {
        lateinit var r: Runnable
        r = Runnable {
            synchronized(pending) { pending.remove(r) }
            block()
        }
        synchronized(pending) { pending.add(r) }
        Main.handler.postDelayed(r, ms.coerceAtLeast(0L))
        return Cancelable {
            synchronized(pending) { pending.remove(r) }
            Main.handler.removeCallbacks(r)
        }
    }

    fun cancelAll() {
        val all = synchronized(pending) { pending.toList().also { pending.clear() } }
        for (r in all) Main.handler.removeCallbacks(r)
    }
}

abstract class StateMachine<S : Any, E : Any>(initial: S) {
    var state: S = initial
        private set

    val timers = Timers()

    protected open val tag: String get() = javaClass.simpleName

    private val queue = ArrayDeque<E>()
    private var dispatching = false
    private val listeners = ArrayList<(old: S, new: S) -> Unit>()

    fun send(event: E) {
        Main.assertMain()
        queue.addLast(event)
        if (dispatching) return
        dispatching = true
        try {
            while (queue.isNotEmpty()) step(queue.removeFirst())
        } finally {
            dispatching = false
        }
    }

    private fun step(event: E) {
        val old = state
        val next = try {
            reduce(old, event)
        } catch (t: Throwable) {
            Log.e(tag, "reduce failed: $event in $old", t)
            return
        }
        if (next == old) return
        timers.cancelAll()
        state = next
        Log.d(tag, "$old --$event--> $next")
        try {
            onEnter(next, old)
        } catch (t: Throwable) {
            Log.e(tag, "onEnter failed: $next from $old", t)
        }
        for (l in listeners.toList()) {
            try {
                l(old, next)
            } catch (t: Throwable) {
                Log.e(tag, "listener failed: $old -> $next", t)
            }
        }
    }

    protected abstract fun reduce(state: S, event: E): S

    protected open fun onEnter(state: S, from: S) {}

    fun addListener(l: (old: S, new: S) -> Unit): Cancelable {
        listeners.add(l)
        return Cancelable { listeners.remove(l) }
    }
}

class Txn(val name: String, timeoutMs: Long, private val onEnd: (ok: Boolean, reason: String) -> Unit) {
    private val ended = AtomicBoolean(false)
    private val startedAt = SystemClock.uptimeMillis()
    private val timeout: Cancelable

    val active: Boolean get() = !ended.get()

    init {
        Log.i(TAG, "$name begin (timeout ${timeoutMs}ms)")
        val r = Runnable { end(false, "timeout") }
        Main.handler.postDelayed(r, timeoutMs.coerceAtLeast(0L))
        timeout = Cancelable { Main.handler.removeCallbacks(r) }
    }

    fun finish() = end(true, "ok")

    fun fail(reason: String) = end(false, reason)

    private fun end(ok: Boolean, reason: String) {
        if (!ended.compareAndSet(false, true)) return
        timeout.cancel()
        val ms = SystemClock.uptimeMillis() - startedAt
        if (ok) Log.i(TAG, "$name done ${ms}ms") else Log.w(TAG, "$name failed: $reason ${ms}ms")
        val run = {
            try {
                onEnd(ok, reason)
            } catch (t: Throwable) {
                Log.e(TAG, "$name onEnd failed", t)
            }
        }
        if (Main.isMain) run() else Main.post(run)
    }

    private companion object {
        const val TAG = "Txn"
    }
}

abstract class FeatureModule(ctx: ReactApplicationContext, private val moduleName: String) :
    ReactContextBaseJavaModule(ctx) {

    override fun getName(): String = moduleName

    fun emit(event: String, payload: WritableMap? = null) {
        val ctx = reactApplicationContext
        if (!ctx.hasActiveReactInstance()) {
            Log.w(moduleName, "emit $event dropped: no active React instance")
            return
        }
        try {
            ctx.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java).emit(event, payload)
        } catch (t: Throwable) {
            Log.e(moduleName, "emit $event failed", t)
        }
    }

    fun <T> Promise.run(block: () -> T) {
        val result = try {
            block()
        } catch (t: Throwable) {
            Log.e(moduleName, "call failed", t)
            reject("E_${moduleName.uppercase()}", t.message ?: t.javaClass.simpleName, t)
            return
        }
        resolve(
            when (result) {
                is Unit -> null
                is Long -> result.toDouble()
                is Float -> result.toDouble()
                is WritableMap, is WritableArray, is String, is Boolean, is Int, is Double, null -> result
                else -> result.toString()
            }
        )
    }
}

class CommonModule(ctx: ReactApplicationContext) : FeatureModule(ctx, "NotipalCommon") {

    @ReactMethod
    fun delay(ms: Double, promise: Promise) {
        Main.handler.postDelayed({ promise.resolve(null) }, ms.toLong().coerceAtLeast(0L))
    }

    @ReactMethod
    fun loadPresets(promise: Promise) {
        promise.run { Presets.load() }
    }

    @ReactMethod
    fun savePresets(json: String, promise: Promise) {
        promise.run { Presets.save(json) }
    }

    @ReactMethod
    fun setLocale(locale: String) {
        Strings.locale = locale
    }

    @ReactMethod
    fun setPluginVersion(versionName: String, versionCode: String) {
        AppInfo.versionName = versionName
        AppInfo.versionCode = versionCode
    }

    @ReactMethod
    fun readTextFile(path: String, promise: Promise) {
        Thread({
            promise.run {
                val file = java.io.File(path)
                if (file.isFile) file.readText(Charsets.UTF_8) else null
            }
        }, "NotipalReadText").start()
    }

    @ReactMethod
    fun cropRegion(
        srcPath: String,
        refW: Double,
        refH: Double,
        left: Double,
        top: Double,
        right: Double,
        bottom: Double,
        outPath: String,
        promise: Promise,
    ) {
        promise.run { Bitmaps.cropRegion(srcPath, refW, refH, left, top, right, bottom, outPath) }
    }
}
