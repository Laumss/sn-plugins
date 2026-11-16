package me.laumss.notipal.relay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import me.laumss.notipal.common.App
import me.laumss.notipal.common.Http
import me.laumss.notipal.common.Log
import me.laumss.notipal.common.Main
import me.laumss.notipal.common.Strings
import me.laumss.notipal.relay.Transcripts.Phase
import me.laumss.notipal.relay.Transcripts.Status
import me.laumss.notipal.relay.Transcripts.Transcript
import me.laumss.notipal.ui_common.Dialogs
import me.laumss.notipal.ui_common.EmptyState
import me.laumss.notipal.ui_common.LineIcon
import me.laumss.notipal.ui_common.ScrollHost
import me.laumss.notipal.ui_common.Style
import me.laumss.notipal.ui_common.Widgets
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

internal object VoiceTags {
    sealed interface Piece {
        val text: String

        data class Plain(override val text: String) : Piece
        data class Voice(override val text: String) : Piece
    }

    private val TAG = Regex("<voice>([\\s\\S]*?)</voice>")

    fun split(text: String): List<Piece> {
        if (!text.contains("<voice>")) return listOf(Piece.Plain(text))
        val out = ArrayList<Piece>()
        fun plain(part: String) = part.trim().takeIf { it.isNotEmpty() }?.let { out += Piece.Plain(it) }
        var at = 0
        for (match in TAG.findAll(text)) {
            plain(text.substring(at, match.range.first))
            match.groupValues[1].trim().takeIf { it.isNotEmpty() }?.let { out += Piece.Voice(it) }
            at = match.range.last + 1
        }
        plain(text.substring(at))
        return out
    }

    fun inline(text: String, label: String): String =
        if (!text.contains("<voice>")) text
        else TAG.replace(text) { "$label${it.groupValues[1].trim()} " }.trim()
}

object Transcripts {
    private const val TAG = "Transcripts"
    private const val FILE_NAME = "relay_transcripts.json"

    private const val RETRY_MIN_MS = 3_000L
    private const val RETRY_MAX_MS = 120_000L
    private const val RETRY_GAP_MS = 1_000L
    private const val RETRY_STABLE_MS = 30_000L

    private const val PARK_MAX_MS = 5 * 60_000L

    private const val UNSUPPORTED_RETRY_MS = 30_000L

    private const val SSE_READ_TIMEOUT_MS = 60_000
    private const val HTTP_TIMEOUT_MS = 15_000

    private const val START_TIMEOUT_MS = 20_000
    private const val SAVE_DELAY_MS = 2_000L
    private const val MAX_KEPT = 200

    enum class Status { RECORDING, PROCESSING, DONE, ERROR }

    enum class Phase { IDLE, STARTING, RECORDING, PROCESSING }

    enum class Link { CONNECTING, LIVE, NO_PHONE, WEB_MODE, UNSUPPORTED, OFFLINE }

    class Analysis(
        val id: String,
        val promptId: String,
        val promptName: String,
        val model: String,
        val text: String,
        val error: String?,
        val running: Boolean,
        val createdAt: Long,
    )

    data class Transcript(
        val id: String,
        val title: String,
        val createdAt: Long,
        val updatedAt: Long,
        val durationMs: Long,
        val status: Status,

        val text: String,
        val length: Int,
        val error: String?,
        val source: String,

        val analyses: List<Analysis>,
        val analysisCount: Int,
        val analyzing: Boolean,
        val full: Boolean,
    ) {
        val live: Boolean get() = status == Status.RECORDING || status == Status.PROCESSING

        val usable: Boolean get() = !live && length > 0
    }

    class Prompt(val id: String, val name: String, val prompt: String)

    class State(val phase: Phase, val activeId: String?, val error: String?) {
        val active: Boolean get() = phase != Phase.IDLE
    }

    fun interface Listener {
        fun onTranscriptsChanged()
    }

    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private val items = LinkedHashMap<String, Transcript>()

    private val starred = HashSet<String>()
    private var promptList: List<Prompt> = emptyList()
    private val listeners = LinkedHashSet<Listener>()
    private var loaded = false
    @Volatile private var worker: Thread? = null
    @Volatile private var sse: Http.Response? = null

    private val signal = Object()
    private var wakeSeq = 0L
    @Volatile private var paused = false
    private var notifyPending = false
    private val saveLater = Runnable { save() }

    @Volatile var state: State = State(Phase.IDLE, null, null); private set
    @Volatile var link: Link = Link.CONNECTING; private set

    fun transcripts(): List<Transcript> = synchronized(lock) { items.values.sortedByDescending { it.createdAt } }

    fun transcript(id: String): Transcript? = synchronized(lock) { items[id] }

    fun isStarred(id: String): Boolean = synchronized(lock) { id in starred }

    fun setStarred(ids: List<String>, value: Boolean) {
        synchronized(lock) { if (value) starred.addAll(ids) else starred.removeAll(ids.toSet()) }
        changed(save = true)
    }

    fun prompts(): List<Prompt> = synchronized(lock) { promptList }

    val available: Boolean get() = link == Link.LIVE

    fun addListener(listener: Listener) {
        load()
        val first = listeners.isEmpty()
        listeners += listener
        if (first) startWorker()
    }

    fun removeListener(listener: Listener) {
        listeners -= listener
        if (listeners.isEmpty()) stopWorker()
    }

    fun setPaused(value: Boolean) {
        if (paused == value) return
        paused = value
        Log.i(TAG, if (value) "paused" else "resumed")
        if (value && !state.active) sse?.close()
        wake()
    }

    fun wake() {
        synchronized(signal) { wakeSeq++; signal.notifyAll() }
    }

    fun install() {
        RelayHooks.transcriptsPaused = { setPaused(it) }
        RelayHooks.transcriptsWake = { wake() }
        RelayHooks.transcriptsActive = { state.active }
    }

