package me.laumss.notipal.host

import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import com.facebook.react.bridge.NativeModule
import com.facebook.react.bridge.ReactApplicationContext
import com.ratta.supernote.pluginlib.api.HostDataCacheAPI
import me.laumss.notipal.BuildConfig
import me.laumss.notipal.common.App
import me.laumss.notipal.common.Log
import me.laumss.notipal.common.Main
import me.laumss.notipal.mosaic.MosaicLink
import java.io.File
import java.io.FileInputStream
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.hypot
import android.view.Surface as AndroidSurface

data class ForegroundActivity(val packageName: String, val activityName: String) {

    val key: String get() = "$packageName/$activityName"

    val isNoteCanvas: Boolean
        get() = packageName == HostProbe.NOTE_PACKAGE && activityName == HostProbe.NOTE_INSIDE_PAGES_ACTIVITY

    val isDocument: Boolean get() = packageName == HostProbe.DOC_PACKAGE

    val isHostPage: Boolean get() = isNoteCanvas || isDocument
}

data class HostEntrySnapshot(
    val lastShowType: Int,
    val pluginViewVisible: Boolean,
    val hasButtonEvent: Boolean,
    val hasConfigEvent: Boolean,
    val isCurrentOwner: Boolean,
) {

    fun opensConfig(): Boolean =
        isCurrentOwner && pluginViewVisible &&
            (hasConfigEvent || (lastShowType == 1 && !hasButtonEvent))

    companion object {
        fun read(pluginApp: Any): HostEntrySnapshot {
            fun field(name: String): Any? =
                HostProbe.findDeclaredField(pluginApp.javaClass, name)?.apply { isAccessible = true }?.get(pluginApp)
            val managerType = Class.forName(HostProbe.HOST_PLUGIN_MANAGER_CLASS, false, pluginApp.javaClass.classLoader)
            val manager = managerType.getMethod("getInstance").invoke(null)
            val owner = manager?.let { managerType.getMethod("getTopPluginApp").invoke(it) }
            val names = (field("pluginEventCacheList") as? List<*>)
                .orEmpty().map { it?.javaClass?.simpleName.orEmpty() }
            return HostEntrySnapshot(
                lastShowType = (field("lastShowType") as? Number)?.toInt() ?: -1,
                pluginViewVisible = (field("pluginView") as? View)?.visibility == View.VISIBLE,
                hasButtonEvent = names.any { it.contains("PluginButtonEvent") },
                hasConfigEvent = names.any { it.contains("ConfigButtonPressEvent") },
                isCurrentOwner = owner === pluginApp &&
                    pluginApp.javaClass.getMethod("isShowPluginView").invoke(pluginApp) == true,
            )
        }
    }
}

object HostProbe {
    private const val TAG = "DocChromeProbe"
    private const val FG_TAG = "HostProbe"

    const val NOTE_PACKAGE = "com.ratta.supernote.note"
    const val NOTE_INSIDE_PAGES_ACTIVITY = "com.ratta.supernote.note.view.NoteInsidePagesActivity"
    const val PLUGIN_PACKAGE = "com.ratta.supernote.pluginhost"
    const val DOC_PACKAGE = "com.supernote.document"
    const val SETTINGS_PACKAGE = "com.ratta.settings"
    const val HOST_PLUGIN_MANAGER_CLASS = "com.ratta.supernote.pluginhost.manager.PluginManager"

    private const val DOC_ACTIVITY_MARKER = "ACTIVITY com.supernote.document/"
    private const val NOTE_ACTIVITY_MARKER = "ACTIVITY com.ratta.supernote.note/"

    private val main = Main.handler
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var pendingReason = ""
    @Volatile private var probeInFlight = false
    @Volatile private var contextEpoch = 0L

    private val VIEW_LINE =
        Regex("""^( *)([A-Za-z0-9_.$]+)\{[0-9a-f]+ (\S)\S* \S+ (-?\d+),(-?\d+)-(-?\d+),(-?\d+)""")

