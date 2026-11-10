package me.laumss.notipal

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.lang.reflect.Method

internal object EinkAuto {
    private const val TAG = "InklingEinkAuto"
    private const val HWC_CMD = 1020
    private const val HWC_REGAL = 9
    private const val MOSAIC_RESEND_MS = 5_000L

    private val owners = HashSet<String>()
    private var enabledFlag: Boolean? = null
    private val handler = Handler(Looper.getMainLooper())
    private var appContext: Context? = null
    private val mosaicResend = object : Runnable {
        override fun run() {
            val ctx = synchronized(this@EinkAuto) { appContext.takeIf { enabledFlag != null } } ?: return
            MosaicLink.publishFullUiAuto(ctx, true, resend = true)
            handler.postDelayed(this, MOSAIC_RESEND_MS)
        }
    }

    @Synchronized
    fun acquire(context: Context, owner: String, uiFlag: Boolean): Boolean {
        if (owner in owners) return true
        if (owners.isEmpty()) {
            if (!setFullUiAuto(context, true, uiFlag, owner)) return false
            enabledFlag = uiFlag
            appContext = context.applicationContext
            MosaicLink.publishFullUiAuto(context, true)
            handler.removeCallbacks(mosaicResend)
            handler.postDelayed(mosaicResend, MOSAIC_RESEND_MS)
        }
        owners.add(owner)
        return true
    }

    @Synchronized
    fun release(context: Context, owner: String) {
        if (!owners.remove(owner) || owners.isNotEmpty()) return
        val flag = enabledFlag ?: return
        enabledFlag = null
        setFullUiAuto(context, false, flag, owner)
        handler.removeCallbacks(mosaicResend)
        MosaicLink.publishFullUiAuto(context, false)
    }

    private fun setFullUiAuto(context: Context, enable: Boolean, uiFlag: Boolean, owner: String): Boolean {
        return try {
            val eink = context.getSystemService("eink") ?: return false
            val twoArg = einkMethod(eink, "enableFullUiAuto", java.lang.Boolean.TYPE, java.lang.Boolean.TYPE)
            if (twoArg != null) {
                twoArg.invoke(eink, enable, uiFlag)
            } else {
                val oneArg = einkMethod(eink, "enableFullUiAuto", java.lang.Boolean.TYPE) ?: return false
                oneArg.invoke(eink, enable)
            }
            Log.i(TAG, "fullUiAuto=$enable flag=$uiFlag owner=$owner twoArg=${twoArg != null}")
            openRegal(eink, "fullUiAuto=$enable owner=$owner")
            true
        } catch (e: Exception) {
            Log.w(TAG, "fullUiAuto=$enable owner=$owner exception: ${e.cause ?: e}")
            false
        }
    }

    private fun openRegal(eink: Any, reason: String) {
        try {
            val method = einkMethod(eink, "sendHwcCmd", Integer.TYPE, IntArray::class.java) ?: run {
                Log.w(TAG, "regal open skipped reason=$reason: no sendHwcCmd")
                return
            }
            method.invoke(eink, HWC_CMD, intArrayOf(HWC_REGAL, 1))
            Log.i(TAG, "regal open reason=$reason")
        } catch (e: Exception) {
            Log.w(TAG, "regal open failed reason=$reason: ${e.cause ?: e}")
        }
    }

    private fun einkMethod(service: Any, name: String, vararg types: Class<*>): Method? =
        service.javaClass.methods.firstOrNull { it.name == name && it.parameterTypes.contentEquals(types) }
}