    fun startRecording(): Result<String> {
        val (status, body) = call("POST", "/start", JSONObject().put("source", "notipal"), START_TIMEOUT_MS)
            ?: return Result.failure(IllegalStateException(offlineReason()))
        if (status !in 200..299) return Result.failure(IllegalStateException(errorOf(body, status)))
        val id = runCatching { JSONObject(body).optString("id") }.getOrNull().orEmpty()
        return if (id.isNotEmpty()) Result.success(id) else Result.failure(IllegalStateException("start returned no id"))
    }

    fun stopRecording(): String? {
        val (status, body) = call("POST", "/stop", null) ?: return offlineReason()
        return if (status in 200..299) null else errorOf(body, status)
    }

    fun fetch(id: String): Transcript? {
        val (status, body) = call("GET", "/$id", null) ?: return null
        if (status !in 200..299) return null
        val transcript = runCatching { parse(JSONObject(body), full = true) }.getOrNull() ?: return null
        put(transcript)
        return transcript
    }

    fun analyze(id: String, promptId: String): String? {
        val (status, body) = call("POST", "/$id/analyze", JSONObject().put("promptId", promptId)) ?: return offlineReason()
        return if (status in 200..299) null else errorOf(body, status)
    }

    fun deleteAnalysis(id: String, analysisId: String): String? {
        val (status, body) = call("DELETE", "/$id/analyses/$analysisId", null) ?: return offlineReason()
        return if (status in 200..299) null else errorOf(body, status)
    }

    fun delete(id: String): String? {
        val (status, body) = call("DELETE", "/$id", null) ?: return offlineReason()
        if (status !in 200..299) return errorOf(body, status)
        remove(id)
        return null
    }

    fun voiceText(id: String): String? {
        val (status, body) = call("POST", "/$id/attachment", null) ?: return null
        if (status !in 200..299) {
            Log.w(TAG, "attachment $id: ${errorOf(body, status)}")
            return null
        }
        return runCatching { JSONObject(body) }.getOrNull()
            ?.takeIf { it.optString("type") == "text" }
            ?.optString("text")?.takeIf { it.isNotBlank() }
    }

    fun toConversation(id: String): Result<String> {
        val (status, body) = call("POST", "/$id/conversation", JSONObject())
            ?: return Result.failure(IllegalStateException(offlineReason()))
        if (status !in 200..299) return Result.failure(IllegalStateException(errorOf(body, status)))
        val conversationId = runCatching { JSONObject(body).optString("conversationId") }.getOrNull().orEmpty()
        return if (conversationId.isNotEmpty()) Result.success(conversationId)
        else Result.failure(IllegalStateException("no conversationId"))
    }

    private fun baseUrl(): String? {
        if (Conversations.isWebMode()) return null
        val phone = Conversations.pairedPhoneInfo()?.takeIf { it.host.isNotEmpty() && it.port > 0 } ?: return null
        return "${phone.baseUrl}/api/transcripts"
    }

    private fun offlineReason(): String = Strings.get(when {
        Conversations.isWebMode() -> "relay_voice_web"
        Conversations.pairedPhoneInfo() == null -> "relay_voice_unpaired"
        else -> "relay_voice_unreachable"
    })

    private fun call(method: String, path: String, body: JSONObject?, timeoutMs: Int = HTTP_TIMEOUT_MS): Pair<Int, String>? {
        val base = baseUrl() ?: return null
        return try {
            Http.request(
                method = method,
                url = base + path,
                headers = if (body != null) mapOf("Content-Type" to "application/json") else emptyMap(),
                body = body?.toString()?.toByteArray(Charsets.UTF_8),
                connectTimeoutMs = minOf(timeoutMs, 10_000),
                readTimeoutMs = timeoutMs,
            ).use { it.status to it.body.readBytes().toString(Charsets.UTF_8) }
        } catch (e: Exception) {
            Log.w(TAG, "$method $path failed: ${e.message}")
            null
        }
    }

    private fun errorOf(body: String, status: Int): String =
        runCatching { JSONObject(body).optString("error") }.getOrNull()?.takeIf { it.isNotBlank() } ?: "HTTP $status"

    private fun startWorker() {
        if (worker?.isAlive == true) return
        worker = thread(name = "Relay-transcripts", isDaemon = true) { streamLoop() }
    }

    private fun stopWorker() {
        val t = worker ?: return
        worker = null
        sse?.close()
        t.interrupt()
    }

    private fun streamLoop() {
        val me = Thread.currentThread()
        var retryMs = RETRY_MIN_MS
        while (worker === me) {
            val base = awaitWanted(me) ?: break
            var retry = retryMs
            var since = 0L
            try {
                val response = Http.request(
                    method = "GET",
                    url = "$base/stream",
                    headers = mapOf("Accept" to "text/event-stream", "Cache-Control" to "no-cache"),
                    connectTimeoutMs = 10_000,
                    readTimeoutMs = SSE_READ_TIMEOUT_MS,
                )
                sse = response
                if (worker !== me) { response.close(); break }
                when (response.status) {
                    200 -> {
                        since = SystemClock.elapsedRealtime()
                        readEvents(response)
                    }
                    404 -> {
                        response.close()
                        setLink(Link.UNSUPPORTED)
                        retry = UNSUPPORTED_RETRY_MS
                    }
                    else -> {
                        response.close()
                        Log.w(TAG, "stream → HTTP ${response.status}")
                        setLink(Link.OFFLINE)
                    }
                }
            } catch (_: InterruptedException) {
                break
            } catch (e: Exception) {
                if (worker === me && !paused) Log.w(TAG, "stream: ${e.message}")
            } finally {
                sse = null
            }
            if (worker !== me) break

            if (paused && !state.active) continue

            if (link != Link.UNSUPPORTED) setLink(Link.OFFLINE)

            if (state.active) { state = State(Phase.IDLE, null, null); changed() }
            if (since != 0L && SystemClock.elapsedRealtime() - since >= RETRY_STABLE_MS) retryMs = RETRY_MIN_MS
            if (!awaitRetry(me, retry)) break
            retryMs = minOf(retryMs * 2, RETRY_MAX_MS)
        }
        Log.i(TAG, "stream stopped")
    }

