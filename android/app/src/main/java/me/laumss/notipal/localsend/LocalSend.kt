package me.laumss.notipal.localsend

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.FileObserver
import android.widget.TextView
import android.widget.Toast
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.ReadableArray
import me.laumss.notipal.BuildConfig
import me.laumss.notipal.capture.CaptureHooks
import me.laumss.notipal.capture.CaptureSend
import me.laumss.notipal.capture.CaptureSettings
import me.laumss.notipal.clipboard.ClipboardIo
import me.laumss.notipal.common.App
import me.laumss.notipal.common.FeatureModule
import me.laumss.notipal.common.Log
import me.laumss.notipal.common.Main
import me.laumss.notipal.common.PluginIdentity
import me.laumss.notipal.common.StateMachine
import me.laumss.notipal.common.Strings
import me.laumss.notipal.host.HostLogMonitor
import me.laumss.notipal.host.HostPenSignals
import me.laumss.notipal.host.HostPlugins
import me.laumss.notipal.host.HostProbe
import me.laumss.notipal.host.HostSession
import me.laumss.notipal.image.ImageInsert
import me.laumss.notipal.image.ImagePicker
import me.laumss.notipal.lasso.LassoHooks
import me.laumss.notipal.lasso.LassoState
import me.laumss.notipal.lasso.PenLasso
import me.laumss.notipal.links.DocLinks
import me.laumss.notipal.mosaic.MosaicLink
import me.laumss.notipal.textinsert.TextReceive
import me.laumss.notipal.toolbar.InsertTxns
import me.laumss.notipal.toolbar.Settings
import me.laumss.notipal.toolbar.SettingsPanel
import me.laumss.notipal.toolbar.Toolbar
import me.laumss.notipal.toolbar.ToolbarHooks
import me.laumss.notipal.toolbar.Tools
import me.laumss.notipal.ui_common.Dialogs
import me.laumss.notipal.ui_common.FloatWindows
import me.laumss.notipal.ui_common.LassoLayer
import me.laumss.notipal.ui_common.Panels
import me.laumss.notipal.ui_common.WindowHooks
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

data class DiscoveredPeer(
    val alias: String,
    val ip: String,
    val port: Int,
    val deviceType: String,
    val fingerprint: String,
    val lastSeen: Long = System.currentTimeMillis(),
    val useTls: Boolean = true,
)

data class LinkedFile(val path: String, val linkType: Int, val label: String)

data class SendData(val text: String, val images: List<String>, val linked: List<LinkedFile>) {
    companion object {
        val EMPTY = SendData("", emptyList(), emptyList())
    }
}

sealed class ServiceState {
    object Off : ServiceState() {
        override fun toString() = "Off"
    }

    data class Starting(val token: Int) : ServiceState()

    object Running : ServiceState() {
        override fun toString() = "Running"
    }
}

sealed class ServiceEvent {
    data class Start(val token: Int) : ServiceEvent()
    data class Started(val token: Int, val ok: Boolean) : ServiceEvent()
    data class Stop(val reason: String) : ServiceEvent()
}

object LocalSend : StateMachine<ServiceState, ServiceEvent>(ServiceState.Off) {
    override val tag: String get() = "LocalSend"

    const val RECEIVE_DIR = "/sdcard/INBOX"

    const val PACKAGE_DIR = "/sdcard/MyStyle"
    private const val DEVICE_ALIAS = "Supernote"

    private const val SYNC_CONFIRM_WAIT_S = 30L

    private const val IMAGE_CHOICE_WAIT_S = 60L

    private const val PREPARE_IMAGE_TIMEOUT_MS = 2000L

    private const val PREPARE_IMAGE_SETTLE_MS = 650L

    private const val INSERT_RECEIVED_DELAY_MS = 300L

    @Volatile internal var module: LocalSendModule? = null

    private var tokens = 0
    private val waiters = ArrayList<(Boolean) -> Unit>()
    private var installed = false