    private var probePending = false

    private var staleLayoutRetries = 0
    private const val STALE_LAYOUT_MAX_RETRIES = 4
    private val staleRetryRunnable = Runnable { request("stale layout retry") }

    private val probeRunnable: Runnable = Runnable {
        if (probeInFlight) {
            probePending = true
            return@Runnable
        }
        val reason = pendingReason
        val document = HostSession.isDocumentContext()
        val epoch = contextEpoch
        val menuToken = HostLogMonitor.toolbarMenuProbeToken()
        probePending = false
        probeInFlight = true
        worker.execute {
            val snapshot = query(document)
            main.post {
                probeInFlight = false
                if (epoch == contextEpoch && snapshot != null &&
                    HostSession.isDocumentContext() == document &&
                    !isStaleLayout(snapshot, reason)) {
                    staleLayoutRetries = 0
                    PenGuard.setHostChromeAreas(snapshot.rects, reason)
                    if (BuildConfig.ENABLE_DEBUG) Log.i(TAG,
                        "probe reason=$reason document=$document menuVisible=${snapshot.menuVisible} token=$menuToken rects=${snapshot.rects}")
                    if (document && menuToken != null &&
                        menuToken == HostLogMonitor.toolbarMenuProbeToken() && snapshot.menuVisible == false) {
                        HostLogMonitor.closeDocumentToolbarMenu(menuToken)
                    }
                }

                if (probePending) request(pendingReason)
            }
        }
    }

    fun request(reason: String) {
        pendingReason = reason
        main.removeCallbacks(staleRetryRunnable)
        main.removeCallbacks(probeRunnable)
        main.post(probeRunnable)
    }

    fun clear(reason: String) {
        contextEpoch++
        probePending = false
        staleLayoutRetries = 0
        main.removeCallbacks(staleRetryRunnable)
        main.removeCallbacks(probeRunnable)
        PenGuard.setHostChromeAreas(emptyList(), reason)
    }

    private fun isStaleLayout(snapshot: Snapshot, reason: String): Boolean {
        val windowLandscape = snapshot.windowLandscape ?: return false
        val screenLandscape = HostSession.screenWidth > HostSession.screenHeight
        if (windowLandscape == screenLandscape) return false
        val window = if (windowLandscape) "landscape" else "portrait"
        if (staleLayoutRetries >= STALE_LAYOUT_MAX_RETRIES) {
            Log.w(TAG, "host window still $window after $staleLayoutRetries retries; using it reason=$reason")
            return false
        }
        staleLayoutRetries++
        Log.i(TAG, "probe discarded: host window $window, screen " +
            "${if (screenLandscape) "landscape" else "portrait"} reason=$reason retry=$staleLayoutRetries")
        main.removeCallbacks(staleRetryRunnable)
        main.postDelayed(staleRetryRunnable, PenGuardTiming.DOC_CHROME_STALE_LAYOUT_RETRY_MS)
        return true
    }

    private fun query(document: Boolean): Snapshot? = try {
        val proc = ProcessBuilder("dumpsys", "activity", "top")
            .redirectErrorStream(true).start()
        val result = proc.inputStream.bufferedReader().useLines { parseSnapshot(it, document) }
        if (proc.waitFor() == 0) result else null
    } catch (e: Exception) {
        Log.w(TAG, "dumpsys failed: ${e.message}")
        null
    }

    private class Node(
        val indent: Int,
        val left: Int,
        val top: Int,
        val visible: Boolean,
        val inSideBar: Boolean,
        val isSideBar: Boolean = false,
        val isDecor: Boolean = false,
    )

    internal data class Snapshot(
        val rects: List<Rect>,
        val menuVisible: Boolean?,
        val bottomVisible: Boolean = false,
        val windowLandscape: Boolean? = null,
    )