    private fun awaitWanted(me: Thread): String? {
        try {
            synchronized(signal) {
                while (worker === me) {
                    val base = baseUrl()
                    if (base == null) setLink(if (Conversations.isWebMode()) Link.WEB_MODE else Link.NO_PHONE)
                    if (base != null && (!paused || state.active)) return base
                    signal.wait(PARK_MAX_MS)
                }
            }
        } catch (_: InterruptedException) {

            Log.d(TAG, "park interrupted")
        }
        return null
    }

    private fun awaitRetry(me: Thread, delayMs: Long): Boolean {
        val start = SystemClock.elapsedRealtime()
        try {
            synchronized(signal) {
                val seen = wakeSeq
                while (worker === me) {
                    val waited = SystemClock.elapsedRealtime() - start
                    val limit = if (wakeSeq != seen) minOf(delayMs, RETRY_GAP_MS) else delayMs
                    if (waited >= limit) break
                    signal.wait(limit - waited)
                }
            }
        } catch (_: InterruptedException) {
            return false
        }
        return worker === me
    }

    private fun readEvents(response: Http.Response) {
        response.use {
            val reader = it.body.bufferedReader(Charsets.UTF_8)
            var event = ""
            val data = StringBuilder()
            while (true) {
                val line = reader.readLine() ?: break
                when {
                    line.startsWith("event:") -> event = line.substring(6).trim()
                    line.startsWith("data:") -> {
                        if (data.isNotEmpty()) data.append('\n')
                        data.append(line.substring(5).trim())
                    }
                    line.isEmpty() && data.isNotEmpty() -> {
                        try { onEvent(event, data.toString()) } catch (e: Exception) {
                            Log.w(TAG, "bad $event event: ${e.message}")
                        }
                        event = ""
                        data.setLength(0)
                    }
                }
            }
        }
    }

    private fun onEvent(event: String, data: String) {
        when (event) {
            "snapshot" -> {
                val json = JSONObject(data)
                val list = json.optJSONArray("items") ?: JSONArray()
                val fresh = (0 until list.length()).mapNotNull { i -> list.optJSONObject(i)?.let { parse(it, full = false) } }
                synchronized(lock) {
                    val old = HashMap(items)
                    items.clear()

                    for (t in fresh) items[t.id] = old[t.id]?.takeIf { it.full && it.updatedAt == t.updatedAt && it.analyzing == t.analyzing } ?: t
                    promptList = parsePrompts(json.optJSONArray("prompts"))
                }
                state = parseState(json.optJSONObject("state"))
                setLink(Link.LIVE)
                Log.i(TAG, "snapshot: ${fresh.size} transcripts, phase ${state.phase}")
                changed(save = true)
            }
            "transcript" -> put(parse(JSONObject(data), full = true))
            "deleted" -> remove(JSONObject(data).optString("id"))
            "state" -> {
                state = parseState(JSONObject(data))
                changed()

                if (paused && !state.active) sse?.close()
            }
            "prompts" -> {
                synchronized(lock) { promptList = parsePrompts(JSONArray(data)) }
                changed(save = true)
            }
        }
    }

    private fun put(transcript: Transcript) {
        synchronized(lock) { items[transcript.id] = transcript }
        changed(save = !transcript.live)
    }

    private fun remove(id: String) {
        if (id.isEmpty()) return
        val removed = synchronized(lock) {
            starred.remove(id)
            items.remove(id) != null
        }
        if (removed) changed(save = true)
    }

    private fun setLink(value: Link) {
        if (link == value) return
        link = value
        Log.i(TAG, "link: $value")
        changed()
    }

    private fun changed(save: Boolean = false) {
        main.post {
            if (save) {
                main.removeCallbacks(saveLater)
                main.postDelayed(saveLater, SAVE_DELAY_MS)
            }
            if (notifyPending) return@post
            notifyPending = true
            main.post {
                notifyPending = false
                listeners.toList().forEach { it.onTranscriptsChanged() }
            }
        }
    }

    private fun parse(json: JSONObject, full: Boolean): Transcript {
        val text = if (full) json.optString("text") else json.optString("preview")
        val analyses = if (!full) emptyList() else json.optJSONArray("analyses")?.let { arr ->
            (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(::parseAnalysis) }
        }.orEmpty()
        return Transcript(
            id = json.optString("id"),
            title = json.optString("title"),
            createdAt = json.optLong("createdAt"),
            updatedAt = json.optLong("updatedAt"),
            durationMs = json.optLong("durationMs"),
            status = when (json.optString("status")) {
                "Recording" -> Status.RECORDING
                "Processing" -> Status.PROCESSING
                "Error" -> Status.ERROR
                else -> Status.DONE
            },
            text = text,
            length = if (full) text.length else json.optInt("length", text.length),
            error = json.optNullableString("error"),
            source = json.optString("source", "phone"),
            analyses = analyses,
            analysisCount = if (full) analyses.count { !it.running && it.error == null } else json.optInt("analysisCount"),
            analyzing = if (full) analyses.any { it.running } else json.optBoolean("analyzing"),
            full = full,
        )
    }

    private fun parseAnalysis(json: JSONObject) = Analysis(
        id = json.optString("id"),
        promptId = json.optString("promptId"),
        promptName = json.optString("promptName"),
        model = json.optString("model"),
        text = json.optString("text"),
        error = json.optNullableString("error"),
        running = json.optBoolean("running"),
        createdAt = json.optLong("createdAt"),
    )