    override fun reduce(state: ServiceState, event: ServiceEvent): ServiceState = when (event) {
        is ServiceEvent.Start -> if (state == ServiceState.Off) ServiceState.Starting(event.token) else state
        is ServiceEvent.Started ->
            if (state is ServiceState.Starting && state.token == event.token) {
                if (event.ok) ServiceState.Running else ServiceState.Off
            } else {
                state
            }
        is ServiceEvent.Stop -> ServiceState.Off
    }

    override fun onEnter(state: ServiceState, from: ServiceState) {

        if (from is ServiceState.Starting && state !is ServiceState.Starting) {
            val ok = state == ServiceState.Running
            val list = waiters.toList()
            waiters.clear()
            for (w in list) {
                try {
                    w(ok)
                } catch (e: Exception) {
                    Log.e(tag, "start waiter failed", e)
                }
            }
        }
    }

    val running: Boolean get() = LocalSendServer.running

    fun install() {
        CaptureHooks.send = captureSend
        LassoHooks.syncClipboard = { onSyncLongPress() }

        ToolbarHooks.sessionShown = { requestForActiveSession("toolbar show") }
        ToolbarHooks.sessionClosed = { stop("toolbar closed") }
        Settings.registerSection("config_sec_localsend") { page -> buildSettingsSection(page) }
        if (installed) return
        installed = true
        startInboxWatcher()
    }