    internal fun parseSnapshot(lines: Sequence<String>, document: Boolean = true): Snapshot {
        val out = mutableListOf<Rect>()
        var inDoc = false
        var resumed = false
        var sawActiveSideBar = false
        var menuVisible = false
        var bottomVisible = false
        var windowW = 0
        var windowH = 0
        var inHierarchy = false
        var hierarchyIndent = -1
        val stack = ArrayDeque<Node>()
        for (raw in lines) {
            val line = raw.trimEnd('\r')
            if (line.startsWith("  ACTIVITY ")) {
                inDoc = line.contains(if (document) DOC_ACTIVITY_MARKER else NOTE_ACTIVITY_MARKER)
                resumed = false
                inHierarchy = false
                stack.clear()
                continue
            }
            if (!inDoc) continue
            if (line.contains("mResumed=true")) resumed = true
            val indent = line.indexOfFirst { it != ' ' }
            if (indent < 0) continue
            if (!inHierarchy) {
                if (line.trim() == "View Hierarchy:") {
                    inHierarchy = true
                    hierarchyIndent = indent
                    stack.clear()
                }
                continue
            }
            if (indent <= hierarchyIndent) {
                inHierarchy = false
                continue
            }
            while (stack.isNotEmpty() && stack.last().indent >= indent) stack.removeLast()
            val match = VIEW_LINE.find(line)
            if (match == null) {
                if (line.trimStart().startsWith("DecorView")) {
                    stack.clear()
                    stack.addLast(Node(indent, 0, 0, visible = true, inSideBar = false, isDecor = true))
                }
                continue
            }
            val parent = stack.lastOrNull()
            val cls = match.groupValues[2]
            val l = match.groupValues[4].toInt()
            val t = match.groupValues[5].toInt()
            val r = match.groupValues[6].toInt()
            val b = match.groupValues[7].toInt()
            val left = (parent?.left ?: 0) + l
            val top = (parent?.top ?: 0) + t
            val visible = (parent?.visible ?: true) && match.groupValues[3] == "V"
            val inSideBar = (parent?.inSideBar ?: false) || cls.endsWith(".SideBarView")
            val isSideBar = cls.endsWith(".SideBarView")
            stack.addLast(Node(indent, left, top, visible, inSideBar, isSideBar))
            if (resumed && parent?.isDecor == true) {
                windowW = maxOf(windowW, r - l)
                windowH = maxOf(windowH, b - t)
            }
            if (isSideBar && resumed) sawActiveSideBar = true

            val focusable = line.getOrNull(match.groups[3]!!.range.last + 1) == 'F'
            if (resumed && parent?.isSideBar == true && visible && focusable && r > l && b > t) {
                menuVisible = true
            }
            val bottomChrome =
                (line.contains("app:id/ll_page}") || line.contains("app:id/ll_fileName}"))
            if (resumed && visible && r > l && b > t &&
                ((document && inSideBar && cls.endsWith(".ButtonBoxView")) || bottomChrome)) {
                if (bottomChrome) bottomVisible = true
                out += Rect(left, top, left + (r - l), top + (b - t))
            }
        }
        val windowLandscape = if (windowW > 0 && windowH > 0) windowW > windowH else null
        return Snapshot(out, if (sawActiveSideBar) menuVisible else null, bottomVisible, windowLandscape)
    }

    private val resumedActivityRegex = Regex("""mResumedActivity:.*?(\S+)/(\S+)\s""")

