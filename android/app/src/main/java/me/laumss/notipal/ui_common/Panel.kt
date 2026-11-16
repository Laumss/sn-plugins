package me.laumss.notipal.ui_common

import android.graphics.Outline
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.LinearLayout
import me.laumss.notipal.BuildConfig
import me.laumss.notipal.common.Cancelable
import me.laumss.notipal.common.Log
import me.laumss.notipal.common.Main
import me.laumss.notipal.host.PenGuard
import me.laumss.notipal.host.PenOwner

private const val FIRST_DRAW_SETTLE_MS = 80L
private const val FIRST_DRAW_TIMEOUT_MS = 1500L

abstract class Panel(val name: String) {
    protected open val tag: String get() = "Panel:$name"

    open val fullScreen: Boolean get() = false

    open val heightRatio: Double get() = 0.81

    open val cornerRadiusDp: Int get() = 12

    open val windowFlags: Int get() = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE

    @Suppress("DEPRECATION")
    open val windowType: Int get() = WindowManager.LayoutParams.TYPE_PHONE

    open val softInputMode: Int? get() = null

    open val forcedOrientation: Int? get() = null

    open val closeOnRotation: Boolean get() = !fullScreen

    open val ownsScreen: Boolean get() = true

    open val parksToolbarRect: Boolean get() = true

    open val keptThroughPanels: Boolean get() = false

    protected open val yieldsLexi: Boolean get() = false
    private val lexiOwner get() = "panel:$name"

    abstract fun build(root: LinearLayout)

    open fun onHide() {}

    private var rootView: View? = null
        set(value) {
            field = value
            if (value != null) Panels.onShown(this) else Panels.onHidden(this)
        }
    private var layoutParams: WindowManager.LayoutParams? = null

    val showing: Boolean get() = rootView != null

    protected val windowView: View? get() = rootView

    protected val screenW: Int get() = Style.screenSize().first
    protected val screenH: Int get() = Style.screenSize().second
    protected val cardW: Int get() = Style.panelWidth()
    protected val cardH: Int get() = Style.panelHeight(heightRatio)

    fun show(onShown: (Boolean) -> Unit = {}) {
        Main.post {
            if (rootView != null) {

                if (yieldsLexi && WindowHooks.lexiOpen()) WindowHooks.lexiHold(lexiOwner)
                onShown(true)
                return@post
            }
            fun failShow(error: Throwable? = null) {
                Log.e(tag, "show panel failed: ${error?.message}", error)
                if (yieldsLexi) WindowHooks.lexiRelease(lexiOwner)
                PenGuard.setFullScreenActive(false, PenOwner.panel(name))
                rootView = null
                layoutParams = null
                onHide()
                onShown(false)
            }
            try {
                PenGuard.setFullScreenActive(true, PenOwner.panel(name))
                val lp = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    windowType,
                    windowFlags or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT
                ).apply { gravity = Gravity.TOP or Gravity.START }
                forcedOrientation?.let { lp.screenOrientation = it }
                softInputMode?.let { lp.softInputMode = it }

                val context = me.laumss.notipal.common.App.context
                if (fullScreen) {
                    val root = TouchSinkLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        setBackgroundColor(Style.paper)
                    }
                    build(root)
                    layoutParams = lp
                    rootView = root
                    addWindow(root, lp)
                    PenGuard.track(PenOwner.panel(name), root)
                    if (BuildConfig.ENABLE_DEBUG) Log.i(tag, "full-screen panel shown")
                } else {
                    val radius = Style.dp(cornerRadiusDp).toFloat()
                    val card = LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        background = Style.box(Style.paper, Style.ink, 1f, cornerRadiusDp.toFloat())
                        if (radius > 0f) {
                            clipToOutline = true
                            outlineProvider = object : ViewOutlineProvider() {
                                override fun getOutline(v: View, o: Outline) {
                                    o.setRoundRect(0, 0, v.width, v.height, radius)
                                }
                            }
                        }
                    }
                    build(card)
                    val scrim = TouchSinkLayout(context).apply { gravity = Gravity.CENTER }
                    scrim.addView(card, LinearLayout.LayoutParams(cardW, cardH))
                    layoutParams = lp
                    rootView = scrim
                    addWindow(scrim, lp)
                    if (BuildConfig.ENABLE_DEBUG) {
                        Log.i(tag, "panel shown (full-screen scrim) card=${cardW}x$cardH screen=${screenW}x$screenH")
                    }
                }
                onShown(true)
            } catch (e: Exception) {
                failShow(e)
            } catch (e: OutOfMemoryError) {
                failShow(e)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun addWindow(view: View, lp: WindowManager.LayoutParams) {
        val wm = WindowSupport.windowManager()
        if (yieldsLexi) WindowHooks.lexiHold(lexiOwner)
        try {
            wm.addView(view, lp)
        } catch (e: Exception) {
            if (lp.type == WindowManager.LayoutParams.TYPE_PHONE) throw e
            Log.w(tag, "window type ${lp.type} refused (${e.message}), using TYPE_PHONE")
            lp.type = WindowManager.LayoutParams.TYPE_PHONE
            wm.addView(view, lp)
        }
    }

    fun afterFirstDraw(block: () -> Unit) {
        Main.post {
            val root = rootView
            if (root == null) {
                block()
                return@post
            }
            var done = false
            lateinit var listener: ViewTreeObserver.OnDrawListener
            val fire = Runnable {
                if (done) return@Runnable
                done = true
                try {
                    root.viewTreeObserver.removeOnDrawListener(listener)
                } catch (e: Exception) {
                    Log.w(tag, "remove draw listener: ${e.message}")
                }
                block()
            }

            listener = ViewTreeObserver.OnDrawListener { Main.handler.postDelayed(fire, FIRST_DRAW_SETTLE_MS) }
            root.viewTreeObserver.addOnDrawListener(listener)
            root.invalidate()
            Main.handler.postDelayed(fire, FIRST_DRAW_TIMEOUT_MS)
        }
    }

    protected fun afterNextDraw(block: () -> Unit) {
        val root = rootView ?: return
        var done = false
        lateinit var listener: ViewTreeObserver.OnDrawListener
        listener = ViewTreeObserver.OnDrawListener {
            if (done) return@OnDrawListener
            done = true

            Main.post {
                try {
                    root.viewTreeObserver.removeOnDrawListener(listener)
                } catch (e: Exception) {
                    Log.w(tag, "remove draw listener: ${e.message}")
                }
                block()
            }
        }
        root.viewTreeObserver.addOnDrawListener(listener)
    }

    open fun hide() {
        Main.post {
            val root = rootView
            if (root != null) {
                try {
                    WindowSupport.windowManager().removeView(root)
                } catch (e: Exception) {
                    Log.w(tag, "removeView: ${e.message}")
                }
            }
            PenGuard.setFullScreenActive(false, PenOwner.panel(name))
            rootView = null
            layoutParams = null
            if (yieldsLexi) WindowHooks.lexiRelease(lexiOwner)
            try {
                onHide()
            } catch (e: Exception) {
                Log.e(tag, "onHide failed", e)
            }
            Panels.announceClosed(this)
        }
    }

    open fun suspendVisibility() {
        Main.post {
            rootView?.visibility = View.GONE
            PenGuard.setFullScreenActive(false, PenOwner.panel(name))
        }
    }

    open fun resumeVisibility() {
        Main.post {
            if (WindowHooks.overlaysSuspended()) return@post

            val root = rootView ?: return@post
            root.visibility = View.VISIBLE
            PenGuard.setFullScreenActive(true, PenOwner.panel(name))
            PenGuard.refresh(PenOwner.panel(name), root)
        }
    }

    open fun onRotation(): Boolean {
        if (showing && closeOnRotation) {
            hide()
            return true
        }
        return false
    }

}

