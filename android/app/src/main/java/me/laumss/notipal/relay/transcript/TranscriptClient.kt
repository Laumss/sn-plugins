package me.laumss.notipal.relay.transcript

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import me.laumss.notipal.NativeLocale
import me.laumss.notipal.relay.AIRelayCore
import me.laumss.notipal.relay.net.RawHttp
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.concurrent.thread

class TranscriptClient private constructor(private val context: Context) {
    companion object {
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

        @Volatile private var instance: TranscriptClient? = null

        fun get(context: Context): TranscriptClient = instance ?: synchronized(this) {
            instance ?: TranscriptClient(context.applicationContext).also { instance = it }
        }
    }

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
    private val core get() = AIRelayCore.get(context)
    private val lock = Any()
    private val items = LinkedHashMap<String, Transcript>()

    private val starred = HashSet<String>()
    private var promptList: List<Prompt> = emptyList()
    private val listeners = LinkedHashSet<Listener>()
    private var loaded = false
    @Volatile private var worker: Thread? = null
    @Volatile private var sse: RawHttp.Response? = null

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
        if (core.isWebMode()) return null
        val phone = core.pairedPhoneInfo()?.takeIf { it.host.isNotEmpty() && it.port > 0 } ?: return null
        return "${phone.baseUrl}/api/transcripts"
    }

    private fun offlineReason(): String = NativeLocale.t(when {
        core.isWebMode() -> "relay_voice_web"
        core.pairedPhoneInfo() == null -> "relay_voice_unpaired"
        else -> "relay_voice_unreachable"
    })

    private fun call(method: String, path: String, body: JSONObject?, timeoutMs: Int = HTTP_TIMEOUT_MS): Pair<Int, String>? {
        val base = baseUrl() ?: return null
        return try {
            RawHttp.request(
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
                val response = RawHttp.request(
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
                    if (base == null) setLink(if (core.isWebMode()) Link.WEB_MODE else Link.NO_PHONE)
                    if (base != null && (!paused || state.active)) return base
                    signal.wait(PARK_MAX_MS)
                }
            }
        } catch (_: InterruptedException) {
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

    private fun readEvents(response: RawHttp.Response) {
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
        val file = File(context.filesDir, FILE_NAME)
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
                val file = File(context.filesDir, FILE_NAME)
                val tmp = File(context.filesDir, "$FILE_NAME.tmp")
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