    fun isWifiConnected(): Boolean {
        val cm = App.context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    fun start(showNetworkTip: Boolean, onResult: (Boolean) -> Unit = {}) {
        Main.post {
            if (!isWifiConnected()) {
                Log.i(tag, "start: no wifi")
                if (showNetworkTip) Dialogs.tip(Strings.get("no_wifi"))
                onResult(false)
                return@post
            }
            when (state) {
                ServiceState.Running -> {
                    if (LocalSendServer.running) {
                        onResult(true)
                        return@post
                    }

                    send(ServiceEvent.Stop("server not running"))
                }
                is ServiceState.Starting -> {
                    waiters += onResult
                    return@post
                }
                ServiceState.Off -> Unit
            }
            val token = ++tokens
            waiters += onResult
            send(ServiceEvent.Start(token))
            if (LocalSendServer.running) {
                send(ServiceEvent.Started(token, true))
                return@post
            }
            requestPermissionsAndStart(token)
        }
    }

    private fun isCurrent(token: Int): Boolean = state.let { it is ServiceState.Starting && it.token == token }

    private fun requestPermissionsAndStart(token: Int) {
        HostPlugins.requestPermission(HostPlugins.FILE_WRITE) { canWrite ->
            if (!isCurrent(token)) return@requestPermission
            if (!canWrite) {
                Dialogs.tip(Strings.get("perm_required"))
                send(ServiceEvent.Started(token, false))
                return@requestPermission
            }
            HostPlugins.requestPermission(HostPlugins.INTERNET) { canNet ->
                if (!isCurrent(token)) return@requestPermission
                if (!canNet) {
                    Dialogs.tip(Strings.get("perm_required"))
                    send(ServiceEvent.Started(token, false))
                    return@requestPermission
                }

                thread(isDaemon = true, name = "LocalSend-Start") {
                    PluginIdentity.claim()
                    var bound = false
                    val ok = try {
                        bound = LocalSendServer.start(DEVICE_ALIAS, LocalSendClient.DEFAULT_PORT, RECEIVE_DIR, "")
                        true
                    } catch (e: Exception) {
                        Log.w(tag, "LocalSend start failed", e)
                        false
                    }
                    Main.post {
                        if (!isCurrent(token)) {

                            if (bound && state == ServiceState.Off) LocalSendServer.stop()
                            return@post
                        }
                        if (ok) Log.i(tag, "LocalSend server started") else Dialogs.tip(Strings.get("perm_required"))
                        send(ServiceEvent.Started(token, ok))
                    }
                }
            }
        }
    }

    fun stop(reason: String) {
        Main.post {
            Log.i(tag, "stop: $reason")
            send(ServiceEvent.Stop(reason))
            LocalSendServer.stop()
        }
    }

    internal fun requestForActiveSession(reason: String) {
        Main.post {
            if (!Toolbar.hasSession()) return@post
            Log.i(tag, "LocalSend auto-start: $reason")
            start(showNetworkTip = false)
        }
    }

    internal fun onWifiLost() {
        if (!LocalSendServer.running) return
        if (BuildConfig.ENABLE_DEBUG) Log.i(tag, "WiFi network lost – stopping LocalSend server")
        LocalSendServer.forceCloseAll()
        Main.post { send(ServiceEvent.Stop("wifi lost")) }
    }

    data class PendingText(val id: String, val text: String, val fileName: String)

    private val pendingTexts = ConcurrentHashMap<String, PendingText>()

    private fun addPendingText(text: String, fileName: String): PendingText {
        val pt = PendingText(UUID.randomUUID().toString().substring(0, 8), text, fileName)
        pendingTexts[pt.id] = pt
        return pt
    }

    internal fun ackPendingText(id: String) {
        pendingTexts.remove(id)
        if (BuildConfig.ENABLE_DEBUG) Log.d(tag, "ackPendingText: id=$id, remaining=${pendingTexts.size}")
    }

    internal fun drainPendingTexts(): List<PendingText> {
        val copy = pendingTexts.values.toList()
        pendingTexts.clear()
        return copy
    }

    private fun emitText(text: String, fileName: String) {
        val pt = addPendingText(text, fileName)
        val m = module
        if (m == null || App.react?.hasActiveReactInstance() != true) {
            Log.w(tag, "text ${pt.id}: bridge unavailable, will flush on next activation")
            return
        }
        if (BuildConfig.ENABLE_DEBUG) Log.d(tag, "text ${pt.id} buffered, emitting")
        m.emit("localsend:text", Arguments.createMap().apply {
            putString("pendingId", pt.id)
            putString("text", text)
            putString("fileName", fileName)
        })
    }

    private fun textGoesToClipboard(): Boolean =
        HostSession.isDocumentContext() || (!HostSession.state.inHostApp && !MosaicLink.boardVisible)

    private fun secondaryUi(): String? = when {
        HostLogMonitor.isSubviewOpen() -> "host-subview"
        HostLogMonitor.isToolbarMenuOpen() -> "host-menu"
        HostSession.state.pluginViewOpen && !MosaicLink.boardVisible -> "plugin-view"
        MosaicLink.chromeMenuOpen -> "mosaic-menu"
        MosaicLink.padOpen -> "mosaic-pad"
        MosaicLink.quickSwitchOpen -> "mosaic-quick-switch"
        ImagePicker.isShowing -> "image-picker"
        Panels.anyLive() || Panels.anyShowing() -> "panel"
        LassoLayer.anyShowing -> "lasso-layer"
        PenLasso.state != LassoState.Idle -> "pen-lasso"
        Dialogs.isShowing() -> "dialog"
        ToolbarHooks.relaySmallUp() -> "relay-small"
        WindowHooks.lexiOpen() -> "lexi"
        else -> null
    }

    internal fun deliverText(text: String, fileName: String) {
        if (BuildConfig.ENABLE_DEBUG) Log.i(tag, "deliverLocalSendText: file=$fileName len=${text.length}")
        val onCanvas = MosaicLink.boardVisible || HostProbe.isNoteCanvasForeground()
        Main.post {
            val secondary = secondaryUi()
            when {
                textGoesToClipboard() -> {
                    if (BuildConfig.ENABLE_DEBUG) {
                        Log.i(tag, "deliverLocalSendText: to clipboard " +
                            "(inNote=${HostSession.state.inHostApp} doc=${HostSession.isDocumentContext()})")
                    }
                    ClipboardIo.copyText(text)
                }
                secondary != null -> {
                    Log.i(tag, "deliverLocalSendText: to clipboard (secondary UI $secondary, receiving=${TextReceive.receiving()})")
                    ClipboardIo.copyText(text)
                }
                TextReceive.receiving() -> emitText(text, fileName)

                onCanvas && !FloatWindows.anyAttached() -> {

                    ClipboardIo.copyText(text)
                    promptOpenTextReceiver(text)
                }
                else -> {
                    if (BuildConfig.ENABLE_DEBUG) Log.i(tag, "deliverLocalSendText: to clipboard (float window up or not on canvas)")
                    ClipboardIo.copyText(text)
                }
            }
        }
    }

    private fun promptOpenTextReceiver(text: String) {
        Dialogs.confirm(Strings.get("text_recv_ask")) { confirmed ->
            if (!confirmed) return@confirm
            if (BuildConfig.ENABLE_DEBUG) Log.i(tag, "text_recv_ask confirmed: requesting text receiver bubble")
            val m = module
            if (m == null) {
                Log.w(tag, "text_recv_ask confirmed but no React module; text stays on the clipboard")
                return@confirm
            }
            m.emit("localsend:openReceiver", Arguments.createMap().apply { putString("text", text) })
        }
    }

    internal fun confirmClipboardSync(senderAlias: String): Boolean {
        val latch = CountDownLatch(1)
        var accepted = false
        val msg = Strings.get("sync_clipboard_ask").replace("%s", senderAlias)
        Log.i(tag, "[SYNC] confirm for prepare-upload")
        Dialogs.confirm(msg) { ok ->

            if (latch.count == 0L) return@confirm
            accepted = ok
            latch.countDown()
        }
        if (!latch.await(SYNC_CONFIRM_WAIT_S, TimeUnit.SECONDS)) latch.countDown()
        if (!accepted) Log.i(tag, "Clipboard sync rejected by user from [$senderAlias]")
        return accepted
    }

    private fun canPromptReceivedImage(): Boolean =
        (MosaicLink.boardVisible || HostProbe.isNoteCanvasForeground()) && !ImagePicker.isShowing

    internal fun askReceivedImage(senderAlias: String): Dialogs.ImageReceiveChoice {
        if (!canPromptReceivedImage()) {
            if (BuildConfig.ENABLE_DEBUG) {
                Log.i(tag, "Image from [$senderAlias] accepted to inbox " +
                    "(inNote=${HostSession.state.inHostApp} doc=${HostSession.isDocumentContext()} panel=${ImagePicker.isShowing})")
            }
            return Dialogs.ImageReceiveChoice.KEEP
        }
        val latch = CountDownLatch(1)
        var choice = Dialogs.ImageReceiveChoice.REJECT
        val msg = Strings.get("image_receive_ask").replace("%s", senderAlias)
        prepareForImageReceive { ready ->

            thread(isDaemon = true, name = "LocalSend-ImageAsk") {
                if (ready && canPromptReceivedImage()) {
                    Log.i(tag, "[INSERT] lasso settled; showing image receive dialog")
                    Dialogs.chooseImageReceive(msg) {
                        if (latch.count == 0L) return@chooseImageReceive
                        choice = it
                        latch.countDown()
                    }
                } else {
                    choice = Dialogs.ImageReceiveChoice.KEEP
                    latch.countDown()
                }
            }
        }
        if (!latch.await(IMAGE_CHOICE_WAIT_S, TimeUnit.SECONDS)) {

            latch.countDown()
            choice = Dialogs.ImageReceiveChoice.REJECT
        }
        return choice
    }

    private var prepareSeq = 0L
    private val prepareWaiters = HashMap<String, (Boolean) -> Unit>()

    private fun prepareForImageReceive(onReady: (Boolean) -> Unit) {
        Main.post {
            if (MosaicLink.boardVisible) {
                onReady(true)
                return@post
            }
            ImageInsert.clearChain("incoming image dialog")
            val completed = AtomicBoolean(false)
            val id = (++prepareSeq).toString()
            val timeout = Runnable {
                prepareWaiters.remove(id)
                if (completed.compareAndSet(false, true)) {
                    Log.w(tag, "[INSERT] receive preparation: lasso close timed out")
                    onReady(false)
                }
            }
            prepareWaiters[id] = { success ->
                prepareWaiters.remove(id)
                if (completed.compareAndSet(false, true)) {
                    Main.handler.removeCallbacks(timeout)
                    Log.i(tag, "[INSERT] receive preparation: lasso close success=$success")
                    if (success) {
                        releaseIdleImageGuard()
                        Main.handler.postDelayed({ onReady(true) }, PREPARE_IMAGE_SETTLE_MS)
                    } else {
                        onReady(false)
                    }
                }
            }
            Main.handler.postDelayed(timeout, PREPARE_IMAGE_TIMEOUT_MS)
            val m = module
            if (m == null || App.react?.hasActiveReactInstance() != true) {
                prepareWaiters[id]?.invoke(false)
                return@post
            }
            m.emit("localsend:prepareImage", Arguments.createMap().apply { putString("requestId", id) })
        }
    }

    internal fun onImagePrepared(requestId: String, success: Boolean) {
        Main.post { prepareWaiters[requestId]?.invoke(success) }
    }

    private fun releaseIdleImageGuard() {
        if (!HostPenSignals.insertImageFlowActive || InsertTxns.activeName == ImageInsert.TXN) return
        Log.i(tag, "[INSERT] receive preparation: release idle insert-image guard")
        HostPenSignals.endInsertImageGuard()
    }

    internal fun insertReceivedImage(path: String) {
        Main.post {
            val canInsert = MosaicLink.boardVisible || (HostSession.state.inHostApp && !HostSession.isDocumentContext())
            if (!canInsert) {
                Log.i(tag, "received image kept in inbox: left the note before it arrived")
                return@post
            }
            Main.handler.postDelayed({
                ImageInsert.request(ImageInsert.Item(path, deletePath = path))
            }, INSERT_RECEIVED_DELAY_MS)
        }
    }

    private var inboxObserver: FileObserver? = null
    private val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "bmp", "gif", "webp")