object Panels {
    private const val TAG = "Panels"
    private val live = LinkedHashSet<Panel>()
    private var owning = false
    private var panelOwning = false

    private val screenOwners = LinkedHashSet<Any>()
    private val activeListeners = ArrayList<(Boolean) -> Unit>()
    private val closeListeners = ArrayList<(String) -> Unit>()

    internal fun onShown(panel: Panel) {
        live += panel
        updateOwning()
    }

    internal fun onHidden(panel: Panel) {
        live -= panel
        updateOwning()
    }

    fun setScreenOwner(owner: Any, owns: Boolean) {
        Main.assertMain()
        if (owns) screenOwners += owner else screenOwners -= owner
        updateOwning()
    }

    private fun updateOwning() {
        val now = anyShowing()
        if (now != owning) {
            owning = now
            FloatWindows.onPanelsActive(now)
        }

        val panels = live.any { it.ownsScreen }
        if (panels == panelOwning) return
        panelOwning = panels
        for (l in activeListeners.toList()) {
            try {
                l(panels)
            } catch (e: Exception) {
                Log.e(TAG, "active listener failed", e)
            }
        }
    }

    fun toolbarRectParked(): Boolean = live.any { it.ownsScreen && it.parksToolbarRect }

    internal fun announceClosed(panel: Panel) {
        for (l in closeListeners.toList()) {
            try {
                l(panel.name)
            } catch (e: Exception) {
                Log.e(TAG, "close listener failed", e)
            }
        }
    }

    fun anyShowing(): Boolean = live.any { it.ownsScreen } || screenOwners.isNotEmpty()

    fun anyLive(): Boolean = live.isNotEmpty()

    fun hideAll(except: Panel? = null) = live.toList().forEach { if (it !== except) it.hide() }

    fun hideForPanel(except: Panel? = null) =
        live.toList().forEach { if (it !== except && !it.keptThroughPanels) it.hide() }

    fun open(panel: Panel, onShown: (Boolean) -> Unit = {}) {
        Main.post {
            val covered = live.any { it !== panel && it.ownsScreen }
            if (!covered) {
                hideForPanel(panel)
                panel.show(onShown)
                return@post
            }

            val old = live.filter { it !== panel && !it.keptThroughPanels }
            panel.show { ok ->
                if (ok) panel.afterFirstDraw { old.forEach { if (it.showing) it.hide() } }
                onShown(ok)
            }
        }
    }

    fun suspendAll() = live.toList().forEach { it.suspendVisibility() }
    fun resumeAll() = live.toList().forEach { it.resumeVisibility() }

    fun onRotation(): Boolean {
        var closed = false
        for (panel in live.toList()) if (panel.onRotation()) closed = true
        return closed
    }

    fun addActiveListener(l: (Boolean) -> Unit): Cancelable {
        activeListeners += l
        return Cancelable { activeListeners -= l }
    }

    fun addCloseListener(l: (name: String) -> Unit): Cancelable {
        closeListeners += l
        return Cancelable { closeListeners -= l }
    }
}
