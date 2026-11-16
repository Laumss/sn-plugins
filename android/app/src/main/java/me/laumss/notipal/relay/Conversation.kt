package me.laumss.notipal.relay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import me.laumss.notipal.common.App
import me.laumss.notipal.common.Http
import me.laumss.notipal.common.Log
import me.laumss.notipal.common.Main
import me.laumss.notipal.common.PluginIdentity
import me.laumss.notipal.common.RelayPrefs
import me.laumss.notipal.common.Strings
import me.laumss.notipal.host.HostSession
import me.laumss.notipal.localsend.LocalSendDiscovery
import me.laumss.notipal.ui_common.Dialogs
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Collections
import java.util.UUID
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

data class PhoneEndpoint(val fingerprint: String, val host: String, val port: Int) {
    val baseUrl: String get() = "http://$host:$port"
}

data class RelayMessage(
    val id: String = UUID.randomUUID().toString(),
    var text: String,

    val source: String,
    val createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis(),

    var closed: Boolean = false,

    var inserted: Boolean = false,

    var kind: String = "",

    val conversationId: String = "",

    var lead: String = "",

    var starred: Boolean = false,

    var seen: Boolean = true,

    val backend: String = "",
) {

    val title: String
        get() = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: ""

    val preview: String
        get() {
            val lines = text.lines()
            val idx = lines.indexOfFirst { it.isNotBlank() }
            if (idx < 0) return ""
            return lines.drop(idx + 1).joinToString("\n").trim()
        }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("text", text)
        put("source", source)
        put("createdAt", createdAt)
        put("updatedAt", updatedAt)
        put("closed", closed)
        put("inserted", inserted)
        put("kind", kind)
        put("conversationId", conversationId)
        if (lead.isNotEmpty()) put("lead", lead)
        if (starred) put("starred", true)
        if (!seen) put("seen", false)
        if (backend.isNotEmpty()) put("backend", backend)
    }

    companion object {
        fun fromJson(obj: JSONObject): RelayMessage = RelayMessage(
            id = obj.optString("id", UUID.randomUUID().toString()),
            text = obj.optString("text", ""),
            source = obj.optString("source", "llm"),
            createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
            updatedAt = obj.optLong("updatedAt", System.currentTimeMillis()),
            closed = obj.optBoolean("closed", true),
            inserted = obj.optBoolean("inserted", false),
            kind = obj.optString("kind", ""),
            conversationId = obj.optString("conversationId", ""),
            lead = obj.optString("lead", ""),
            starred = obj.optBoolean("starred", false),

            seen = obj.optBoolean("seen", true),
            backend = obj.optString("backend", ""),
        )

        fun listToJson(list: List<RelayMessage>): JSONArray =
            JSONArray().apply { list.forEach { put(it.toJson()) } }

        fun listFromJson(arr: JSONArray): List<RelayMessage> =
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { fromJson(it) }
            }
    }
}

data class ChatTurn(
    val id: String,
    val fromUser: Boolean,
    val text: String,
    val continues: Boolean = false,
    val images: List<StagedImage> = emptyList(),
)

data class StagedImage(val imagePath: String, val maskPath: String, val detail: String = "") {
    val isDocument: Boolean get() = imagePath.endsWith(".pdf", ignoreCase = true)

    val fileName: String get() = File(imagePath).name
}

interface RelayInbox {

    fun load()

    fun flush()
    fun snapshot(): List<RelayMessage>
    fun get(id: String): RelayMessage?
    fun contains(id: String): Boolean

    fun upsert(
        id: String, conversationId: String, text: String, kind: String, closed: Boolean,
        lead: String = "", source: String = "llm", backend: String = "",
    ): Boolean
    fun markInserted(id: String)
    fun setStarred(ids: Collection<String>, starred: Boolean)
    fun markSeen(ids: Collection<String>)
    fun deleteMany(ids: Collection<String>)
}

private object NoInbox : RelayInbox {
    @Volatile private var warned = false

    private fun warnOnce() {
        if (warned) return
        warned = true
        Log.w("AIRelay", "inbox not installed: replies are not kept")
    }

    override fun load() = warnOnce()
    override fun flush() = Unit
    override fun snapshot(): List<RelayMessage> = emptyList()
    override fun get(id: String): RelayMessage? = null
    override fun contains(id: String): Boolean = false
    override fun upsert(
        id: String, conversationId: String, text: String, kind: String, closed: Boolean,
        lead: String, source: String, backend: String,
    ): Boolean {
        warnOnce()
        return false
    }
    override fun markInserted(id: String) = Unit
    override fun setStarred(ids: Collection<String>, starred: Boolean) = Unit
    override fun markSeen(ids: Collection<String>) = Unit
    override fun deleteMany(ids: Collection<String>) = Unit
}

object RelayHooks {

    @Volatile var inbox: RelayInbox = NoInbox

    @Volatile var transcriptsPaused: (Boolean) -> Unit = {}

    @Volatile var transcriptsWake: () -> Unit = {}

    @Volatile var transcriptsActive: () -> Boolean = { false }

    @Volatile var flashStatus: (text: String, ms: Long) -> Boolean = { _, _ -> false }

    @Volatile var replyFailed: (text: String) -> Boolean = { false }

    @Volatile var backendChanged: () -> Unit = {}

    @Volatile var showWebLink: () -> Unit = { Log.w("AIRelay", "web link panel not installed") }

    @Volatile var webLinkShowing: () -> Boolean = { false }

    @Volatile var openTrace: (String) -> Unit = {}
}

object Conversations {
    private const val TAG = "AIRelay"

    private const val OWN_STREAMS_KEPT = 4

    private const val REPLY_WAIT_MS = 90_000L