    private fun startInboxWatcher() {
        if (inboxObserver != null) return
        File(RECEIVE_DIR).mkdirs()

        @Suppress("DEPRECATION")
        val observer = object : FileObserver(RECEIVE_DIR, CLOSE_WRITE or MOVED_TO) {
            override fun onEvent(event: Int, path: String?) {
                if (path == null) return
                val ext = path.substringAfterLast('.', "").lowercase()
                if (ext in IMAGE_EXTS) ImagePicker.onFileReceived()
                if (ext in DocLinks.DOC_EXTS) DocLinks.onFileReceived()
            }
        }
        observer.startWatching()
        inboxObserver = observer
        if (BuildConfig.ENABLE_DEBUG) Log.i(tag, "INBOX watcher started: $RECEIVE_DIR")
    }

    private fun toast(key: String) {
        Main.post { Toast.makeText(App.context, Strings.get(key), Toast.LENGTH_LONG).show() }
    }

    private val captureSend = object : CaptureSend {
        override fun ready(): Boolean {
            if (!isWifiConnected()) {
                Dialogs.tip(Strings.get("no_wifi"))
                return false
            }
            if (!running) {
                if (BuildConfig.ENABLE_DEBUG) Log.i(tag, "[CROP-SEND] auto-start LocalSend")
                start(showNetworkTip = true)
            }
            return true
        }

        override fun hasDefaultPeer(): Boolean = LocalSendClient.defaultPeer() != null

        override fun sendDirect(path: String, onDone: (Boolean) -> Unit) {
            val defaultPeer = LocalSendClient.defaultPeer()
            if (defaultPeer == null) {

                SendPanel.open(data = SendData("", listOf(path), emptyList())) { shown ->
                    if (!shown) {
                        File(path).delete()
                        toast("screenshot_panel_failed")
                    }
                    onDone(false)
                }
                return
            }
            thread(isDaemon = true, name = "LocalSend-CropSend") {
                PluginIdentity.claim()
                var sent = false
                try {
                    LocalSendClient.sendFileDirect(defaultPeer.ip, defaultPeer.port, path, defaultPeer.useTls)
                    sent = true
                    Dialogs.tip(Strings.get("send_success"))
                } catch (e: Exception) {
                    val msg = if (e.message?.contains("403") == true) Strings.get("image_send_rejected")
                    else "${Strings.get("send_failed")}: ${e.message}"
                    Dialogs.tip(msg)
                } finally {
                    if (!File(path).delete()) Log.w(tag, "[CROP-SEND] cropped file not deleted: $path")
                    Main.post { onDone(sent) }
                }
            }
        }

        override fun openPending(onShown: (Int?) -> Unit) {
            SendPanel.openPending("preparing_image", onShown)
        }

        override fun fillPending(gen: Int, path: String?, onResult: (Boolean) -> Unit) {
            SendPanel.fillPending(gen, if (path == null) SendData.EMPTY else SendData("", listOf(path), emptyList()), onResult)
        }

        override fun openPanel(paths: List<String>, fromBubble: Boolean) {
            SendPanel.open(fromBubble = fromBubble, data = SendData("", paths, emptyList())) { shown ->

                if (!shown) Toolbar.restore("send panel not shown")
            }
        }
    }

