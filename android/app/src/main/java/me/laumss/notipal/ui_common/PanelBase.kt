package me.laumss.notipal.ui_common

import me.laumss.notipal.FloatingPenGuard
import me.laumss.notipal.PenGuardOwner
import me.laumss.notipal.BuildConfig

import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.*
import android.widget.LinearLayout
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.WritableMap
import me.laumss.notipal.overlays.TouchSinkLayout

private const val FIRST_DRAW_SETTLE_MS = 80L
private const val FIRST_DRAW_TIMEOUT_MS = 1500L

abstract class PanelBase(
    protected val reactContext: ReactApplicationContext,
    protected val toolbarModule: ToolbarHost
) {
    abstract val tag: String
    abstract val panelName: String
    open fun onHide() {}

    open fun buildContent(root: LinearLayout) {}

    open val fullScreen: Boolean = false
    open fun buildFullScreenContent(): View? = null

    open val heightRatio: Double = 0.81

    open val cornerRadiusDp: Int = 12

    open val windowFlags: Int = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE

    @Suppress("DEPRECATION")
    open val windowType: Int = WindowManager.LayoutParams.TYPE_PHONE

    open val softInputMode: Int? = null

    open val forcedOrientation: Int? = null

    open val closeOnRotation: Boolean get() = !fullScreen

    open val ownsScreen: Boolean get() = true

    open val keptThroughPanels: Boolean get() = false

    open fun onRotation(): Boolean {
        if (isShowing && closeOnRotation) { hide(); return true }
        return false
    }

    protected val handler = Handler(Looper.getMainLooper())
    protected var windowManager: WindowManager? = null

    protected var rootView: View? = null
        set(value) {
            field = value
            if (value != null) PanelRegistry.onShown(this) else PanelRegistry.onHidden(this)
        }
    protected var layoutParams: WindowManager.LayoutParams? = null

    val isShowing: Boolean get() = rootView != null

    protected val density get() = reactContext.resources.displayMetrics.density
    protected val screenW get() = toolbarModule.screenW
    protected val screenH get() = toolbarModule.screenH
    protected val isLandscape get() = screenW > screenH
    protected val screenLong get() = maxOf(screenW, screenH)
    protected val winW: Int get() = ScreenScale.panelWidth(density, screenW, screenH, screenLong)
    protected val winH: Int get() = ScreenScale.panelHeight(screenH, screenW, screenLong, heightRatio)
    protected fun dp(v: Int) = ScreenScale.dp(reactContext, v)
    protected fun dp(v: Float) = ScreenScale.dp(reactContext, v)
    protected fun sp(v: Float) = ScreenScale.sp(reactContext, v)

    protected fun showPanel(onResult: ((Boolean) -> Unit)? = null) {
        handler.post {
            if (rootView != null) {

                if (yieldsLexi && me.laumss.notipal.LexiLink.isOpen()) me.laumss.notipal.LexiLink.hold(lexiHold)
                onResult?.invoke(true)
                return@post
            }
            fun failShow(error: Throwable? = null) {
                if (BuildConfig.ENABLE_DEBUG) Log.e(tag, "show panel failed: ${error?.message}", error)
                if (yieldsLexi) me.laumss.notipal.LexiLink.release(lexiHold)
                FloatingPenGuard.setFullScreenActive(false, PenGuardOwner.panel(panelName))
                rootView = null
                windowManager = null
                layoutParams = null
                onHide()
                onResult?.invoke(false)
            }

            try {
                toolbarModule.refreshScreenDimensions()
                FloatingPenGuard.setFullScreenActive(true, PenGuardOwner.panel(panelName))
                windowManager = reactContext.getSystemService(android.content.Context.WINDOW_SERVICE) as WindowManager

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

                if (fullScreen) {
                    val root = buildFullScreenContent() ?: run {
                        failShow()
                        return@post
                    }
                    layoutParams = lp
                    rootView = root
                    addWindow(root, lp)
                    FloatingPenGuard.track(PenGuardOwner.panel(panelName), root)
                    if (BuildConfig.ENABLE_DEBUG) Log.i(tag, "full-screen panel shown")
                } else {
                    val cr = dp(cornerRadiusDp).toFloat()
                    val card = LinearLayout(reactContext).apply {
                        orientation = LinearLayout.VERTICAL
                        background = GradientDrawable().apply {
                            setColor(Color.WHITE)
                            setStroke(dp(1), Color.BLACK)
                            cornerRadius = cr
                        }
                        if (cr > 0f) {
                            clipToOutline = true
                            outlineProvider = object : ViewOutlineProvider() {
                                override fun getOutline(v: View, o: android.graphics.Outline) {
                                    o.setRoundRect(0, 0, v.width, v.height, cr)
                                }
                            }
                        }
                    }

                    buildContent(card)

                    val scrim = TouchSinkLayout(reactContext).apply {
                        gravity = Gravity.CENTER
                    }
                    scrim.addView(card, LinearLayout.LayoutParams(winW, winH))

                    layoutParams = lp
                    rootView = scrim
                    addWindow(scrim, lp)
                    if (BuildConfig.ENABLE_DEBUG) Log.i(tag, "panel shown (full-screen scrim) card=${winW}x$winH screen=${screenW}x${screenH} landscape=$isLandscape")
                }
                onResult?.invoke(true)
            } catch (e: Exception) {
                failShow(e)
            } catch (e: OutOfMemoryError) {
                failShow(e)
            }
        }
    }

    protected open val yieldsLexi = false
    private val lexiHold get() = "panel:$panelName"

    @Suppress("DEPRECATION")
    private fun addWindow(view: View, lp: WindowManager.LayoutParams) {
        val wm = windowManager ?: return
        if (yieldsLexi) me.laumss.notipal.LexiLink.hold(lexiHold)
        try {
            wm.addView(view, lp)
        } catch (e: Exception) {
            if (lp.type == WindowManager.LayoutParams.TYPE_PHONE) throw e
            Log.w(tag, "window type ${lp.type} refused (${e.message}), using TYPE_PHONE")
            lp.type = WindowManager.LayoutParams.TYPE_PHONE
            wm.addView(view, lp)
        }
    }

    fun runAfterFirstDraw(block: () -> Unit) {
        handler.post {
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
                try { root.viewTreeObserver.removeOnDrawListener(listener) } catch (_: Exception) {}
                block()
            }

            listener = ViewTreeObserver.OnDrawListener { handler.postDelayed(fire, FIRST_DRAW_SETTLE_MS) }
            root.viewTreeObserver.addOnDrawListener(listener)
            root.invalidate()
            handler.postDelayed(fire, FIRST_DRAW_TIMEOUT_MS)
        }
    }

    protected fun afterNextDraw(block: () -> Unit) {
        val root = rootView ?: return
        var done = false
        lateinit var listener: ViewTreeObserver.OnDrawListener
        listener = ViewTreeObserver.OnDrawListener {
            if (done) return@OnDrawListener
            done = true

            handler.post {
                try { root.viewTreeObserver.removeOnDrawListener(listener) } catch (_: Exception) {}
                block()
            }
        }
        root.viewTreeObserver.addOnDrawListener(listener)
    }

    open fun hide() = teardown()

    private fun teardown() {
        handler.post {
            try { windowManager?.removeView(rootView) } catch (_: Exception) {}

            FloatingPenGuard.setFullScreenActive(false, PenGuardOwner.panel(panelName))
            rootView = null
            windowManager = null
            layoutParams = null
            if (yieldsLexi) me.laumss.notipal.LexiLink.release(lexiHold)
            onHide()
            runDslDisposers()
            emitCloseEvent()
        }
    }

    protected open fun emitCloseEvent() {
        toolbarModule.emitEvent(
            "onNativePanelClose",
            Arguments.createMap().apply { putString("panel", panelName) }
        )
    }

    open fun suspendVisibility() {
        handler.post {
            rootView?.visibility = View.GONE

            FloatingPenGuard.setFullScreenActive(false, PenGuardOwner.panel(panelName))
        }
    }

    open fun resumeVisibility() {
        handler.post {
            if (me.laumss.notipal.FloatingToolbarModule.areFloatingOverlaysSuspendedForPalette()) return@post

            val root = rootView ?: return@post
            root.visibility = View.VISIBLE
            FloatingPenGuard.setFullScreenActive(true, PenGuardOwner.panel(panelName))
            FloatingPenGuard.refresh(PenGuardOwner.panel(panelName), root)
        }
    }

    protected fun makeEmptyView(text: String): LinearLayout = PanelWidgets.emptyView(dslHost, text)

    protected fun makeEmptyView(title: String, hint: String): LinearLayout =
        PanelWidgets.emptyView(dslHost, title, hint)

    protected fun makeOutlinedBtn(label: String, onClick: () -> Unit): android.widget.TextView =
        PanelWidgets.outlinedButton(dslHost, label, onClick)

    protected fun makeFilledBtn(label: String, onClick: () -> Unit): android.widget.TextView =
        PanelWidgets.filledButton(dslHost, label, onClick)

    protected fun formatSize(size: Long): String = PanelWidgets.formatSize(size)

    private val dslDisposers = mutableListOf<() -> Unit>()

    protected val dslHost: PanelHost = object : PanelHost {
        override val ctx: ReactApplicationContext get() = reactContext
        override fun dp(v: Int): Int = this@PanelBase.dp(v)
        override fun dp(v: Float): Int = this@PanelBase.dp(v)
        override fun sp(v: Float): Float = this@PanelBase.sp(v)
        override fun close() { hide(); toolbarModule.restoreToolbar() }
        override fun onDispose(block: () -> Unit) { dslDisposers.add(block) }
        override val panelW: Int get() = winW
        override val screenW: Int get() = this@PanelBase.screenW
        override val screenH: Int get() = this@PanelBase.screenH
        override fun emitEvent(name: String, data: com.facebook.react.bridge.WritableMap) {
            toolbarModule.emitEvent(name, data)
        }
    }

    protected fun renderDsl(root: LinearLayout, build: PanelScope.() -> Unit) {
        val scope = PanelScope().apply(build)
        scope.components.forEach { root.addView(it.build(dslHost)) }
    }

    private fun runDslDisposers() {
        dslDisposers.forEach { runCatching { it() } }
        dslDisposers.clear()
    }

    protected fun makeBottomBar(
        leftButtons: List<View> = emptyList(),
        leftFlex: View? = null,
        rightButtons: List<View>
    ): LinearLayout = PanelWidgets.bottomBar(dslHost, leftButtons, leftFlex, rightButtons)
}