    private fun parsePrompts(arr: JSONArray?): List<Prompt> = arr?.let {
        (0 until it.length()).mapNotNull { i ->
            it.optJSONObject(i)?.let { p -> Prompt(p.optString("id"), p.optString("name"), p.optString("prompt")) }
        }
    }.orEmpty()

    private fun parseState(json: JSONObject?): State {
        json ?: return State(Phase.IDLE, null, null)
        val phase = when (json.optString("phase")) {
            "Starting" -> Phase.STARTING
            "Recording" -> Phase.RECORDING
            "Processing" -> Phase.PROCESSING
            else -> Phase.IDLE
        }
        return State(phase, json.optNullableString("activeId"), json.optNullableString("error"))
    }

    private fun JSONObject.optNullableString(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

    private fun load() {
        if (loaded) return
        loaded = true
        val file = File(App.context.filesDir, FILE_NAME)
        if (!file.exists()) return
        try {
            val json = JSONObject(file.readText())
            val list = json.optJSONArray("items") ?: JSONArray()
            synchronized(lock) {
                for (i in 0 until list.length()) {
                    val t = list.optJSONObject(i)?.let { parse(it, full = it.optBoolean("full")) } ?: continue

                    items[t.id] = if (t.live) t.copy(status = Status.DONE) else t
                }
                promptList = parsePrompts(json.optJSONArray("prompts"))
                json.optJSONArray("starred")?.let { stars ->
                    for (i in 0 until stars.length()) starred.add(stars.optString(i))
                }
            }
            Log.i(TAG, "loaded ${items.size} transcripts")
        } catch (e: Exception) {
            Log.w(TAG, "load failed: ${e.message}")
        }
    }

    private fun save() {
        val (list, prompts) = synchronized(lock) { transcripts().take(MAX_KEPT) to promptList }
        val stars = synchronized(lock) { starred.toList() }
        val json = JSONObject()
            .put("items", JSONArray().apply { list.forEach { put(toJson(it)) } })
            .put("starred", JSONArray().apply { stars.forEach { put(it) } })
            .put("prompts", JSONArray().apply {
                prompts.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("prompt", it.prompt)) }
            })
            .toString()
        thread(name = "Relay-transcripts-save", isDaemon = true) {
            try {
                val file = File(App.context.filesDir, FILE_NAME)
                val tmp = File(App.context.filesDir, "$FILE_NAME.tmp")
                tmp.writeText(json)
                if (!tmp.renameTo(file)) file.writeText(json)
            } catch (e: Exception) {
                Log.w(TAG, "save failed: ${e.message}")
            }
        }
    }

    private fun toJson(t: Transcript): JSONObject = JSONObject().apply {
        put("id", t.id)
        put("title", t.title)
        put("createdAt", t.createdAt)
        put("updatedAt", t.updatedAt)
        put("durationMs", t.durationMs)
        put("status", when (t.status) {
            Status.RECORDING -> "Recording"
            Status.PROCESSING -> "Processing"
            Status.DONE -> "Done"
            Status.ERROR -> "Error"
        })
        put(if (t.full) "text" else "preview", t.text)
        put("length", t.length)
        t.error?.let { put("error", it) }
        put("source", t.source)
        put("analysisCount", t.analysisCount)
        put("analyzing", t.analyzing)
        put("full", t.full)
        if (t.full) put("analyses", JSONArray().apply {
            t.analyses.forEach { a ->
                put(JSONObject().apply {
                    put("id", a.id)
                    put("promptId", a.promptId)
                    put("promptName", a.promptName)
                    put("model", a.model)
                    put("text", a.text)
                    a.error?.let { put("error", it) }
                    put("running", a.running)
                    put("createdAt", a.createdAt)
                })
            }
        })
    }
}

@SuppressLint("ViewConstructor")
class TranscriptPane(private val host: RelayHost) : LinearLayout(App.context), Transcripts.Listener {

    var onChanged: () -> Unit = {}

    var onPicksChanged: () -> Unit = {}

    var onListDrawn: (Boolean) -> Unit = {}

    var onDetailClosed: () -> Unit = {}

    val view: View get() = this

    private companion object {
        const val RENDER_DELAY_MS = 500L
        const val DELETE_ARM_MS = 3_000L
    }

    private fun u(v: Number) = Style.u(v)
    private fun t(key: String) = Strings.get(key)

    private var openId: String? = null
    private var choosingPrompt = false
    private var deleteArmedAt = 0L
    private var editing = false
    private val picked = LinkedHashSet<String>()

    private val checks = HashMap<String, CheckMark>()

    private var busy = false
    private var fetching: String? = null

    private var waitingFor: String? = null
    private var stale = false
    private var renderPending = false
    private val renderLater = Runnable {
        renderPending = false
        render(keepScroll = true)
    }