    fun foregroundActivity(): ForegroundActivity? {
        return try {
            val bin = if (File("/system/bin/dumpsys").exists()) "/system/bin/dumpsys" else "dumpsys"
            val proc = Runtime.getRuntime().exec(arrayOf(bin, "activity", "activities"))
            var matched: MatchResult? = null
            proc.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    if ("mResumedActivity" in line) matched = resumedActivityRegex.find(line)
                }
            }
            proc.waitFor()
            val found = matched
            if (found != null) {
                val pkg = found.groupValues[1]
                val rawActivity = found.groupValues[2]
                val activity = if (rawActivity.startsWith('.')) pkg + rawActivity else rawActivity
                Log.d(FG_TAG, "foreground: $pkg/$activity")
                ForegroundActivity(pkg, activity)
            } else {
                if (BuildConfig.ENABLE_DEBUG) Log.w(FG_TAG, "foregroundActivity: no mResumedActivity found")
                null
            }
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) Log.w(FG_TAG, "foregroundActivity: ${e.message}")
            null
        }
    }

    fun isHostPage(foreground: ForegroundActivity): Boolean = foreground.isHostPage

    fun isHandwritingForeground(foreground: ForegroundActivity): Boolean = foreground.isHostPage

    fun isNoteCanvasForeground(): Boolean = foregroundActivity()?.isNoteCanvas == true

    fun hostFilePath(): String? = try {
        HostDataCacheAPI.getInstance()?.currentFilePath?.takeIf { it.isNotBlank() }
    } catch (error: Throwable) {
        if (BuildConfig.ENABLE_DEBUG) Log.w(FG_TAG, "current file path read failed: $error")
        null
    }

    @Suppress("DEPRECATION")
    fun readDisplayRotation(): Int = try {
        val wm = App.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        wm.defaultDisplay.rotation
    } catch (e: Exception) {
        Log.w(FG_TAG, "display rotation unreadable: ${e.message}")
        AndroidSurface.ROTATION_0
    }

    fun findDeclaredField(cls: Class<*>, name: String): Field? {
        var c: Class<*>? = cls
        while (c != null) {
            c.declaredFields.firstOrNull { it.name == name }?.let { return it }
            c = c.superclass
        }
        return null
    }

    @Suppress("DEPRECATION")
    fun nativePluginManager(context: ReactApplicationContext? = App.react): NativeModule? =
        context?.catalystInstance?.getNativeModule("NativePluginManager")

    fun hostPluginApp(context: ReactApplicationContext? = App.react): Any? {
        val pm = nativePluginManager(context) ?: return null
        return findDeclaredField(pm.javaClass, "pluginApp")?.apply { isAccessible = true }?.get(pm)
    }

    fun readHostEntrySnapshot(): HostEntrySnapshot? {
        return try {
            HostEntrySnapshot.read(hostPluginApp() ?: return null)
        } catch (t: Throwable) {
            Log.w(FG_TAG, "host entry snapshot unreadable: $t")
            null
        }
    }
}

