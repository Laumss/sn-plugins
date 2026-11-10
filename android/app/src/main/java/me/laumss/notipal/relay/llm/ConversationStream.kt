package me.laumss.notipal.relay.llm

import android.os.SystemClock
import android.util.Log
import me.laumss.notipal.NativeLocale
import me.laumss.notipal.relay.net.HttpJson
import me.laumss.notipal.relay.net.RawHttp
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class ConversationStream(
    private val baseUrl: String,

    private val followActive: Boolean,

    conversationId: String? = null,

    private val creationId: String? = null,

    private val seeds: RelaySeeds = RelaySeeds(),
) {
    companion object {
        private const val TAG = "ConversationStream"

        private const val RETRY_MIN_MS = 3_000L
        private const val RETRY_MAX_MS = 120_000L
        private const val RETRY_GAP_MS = 1_000L
        private const val RETRY_STABLE_MS = 30_000L
        private const val HTTP_TIMEOUT_MS = 30_000
        private const val SSE_READ_TIMEOUT_MS = 90_000
        private const val PRIME_WAIT_MS = 5_000L
        private const val PENDING_WINDOW_MS = 5 * 60_000L

        private const val SLOW_EVENT_MS = 60L

        private const val COALESCE_LOG_MIN = 3

        private val NODE_INDEX = Regex(""""nodeIndex"\s*:\s*(-?\d+)""")
    }

    class Message(val id: String, val role: String, val text: String, val finished: Boolean, val images: List<String> = emptyList())

    class Node(val id: String, val messages: List<Message>, val selectIndex: Int) {
        val current: Message? get() = messages.getOrNull(selectIndex) ?: messages.firstOrNull()
    }

    class Snapshot(val conversationId: String, val title: String, val nodes: List<Node>, val generating: Boolean)

    class RelaySettings(val prefillEnabled: Boolean, val startMarkers: String)

    var onReply: ((conversationId: String, messageId: String, text: String, done: Boolean, lead: String) -> Unit)? = null
    var onSnapshot: ((Snapshot) -> Unit)? = null
    var onConnectionChanged: ((Boolean) -> Unit)? = null

    var onSwitched: ((String) -> Unit)? = null

    private val running = AtomicBoolean(false)
    val isRunning: Boolean get() = running.get()
    @Volatile var isConnected = false; private set

    @Volatile var isPaused = false; private set

    @Volatile var retryAt = 0L; private set

    private val loopback = runCatching { URI(baseUrl).host }.getOrNull().let { it == "127.0.0.1" || it == "localhost" }

    private val signal = Object()
    private var wakeSeq = 0L
    @Volatile private var targetId: String? = conversationId
    val conversationId: String? get() = targetId

    private val threads = mutableListOf<Thread>()
    @Volatile private var conversationSse: RawHttp.Response? = null
    @Volatile private var followSse: RawHttp.Response? = null

    private val lock = Any()
    private var mirrorId: String? = null
    private var title = ""
    private val nodes = mutableListOf<Node>()
    private var generating = false
    private var primed = false
    private val history = HashSet<String>()
    private val replies = HashMap<String, Pair<String, Boolean>>()

    private var sentOver: Set<String>? = null
    private var sentAt = 0L

    private var pausedOver: Set<String>? = null

    private val replySeeds = HashMap<String, RelaySeeds.Seed?>()
    private val onSeedsReleased: (String) -> Unit = { id ->
        deliver(synchronized(lock) { if (mirrorId == id) collectReplies() else emptyList() })
    }

    private class Reply(val conversationId: String, val id: String, val text: String, val done: Boolean, val lead: String)

    val isGenerating: Boolean get() = synchronized(lock) { generating }

    val lastSentAt: Long get() = synchronized(lock) { sentAt }

    fun start() {
        if (running.getAndSet(true)) return
        Log.i(TAG, "start $baseUrl conversation=$targetId follow=$followActive")
        seeds.addListener(onSeedsReleased)
        spawn("conversation", ::conversationLoop)
        if (followActive) spawn("follow", ::followLoop)
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        Log.i(TAG, "stop $baseUrl")
        seeds.removeListener(onSeedsReleased)
        conversationSse?.close()
        followSse?.close()
        synchronized(threads) { threads.forEach { it.interrupt() }; threads.clear() }
        setConnected(false)
    }

    fun pause() {
        if (!running.get() || isPaused) return
        synchronized(lock) {
            if (primed && mirrorId != null) pausedOver = nodes.flatMapTo(HashSet()) { n -> n.messages.map { it.id } }
        }
        isPaused = true
        Log.i(TAG, "pause $baseUrl conversation=$targetId")
        conversationSse?.close()
        followSse?.close()
        setConnected(false)
        wakeReconnect()
    }

    fun resume() {
        if (!isPaused) return
        isPaused = false
        Log.i(TAG, "resume $baseUrl conversation=$targetId")
        wakeReconnect()
    }

    fun wakeReconnect() {
        synchronized(signal) { wakeSeq++; signal.notifyAll() }
    }

    fun sendQuery(text: String, baseMessageId: String? = null, continueUserTurn: Boolean = false): Boolean =
        send("relay-query", JSONObject().put("text", text).apply {
            baseMessageId?.let { put("baseMessageId", it) }
            if (continueUserTurn) put("continueUserTurn", true)
        })

    fun fetchRelaySettings(): RelaySettings? {
        val json = HttpJson.getJsonOrNull("$baseUrl/api/conversations/relay-settings", 3_000) ?: return null
        return RelaySettings(json.optBoolean("prefillEnabled", false), json.optString("startMarkers"))
    }

    fun sendMessage(parts: JSONArray): Boolean = send("messages", JSONObject().put("parts", parts))

    fun sendText(text: String): Boolean =
        sendMessage(JSONArray().put(JSONObject().put("type", "text").put("text", text)))

    fun stopGeneration() {
        val id = targetId ?: return
        runCatching { HttpJson.postJson("$baseUrl/api/conversations/$id/stop", JSONObject(), HTTP_TIMEOUT_MS) }
            .onFailure { Log.w(TAG, "stop generation failed: ${it.message}") }
    }

    private fun send(endpoint: String, body: JSONObject): Boolean {

        if (isPaused) resume()
        if (followActive) fetchActive()?.let { (id, _) -> if (id != targetId) switchTo(id) }
        val id = targetId ?: return false
        awaitPrimed(id)
        val relayQuery = endpoint == "relay-query"
        synchronized(lock) {
            sentOver = if (mirrorId == id) nodes.flatMap { n -> n.messages.map { it.id } }.toSet() else null
            sentAt = System.currentTimeMillis()
        }

        if (relayQuery) seeds.hold(id)
        var ok = false
        var newSeed: RelaySeeds.Seed? = null
        try {
            val response = HttpJson.postJson("$baseUrl/api/conversations/$id/$endpoint", body, HTTP_TIMEOUT_MS)
            if (relayQuery) {
                val json = JSONObject(response)
                val seed = json.optString("seed")
                if (json.optString("mode") == "prefill_branches_stream" && seed.isNotEmpty()) {

                    val start = json.optInt("cardStart", -1).takeIf { it in 0..seed.length } ?: seed.length
                    newSeed = RelaySeeds.Seed(seed, start, json.optString("leadMarker"))
                }
            }
            ok = true
        } catch (e: Exception) {
            Log.w(TAG, "$endpoint failed: ${e.message}")
        }

        if (relayQuery) seeds.release(id, newSeed.takeIf { ok })
        else deliver(synchronized(lock) { collectReplies() })
        return ok
    }

    private fun awaitPrimed(id: String) {
        val deadline = System.currentTimeMillis() + PRIME_WAIT_MS
        while (System.currentTimeMillis() < deadline && running.get()) {
            if (synchronized(lock) { mirrorId == id && primed }) return
            try { Thread.sleep(100) } catch (_: InterruptedException) { return }
        }
    }

    private fun spawn(name: String, body: () -> Unit) {
        val t = thread(name = "Relay-$name", isDaemon = true) { body() }
        synchronized(threads) { threads += t }
    }

    private class GoneException(path: String) : IOException("SSE $path → HTTP 404")

    private fun conversationLoop() {
        var retryMs = RETRY_MIN_MS
        while (running.get()) {
            if (!awaitResumed()) break
            var id: String? = null
            var gone = false
            var since = 0L
            try {
                val conversation = targetId ?: resolveConversation().also { targetId = it }
                id = conversation

                val sse = openSse("/api/conversations/$conversation/stream?delta=message")

                conversationSse = sse
                if (conversation != targetId) { sse.close(); continue }
                synchronized(lock) {
                    if (mirrorId != conversation) resetMirror(conversation)
                    primed = false
                }
                since = SystemClock.elapsedRealtime()
                setConnected(true)
                Log.i(TAG, "conversation $conversation connected")
                readEvents(sse) { event, data -> onConversationEvent(conversation, event, data) }
            } catch (_: InterruptedException) {
                break
            } catch (e: GoneException) {

                if (running.get() && followActive && id != null && id == targetId) {
                    Log.i(TAG, "conversation $id is gone, resolving again")
                    targetId = null
                    gone = true
                } else if (running.get() && id == targetId) {
                    Log.w(TAG, "conversation stream: ${e.message}")
                }
            } catch (e: Exception) {
                if (running.get() && !isPaused && id == targetId) Log.w(TAG, "conversation stream: ${e.message}")
            } finally {
                conversationSse = null
            }
            if (!running.get()) break

            if (isPaused) { setConnected(false); continue }
            if (since != 0L && SystemClock.elapsedRealtime() - since >= RETRY_STABLE_MS) retryMs = RETRY_MIN_MS

            if (gone) {
                setConnected(false)
                if (!awaitRetry(retryMs)) break
                retryMs = minOf(retryMs * 2, RETRY_MAX_MS)
                continue
            }
            if (id != null && id != targetId) continue
            setConnected(false)
            retryAt = SystemClock.elapsedRealtime() + retryMs
            val goOn = awaitRetry(retryMs)
            retryAt = 0L
            if (!goOn) break
            retryMs = minOf(retryMs * 2, RETRY_MAX_MS)
        }
    }

    private fun followLoop() {
        var retryMs = RETRY_MIN_MS
        while (running.get()) {
            if (!awaitResumed()) break
            var since = 0L
            try {
                val sse = openSse("/api/conversations/stream")
                followSse = sse
                since = SystemClock.elapsedRealtime()
                followActiveConversation()
                readEvents(sse) { _, _ -> followActiveConversation() }
            } catch (_: InterruptedException) {
                break
            } catch (e: Exception) {
                if (running.get() && !isPaused) Log.w(TAG, "follow stream: ${e.message}")
            } finally {
                followSse = null
            }
            if (!running.get()) break
            if (isPaused) continue
            if (since != 0L && SystemClock.elapsedRealtime() - since >= RETRY_STABLE_MS) retryMs = RETRY_MIN_MS
            if (!awaitRetry(retryMs)) break
            retryMs = minOf(retryMs * 2, RETRY_MAX_MS)
        }
    }

    private fun awaitRetry(delayMs: Long): Boolean {
        val start = SystemClock.elapsedRealtime()
        try {
            synchronized(signal) {
                val seen = wakeSeq
                while (running.get()) {
                    val waited = SystemClock.elapsedRealtime() - start
                    val limit = if (wakeSeq != seen) minOf(delayMs, RETRY_GAP_MS) else delayMs
                    if (waited >= limit) break
                    signal.wait(limit - waited)
                }
            }
        } catch (_: InterruptedException) {
            return false
        }
        return running.get()
    }

    private fun awaitResumed(): Boolean {
        try {
            synchronized(signal) { while (running.get() && isPaused) signal.wait() }
        } catch (_: InterruptedException) {
            return false
        }
        return running.get()
    }

    private fun followActiveConversation() {
        val (id, onScreen) = fetchActive() ?: return
        if (onScreen && id != targetId) switchTo(id)
    }

    private fun switchTo(id: String) {
        Log.i(TAG, "switch to conversation $id")
        targetId = id
        conversationSse?.close()
        onSwitched?.invoke(id)
    }

    private fun setConnected(value: Boolean) {
        if (isConnected == value) return
        isConnected = value
        onConnectionChanged?.invoke(value)
    }

    private fun resolveConversation(): String {
        val active = HttpJson.getJson("$baseUrl/api/conversations/active", 3_000)
            .optString("conversationId").takeIf { it.isNotBlank() && it != "null" }
        if (active != null) return active
        val creation = creationId ?: throw IOException("no conversation to follow")
        val created = JSONObject(HttpJson.postJson(
            "$baseUrl/api/conversations", JSONObject().put("conversationId", creation), HTTP_TIMEOUT_MS,
        )).optString("conversationId")
        if (created.isBlank() || created == "null") throw IOException("create returned no conversationId")
        return created
    }

    private fun fetchActive(): Pair<String, Boolean>? {
        val json = HttpJson.getJsonOrNull("$baseUrl/api/conversations/active", 3_000) ?: return null
        val id = json.optString("conversationId").takeIf { it.isNotBlank() && it != "null" } ?: return null
        return id to json.optBoolean("isOnScreen", false)
    }

    private fun openSse(path: String): RawHttp.Response {
        val response = RawHttp.request(
            method = "GET",
            url = "$baseUrl$path",
            headers = mapOf("Accept" to "text/event-stream", "Cache-Control" to "no-cache"),
            connectTimeoutMs = HTTP_TIMEOUT_MS,
            readTimeoutMs = if (loopback) 0 else SSE_READ_TIMEOUT_MS,
        )
        if (response.status != 200) {
            response.close()
            if (response.status == 404) throw GoneException(path)
            throw IOException("SSE $path → HTTP ${response.status}")
        }
        return response
    }

    private fun readEvents(sse: RawHttp.Response, onEvent: (String, String) -> Unit) {
        sse.use {
            val lock = Object()
            val queue = ArrayList<Pair<String, String>>()
            var ended = false
            var failure: Exception? = null
            val reader = thread(name = "$TAG-sse", isDaemon = true) {
                try {
                    val lines = it.body.bufferedReader(Charsets.UTF_8)
                    var event = ""
                    val data = StringBuilder()
                    while (running.get()) {
                        val line = lines.readLine() ?: break
                        when {
                            line.startsWith("event:") -> event = line.substring(6).trim()
                            line.startsWith("data:") -> {
                                if (data.isNotEmpty()) data.append('\n')
                                data.append(line.substring(5).trim())
                            }
                            line.isEmpty() && data.isNotEmpty() -> {
                                synchronized(lock) {
                                    queue += event to data.toString()
                                    lock.notifyAll()
                                }
                                event = ""
                                data.setLength(0)
                            }
                        }
                    }
                } catch (e: Exception) {
                    synchronized(lock) { failure = e }
                } finally {
                    synchronized(lock) {
                        ended = true
                        lock.notifyAll()
                    }
                }
            }
            try {
                while (running.get()) {
                    val batch = synchronized(lock) {
                        while (queue.isEmpty() && !ended) lock.wait()
                        if (queue.isEmpty()) null else ArrayList(queue).also { queue.clear() }
                    } ?: break
                    val kept = coalesce(batch)
                    if (batch.size - kept.size >= COALESCE_LOG_MIN) Log.i(TAG, "events: ${batch.size} waiting, ${kept.size} handled")
                    for ((event, data) in kept) {
                        val started = SystemClock.elapsedRealtime()
                        try { onEvent(event, data) } catch (e: Exception) {
                            Log.w(TAG, "bad $event event: ${e.message}")
                        }
                        val took = SystemClock.elapsedRealtime() - started
                        if (took >= SLOW_EVENT_MS) Log.i(TAG, "slow $event: ${data.length} chars, ${took}ms")
                    }
                }
            } finally {
                reader.interrupt()
            }
            synchronized(lock) { failure }?.let { error -> if (running.get()) throw error }
        }
    }

    private fun coalesce(batch: List<Pair<String, String>>): List<Pair<String, String>> {
        if (batch.size < 2) return batch
        val start = batch.indexOfLast { it.first == "snapshot" }.coerceAtLeast(0)
        val others = batch.filter { (event, _) -> event != "snapshot" && event != "node_update" && event != "message_update" }
        val updates = LinkedHashMap<Int, Pair<String, String>>()
        val lastWhole = HashMap<Int, Int>()
        val unkeyed = ArrayList<Pair<String, String>>()
        for (i in start until batch.size) {
            val item = batch[i]
            if (item.first != "node_update") continue
            val index = NODE_INDEX.find(item.second)?.groupValues?.get(1)?.toIntOrNull()
            if (index == null) unkeyed += item else {
                updates[index] = item
                lastWhole[index] = i
            }
        }
        val deltas = (start until batch.size).map { batch[it] to it }.filter { (item, i) ->
            item.first == "message_update" &&
                (NODE_INDEX.find(item.second)?.groupValues?.get(1)?.toIntOrNull()?.let { lastWhole[it] }?.let { i > it } ?: true)
        }.map { it.first }
        return listOfNotNull(batch.getOrNull(start)?.takeIf { it.first == "snapshot" }) +
            updates.toSortedMap().values + unkeyed + deltas + others
    }

    private fun onConversationEvent(id: String, event: String, data: String) {
        val json = JSONObject(data)
        val result = synchronized(lock) {
            if (mirrorId != id) return
            when (event) {
                "snapshot" -> {
                    val conversation = json.getJSONObject("conversation")
                    title = conversation.optString("title")
                    generating = conversation.optBoolean("isGenerating")
                    nodes.clear()
                    conversation.optJSONArray("messages")?.let { arr ->
                        for (i in 0 until arr.length()) nodes += parseNode(arr.getJSONObject(i))
                    }
                }
                "node_update" -> {
                    val index = json.optInt("nodeIndex", -1)
                    val node = parseNode(json.getJSONObject("node"))
                    generating = json.optBoolean("isGenerating")
                    when (index) {
                        in nodes.indices -> nodes[index] = node
                        nodes.size -> nodes += node
                        else -> return
                    }
                }
                "message_update" -> {
                    val index = json.optInt("nodeIndex", -1)
                    val node = nodes.getOrNull(index)?.takeIf { it.id == json.optString("nodeId") }
                    val merged = node?.let { mergeMessages(it, json) }
                    if (node == null || merged == null) {

                        Log.w(TAG, "message_update for node $index does not fit the mirror: reconnecting")
                        conversationSse?.close()
                        return
                    }
                    nodes[index] = Node(node.id, merged, json.optInt("selectIndex", node.selectIndex))
                    generating = json.optBoolean("isGenerating")
                }
                "error" -> { Log.w(TAG, "phone reported: ${data.take(200)}"); return }
                else -> return
            }
            if (!primed) prime()
            collectReplies() to onSnapshot?.let { Snapshot(id, title, nodes.toList(), generating) }
        }
        deliver(result.first)
        result.second?.let { onSnapshot?.invoke(it) }
    }

    private fun resetMirror(id: String) {
        mirrorId = id
        title = ""
        nodes.clear()
        generating = false
        history.clear()
        replies.clear()
        replySeeds.clear()
        sentOver = null
        pausedOver = null
    }

    private fun prime() {
        primed = true
        val inProgress = if (generating) nodes.lastOrNull()?.current?.id else null

        val ours = pausedOver ?: sentOver?.takeIf { System.currentTimeMillis() - sentAt < PENDING_WINDOW_MS }
        pausedOver = null
        for (node in nodes) for (message in node.messages) {
            if (message.role != "ASSISTANT" || message.id in replies || message.id == inProgress) continue
            if (ours != null && message.id !in ours) continue
            history += message.id
        }
    }

    private fun collectReplies(): List<Reply> {
        val id = mirrorId ?: return emptyList()
        if (!primed || seeds.isHeld(id)) return emptyList()
        val out = mutableListOf<Reply>()
        for (node in nodes) for (message in node.messages) {
            if (message.role != "ASSISTANT" || message.id in history) continue
            if (message.id !in replySeeds) replySeeds[message.id] = seeds.seedOf(id, message.text)
            val cut = replySeeds[message.id]
            val text = if (cut != null && message.text.startsWith(cut.text)) cut.cut(message.text) else message.text
            val done = message.finished || !generating || replies[message.id]?.second == true
            val reply = text.trim() to done
            if (replies[message.id] == reply) continue
            replies[message.id] = reply
            out += Reply(id, message.id, reply.first, reply.second, cut?.lead.orEmpty())
        }
        return out
    }

    private fun deliver(replies: List<Reply>) {
        replies.forEach { onReply?.invoke(it.conversationId, it.id, it.text, it.done, it.lead) }
    }

    private fun mergeMessages(node: Node, json: JSONObject): List<Message>? {
        val arr = json.optJSONArray("messages") ?: JSONArray()
        val changed = (0 until arr.length()).map { parseMessage(arr.getJSONObject(it)) }
        val byId = changed.associateBy { it.id }
        val merged = node.messages.map { byId[it.id] ?: it }.toMutableList()
        val known = node.messages.mapTo(HashSet()) { it.id }
        changed.filterTo(merged) { it.id !in known }
        return merged.takeIf { it.size == json.optInt("messageCount", it.size) }
    }

    private fun parseMessage(m: JSONObject): Message =
        Message(m.optString("id"), m.optString("role"), textOf(m), m.has("finishedAt") && !m.isNull("finishedAt"), imagesOf(m))

    private fun parseNode(json: JSONObject): Node {
        val arr = json.optJSONArray("messages") ?: JSONArray()
        val messages = (0 until arr.length()).map { i -> parseMessage(arr.getJSONObject(i)) }
        return Node(json.optString("id"), messages, json.optInt("selectIndex", 0))
    }

    private fun imagesOf(message: JSONObject): List<String> {
        val parts = message.optJSONArray("parts") ?: return emptyList()
        return (0 until parts.length()).mapNotNull { i ->
            parts.optJSONObject(i)?.takeIf { it.optString("type") == "image" }?.optString("url")?.ifEmpty { null }
        }
    }

    private fun textOf(message: JSONObject): String {
        val parts = message.optJSONArray("parts") ?: return ""
        return buildString {
            for (i in 0 until parts.length()) {
                val part = parts.optJSONObject(i) ?: continue
                when (part.optString("type")) {
                    "text" -> append(part.optString("text"))
                    "document" -> append(NativeLocale.t("relay_doc_alt", part.optString("fileName"))).append('\n')
                }
            }
        }
    }
}