    private fun onSyncLongPress() {
        Main.post {
            if (!isWifiConnected()) {
                if (BuildConfig.ENABLE_DEBUG) Log.i(tag, "handleSendLongPress: no wifi")
                Dialogs.tip(Strings.get("no_wifi"))
                return@post
            }
            if (!running) {
                if (BuildConfig.ENABLE_DEBUG) Log.i(tag, "handleSendLongPress: auto-start LocalSend")
                start(showNetworkTip = true) { ok -> if (ok) openClipboardSync() }
                return@post
            }
            val defaultPeer = LocalSendClient.defaultPeer()
            if (defaultPeer != null) {
                if (BuildConfig.ENABLE_DEBUG) Log.i(tag, "handleSendLongPress: direct-send to ${defaultPeer.alias}")
                directSendClipboard(defaultPeer)
                return@post
            }
            if (BuildConfig.ENABLE_DEBUG) Log.i(tag, "handleSendLongPress: LocalSend running, open panel")
            openClipboardSync()
        }
    }

    private fun openClipboardSync() {
        if (BuildConfig.ENABLE_DEBUG) Log.i(tag, "openSendPanelClipboardSync")
        SendPanel.open(syncClipboard = true)
    }

    private fun directSendClipboard(peer: DiscoveredPeer) {
        thread(isDaemon = true, name = "LocalSend-SyncSend") {
            PluginIdentity.claim()
            val zip = try {
                ClipboardIo.packageSync()
            } catch (e: Exception) {
                Log.e(tag, "clipboard sync package failed", e)
                Dialogs.tip("${Strings.get("send_failed")}: ${e.message}")
                return@thread
            }
            if (zip == null) {
                Dialogs.tip(Strings.get("sync_clipboard_empty"))
                return@thread
            }
            try {
                LocalSendClient.sendFileDirect(peer.ip, peer.port, zip.absolutePath, peer.useTls)
                Dialogs.tip(Strings.get("send_success"))
            } catch (e: Exception) {
                val msg = if (e.message?.contains("403") == true) Strings.get("sync_rejected")
                else "${Strings.get("send_failed")}: ${e.message}"
                Dialogs.tip(msg)
            } finally {
                if (!zip.delete()) Log.w(tag, "sync package not deleted: ${zip.name}")
            }
        }
    }