    private val status = Widgets.oneLine(Widgets.text(context, "", Style.F_META, Style.soft))
    private val button = TextView(context).apply {
        setTextColor(Style.ink)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, u(Style.F_META).toFloat())
        gravity = Gravity.CENTER
        minWidth = u(120)
        setPadding(u(20), u(8), u(20), u(8))
        background = Style.boxPx(Style.paper, Style.ink, Style.inkPx(2).coerceAtLeast(1), u(6).toFloat())
        setOnClickListener { onButton() }
    }

    private val scroll = ScrollHost(context).apply { follow = true }
    private val empty = EmptyState(context).apply { visibility = GONE }

    init {
        orientation = VERTICAL
        setBackgroundColor(Style.paper)
        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = u(Style.ROW_MIN)
            addView(LineIcon(context, LineIcon.Glyph.MIC, Style.ICON_U), LayoutParams(u(Style.ICON_U), u(Style.ICON_U)).apply { rightMargin = u(14) })
            addView(status, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(button, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { leftMargin = u(16) })
        }
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            leftMargin = u(Style.PANE_INSET); rightMargin = u(Style.PANE_INSET); topMargin = u(24)
        })
        addView(Widgets.rule(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, Style.inkPx(2).coerceAtLeast(1)).apply {
                leftMargin = u(Style.PANE_INSET); rightMargin = u(Style.PANE_INSET); topMargin = u(10)
            }
        })
        scroll.content.setPadding(0, 0, maxOf(u(Style.PANE_INSET), scroll.followLaneWidthPx), u(24))
        addView(FrameLayout(context).apply {

            setPadding(u(Style.PANE_INSET), 0, 0, 0)
            addView(scroll, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(empty, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
                .apply { rightMargin = u(Style.PANE_INSET) })
        }, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Transcripts.addListener(this)

        onChanged()

        render(keepScroll = openId != null || editing)
    }

    override fun onDetachedFromWindow() {
        Transcripts.removeListener(this)
        removeCallbacks(renderLater)
        renderPending = false
        super.onDetachedFromWindow()
    }

    override fun onTranscriptsChanged() {
        onChanged()
        if (visibility != VISIBLE) {
            stale = true
            return
        }
        if (renderPending) return
        renderPending = true
        postDelayed(renderLater, RENDER_DELAY_MS)
    }

    fun refreshIfStale() {
        if (stale) render(keepScroll = true)
    }

    fun open(id: String?) {
        if (openId == id) return
        openId = id
        choosingPrompt = false
        deleteArmedAt = 0L
        render(keepScroll = false)
        if (id == null) onDetailClosed()
    }

    fun back(): Boolean {
        if (openId == null) return false
        open(null)
        return true
    }

    val hasRows: Boolean get() = openId == null && Transcripts.transcripts().isNotEmpty()
    val pickedCount: Int get() = picked.size
    val pickedAllStarred: Boolean get() = picked.isNotEmpty() && picked.all(Transcripts::isStarred)

    fun setEditing(value: Boolean) {
        if (editing == value) return
        editing = value
        picked.clear()
        render(keepScroll = true)
    }

    fun clearPicks() {
        if (picked.isEmpty()) return
        picked.clear()
        render(keepScroll = true)
    }

    private fun toggle(id: String) {
        if (!picked.remove(id)) picked.add(id)
        checks[id]?.checked = id in picked
        onPicksChanged()
    }

    fun starPicked(): Boolean {
        if (picked.isEmpty()) return false
        Transcripts.setStarred(picked.toList(), !pickedAllStarred)
        return true
    }

    fun deletePicked() {
        delete(picked.toList())
        picked.clear()
    }

    fun delete(ids: List<String>) {
        if (ids.isEmpty()) return
        io({ ids.mapNotNull(Transcripts::delete).firstOrNull() }) { error -> error?.let(::tip) }
    }

    private fun onButton() {
        if (busy) return
        val state = Transcripts.state
        when (state.phase) {
            Phase.IDLE -> {
                busy = true
                render(keepScroll = true)
                io({ Transcripts.startRecording() }) { result ->
                    busy = false
                    result.onSuccess { id -> openId = id; waitingFor = id; choosingPrompt = false }
                        .onFailure { tip(it.message.orEmpty()) }
                    render(keepScroll = false)
                }
            }
            Phase.RECORDING -> stop()
            else -> Unit
        }
    }

    private fun stop() {
        busy = true
        render(keepScroll = true)
        io({ Transcripts.stopRecording() }) { error ->
            busy = false
            error?.let(::tip)
            render(keepScroll = true)
        }
    }

    private fun <T> io(block: () -> T, done: (T) -> Unit) {
        thread(name = "Relay-transcript-action", isDaemon = true) {
            val result = block()
            post { done(result) }
        }
    }

    private fun render(keepScroll: Boolean) {
        stale = false
        if (renderPending) {
            renderPending = false
            removeCallbacks(renderLater)
        }
        renderHeader()
        val offset = scroll.scrollOffset
        scroll.content.removeAllViews()
        checks.clear()
        val detail = openId?.let(Transcripts::transcript)

        if (detail == null && openId != null && openId != waitingFor) openId = null
        if (detail != null && openId == waitingFor) waitingFor = null
        if (detail != null) {
            empty.visibility = GONE
            renderDetail(detail)
        } else {
            val list = Transcripts.transcripts()
            if (picked.retainAll(list.filterNot { it.live }.mapTo(HashSet()) { it.id })) onPicksChanged()
            empty.visibility = if (list.isEmpty()) VISIBLE else GONE
            if (list.isEmpty()) empty.set(LineIcon.Glyph.MIC, t("relay_audio_empty"), t("relay_audio_empty_desc"))
            list.groupBy { inboxDay(it.createdAt) }.toSortedMap().forEach { (day, inDay) ->
                scroll.content.addView(Widgets.sectionLabel(context, inboxDayLabel(day), topU = 22))
                scroll.content.addView(Widgets.rule(context))
                inDay.forEachIndexed { i, item ->
                    val check = if (editing && !item.live) CheckMark(context).apply { checked = item.id in picked }.also { checks[item.id] = it } else null
                    scroll.content.addView(row(item, last = i == inDay.lastIndex, check = check) {
                        when {
                            !editing -> open(item.id)
                            !item.live -> toggle(item.id)
                        }
                    })
                }
            }
        }
        scroll.scrollToOffset(if (keepScroll) offset else 0)
        onListDrawn(hasRows)
    }

    private fun renderHeader() {
        val state = Transcripts.state
        val link = Transcripts.link
        val active = state.activeId?.let(Transcripts::transcript)
        val (line, label) = when {
            link == Transcripts.Link.WEB_MODE -> t("relay_audio_web") to null
            link == Transcripts.Link.NO_PHONE -> t("relay_audio_no_phone") to null
            link == Transcripts.Link.UNSUPPORTED -> t("relay_audio_unsupported") to null
            link == Transcripts.Link.CONNECTING -> t("relay_audio_connecting") to null
            link == Transcripts.Link.OFFLINE -> t("relay_audio_offline") to null
            state.phase == Phase.STARTING -> t("relay_audio_starting") to ""
            state.phase == Phase.RECORDING -> Strings.get("relay_audio_recording",
                formatDuration(active?.durationMs ?: 0L), active?.length ?: 0) to t("relay_audio_stop")
            state.phase == Phase.PROCESSING -> t("relay_audio_processing") to ""
            state.error != null -> Strings.get("relay_audio_last_error", state.error) to t("relay_audio_start")
            else -> t("relay_audio_idle") to t("relay_audio_start")
        }
        status.text = line
        status.setTypeface(null, if (state.phase == Phase.RECORDING) Typeface.BOLD else Typeface.NORMAL)
        button.visibility = if (label == null) GONE else VISIBLE
        val enabled = !busy && !label.isNullOrEmpty()
        button.text = if (busy || label.isNullOrEmpty()) "…" else label
        button.isEnabled = enabled
        button.alpha = if (enabled) 1f else Style.DISABLED_ALPHA
    }

    private fun meta(item: Transcript): String = buildList {
        if (item.durationMs > 0) add(formatDuration(item.durationMs))
        add(Strings.get("relay_audio_chars", item.length))
        if (item.analysisCount > 0) add(Strings.get("relay_audio_analyses", item.analysisCount))
        if (item.source == "notipal") add(t("relay_audio_from_notipal"))
        if (Transcripts.isStarred(item.id)) add(t("relay_tab_starred"))
    }.joinToString(" · ")

    fun row(item: Transcript, last: Boolean, check: View?, onTap: () -> Unit): View {
        val (line, strong) = when {
            item.status == Status.RECORDING -> t("relay_audio_live") to true
            item.status == Status.PROCESSING -> t("relay_audio_live_processing") to true
            item.analyzing -> t("relay_audio_analyzing") to true
            item.status == Status.ERROR && item.length == 0 -> Strings.get("relay_audio_error", item.error.orEmpty()) to false
            else -> item.text.replace('\n', ' ').trim() to false
        }
        val extra = Widgets.oneLine(Widgets.text(context, line, Style.F_META, if (strong) Style.ink else Style.soft, bold = strong))
        val lead: View = check ?: LineIcon(context, LineIcon.Glyph.MIC, Style.ICON_U)
        lead.layoutParams = LayoutParams(u(Style.ICON_U), u(Style.ICON_U))
        return Widgets.listRow(
            context, item.title,
            meta = meta(item),
            right = formatInboxTime(item.createdAt),
            solid = last,
            lead = lead,
            extra = extra.takeIf { line.isNotEmpty() },
            onClick = onTap,
        ).view
    }

    private fun renderDetail(item: Transcript) {
        val content = scroll.content
        content.addView(LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, u(18), 0, u(10))
            addView(LineIcon(context, LineIcon.Glyph.BACK, Style.CHEVRON_U), LayoutParams(u(Style.CHEVRON_U), u(Style.CHEVRON_U)).apply { rightMargin = u(8) })
            addView(Widgets.text(context, t("relay_audio_back"), Style.F_META, Style.muted))
            setOnClickListener { open(null) }
        }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        content.addView(Widgets.text(context, item.title, 26, bold = true))
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(item.createdAt))
        content.addView(Widgets.text(context, "$time · ${meta(item)}", Style.F_META, Style.subtle).apply { setPadding(0, u(6), 0, u(14)) })
        content.addView(actions(item))
        if (choosingPrompt) content.addView(promptChoices(item))
        content.addView(Widgets.rule(context).apply {
            (layoutParams as LayoutParams).topMargin = u(14)
        })

        if (!item.full && fetching != item.id) {
            fetching = item.id
            io({ Transcripts.fetch(item.id) }) { fetching = null }
        }
        val body = when {

            !item.full -> if (Transcripts.available) t("relay_audio_loading") else item.text.trim() + "…"
            item.text.isNotBlank() -> item.text
            item.live -> t("relay_audio_waiting_text")
            else -> t("relay_audio_no_text")
        }
        content.addView(bodyText(body, if (item.full && item.text.isNotBlank()) Style.ink else Style.muted).apply {
            setPadding(0, u(18), 0, u(10))
        })
        item.error?.let { content.addView(Widgets.text(context, Strings.get("relay_audio_error", it), Style.F_META, Style.ink, bold = true)) }

        for (analysis in item.analyses.sortedByDescending { it.createdAt }) {
            val heading = listOf(analysis.promptName, analysis.model).filter { it.isNotBlank() }.joinToString(" · ")
            content.addView(Widgets.sectionLabel(context, heading, topU = 30))
            content.addView(Widgets.rule(context))
            val text = when {
                analysis.running -> t("relay_audio_analyzing")
                analysis.error != null -> Strings.get("relay_audio_analysis_failed", analysis.error)
                else -> plainMarkdown(analysis.text)
            }
            content.addView(bodyText(text, if (analysis.running || analysis.error != null) Style.muted else Style.ink).apply {
                setPadding(0, u(14), 0, u(8))
            })
            if (!analysis.running) content.addView(linkRow(buildList {
                if (analysis.error == null && analysis.text.isNotBlank()) add(t("relay_insert_note") to { insert(plainMarkdown(analysis.text)) })
                add(t("relay_delete") to {
                    io({ Transcripts.deleteAnalysis(item.id, analysis.id) }) { error -> error?.let(::tip) }
                })
            }))
        }
    }

    private fun actions(item: Transcript): View {
        val state = Transcripts.state
        val recordingThis = state.activeId == item.id && state.phase == Phase.RECORDING
        val armed = System.currentTimeMillis() - deleteArmedAt < DELETE_ARM_MS
        return linkRow(buildList {
            if (recordingThis) add(t("relay_audio_stop_recording") to { if (!busy) stop() })
            if (item.usable && item.full) {
                add(t("relay_audio_analyze") to {
                    choosingPrompt = !choosingPrompt
                    render(keepScroll = true)
                })
                add(t("relay_audio_ask") to { host.voice.ask(item) })
                add(t("relay_insert_note") to { insert(item.text) })
                add(t("relay_audio_conversation") to { openConversation(item) })
            }
            val starred = Transcripts.isStarred(item.id)
            add(t(if (starred) "relay_unstar" else "relay_star") to { Transcripts.setStarred(listOf(item.id), !starred) })
            if (!item.live) add(t(if (armed) "picker_delete_again" else "relay_delete") to { deleteTapped(item) })
        })
    }

    private fun promptChoices(item: Transcript): View {
        val prompts = Transcripts.prompts()
        val row = linkRow(prompts.map { prompt ->
            prompt.name to {
                choosingPrompt = false
                render(keepScroll = true)
                io({ Transcripts.analyze(item.id, prompt.id) }) { error -> error?.let(::tip) }
            }
        } + (t("cancel") to { choosingPrompt = false; render(keepScroll = true) }))
        row.addView(Widgets.text(context, t(if (prompts.isEmpty()) "relay_audio_no_prompts" else "relay_audio_pick_prompt"),
            Style.F_META, Style.muted), 0)
        return row
    }

    private fun deleteTapped(item: Transcript) {
        val now = System.currentTimeMillis()
        if (now - deleteArmedAt >= DELETE_ARM_MS) {
            deleteArmedAt = now
            render(keepScroll = true)
            postDelayed({ if (deleteArmedAt == now) render(keepScroll = true) }, DELETE_ARM_MS)
            return
        }
        deleteArmedAt = 0L
        io({ Transcripts.delete(item.id) }) { error ->
            if (error != null) tip(error) else if (openId == item.id) open(null)
        }
    }

    private fun tip(text: String) = Dialogs.tip(text)

    private fun insertText(text: String) {
        Dialogs.tip(Strings.get(if (Conversations.insertText(text)) "relay_audio_inserted" else "relay_audio_doc_mode"))
    }

    private fun openConversation(transcript: Transcript) {
        thread(name = "Relay-transcript-chat", isDaemon = true) {
            val result = Transcripts.toConversation(transcript.id)
            val text = Transcripts.transcript(transcript.id)?.takeIf { it.full }?.text ?: transcript.text
            Main.post {
                result.onSuccess { conversationId ->
                    val cardId = Conversations.addTranscriptCard(conversationId, transcript.title, text)

                    host.openCard(cardId, fresh = true)
                }.onFailure { Dialogs.tip(it.message.orEmpty()) }
            }
        }
    }

    private fun insert(text: String) {
        if (text.isNotBlank()) insertText(text)
    }

    private fun linkRow(items: List<Pair<String, () -> Unit>>): ViewGroup = WrapRow(context, u(28), u(10)).apply {
        items.forEach { (label, action) ->
            addView(Widgets.link(context, label, Style.F_META) { action() }.apply { setPadding(0, u(8), 0, u(8)) })
        }
    }

    private fun bodyText(text: String, color: Int): TextView =
        Widgets.text(context, text, Style.F_BODY, color).apply { setLineSpacing(0f, 1.3f) }

    private fun plainMarkdown(text: String): String =
        MarkdownDocumentParser.parse(text).blocks.mapNotNull { block ->
            when (block) {
                is MarkdownBlock.Heading -> plainLine(block.text)
                is MarkdownBlock.Paragraph -> plainLine(block.text)
                is MarkdownBlock.Quote -> plainLine(block.text)
                is MarkdownBlock.ListItem -> "${block.marker} ${plainLine(block.text)}"
                is MarkdownBlock.CodeFence -> block.code.trimEnd()
                is MarkdownBlock.Table -> (listOf(block.headers) + block.rows).joinToString("\n") { cells ->
                    cells.joinToString(" · ") { plainLine(it) }
                }
                is MarkdownBlock.Rule -> null
            }?.takeIf { it.isNotBlank() }
        }.joinToString("\n").ifBlank { text.trim() }

    private class WrapRow(context: Context, private val gap: Int, private val lineGap: Int) : ViewGroup(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val width = MeasureSpec.getSize(widthMeasureSpec)
            var x = 0
            var y = 0
            var lineHeight = 0
            for (i in 0 until childCount) {
                val child = getChildAt(i)
                if (child.visibility == GONE) continue
                child.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST), MeasureSpec.UNSPECIFIED)
                if (x > 0 && x + child.measuredWidth > width) {
                    x = 0
                    y += lineHeight + lineGap
                    lineHeight = 0
                }
                x += child.measuredWidth + gap
                lineHeight = maxOf(lineHeight, child.measuredHeight)
            }
            setMeasuredDimension(width, y + lineHeight)
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val width = r - l
            var x = 0
            var y = 0
            var lineHeight = 0
            for (i in 0 until childCount) {
                val child = getChildAt(i)
                if (child.visibility == GONE) continue
                if (x > 0 && x + child.measuredWidth > width) {
                    x = 0
                    y += lineHeight + lineGap
                    lineHeight = 0
                }
                child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight)
                x += child.measuredWidth + gap
                lineHeight = maxOf(lineHeight, child.measuredHeight)
            }
        }
    }
}