internal class WindowStateMonitor(
    private val reactContext: ReactApplicationContext,
    private val onChanged: (showing: Boolean) -> Unit,
) {

    fun isFor(context: ReactApplicationContext): Boolean = reactContext === context

    companion object {
        private const val TAG = "WindowStateMonitor"
        private const val ATTACH_RETRY_MS = 1000L
        private const val MAX_ATTACH_RETRIES = 15

        @Volatile
        private var isPluginHostShowing = false

        private val handler = Main.handler
    }

    private val startRequested = AtomicBoolean(false)

    private var hooked = false
    private var retries = 0
    private var container: ViewGroup? = null
    private var listenerField: Field? = null
    private var originalListener: Any? = null
    private var proxyListener: Any? = null
    private var visibilityGeneration = 0L

    private val hookRunnable = Runnable { tryHook() }

    fun start() {
        startRequested.set(true)
        handler.post { sync() }
    }

    fun stop() {
        startRequested.set(false)
        handler.post { sync() }
    }

    private fun sync() {
        if (startRequested.get()) {
            if (!hooked) {
                retries = 0
                handler.removeCallbacks(hookRunnable)
                tryHook()
            } else {

                refreshCurrentVisibility()
            }
        } else {
            handler.removeCallbacks(hookRunnable)
            unhook()
        }
    }

    private fun refreshCurrentVisibility() {
        val current = container
        val live = findPluginContainer()
        if (live == null) {
            if (BuildConfig.ENABLE_DEBUG) {
                Log.i(TAG, "visibility refresh deferred: PluginContainer unavailable")
            }
            return
        }
        if (current !== live) {

            if (BuildConfig.ENABLE_DEBUG) {
                Log.i(TAG, "visibility refresh: container instance changed")
            }
            unhook()
            retries = 0
            tryHook()
            return
        }
        onContainerVisibility(live.visibility == View.VISIBLE, force = true)
    }

    private fun tryHook() {
        if (!startRequested.get() || hooked) return
        val ok = hookInternal()
        if (!ok) {
            retries++
            if (retries <= MAX_ATTACH_RETRIES) {
                handler.postDelayed(hookRunnable, ATTACH_RETRY_MS)
            } else {
                if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "give up hooking after $retries retries")
            }
        }
    }

    private fun hookInternal(): Boolean {
        try {
            if (!reactContext.hasActiveReactInstance()) {
                if (BuildConfig.ENABLE_DEBUG) {
                    Log.w(TAG, "React bridge not ready (retry $retries)")
                }
                return false
            }
            val c = findPluginContainer() ?: run {
                if (BuildConfig.ENABLE_DEBUG) Log.w(TAG, "PluginContainer not ready (retry $retries)")
                return false
            }
            val field = HostProbe.findDeclaredField(c.javaClass, "visibilityListener") ?: run {
                if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "visibilityListener field not found on ${c.javaClass.name}")
                return false
            }
            field.isAccessible = true
            val original = field.get(c)
            if (original != null && Proxy.isProxyClass(original.javaClass)) {

                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "listener already wrapped, skip")
                hooked = true
                container = c
                listenerField = field
                return true
            }
            val iface = field.type
            val proxy = Proxy.newProxyInstance(iface.classLoader, arrayOf(iface)) { proxyObj, method, args ->
                if (method.declaringClass == Any::class.java) {
                    return@newProxyInstance when (method.name) {
                        "hashCode" -> System.identityHashCode(proxyObj)
                        "equals" -> proxyObj === args?.getOrNull(0)
                        "toString" -> "WindowStateMonitor.VisibilityListenerProxy"
                        else -> null
                    }
                }

                var result: Any? = null
                if (original != null) {
                    try {
                        result = method.invoke(original, *(args ?: emptyArray()))
                    } catch (e: Exception) {
                        if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "forward to host listener failed: ${e.message}")
                    }
                }
                if (method.name == "onVisibilityChange") {
                    val vis = (args?.getOrNull(0) as? Int) ?: View.GONE
                    onContainerVisibility(vis == View.VISIBLE)
                }
                result
            }
            field.set(c, proxy)
            container = c
            listenerField = field
            originalListener = original
            proxyListener = proxy
            hooked = true

            onContainerVisibility(c.visibility == View.VISIBLE, force = true)
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "hooked PluginContainer.visibilityListener (original=${original?.javaClass?.name})")
            return true
        } catch (e: AssertionError) {

            if (BuildConfig.ENABLE_DEBUG) Log.w(TAG, "React bridge transition: ${e.message}")
            return false
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "hook error: ${e.message}", e)
            return false
        }
    }

    private fun unhook() {
        try {
            val c = container ?: return
            val field = listenerField ?: return
            val current = field.get(c)

            if (current != null && current === proxyListener) {
                field.set(c, originalListener)
                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "unhooked, original listener restored")
            }
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "unhook error: ${e.message}")
        } finally {
            container = null
            listenerField = null
            originalListener = null
            proxyListener = null
            hooked = false
        }
    }

    private fun onContainerVisibility(visible: Boolean, force: Boolean = false) {
        if (!force && visible == isPluginHostShowing) return
        isPluginHostShowing = visible
        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "PluginHost window state changed: showing=$visible")

        val generation = ++visibilityGeneration
        handler.post {
            if (startRequested.get() && generation == visibilityGeneration) onChanged(visible)
        }
    }

    private fun findPluginContainer(): ViewGroup? {
        return try {
            val pa = HostProbe.hostPluginApp(reactContext) ?: return null
            HostProbe.findDeclaredField(pa.javaClass, "pluginView")?.apply { isAccessible = true }?.get(pa) as? ViewGroup
        } catch (e: AssertionError) {
            if (BuildConfig.ENABLE_DEBUG) Log.w(TAG, "findPluginContainer: React bridge not ready")
            null
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "findPluginContainer error: ${e.message}")
            null
        }
    }
}