    private const val CONNECT_WAIT_MS = 4_000L

    private const val PHONE_SEARCH_MS = 60_000L

    private const val WEB_FINGERPRINT = "web"

    private const val IDLE_PAUSE_MS = 3 * 60_000L

    private const val BUSY_RECHECK_MS = 15_000L
    private const val PAUSE_SCREEN = "screen off"

    private const val WEB_PAGE_WAIT_MS = 6_000L

    private const val DOCUMENT_UPLOAD_TIMEOUT_MS = 120_000
    private const val PAUSE_IDLE = "window idle"

    interface Listener {
        fun onInboxChanged(messages: List<RelayMessage>) {}
        fun onInsertionRequested(request: InsertionRequest) {}
    }

    sealed class InsertionRequest {
        abstract val text: String
        data class Text(override val text: String, val messageId: String) : InsertionRequest()
    }

    enum class SubmitResult { DISABLED, SENT, NOT_CONNECTED, NO_ENDPOINT, EMPTY, FAILED }

    enum class ImageSubmit { STAGED, SENDING, NOT_CONNECTED, DISABLED }

    enum class Link { OFF, WEB, UNPAIRED, SEARCHING, CONNECTED, CONNECTING, RETRYING, PAUSED }
    class LinkStatus(val link: Link, val host: String = "", val retryInMs: Long = 0L)

    enum class Backend { RIKKAHUB, WEB }

    enum class OnceResult { SENT, UNPAIRED, UNREACHABLE, REFUSED, NO_PAGE }

    private val appContext: Context get() = App.context.applicationContext
    private val listeners = CopyOnWriteArraySet<Listener>()

    val inbox: RelayInbox get() = RelayHooks.inbox

    @Volatile private var started = false
    @Volatile private var enabled = false

    @Volatile private var searching = false
    @Volatile private var searchUntil = 0L
    private val stopSearch = Runnable { stopSearchIfIdle() }

    private val phoneLock = Object()
    private var phoneVersion = 0L

    @Volatile private var activeEndpoint: PhoneEndpoint? = null
    @Volatile private var pairingInFlight: String? = null
    private val declinedFingerprints = CopyOnWriteArraySet<String>()
    private val authWarned = CopyOnWriteArraySet<String>()

    @Volatile private var chatStream: ConversationStream? = null

    private val seeds = RelaySeeds()
    private var chatEndpoint: PhoneEndpoint? = null

    private val ownStreams = ArrayDeque<ConversationStream>()

    @Volatile private var temporaryWeb = false
    @Volatile private var webServer: WebRelayServer? = null

    @Volatile private var offeringWeb = false

    @Volatile private var webLinkShown = false

    private class ChatBinding(val conversationId: String, val before: Set<String>)
    @Volatile private var chatBinding: ChatBinding? = null