class VoiceStage(private val host: RelayHost) {
    private val tag = "RelayVoice"

    private val voices = mutableListOf<String>()

    private var voiceBusy = false
    private val listener = Transcripts.Listener { onTranscriptsChanged() }

    private var micIcon: ImageView? = null

    private var micView: View? = null
    private var micPx = 0
    private var micTint = Style.ink
    private var micRecording: Boolean? = null

    val staged: List<String> get() = voices.toList()

    fun attach() {
        Transcripts.addListener(listener)
    }

    fun detach() {
        Transcripts.removeListener(listener)
        voices.clear()
        voiceBusy = false
        micIcon = null
        micView = null
        micRecording = null
    }

    fun bindMic(icon: ImageView?, cell: View?, px: Int, tint: Int = Style.ink) {
        micIcon = icon
        micView = cell
        micPx = px
        micTint = tint
        micRecording = null
        refresh()
    }

    private fun notice(text: String, ms: Long) {
        if (host.small) host.flashStatus(text, ms) else Dialogs.tip(text)
    }

    fun toggle() {
        if (voiceBusy) return
        val state = Transcripts.state
        val recording = state.activeId?.takeIf { state.phase == Phase.RECORDING }
        if (recording == null && (!Transcripts.available || state.active)) {
            val reason = when {
                state.phase == Phase.STARTING -> Strings.get("relay_audio_starting")
                state.active -> Strings.get("relay_audio_processing")
                Transcripts.link == Transcripts.Link.WEB_MODE -> Strings.get("relay_voice_web")
                Transcripts.link == Transcripts.Link.NO_PHONE -> Strings.get("relay_voice_unpaired")
                Transcripts.link == Transcripts.Link.UNSUPPORTED -> Strings.get("relay_audio_unsupported")
                else -> Strings.get("relay_voice_unreachable")
            }
            notice(reason, 3000)
            return
        }
        voiceBusy = true
        refresh()
        if (recording != null) {

            stage(recording)
            thread(name = "Relay-voice-stop", isDaemon = true) {
                val error = Transcripts.stopRecording()
                Main.post {
                    voiceBusy = false
                    error?.let { Dialogs.tip(it) }
                    refresh()
                }
            }
            return
        }
        thread(name = "Relay-voice-start", isDaemon = true) {
            val result = Transcripts.startRecording()
            Main.post {
                voiceBusy = false
                result.onSuccess { id ->
                    stage(id)
                    if (host.small) host.flashStatus(Strings.get("relay_voice_started"), 3000)
                }.onFailure { Dialogs.tip(it.message.orEmpty()) }
                refresh()
            }
        }
    }