    private fun buildSettingsSection(page: android.widget.LinearLayout) {
        val peerValue = SettingsPanel.valueText()
        lateinit var peerBtn: TextView
        fun refreshPeer() {
            val def = LocalSendClient.defaultPeer()
            if (def != null) {
                peerValue.text = "${def.alias} · ${def.ip}"
                peerBtn.visibility = android.view.View.VISIBLE
            } else {
                peerValue.text = Strings.get("config_default_peer_none")
                peerBtn.visibility = android.view.View.GONE
            }
        }
        peerBtn = SettingsPanel.smallButton(Strings.get("config_default_peer_unbind")) {
            LocalSendClient.setDefaultPeer(null)
            refreshPeer()
        }
        refreshPeer()
        page.addView(SettingsPanel.settingRow(Strings.get("config_default_peer"), peerValue, peerBtn))

        val hdValue = SettingsPanel.valueText()
        lateinit var hdBtn: TextView
        fun refreshHd() {
            val on = Settings.get(CaptureSettings.SEND_HD)
            hdValue.text = Strings.get(if (on) "config_send_hd_on" else "config_send_hd_off")
            hdBtn.text = Strings.get(if (on) "config_send_hd_disable" else "config_send_hd_enable")
        }
        hdBtn = SettingsPanel.smallButton("") {
            Settings.set(CaptureSettings.SEND_HD, !Settings.get(CaptureSettings.SEND_HD))
            refreshHd()
        }
        refreshHd()
        page.addView(SettingsPanel.settingRow(Strings.get("config_send_hd"), hdValue, hdBtn))
    }
}