    private val chatMessageIds: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val peerListener = LocalSendDiscovery.DictationPeerListener { peer, endpointChanged ->
        Main.handler.post { onPeerSeen(peer, endpointChanged) }
    }
    private val networkListener = LocalSendDiscovery.NetworkListener { reason -> wakeStreams("network $reason") }
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> requestPause(PAUSE_SCREEN)
                Intent.ACTION_SCREEN_ON -> clearPause(PAUSE_SCREEN)
            }
        }
    }
    @Volatile private var screenReceiverOn = false

    private val pauseReasons = CopyOnWriteArraySet<String>()
    @Volatile private var streamsPaused = false
    @Volatile private var windowIdle = false
    private val idlePause = Runnable { if (windowIdle) requestPause(PAUSE_IDLE) }
    private val applyPauseLater = Runnable { applyPause() }

    private val detailStreams: MutableSet<ConversationStream> = Collections.newSetFromMap(WeakHashMap())

    fun addListener(listener: Listener) {
        listeners += listener
        Main.handler.post { listener.onInboxChanged(inbox.snapshot()) }
    }

    fun removeListener(listener: Listener) {
        listeners -= listener
    }

    interface Stager {
        fun stageText(text: String)
        fun stageImage(imagePath: String, maskPath: String)
    }

    @Volatile var stager: Stager? = null

    fun stageText(text: String): Boolean {
        val target = stager ?: return false
        Main.handler.post { target.stageText(text) }
        return true
    }

    fun sendOnce(text: String, onResult: (OnceResult) -> Unit) {
        val query = text.trim()
        if (query.isEmpty()) return
        val endpoint = queryEndpoint() ?: return onResult(OnceResult.UNPAIRED)
        thread(name = "AIRelay-once", isDaemon = true) {
            if (isWebMode() && !awaitWebPage(WEB_PAGE_WAIT_MS)) return@thread onResult(OnceResult.NO_PAGE)
            val base = endpoint.baseUrl
            val conversationId = try {
                Http.getJson("$base/api/conversations/active", 3_000)
                    .optString("conversationId").takeIf { it.isNotBlank() && it != "null" }
            } catch (e: Exception) {
                Log.w(TAG, "sendOnce: ${endpoint.host}:${endpoint.port} not reachable: ${e.message}")
                return@thread onResult(OnceResult.UNREACHABLE)
            }
            val result = try {
                val id = conversationId ?: JSONObject(Http.postJson("$base/api/conversations", JSONObject()
                    .put("conversationId", RelayPrefs.conversationCreation(endpoint.fingerprint, "chat").first),
                    10_000)).optString("conversationId").takeIf { it.isNotBlank() && it != "null" }
                    ?: throw IllegalStateException("create returned no conversationId")
                Http.postJson("$base/api/conversations/$id/relay-query", JSONObject().put("text", query), 15_000)
                Log.i(TAG, "sendOnce: sent to ${endpoint.host} conversation $id (${query.length} chars)")
                OnceResult.SENT
            } catch (e: Exception) {
                Log.w(TAG, "sendOnce: ${endpoint.host} refused: ${e.message}")
                OnceResult.REFUSED
            }
            onResult(result)
        }
    }

    private fun stageImage(imagePath: String, maskPath: String): Boolean {
        val target = stager ?: return false
        Main.handler.post { target.stageImage(imagePath, maskPath) }
        return true
    }

    fun isEnabled(): Boolean = enabled

    fun isConnected(): Boolean = chatStream?.isConnected == true

    fun awaitConnected(timeoutMs: Long): Boolean {
        if (isConnected()) return true
        if (!started || chatStream == null) return false
        wake("ping")
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (isConnected()) return true
            try { Thread.sleep(100) } catch (_: InterruptedException) { return false }
        }
        return false
    }

    private fun ensureConnected(reason: String): Boolean {
        if (!webCanAnswer()) return false
        if (isConnected()) return true
        val connected = if (Looper.myLooper() == Looper.getMainLooper()) { wake(reason); false } else awaitConnected(CONNECT_WAIT_MS)
        if (!connected) noteNotConnected(reason, "relay_not_connected")
        return connected
    }

    private fun noteNotConnected(reason: String, textKey: String) {
        Log.i(TAG, "nothing sent: $reason")
        Main.handler.post {
            if (!started) return@post
            val ctx = App.react
            if (!isWebMode() && !offeringWeb && ctx != null && HostSession.state.inHostApp) {
                offerWebSwitch(reason)
                return@post
            }
            val text = Strings.get(textKey)
            if (!RelayHooks.flashStatus(text, 3_000L) && ctx != null) Dialogs.tip(text)
        }
    }

    fun linkStatus(): LinkStatus {
        if (!started) return LinkStatus(if (searching) Link.SEARCHING else Link.OFF)
        if (isWebMode()) return LinkStatus(Link.WEB)
        val endpoint = activeEndpoint ?: return LinkStatus(Link.UNPAIRED)
        val stream = chatStream ?: return LinkStatus(Link.CONNECTING)
        val retryIn = stream.retryAt - SystemClock.elapsedRealtime()
        return when {
            stream.isConnected -> LinkStatus(Link.CONNECTED, endpoint.host)
            stream.isPaused -> LinkStatus(Link.PAUSED, endpoint.host)
            retryIn > 0 -> LinkStatus(Link.RETRYING, endpoint.host, retryIn)
            else -> LinkStatus(Link.CONNECTING, endpoint.host)
        }
    }

    fun searchAgain() {
        declinedFingerprints.clear()
        if (started) wake("manual search") else searchPhone("manual")
    }

    @Synchronized
    fun setEnabledByUser(value: Boolean): Boolean {
        if (enabled == value) return enabled
        enabled = value
        if (value) start() else stop()
        return enabled
    }

    fun start() {
        if (!enabled) return

        if (started) { wake("start"); return }
        started = true

        searching = false
        Main.handler.removeCallbacks(stopSearch)
        inbox.load()
        RelayHooks.openTrace("inbox ready")
        if (isWebMode()) {

            LocalSendDiscovery.removeDictationPeerListener(peerListener)
            LocalSendDiscovery.stopDictationListening()
            val server = ensureWebServer()
            if (server == null) Log.e(TAG, "web backend did not start") else connectWeb(server)
        } else {
            LocalSendDiscovery.addDictationPeerListener(peerListener)
            LocalSendDiscovery.startDictationListening(appContext)
            pairedPhoneInfo()?.takeIf { it.host.isNotEmpty() }?.let(::connectTo)
        }
        registerScreenReceiver()
        LocalSendDiscovery.addNetworkListener(networkListener)
        publishInbox()
    }

    fun stop() {
        if (!started) return
        started = false
        LocalSendDiscovery.removeNetworkListener(networkListener)
        unregisterScreenReceiver()
        Main.handler.removeCallbacks(idlePause)
        Main.handler.removeCallbacks(applyPauseLater)
        pauseReasons.clear()
        streamsPaused = false
        windowIdle = false
        RelayHooks.transcriptsPaused(false)
        LocalSendDiscovery.removeDictationPeerListener(peerListener)
        LocalSendDiscovery.stopDictationListening()
        dropConnection()
        authWarned.clear()
        inbox.flush()
        webLinkShown = false
        if (temporaryWeb) {
            temporaryWeb = false
            stopWebServer()
            Main.handler.post { RelayHooks.backendChanged() }
        }
    }

    fun wake(reason: String) {
        Main.handler.post {
            if (!started) return@post
            if (pauseReasons.remove(PAUSE_IDLE)) Log.i(TAG, "idle over ($reason)")
            if (windowIdle) {
                Main.handler.removeCallbacks(idlePause)
                Main.handler.postDelayed(idlePause, IDLE_PAUSE_MS)
            }
            applyPause()
            wakeStreams(reason)
        }
    }

    private fun wakeStreams(reason: String) {
        if (!started) return
        val streams = allStreams()
        if (streams.any { it.isRunning && !it.isConnected && !it.isPaused }) Log.i(TAG, "reconnect now ($reason)")
        streams.forEach { it.wakeReconnect() }
        RelayHooks.transcriptsWake()
    }

    fun setWindowIdle(idle: Boolean) {
        if (windowIdle == idle) return
        windowIdle = idle
        Main.handler.removeCallbacks(idlePause)
        if (idle) Main.handler.postDelayed(idlePause, IDLE_PAUSE_MS) else clearPause(PAUSE_IDLE)
        Log.i(TAG, "window ${if (idle) "idle" else "in use"}")
    }

    private fun requestPause(reason: String) {
        if (pauseReasons.add(reason)) Main.handler.post(applyPauseLater)
    }

    private fun clearPause(reason: String) {
        if (pauseReasons.remove(reason)) Main.handler.post(applyPauseLater)
    }

    private fun applyPause() {
        Main.handler.removeCallbacks(applyPauseLater)
        if (!started || pauseReasons.isEmpty()) {
            if (streamsPaused) resumeStreams()
            return
        }
        if (streamsPaused) return
        if (isBusy()) {
            Main.handler.postDelayed(applyPauseLater, BUSY_RECHECK_MS)
            return
        }
        pauseStreams(pauseReasons.joinToString())
    }

    private fun isBusy(): Boolean {
        val now = System.currentTimeMillis()
        fun ConversationStream.busy() = isRunning && (isGenerating || now - lastSentAt < REPLY_WAIT_MS)
        return allStreams().any { it.busy() } || RelayHooks.transcriptsActive()
    }

    private fun pauseStreams(reason: String) {
        streamsPaused = true
        Log.i(TAG, "streams paused ($reason)")
        allStreams().forEach { it.pause() }
        RelayHooks.transcriptsPaused(true)
    }

    private fun resumeStreams() {
        streamsPaused = false
        Log.i(TAG, "streams resumed")
        allStreams().forEach { it.resume() }
        RelayHooks.transcriptsPaused(false)
    }

    private fun allStreams(): List<ConversationStream> =
        listOfNotNull(chatStream) +
            synchronized(ownStreams) { ownStreams.toList() } +
            synchronized(detailStreams) { detailStreams.toList() }

    private fun registerScreenReceiver() {
        if (screenReceiverOn) return
        try {
            appContext.registerReceiver(screenReceiver, IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            })
            screenReceiverOn = true
        } catch (e: Exception) {
            Log.w(TAG, "screen receiver: ${e.message}")
        }
        val interactive = (appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isInteractive ?: true
        if (!interactive) requestPause(PAUSE_SCREEN)
    }

    private fun unregisterScreenReceiver() {
        if (!screenReceiverOn) return
        screenReceiverOn = false
        runCatching { appContext.unregisterReceiver(screenReceiver) }
    }

    fun backend(): Backend =
        if (temporaryWeb || RelayPrefs.relayBackend() == "web") Backend.WEB else Backend.RIKKAHUB

    fun isWebMode(): Boolean = backend() == Backend.WEB

    fun setBackend(value: Backend) {
        RelayPrefs.setRelayBackend(if (value == Backend.WEB) "web" else "rikkahub")
        temporaryWeb = false
        val wasStarted = started
        if (wasStarted) stop()
        if (value == Backend.RIKKAHUB) stopWebServer()
        if (wasStarted) start()
        Main.handler.post { RelayHooks.backendChanged() }

        RelayHooks.transcriptsWake()
        Log.i(TAG, "backend: $value")
    }

    @Synchronized
    internal fun ensureWebServer(): WebRelayServer? {
        if (!isWebMode()) return null
        webServer?.let { return it }
        val server = WebRelayServer(
            baseDir = File(appContext.filesDir, "web_relay"),
            token = { RelayPrefs.webToken() },

            assets = { (App.react ?: App.context).assets },
        )
        if (!server.start()) return null
        server.onWorkersChanged = { count -> onWebWorkersChanged(count) }
        server.onReplyFailed = { message -> onWebReplyFailed(message) }
        webServer = server
        return server
    }

    private fun stopWebServer() {
        webServer?.stop()
        webServer = null
    }

    internal fun webServer(): WebRelayServer? = webServer

    fun localImagePath(url: String): String? = webServer?.localFile(url)?.path

    fun rotateWebToken() {
        RelayPrefs.newWebToken()
        webServer?.dropRemoteClients()
    }

    fun webPageMissing(): Boolean = isWebMode() && webServer?.chatReady != true

    fun webCanAnswer(): Boolean {
        if (!webPageMissing()) return true
        Log.i(TAG, "nothing sent: no web page can answer")
        noteNoPage()
        return false
    }

    private fun awaitWebPage(timeoutMs: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (webPageMissing()) {
            if (SystemClock.elapsedRealtime() >= deadline) return false
            try { Thread.sleep(200) } catch (_: InterruptedException) { return false }
        }
        return true
    }

    private fun onWebReplyFailed(message: String) {
        Log.w(TAG, "web reply failed: $message")
        Main.handler.post {
            if (!started) return@post
            val text = Strings.get("airrelay_web_reply_failed", message.take(120))
            if (!RelayHooks.replyFailed(text) && App.react != null) Dialogs.tip(text)
        }
    }

    private fun backendTag(): String = if (isWebMode()) "web" else "rikkahub"

    fun ownsCard(message: RelayMessage): Boolean = message.backend.isEmpty() || message.backend == backendTag()

    private fun noteNoPage() {
        val server = webServer ?: return
        if (!isWebMode() || server.chatReady) return
        val connected = server.workerCount > 0
        Main.handler.post {
            val notice = if (connected) "airrelay_web_no_chat_model" else "airrelay_web_no_page"
            RelayHooks.flashStatus(Strings.get(notice), 3_000L)
            if (!connected && !webLinkShown && !RelayHooks.webLinkShowing()) {
                webLinkShown = true
                showWebLink()
            }
        }
    }

    fun queryEndpoint(): PhoneEndpoint? =
        if (isWebMode()) ensureWebServer()?.let { PhoneEndpoint(WEB_FINGERPRINT, "127.0.0.1", it.port) }
        else pairedPhoneInfo()?.takeIf { it.host.isNotEmpty() && it.port > 0 }

    private fun connectWeb(server: WebRelayServer) {

        server.newSession()
        val endpoint = PhoneEndpoint(WEB_FINGERPRINT, "127.0.0.1", server.port)
        activeEndpoint = endpoint
        phoneChanged()
        startChatStream(endpoint)
    }

    private fun onWebWorkersChanged(count: Int) {
        Log.i(TAG, "web pages connected: $count")
        if (count > 0) Main.handler.post {
            RelayHooks.flashStatus(Strings.get("airrelay_web_page_connected"), 2_500L)
        }
    }

    private fun offerWebSwitch(reason: String) {
        Main.handler.post {
            if (!started || isWebMode() || offeringWeb) return@post
            if (App.react == null) return@post
            if (!HostSession.state.inHostApp) return@post
            offeringWeb = true
            Log.i(TAG, "offering the web page ($reason)")
            Dialogs.confirm(
                Strings.get("airrelay_web_offer"),
                Strings.get("cancel"), Strings.get("airrelay_web_offer_switch"),
            ) { switch ->
                if (!switch) {
                    offeringWeb = false
                    return@confirm
                }
                Dialogs.confirm(
                    Strings.get("airrelay_web_offer_keep"),
                    Strings.get("airrelay_web_once"), Strings.get("airrelay_web_always"),
                ) { always ->
                    offeringWeb = false
                    switchToWeb(persist = always)
                }
            }
        }
    }

    fun switchToWeb(persist: Boolean) {
        Main.handler.post {
            val wasStarted = started
            if (!wasStarted) {

                if (persist) RelayPrefs.setRelayBackend("web")
                RelayHooks.backendChanged()
                return@post
            }
            stop()
            if (persist) RelayPrefs.setRelayBackend("web") else temporaryWeb = true
            RelayHooks.backendChanged()
            start()
            webLinkShown = true
            Log.i(TAG, "switched to the web page (${if (persist) "saved" else "this session"})")
            showWebLink()
        }
    }

    fun showWebLink() {
        if (App.react == null) return
        RelayHooks.showWebLink()
    }

    fun searchPhone(reason: String) {
        if (isWebMode()) {
            ensureWebServer()
            return
        }
        Main.handler.post {
            if (started) return@post
            searchUntil = SystemClock.elapsedRealtime() + PHONE_SEARCH_MS
            Main.handler.removeCallbacks(stopSearch)
            Main.handler.postDelayed(stopSearch, PHONE_SEARCH_MS)
            if (searching) return@post
            searching = true
            Log.i(TAG, "phone search on ($reason)")
            LocalSendDiscovery.addDictationPeerListener(peerListener)
            LocalSendDiscovery.startDictationListening(appContext)
        }
    }

    private fun stopSearchIfIdle() {
        if (!searching) return
        val left = searchUntil - SystemClock.elapsedRealtime()

        if (left > 0 || pairingInFlight != null) {
            Main.handler.postDelayed(stopSearch, left.coerceAtLeast(5_000L))
            return
        }
        searching = false
        if (started) return
        Log.i(TAG, "phone search off")
        LocalSendDiscovery.removeDictationPeerListener(peerListener)
        LocalSendDiscovery.stopDictationListening()
    }

    fun awaitPhone(
        previous: PhoneEndpoint?,
        lookMs: Long,
        promptMs: Long,
        cancelled: () -> Boolean,
        onPrompt: () -> Unit,
    ): PhoneEndpoint? {
        val start = SystemClock.elapsedRealtime()
        val version = synchronized(phoneLock) { phoneVersion }
        var prompted = false
        while (!cancelled()) {
            synchronized(phoneLock) {
                if (phoneVersion != version) {
                    return pairedPhoneInfo()?.takeIf { it.host.isNotEmpty() && it != previous }
                }
                phoneLock.wait(250L)
            }
            if (pairingInFlight != null && !prompted) {
                prompted = true
                onPrompt()
            }
            val waited = SystemClock.elapsedRealtime() - start
            if (waited >= promptMs || (!prompted && waited >= lookMs)) break
        }
        return null
    }

    private fun phoneChanged() {
        synchronized(phoneLock) {
            phoneVersion++
            phoneLock.notifyAll()
        }

        RelayHooks.transcriptsWake()
    }

    fun pairedPhoneInfo(): PhoneEndpoint? {
        val fingerprint = RelayPrefs.phoneFingerprint()
        if (fingerprint.isEmpty()) return null
        return PhoneEndpoint(fingerprint, RelayPrefs.phoneIp(), RelayPrefs.phonePort())
    }

    fun unpairPhone() {
        val previous = pairedPhoneInfo()
        dropConnection()
        declinedFingerprints.clear()
        authWarned.clear()
        RelayPrefs.clearPhonePairing()
        Log.i(TAG, "phone unpaired: ${previous?.fingerprint.orEmpty()} ${previous?.host.orEmpty()}:${previous?.port ?: 0}")
    }

    private fun dropConnection() {
        activeEndpoint = null
        pairingInFlight = null
        chatStream?.stop()
        chatStream = null
        chatEndpoint = null
        synchronized(ownStreams) { ownStreams.toList().also { ownStreams.clear() } }.forEach { it.stop() }
        chatBinding = null
        chatMessageIds.clear()
    }

    private fun onPeerSeen(peer: LocalSendDiscovery.DictationPeer, endpointChanged: Boolean) {
        if (!started && !searching) return

        if (isWebMode()) return
        if (peer.authRequired) {
            warnAuthUnsupportedOnce(peer)
            return
        }
        authWarned -= peer.fingerprint
        val pairedFp = RelayPrefs.phoneFingerprint()
        val endpoint = PhoneEndpoint(peer.fingerprint, peer.ip, peer.llmPort)

        val current = if (started) activeEndpoint else pairedPhoneInfo()
        val decision = when {
            peer.fingerprint == pairedFp -> when {
                endpoint != current -> "follow"

                endpointChanged && started -> { wakeStreams("phone announced"); return }
                else -> return
            }
            peer.fingerprint in declinedFingerprints || pairingInFlight != null -> return
            pairedFp.isEmpty() || endpointChanged -> "ask"
            else -> return
        }
        Log.i(TAG, "peer ${peer.alias} ${peer.ip}:${peer.llmPort} fp=${peer.fingerprint} paired=$pairedFp → $decision")
        if (decision == "follow") connectTo(endpoint) else requestPairing(peer)
    }

    private fun requestPairing(peer: LocalSendDiscovery.DictationPeer) {
        if (App.react == null) {
            Log.w(TAG, "pairing request from ${peer.alias} deferred: no React context")
            return
        }

        if (!HostSession.state.inHostApp) {
            Log.i(TAG, "pairing request from ${peer.alias} deferred: not in note/doc/Mosaic")
            return
        }
        pairingInFlight = peer.fingerprint
        Dialogs.confirm(Strings.get("airrelay_pair_request", peer.alias, peer.ip)) { accepted ->
            pairingInFlight = null
            Log.i(TAG, "pairing ${if (accepted) "accepted" else "declined"}: ${peer.fingerprint} ${peer.ip}:${peer.llmPort}")
            if (!started && !searching) {
                phoneChanged()
                return@confirm
            }
            if (!accepted) {
                declinedFingerprints += peer.fingerprint
                phoneChanged()
                return@confirm
            }
            if (RelayPrefs.phoneFingerprint() != peer.fingerprint) unpairPhone()
            RelayPrefs.setPhoneFingerprint(peer.fingerprint)
            connectTo(PhoneEndpoint(peer.fingerprint, peer.ip, peer.llmPort))
        }
    }

    private fun warnAuthUnsupportedOnce(peer: LocalSendDiscovery.DictationPeer) {
        if (!authWarned.add(peer.fingerprint)) return
        Log.w(TAG, "server ${peer.alias} @ ${peer.ip} requires auth; AIRelay cannot connect")
        if (App.react != null) Dialogs.tip(Strings.get("airrelay_auth_unsupported", peer.alias))
    }

    private fun connectTo(endpoint: PhoneEndpoint) {
        if (!started) {

            if (!searching) return
            RelayPrefs.setPhoneIp(endpoint.host)
            RelayPrefs.setPhonePort(endpoint.port)
            Log.i(TAG, "phone saved for translation: ${endpoint.host}:${endpoint.port}")
            phoneChanged()
            return
        }
        activeEndpoint = endpoint
        RelayPrefs.setPhoneIp(endpoint.host)
        RelayPrefs.setPhonePort(endpoint.port)
        phoneChanged()
        startChatStream(endpoint)
    }

    @Synchronized
    private fun startChatStream(endpoint: PhoneEndpoint) {
        if (!started) return
        if (chatEndpoint == endpoint && chatStream?.isRunning == true) return
        chatStream?.stop()
        val creationId = RelayPrefs.conversationCreation(endpoint.fingerprint, "chat").first
        val stream = ConversationStream(endpoint.baseUrl, followActive = true, creationId = creationId, seeds = seeds)
        stream.onReply = { conversationId, id, text, done, lead ->
            if (chatStream === stream) offerReply(conversationId, id, text, done, lead)
        }

        stream.onSwitched = { id ->
            if (chatStream === stream && chatBinding?.conversationId != id) unbindChat("followed conversation switched to $id")
        }
        chatStream = stream
        chatEndpoint = endpoint
        stream.start()
        if (streamsPaused) stream.pause()
    }

    fun openConversation(conversationId: String): ConversationStream? {
        val endpoint = activeEndpoint
        if (!started || endpoint == null || conversationId.isEmpty()) return null
        wake("detail opened")
        return ConversationStream(endpoint.baseUrl, followActive = false, conversationId = conversationId, seeds = seeds)
            .also { stream ->
                synchronized(detailStreams) { detailStreams += stream }
                stream.start()
            }
    }

    fun offerReply(conversationId: String, id: String, text: String, done: Boolean, lead: String) {
        if (started && isCardReply(conversationId, id)) {
            inbox.upsert(id, conversationId, text, kind = "", closed = done, lead = lead, backend = backendTag())
        }
    }

    fun prepareChatSend(conversationId: String, existingIds: Set<String>, prefill: Boolean) {
        if (!prefill) bindChat(conversationId, existingIds)
        else if (chatBinding?.conversationId == conversationId) unbindChat("prefill chat")
    }

    private fun bindChat(conversationId: String, existingIds: Set<String>) {
        if (chatBinding?.conversationId == conversationId) return
        chatBinding = ChatBinding(conversationId, existingIds)
        Log.i(TAG, "chat bound to conversation $conversationId (${existingIds.size} messages before)")
    }

    fun noteChatMessages(conversationId: String, ids: Collection<String>) {
        val binding = chatBinding?.takeIf { it.conversationId == conversationId } ?: return
        ids.filterTo(chatMessageIds) { it !in binding.before && !inbox.contains(it) }
    }

    private fun unbindChat(reason: String) {
        val binding = chatBinding ?: return
        chatBinding = null
        Log.i(TAG, "chat unbound from conversation ${binding.conversationId}: $reason")
    }

    private fun unbindChatFor(stream: ConversationStream) {
        if (stream.conversationId != null && stream.conversationId == chatBinding?.conversationId) {
            unbindChat("note query")
        }
    }

    private fun isCardReply(conversationId: String, id: String): Boolean {
        if (inbox.contains(id)) return true
        if (id in chatMessageIds) return false
        val binding = chatBinding ?: return true
        if (binding.conversationId != conversationId || id in binding.before || inbox.contains(id)) return true
        chatMessageIds += id
        return false
    }

    fun submitQuery(text: String, newConversation: Boolean = false, onResult: (SubmitResult) -> Unit) {
        val query = text.trim()
        when {
            query.isEmpty() -> onResult(SubmitResult.EMPTY)
            !started -> onResult(SubmitResult.DISABLED)
            activeEndpoint == null -> {
                noteNotConnected("no phone", "relay_unpaired")
                onResult(SubmitResult.NO_ENDPOINT)
            }
            else -> thread(name = "AIRelay-send", isDaemon = true) {
                val stream = chatStream?.takeIf { ensureConnected("query") }
                if (stream == null) {
                    onResult(SubmitResult.NOT_CONNECTED)
                    return@thread
                }
                val sent = sendQueryNow(stream, query, newConversation)
                Log.i(TAG, "submitQuery: ${if (sent) "sent" else "failed"} (${query.length} chars)")
                onResult(if (sent) SubmitResult.SENT else SubmitResult.FAILED)
            }
        }
    }

    private fun sendQueryNow(stream: ConversationStream, query: String, newConversation: Boolean): Boolean {
        if (!newConversation) {
            unbindChatFor(stream)
            return stream.sendQuery(query)
        }
        val target = ownConversation() ?: return false
        return target.sendQuery(query).also { sent -> afterOwnSend(target, sent) }
    }

    private fun ownConversation(): ConversationStream? {
        val endpoint = activeEndpoint ?: return null
        if (isWebMode()) {
            webServer?.freshSession()
            return chatStream
        }
        val id = try {
            JSONObject(Http.postJson("${endpoint.baseUrl}/api/conversations", JSONObject(), 10_000))
                .optString("conversationId").ifEmpty { return null }
        } catch (error: Exception) {
            Log.w(TAG, "new conversation unavailable: ${error.message}")
            return null
        }
        val stream = ConversationStream(endpoint.baseUrl, followActive = false, conversationId = id, seeds = seeds)

        val answered = AtomicBoolean(false)
        stream.onReply = { conversationId, messageId, text, done, lead ->
            offerReply(conversationId, messageId, text, done, lead)
            if (done) {
                answered.set(true)
                if (!stream.isGenerating) Main.handler.post { retireOwnStream(stream) }
            }
        }
        stream.onSnapshot = { snapshot -> if (answered.get() && !snapshot.generating) Main.handler.post { retireOwnStream(stream) } }
        val oldest = synchronized(ownStreams) {
            if (!started) return null
            ownStreams.addLast(stream)
            if (ownStreams.size > OWN_STREAMS_KEPT) ownStreams.removeFirst() else null
        }
        oldest?.stop()
        stream.start()
        Log.i(TAG, "new conversation $id")
        return stream
    }

    private fun retireOwnStream(stream: ConversationStream) {
        if (!synchronized(ownStreams) { ownStreams.remove(stream) }) return
        stream.stop()
        Log.i(TAG, "conversation ${stream.conversationId} answered, its stream closed")
    }

    private fun dropOwnConversation(stream: ConversationStream) {
        if (!synchronized(ownStreams) { ownStreams.remove(stream) }) return
        stream.stop()
        val id = stream.conversationId ?: return
        val baseUrl = activeEndpoint?.baseUrl ?: return
        Http.delete("$baseUrl/api/conversations/$id")
    }

    fun sendInNewConversation(text: String, images: List<Pair<String, String>>): Boolean {

        if (!ensureConnected("new conversation")) return false
        val target = ownConversation() ?: return false
        return sendWithImages(target, text, images).also { sent -> afterOwnSend(target, sent) }
    }

    private fun afterOwnSend(target: ConversationStream, sent: Boolean) {
        if (sent) target.conversationId?.let(::openOnPhone) else dropOwnConversation(target)
    }

    fun openOnPhone(conversationId: String) {
        if (!RelayPrefs.phoneFollows()) return
        if (isWebMode()) {
            webServer?.showOnPages(conversationId)
            return
        }
        val endpoint = activeEndpoint ?: return
        thread(name = "AIRelay-open", isDaemon = true) {
            runCatching { Http.postJson("${endpoint.baseUrl}/api/conversations/$conversationId/open", JSONObject(), 5_000) }
                .onFailure { Log.i(TAG, "phone did not open $conversationId: ${it.message}") }
        }
    }

    fun submitImageQuery(imagePath: String, maskPath: String, prompt: String): ImageSubmit {
        if (!started) return ImageSubmit.DISABLED
        wake("image query")
        if (stageImage(imagePath, maskPath)) {
            Log.i(TAG, "image query staged in the small window: $imagePath")
            return ImageSubmit.STAGED
        }
        if (activeEndpoint == null) {
            noteNotConnected("image: no phone", "relay_unpaired")
            return ImageSubmit.NOT_CONNECTED
        }
        if (!ensureConnected("image query")) return ImageSubmit.NOT_CONNECTED
        thread(name = "AIRelay-image-query", isDaemon = true) {
            try {
                sendImageQuery(imagePath, maskPath, prompt)
            } catch (error: Exception) {
                Log.e(TAG, "Image query failed", error)
            }
        }
        return ImageSubmit.SENDING
    }

    fun sendWithImages(
        stream: ConversationStream?,
        text: String,
        images: List<Pair<String, String>>,
    ): Boolean {
        val endpoint = activeEndpoint ?: return false
        val target = stream ?: chatStream ?: return false
        if (!ensureConnected("send with images")) return false
        val parts = JSONArray()
        for ((imagePath, maskPath) in images) {
            val document = imagePath.endsWith(".pdf", ignoreCase = true)
            val url = try {
                if (document) uploadDocument(endpoint, File(imagePath)) else uploadImage(endpoint, imagePath, maskPath)
            } catch (e: Exception) {
                Log.w(TAG, "upload failed: ${e.message}")
                null
            } ?: return false
            parts.put(
                if (document) JSONObject().put("type", "document").put("url", url)
                    .put("fileName", File(imagePath).name).put("mime", "application/pdf")
                else JSONObject().put("type", "image").put("url", url)
            )
        }
        if (text.isNotBlank()) parts.put(JSONObject().put("type", "text").put("text", text))
        if (parts.length() == 0 || !started) return false
        if (stream == null) unbindChatFor(target)
        return target.sendMessage(parts)
    }

    private fun uploadImage(endpoint: PhoneEndpoint, imagePath: String, maskPath: String): String? {

        PluginIdentity.claim()
        val image = cropImageWithMask(imagePath, maskPath) ?: return null
        val boundary = "----AIRelayBoundary${System.currentTimeMillis()}"
        val multipart = ByteArrayOutputStream().apply {
            write("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"lasso.png\"\r\nContent-Type: image/png\r\n\r\n".toByteArray())
            write(image)
            write("\r\n--$boundary--\r\n".toByteArray())
        }.toByteArray()
        val upload = Http.post("${endpoint.baseUrl}/api/files/upload", multipart,
            "multipart/form-data; boundary=$boundary", 15_000)
        return JSONObject(upload).optJSONArray("files")?.optJSONObject(0)?.optString("url")?.ifEmpty { null }
    }

    private fun uploadDocument(endpoint: PhoneEndpoint, pdf: File): String? {
        PluginIdentity.claim()
        val boundary = "----AIRelayBoundary${System.currentTimeMillis()}"
        val multipart = ByteArrayOutputStream(pdf.length().toInt() + 512).apply {
            write("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"pages.pdf\"\r\nContent-Type: application/pdf\r\n\r\n".toByteArray())
            write(pdf.readBytes())
            write("\r\n--$boundary--\r\n".toByteArray())
        }.toByteArray()
        val upload = Http.post("${endpoint.baseUrl}/api/files/upload", multipart,
            "multipart/form-data; boundary=$boundary", DOCUMENT_UPLOAD_TIMEOUT_MS)
        return JSONObject(upload).optJSONArray("files")?.optJSONObject(0)?.optString("url")?.ifEmpty { null }
    }

    private fun sendImageQuery(imagePath: String, maskPath: String, prompt: String) {
        val endpoint = activeEndpoint ?: return
        val fileUrl = uploadImage(endpoint, imagePath, maskPath) ?: return
        if (!started) return
        val stream = chatStream ?: return
        val text = prompt
        val parts = JSONArray().put(JSONObject().put("type", "image").put("url", fileUrl))
        if (text.isNotBlank()) parts.put(JSONObject().put("type", "text").put("text", text))
        if (!started) return
        unbindChatFor(stream)
        stream.sendMessage(parts)
    }

    private fun cropImageWithMask(imagePath: String, maskPath: String): ByteArray? {
        val source = BitmapFactory.decodeFile(imagePath) ?: return null
        val rect = try {
            if (maskPath.isBlank()) null else JSONObject(File(maskPath).readText()).optJSONObject("boundingBox")?.let {
                Rect(it.optInt("x").coerceAtLeast(0), it.optInt("y").coerceAtLeast(0),
                    (it.optInt("x") + it.optInt("w")).coerceAtMost(source.width),
                    (it.optInt("y") + it.optInt("h")).coerceAtMost(source.height))
            }
        } catch (_: Exception) { null }
        val cropped = if (rect != null && rect.width() > 0 && rect.height() > 0) {
            Bitmap.createBitmap(source, rect.left, rect.top, rect.width(), rect.height())
        } else source
        return ByteArrayOutputStream().use { out ->
            cropped.compress(Bitmap.CompressFormat.PNG, 100, out)
            if (cropped !== source) cropped.recycle()
            source.recycle()
            out.toByteArray()
        }
    }

    fun insertText(text: String): Boolean {
        if (isDocMode() || text.isBlank()) return false
        notifyInsertion(InsertionRequest.Text(text, ""))
        return true
    }

    fun addTranscriptCard(conversationId: String, title: String, text: String): String {
        val id = "transcript-$conversationId"
        inbox.upsert(id, conversationId, Strings.get("relay_transcript_card", title) + "\n\n" + text.trim(),
            kind = "transcript", closed = true, source = "transcript", backend = backendTag())
        return id
    }

    fun insertSelection(id: String, text: String) {
        if (isDocMode() || text.isBlank() || !inbox.contains(id)) return
        notifyInsertion(InsertionRequest.Text(text, id))
        inbox.markInserted(id)
    }

    private fun isDocMode(): Boolean =
        HostSession.state.document.also { if (it) Log.i(TAG, "insert skipped in document mode") }

    fun deleteMany(ids: Collection<String>) = inbox.deleteMany(ids)
    fun setStarred(ids: Collection<String>, starred: Boolean) = inbox.setStarred(ids, starred)
    fun markSeen(ids: Collection<String>) = inbox.markSeen(ids)

    private fun notifyInsertion(request: InsertionRequest) = Main.handler.post {
        listeners.forEach { it.onInsertionRequested(request) }
    }

    fun inboxChanged() {
        publishInbox()
    }

    private fun publishInbox() = Main.handler.post {
        val snapshot = inbox.snapshot()
        listeners.forEach { it.onInboxChanged(snapshot) }
    }
}
