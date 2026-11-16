package me.laumss.notipal.toolbar

import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.ReadableArray
import com.facebook.react.bridge.WritableMap
import me.laumss.notipal.BuildConfig
import me.laumss.notipal.common.Cancelable
import me.laumss.notipal.common.FeatureModule
import me.laumss.notipal.common.Log
import me.laumss.notipal.common.Main
import me.laumss.notipal.common.Strings
import me.laumss.notipal.common.Timers
import me.laumss.notipal.common.Txn
import me.laumss.notipal.host.HostLogMonitor
import me.laumss.notipal.host.HostNotice
import me.laumss.notipal.host.HostPenSignals
import me.laumss.notipal.host.HostSession
import me.laumss.notipal.host.LassoHolds
import me.laumss.notipal.host.MonitorPhase
import me.laumss.notipal.host.PenGuard
import me.laumss.notipal.host.PenOwner
import me.laumss.notipal.mosaic.MosaicEvent
import me.laumss.notipal.mosaic.MosaicLink
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class ToolbarModule(ctx: ReactApplicationContext) : FeatureModule(ctx, "NotipalToolbar") {

    init {
        Toolbar.attach(this)
    }

    override fun invalidate() {
        Toolbar.detach(this)
        super.invalidate()
    }

    @ReactMethod
    fun toggle(mode: String?) = Toolbar.toggleFromPluginButton(mode)

    @ReactMethod
    fun restore(reason: String) = Toolbar.restore(reason)

    @ReactMethod
    fun setActive(tool: String, active: Boolean) = Toolbar.setActive(tool, active)

    @ReactMethod
    fun setClips(filled: ReadableArray) {
        Toolbar.setClips(BooleanArray(filled.size()) { i -> filled.getBoolean(i) })
    }

    @ReactMethod
    fun openSettings() = Settings.open()

    @ReactMethod(isBlockingSynchronousMethod = true)
    fun getState(): WritableMap = Toolbar.stateMap()

    @ReactMethod
    fun beginTxn(name: String, timeoutMs: Double) = InsertTxns.begin(name, timeoutMs.toLong())

    @ReactMethod
    fun endTxn(name: String, ok: Boolean, reason: String) = InsertTxns.end(name, ok, reason)
}

object InsertTxns {
    private const val TAG = "InsertTxns"

    const val BUSY_DELAY_MS = 1_500L

    const val NATIVE_GRACE_MS = 2_000L

    private var current: Txn? = null
    private val busyTimer = Timers()
    private val listeners = CopyOnWriteArrayList<(name: String, ok: Boolean, reason: String) -> Unit>()

    val activeName: String? get() = current?.takeIf { it.active }?.name

    fun addEndListener(l: (name: String, ok: Boolean, reason: String) -> Unit): Cancelable {
        listeners.add(l)
        return Cancelable { listeners.remove(l) }
    }

    fun begin(name: String, timeoutMs: Long) {
        Main.post {
            current?.takeIf { it.active }?.fail("superseded by $name")
            lateinit var txn: Txn
            txn = Txn(name, timeoutMs + NATIVE_GRACE_MS) { ok, reason -> onEnd(txn, ok, reason) }
            current = txn
            busyTimer.after(BUSY_DELAY_MS) {

                if (current === txn && txn.active && Toolbar.hasSession()) Toolbar.busy(true, busyText(name))
            }
        }
    }

    fun end(name: String, ok: Boolean, reason: String) {
        Main.post {
            val txn = current
            if (txn == null || !txn.active || txn.name != name) {
                Log.i(TAG, "end $name ignored (current=${txn?.takeIf { it.active }?.name})")
                return@post
            }
            if (ok) txn.finish() else txn.fail(reason.ifEmpty { "js failed" })
        }
    }

    private fun onEnd(txn: Txn, ok: Boolean, reason: String) {
        if (current === txn) {
            current = null
            busyTimer.cancelAll()
            Toolbar.busy(false)
        }
        listeners.forEach {
            try {
                it(txn.name, ok, reason)
            } catch (t: Throwable) {
                Log.e(TAG, "end listener failed for ${txn.name}", t)
            }
        }
        Toolbar.restore("insert txn ${txn.name} ${if (ok) "done" else reason}")
    }

    private fun busyText(name: String): String? {
        val key = "${name}_processing"
        val text = Strings.get(key)
        return if (text == key) null else text
    }
}

object SelectionHold {
    private const val TAG = "SelectionHold"

    const val HOLD_MAX_MS = 120_000L

    private const val BEGIN_WATCHDOG_MS = HOLD_MAX_MS + 2 * InsertTxns.NATIVE_GRACE_MS

    @Volatile private var inFlight = false

    @Volatile private var held = false

    private var txnName: String? = null
    private var holdTimeout: Cancelable? = null
    private var beginWatchdog: Cancelable? = null
    private val timers = Timers()
    private var installed = false

    private val otherHolders = ConcurrentHashMap<String, () -> Boolean>()