object EinkAuto {
    private const val TAG = "InklingEinkAuto"
    private const val HWC_CMD = 1020
    private const val HWC_REGAL = 9
    private const val MOSAIC_RESEND_MS = 5_000L

    private val owners = HashSet<String>()
    private var enabledFlag: Boolean? = null
    private val handler = Main.handler
    private val mosaicResend = object : Runnable {
        override fun run() {
            val enabled = synchronized(this@EinkAuto) { enabledFlag != null }
            if (!enabled) return
            MosaicLink.publishFullUiAuto(true, resend = true)
            handler.postDelayed(this, MOSAIC_RESEND_MS)
        }
    }

    @Synchronized
    fun acquire(owner: String, uiFlag: Boolean): Boolean {
        if (owner in owners) return true
        if (owners.isEmpty()) {
            if (!setFullUiAuto(true, uiFlag, owner)) return false
            enabledFlag = uiFlag
            MosaicLink.publishFullUiAuto(true)
            handler.removeCallbacks(mosaicResend)
            handler.postDelayed(mosaicResend, MOSAIC_RESEND_MS)
        }
        owners.add(owner)
        return true
    }

    @Synchronized
    fun release(owner: String) {
        if (!owners.remove(owner) || owners.isNotEmpty()) return
        val flag = enabledFlag ?: return
        enabledFlag = null
        setFullUiAuto(false, flag, owner)
        handler.removeCallbacks(mosaicResend)
        MosaicLink.publishFullUiAuto(false)
    }