    fun stage(id: String) {
        host.reveal("voice staged")
        if (id !in voices) voices += id
        host.attachmentsChanged()
        Log.i(tag, "staged transcript $id (${voices.size} waiting)")
    }

    fun drop(id: String) {
        voices.remove(id)
        host.attachmentsChanged()
        val state = Transcripts.state
        if (state.activeId == id && state.phase == Phase.RECORDING) {
            thread(name = "Relay-voice-stop", isDaemon = true) { Transcripts.stopRecording() }
        }
    }

    fun remove(ids: Collection<String>) {
        voices.removeAll(ids.toSet())
    }

    fun ready(): List<String>? {
        val state = Transcripts.state
        val gone = voices.filter { Transcripts.transcript(it) == null && it != state.activeId && Transcripts.available }
        if (gone.isNotEmpty()) {
            voices.removeAll(gone.toSet())
            host.attachmentsChanged()
        }
        val waiting = voices.any { id -> id == state.activeId || Transcripts.transcript(id)?.live == true }
        if (waiting) {
            notice(Strings.get("relay_voice_wait"), 3000)
            return null
        }
        return voices.toList()
    }

    fun blocks(ids: List<String>): List<String>? =
        ids.map { id -> Transcripts.voiceText(id) ?: return null }

    fun withVoices(text: String, blocks: List<String>): String =
        listOf(blocks.joinToString("\n"), text.trim()).filter { it.isNotEmpty() }.joinToString("\n\n")