class LocalSendModule(ctx: ReactApplicationContext) : FeatureModule(ctx, "NotipalLocalSend") {
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    init {
        LocalSend.module = this
        registerNetworkListener()
    }

    override fun initialize() {
        super.initialize()
        Tools.handle(Tools.DOC_SHARE, onTap = { DocShare.onToolTap() })
        DocShare.releaseForNewModule()
    }

    private fun registerNetworkListener() {
        val cm = reactApplicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) = LocalSend.onWifiLost()

            override fun onAvailable(network: Network) = LocalSend.requestForActiveSession("WiFi available")
        }
        try {
            val req = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            cm.registerNetworkCallback(req, cb)
            networkCallback = cb
            if (BuildConfig.ENABLE_DEBUG) Log.i("LocalSendModule", "WiFi network callback registered")
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) Log.w("LocalSendModule", "Failed to register network callback: ${e.message}")
        }
    }

    override fun invalidate() {
        val cm = reactApplicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        networkCallback?.let {
            try {
                cm?.unregisterNetworkCallback(it)
            } catch (e: Exception) {
                Log.d("LocalSendModule", "unregister network callback: ${e.message}")
            }
        }
        networkCallback = null
        if (LocalSend.module === this) LocalSend.module = null

        LocalSend.stop("module invalidated")
        if (BuildConfig.ENABLE_DEBUG) Log.i("LocalSendModule", "invalidate: LocalSend server stopped")
        super.invalidate()
    }

    @ReactMethod
    fun start(showNetworkTip: Boolean, promise: Promise) {
        LocalSend.start(showNetworkTip) { ok -> promise.resolve(ok) }
    }

    @ReactMethod
    fun setSendData(text: String, imagePaths: ReadableArray, linkedFiles: ReadableArray) {
        val images = (0 until imagePaths.size()).mapNotNull { imagePaths.getString(it) }
        val linked = (0 until linkedFiles.size()).mapNotNull { i ->
            val m = linkedFiles.getMap(i) ?: return@mapNotNull null
            val path = if (m.hasKey("path")) m.getString("path") else null
            if (path == null) null else LinkedFile(
                path,
                if (m.hasKey("linkType")) m.getInt("linkType") else -1,
                if (m.hasKey("label")) m.getString("label") ?: "file" else "file",
            )
        }
        SendPanel.setLassoData(SendData(text, images, linked))
    }

    @ReactMethod
    fun openLassoPanel() {
        Main.post {
            if (BuildConfig.ENABLE_DEBUG) Log.i("LocalSendModule", "showSendPanelFromBubble")
            Toolbar.allowRestoreOverHostLasso()
            SendPanel.open(fromBubble = true, useStaged = true)
        }
    }

    @ReactMethod
    fun openDocTextPanel(text: String) = SendPanel.openDocText(text)

    @ReactMethod
    fun flushPendingTexts(promise: Promise) {
        val pending = LocalSend.drainPendingTexts()
        val result = Arguments.createArray()
        for (pt in pending) {
            result.pushMap(Arguments.createMap().apply {
                putString("pendingId", pt.id)
                putString("text", pt.text)
                putString("fileName", pt.fileName)
            })
        }
        if (BuildConfig.ENABLE_DEBUG) Log.i("LocalSendModule", "flushPendingTexts: returning ${pending.size} unacked text(s)")
        promise.resolve(result)
    }

    @ReactMethod
    fun ackText(pendingId: String) = LocalSend.ackPendingText(pendingId)

    @ReactMethod
    fun imagePrepared(requestId: String, success: Boolean) = LocalSend.onImagePrepared(requestId, success)
}