    private fun setFullUiAuto(enable: Boolean, uiFlag: Boolean, owner: String): Boolean {
        return try {
            val eink = App.context.getSystemService("eink") ?: return false
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

object TwoFingerDoubleTap {
    private const val TAG = "NotipalTwoFinger"

    private const val TAP_MAX_MS = 300L
    private const val DOUBLE_TAP_GAP_MS = 400L
    private const val TAP_MOVE_SLOP_DP = 24f
    private const val DOUBLE_TAP_DIST_DP = 120f

    private const val EV_SYN = 0
    private const val EV_KEY = 1
    private const val EV_ABS = 3
    private const val SYN_REPORT = 0
    private const val BTN_TOUCH = 330
    private const val ABS_MT_SLOT = 47
    private const val ABS_MT_POSITION_X = 53
    private const val ABS_MT_POSITION_Y = 54
    private const val ABS_MT_TRACKING_ID = 57
    private const val MAX_SLOTS = 10
    private const val INPUT_EVENT_SIZE_64 = 24
    private const val INPUT_EVENT_SIZE_32 = 16
    private const val PEN_BATCH_EVENTS = 64
    private const val PEN_BATCH_SLEEP_MS = 15L

    private val isA5X2 = TextUtils.equals(Build.BOARD, "A5X2")
    private val main = Main.handler

    private class Devices(val touch: String, val touchName: String, val pen: String?)

    @Volatile private var devices: Devices? = null

    @Volatile private var generation = 0
    @Volatile private var running = false
    @Volatile private var penTip = false

    fun start(onDoubleTap: () -> Unit) {
        if (running) return
        running = true
        val gen = ++generation
        val metrics = App.context.resources.displayMetrics
        val portraitW = minOf(metrics.widthPixels, metrics.heightPixels)
        val portraitH = maxOf(metrics.widthPixels, metrics.heightPixels)
        val density = metrics.density
        Thread({
            val found = devices ?: scanDevices().also { devices = it }
            if (gen == generation) {
                found.pen?.let { pen -> Thread({ readPen(pen, gen) }, "$TAG-pen").apply { isDaemon = true }.start() }
                TouchLoop(found, gen, portraitW, portraitH, density) {
                    main.post { if (gen == generation) onDoubleTap() }
                }.run()
            }
        }, "$TAG-touch").apply { isDaemon = true }.start()
        Log.i(TAG, "started")
    }

    fun stop() {
        if (!running) return
        running = false
        generation++
        penTip = false
        Log.i(TAG, "stopped")
    }

    private val inputEventSize: Int
        get() = if (Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()) INPUT_EVENT_SIZE_64 else INPUT_EVENT_SIZE_32

    private class TouchLoop(
        private val devices: Devices,
        private val gen: Int,
        private val portraitW: Int,
        private val portraitH: Int,
        private val density: Float,
        private val onDoubleTap: () -> Unit,
    ) {
        private val isPtMt = devices.touchName.contains("pt_mt", ignoreCase = true)
        private val trackingId = IntArray(MAX_SLOTS) { -1 }
        private val rawX = IntArray(MAX_SLOTS) { -1 }
        private val rawY = IntArray(MAX_SLOTS) { -1 }
        private val downX = FloatArray(MAX_SLOTS) { Float.NaN }
        private val downY = FloatArray(MAX_SLOTS) { Float.NaN }
        private val point = FloatArray(2)
        private var slot = 0
        private var fingers = 0

        private var episodeStartMs = 0L
        private var episodeMaxFingers = 0
        private var episodeMoved = false
        private var episodeCx = 0f
        private var episodeCy = 0f
        private var lastTapMs = 0L
        private var lastTapX = 0f
        private var lastTapY = 0f

        fun run() {
            val size = inputEventSize
            val buffer = ByteArray(size)
            val bb = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN)
            try {
                FileInputStream(devices.touch).use { fis ->
                    Log.i(TAG, "touch reader opened ${devices.touch} (${devices.touchName})")
                    val pollFd = StructPollfd().apply { fd = fis.fd; events = OsConstants.POLLIN.toShort() }
                    while (gen == generation) {
                        if (Os.poll(arrayOf(pollFd), 200) == 0) continue
                        var offset = 0
                        while (offset < size) {
                            val n = fis.read(buffer, offset, size - offset)
                            if (n < 0) {
                                Log.w(TAG, "EOF on ${devices.touch}")
                                return
                            }
                            offset += n
                        }
                        if (gen != generation) return
                        bb.position(size - 8)
                        val type = bb.short.toInt() and 0xFFFF
                        val code = bb.short.toInt() and 0xFFFF
                        val value = bb.int
                        handle(type, code, value)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "touch reader error on ${devices.touch}", e)
            }
        }

        private fun handle(type: Int, code: Int, value: Int) {
            when (type) {
                EV_ABS -> when (code) {
                    ABS_MT_SLOT -> slot = value.coerceIn(0, MAX_SLOTS - 1)
                    ABS_MT_TRACKING_ID -> {
                        trackingId[slot] = value
                        rawX[slot] = -1
                        rawY[slot] = -1
                        downX[slot] = Float.NaN
                        downY[slot] = Float.NaN
                    }
                    ABS_MT_POSITION_X -> rawX[slot] = value
                    ABS_MT_POSITION_Y -> rawY[slot] = value
                }
                EV_SYN -> if (code == SYN_REPORT) frame(SystemClock.uptimeMillis())
            }
        }

        private fun frame(now: Long) {
            val before = fingers
            var count = 0
            var landed = false
            var sumX = 0f
            var sumY = 0f
            for (i in 0 until MAX_SLOTS) {
                if (trackingId[i] < 0 || rawX[i] < 0 || rawY[i] < 0) continue
                count++
                toPx(rawX[i], rawY[i])
                sumX += point[0]
                sumY += point[1]
                if (downX[i].isNaN()) {
                    downX[i] = point[0]
                    downY[i] = point[1]
                    landed = true
                } else if (abs(point[0] - downX[i]) > TAP_MOVE_SLOP_DP * density ||
                    abs(point[1] - downY[i]) > TAP_MOVE_SLOP_DP * density) {
                    episodeMoved = true
                }
            }
            fingers = count
            if (before == 0 && count > 0) {
                episodeStartMs = now
                episodeMaxFingers = 0
                episodeMoved = false
            }
            if (count > 0) {
                if (landed && penTip) episodeMoved = true
                episodeMaxFingers = maxOf(episodeMaxFingers, count)
                if (count == 2 && before < 2) {
                    episodeCx = sumX / 2f
                    episodeCy = sumY / 2f
                }
            } else if (before > 0) {
                val qualifies = episodeMaxFingers == 2 && !episodeMoved && now - episodeStartMs <= TAP_MAX_MS
                if (qualifies) onTap(now)
            }
        }

        private fun onTap(now: Long) {
            val gap = now - lastTapMs
            val near = hypot(episodeCx - lastTapX, episodeCy - lastTapY) <= DOUBLE_TAP_DIST_DP * density
            if (lastTapMs != 0L && gap <= DOUBLE_TAP_GAP_MS && near) {
                Log.i(TAG, "double tap gap=${gap}ms")
                lastTapMs = 0L
                onDoubleTap()
            } else {
                lastTapMs = now
                lastTapX = episodeCx
                lastTapY = episodeCy
            }
        }

        private fun toPx(x: Int, y: Int) {
            if (isA5X2) {
                if (isPtMt) {
                    point[0] = x * portraitW.toFloat() / portraitH
                    point[1] = y * portraitH.toFloat() / portraitW
                } else {
                    point[0] = x.toFloat()
                    point[1] = y.toFloat()
                }
            } else {
                point[0] = portraitW - y.toFloat()
                point[1] = x.toFloat()
            }
        }
    }

    private fun readPen(path: String, gen: Int) {
        val size = inputEventSize
        val buffer = ByteArray(size * PEN_BATCH_EVENTS)
        val bb = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN)
        try {
            FileInputStream(path).use { fis ->
                Log.i(TAG, "pen reader opened $path")
                val pollFd = StructPollfd().apply { fd = fis.fd; events = OsConstants.POLLIN.toShort() }
                while (gen == generation) {
                    if (Os.poll(arrayOf(pollFd), 200) == 0) continue
                    val read = fis.read(buffer)
                    if (read < 0) return
                    var offset = 0
                    while (offset + size <= read) {
                        bb.position(offset + size - 8)
                        val type = bb.short.toInt() and 0xFFFF
                        val code = bb.short.toInt() and 0xFFFF
                        val value = bb.int
                        if (type == EV_KEY && code == BTN_TOUCH && gen == generation) penTip = value == 1
                        offset += size
                    }
                    Thread.sleep(PEN_BATCH_SLEEP_MS)
                }
            }
        } catch (e: InterruptedException) {
            Log.i(TAG, "pen reader interrupted: $path")
        } catch (e: Exception) {
            Log.e(TAG, "pen reader error on $path", e)
        }
    }

    private fun scanDevices(): Devices {
        var touch: String? = null
        var touchName = ""
        var pen: String? = null
        try {
            val process = ProcessBuilder("getevent", "-lp").redirectErrorStream(true).start()
            val content = process.inputStream.bufferedReader().readText()
            process.waitFor()
            for (block in content.split("add device")) {
                if (block.isBlank()) continue
                val path = Regex("/dev/input/event\\d+").find(block)?.value ?: continue
                val name = Regex("name:\\s*\"(.+?)\"").find(block)?.groupValues?.get(1) ?: continue
                when {
                    name.contains("Wacom", true) || name.contains("Digitizer", true) -> pen = path
                    name.contains("Atmel", true) || name.contains("maXTouch", true) ||
                        name.contains("pt_mt", true) || name.contains("fts_ts", true) ||
                        name.contains("Touchscreen", true) -> {
                        touch = path
                        touchName = name
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "device scan failed", e)
        }
        Log.i(TAG, "devices touch=$touch ($touchName) pen=$pen")
        return Devices(touch ?: "/dev/input/event1", touchName, pen ?: "/dev/input/event0")
    }
}