    fun chip(id: String): Pair<String, String> {
        val t = Transcripts.transcript(id)
        val detail = when (t?.status) {
            null -> Strings.get("relay_audio_starting")
            Status.RECORDING -> Strings.get("relay_voice_chip_recording", t.length)
            Status.PROCESSING -> Strings.get("relay_voice_chip_processing")
            else -> Strings.get("relay_voice_chip", t.length)
        }
        return (t?.title ?: "…") to detail
    }

    fun ask(transcript: Transcript) {
        if (transcript.id !in voices) voices += transcript.id
        host.open(RelayPage.COMPOSE)
        host.attachmentsChanged()
    }

    private fun onTranscriptsChanged() {
        val empty = voices.filter { id -> Transcripts.transcript(id)?.let { it.usable || it.live } == false }
        if (empty.isNotEmpty()) {
            voices.removeAll(empty.toSet())
            notice(Strings.get("relay_audio_no_text"), 2500)
        }
        if (voices.isNotEmpty() || empty.isNotEmpty()) host.attachmentsChanged() else refresh()
    }

    fun refresh() {

        micView?.visibility = if (Conversations.isWebMode()) View.GONE else View.VISIBLE
        val icon = micIcon ?: return
        val state = Transcripts.state
        val recording = state.phase == Phase.RECORDING
        val offered = Transcripts.available || recording
        val settled = state.phase == Phase.IDLE || recording
        if (micRecording != recording) {
            micRecording = recording
            val asset = if (recording) "icons/ic_ai_mic_stop.xml" else "icons/ic_ai_mic.xml"
            icon.setImageDrawable(Style.iconPx(asset, micPx, micTint))
        }
        icon.alpha = if (offered && !voiceBusy && settled) 1f else Style.DISABLED_ALPHA
    }
}