    val applyInFlight: Boolean get() = inFlight

    val selectionHeld: Boolean get() = held

    fun install() {
        LassoHolds.applyInFlight = { inFlight }
        LassoHolds.selectionHeld = { held || otherHolders.values.any { it() } }
        if (installed) return
        installed = true
        HostSession.addNoticeListener { notice -> onNotice(notice) }
        MosaicLink.addListener { event ->
            if (event is MosaicEvent.BoardState && event.visible) Main.post { dropUnderMosaic("Mosaic board visible") }
        }
        InsertTxns.addEndListener { name, _, reason -> onTxnEnd(name, reason) }
    }

    fun addHolder(name: String, test: () -> Boolean) {
        otherHolders[name] = test
    }

    fun begin(txn: String) {
        Main.post {
            if (inFlight) {
                Log.w(TAG, "begin $txn ignored: $txnName already in flight")
                return@post
            }
            inFlight = true
            txnName = txn
            setHeld(false)
            beginWatchdog?.cancel()
            beginWatchdog = timers.after(BEGIN_WATCHDOG_MS) { onBeginWatchdog() }
            PenGuard.setFullScreenActive(true, PenOwner.CLIP_PASTE)
            Toolbar.yieldFor(Toolbar.REASON_PASTE)
        }
    }

    fun park(hold: Boolean) {
        Main.post {
            if (!inFlight) {

                Log.w(TAG, "park for clip paste ignored: no transaction in flight")
                return@post
            }
            setHeld(hold)

            if (hold && HostSession.state.document) {
                HostPenSignals.armDocLassoHold("clip pasted", confirmed = true)
            }
            Toolbar.yieldFor(Toolbar.REASON_PASTE)
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "toolbar parked for clip paste hold=$hold")
        }
    }

    fun finish() {
        Main.post {
            if (!inFlight) {
                Log.w(TAG, "finish ignored: no transaction in flight")
                return@post
            }
            complete("finished")
        }
    }

    private fun complete(why: String) {
        beginWatchdog?.cancel()
        beginWatchdog = null
        inFlight = false
        txnName = null
        if (held && HostSession.state.document) {
            HostPenSignals.armDocLassoHold("palette guard handoff", confirmed = true)
        }
        PenGuard.setFullScreenActive(false, PenOwner.CLIP_PASTE)
        if (!held) {

            HostPenSignals.releaseDocLassoHold("palette apply finished without selection")
        }
        if (!held && !HostLogMonitor.isLassoMenuOpen()) {

            HostPenSignals.scheduleLassoMenuRelease()
        }
        dropUnderMosaic("transaction ended under the Mosaic board")

        Toolbar.resume(Toolbar.REASON_PASTE)
        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "palette-apply guard released ($why) held=$held")
    }

    private fun dropUnderMosaic(reason: String) {
        if (!held || inFlight) return
        if (!HostSession.state.mosaicVisible || !MosaicLink.boardVisible) return
        setHeld(false)
        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "selection hold dropped: $reason")
    }

    private fun setHeld(hold: Boolean) {
        held = hold
        holdTimeout?.cancel()
        holdTimeout = if (hold) timers.after(HOLD_MAX_MS) { onHoldTimeout() } else null
    }

    private fun onHoldTimeout() {
        holdTimeout = null
        if (!held) return
        held = false
        Log.w(TAG, "palette selection hold timed out; restoring toolbar")
        HostPenSignals.releaseDocLassoHold("palette hold timeout")

        if (!HostLogMonitor.isLassoMenuOpen()) HostPenSignals.scheduleLassoMenuRelease()
        Toolbar.restore("selection hold timed out")
    }

    private fun onBeginWatchdog() {
        beginWatchdog = null
        if (!inFlight) return
        Log.w(TAG, "$txnName never ended within ${BEGIN_WATCHDOG_MS}ms of begin; releasing the guard")
        complete("begin watchdog")
    }

    private fun onTxnEnd(name: String, reason: String) {
        if (!inFlight || name != txnName) return
        Log.w(TAG, "$name ended ($reason) before finish; releasing the guard")
        complete("txn end: $reason")
    }

    private fun onNotice(notice: HostNotice) {
        when (notice) {
            is HostNotice.LassoCancelling -> {
                setHeld(false)
            }
            is HostNotice.DocLassoReleased -> {

                if (held) {
                    setHeld(false)
                    timers.after(HostSession.RESTORE_DELAY_NORMAL) { Toolbar.restore("doc lasso released") }
                }
            }
            is HostNotice.Monitor -> when (notice.phase) {
                MonitorPhase.STARTED -> {
                    resetForMonitor()

                    Toolbar.resume(Toolbar.REASON_PASTE)
                }
                MonitorPhase.STOPPING -> resetForMonitor()
                else -> Unit
            }
            else -> Unit
        }
    }

    private fun resetForMonitor() {
        beginWatchdog?.cancel()
        beginWatchdog = null
        inFlight = false
        txnName = null
        setHeld(false)
    }
}
