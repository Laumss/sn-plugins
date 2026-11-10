package me.laumss.notipal.relay.web

import android.content.res.AssetManager
import android.util.Log
import me.laumss.notipal.NativeLocale
import me.laumss.notipal.net.PluginIdentity
import me.laumss.notipal.relay.core.RelayTurns
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

internal class WebRelayServer(
    baseDir: File,
    private val token: () -> String,
    private val assets: () -> AssetManager?,
) {
    companion object {
        private const val TAG = "WebRelay"
        const val DEFAULT_PORT = 8765
        private const val PORT_TRIES = 10
        private const val MAX_BODY = 24 * 1024 * 1024

        private const val SSE_PING_MS = 30_000L

        private const val LOCAL_POLL_MS = 5 * 60_000L

        private const val WORKER_SILENT_MS = 90_000L

        private const val DRAIN_MS = 3_000
        private const val DRAIN_BYTES = 32L * 1024 * 1024
        private const val TRANSLATE_WAIT_MS = 170_000L

        private const val CANCEL_POLL_MS = 1_000L

        private const val PENDING_JOB_TTL_MS = 60_000L
        private const val STALL_MS = 5 * 60_000L
        private const val SWEEP_MS = 30_000L
        private const val START_WAIT_MS = 3_000L
        private val FILE_NAME = Regex("[A-Za-z0-9._-]{1,80}")

        fun lanIpv4(): String? {
            val candidates = mutableListOf<Pair<String, String>>()
            try {
                val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
                while (interfaces.hasMoreElements()) {
                    val iface = interfaces.nextElement()
                    val usable = runCatching { iface.isUp && !iface.isLoopback && !iface.isPointToPoint }.getOrDefault(false)
                    if (!usable) continue
                    for (address in iface.interfaceAddresses) {
                        val ip = address.address as? Inet4Address ?: continue
                        if (ip.isSiteLocalAddress) candidates += iface.name to ip.hostAddress.orEmpty()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "lan address: ${e.message}")
            }
            return (candidates.firstOrNull { it.first.startsWith("wlan") } ?: candidates.firstOrNull())?.second
        }
    }

    class ApiError(val status: Int, message: String) : Exception(message)

    private class SseClient(val kind: Kind, val key: String, val params: Map<String, String>, val local: Boolean) {
        enum class Kind { CONVERSATION, FOLLOW, WORKER }

        val id: String = UUID.randomUUID().toString()
        val connectedAt = System.currentTimeMillis()
        val queue = LinkedBlockingQueue<String>()
        private val seq = AtomicLong(0)
        @Volatile var closed = false

        @Volatile var lastSeen = connectedAt

        @Volatile var socket: Socket? = null

        fun nextSeq(): Long = seq.incrementAndGet()

        fun send(event: String, data: String) {
            if (!closed) queue.offer("event: $event\ndata: $data\n\n")
        }

        fun close() {
            closed = true
            queue.offer("")
            socket?.let { runCatching { it.close() } }
        }

        fun can(cap: String): Boolean = params[cap] == "1"
    }

    private class Job(
        val id: String,

        val kind: String,
        val conversationId: String,
        val messageId: String,
        val payload: JSONObject,

        val local: Boolean = false,
    ) {
        val createdAt = System.currentTimeMillis()
        @Volatile var worker: SseClient? = null
        @Volatile var lastActivity = createdAt
        @Volatile var cancelled = false
        val waiter = Object()
        @Volatile var result: JSONObject? = null
        @Volatile var error: String? = null
    }

    private class Request(
        val method: String,
        val path: String,
        val query: Map<String, String>,
        val headers: Map<String, String>,
        val body: ByteArray,
        val remote: InetAddress,

        val gone: () -> Boolean,
    ) {
        val isLocal: Boolean get() = remote.isLoopbackAddress
        fun header(name: String): String? = headers[name.lowercase()]
        fun json(): JSONObject = if (body.isEmpty()) JSONObject() else try {
            JSONObject(String(body, Charsets.UTF_8))
        } catch (e: Exception) {
            throw ApiError(400, "Invalid JSON body")
        }
    }

    private sealed class Reply {
        class Full(val status: Int, val contentType: String, val body: ByteArray, val headers: Map<String, String> = emptyMap()) : Reply()
        class Stream(val client: SseClient) : Reply()
    }

    val store = WebConversationStore(File(baseDir, "conversations"))
    private val filesDir = File(baseDir, "files")
    private val settingsFile = File(baseDir, "settings.json")

    @Volatile var port: Int = 0
        private set
    private val running = AtomicBoolean(false)
    @Volatile private var serverSocket: ServerSocket? = null
    private val clients = CopyOnWriteArraySet<SseClient>()
    private val jobs = ConcurrentHashMap<String, Job>()
    private val jobLock = Any()
    private val pendingJobs = ArrayDeque<Job>()
    @Volatile private var sweeper: ScheduledExecutorService? = null

    @Volatile var onWorkersChanged: ((Int) -> Unit)? = null

    @Volatile var onReplyFailed: ((String) -> Unit)? = null

    val workerCount: Int get() = clients.count { it.kind == SseClient.Kind.WORKER && !it.closed }

    val chatReady: Boolean get() = clients.any { it.kind == SseClient.Kind.WORKER && !it.closed && it.can("chat") }
    val translateReady: Boolean get() = clients.any { it.kind == SseClient.Kind.WORKER && !it.closed && it.can("translate") }
    private val lifeLock = Any()

    private var loaded = false
    private var sessionWanted = false

    fun pageUrl(): String? = lanIpv4()?.let { "http://$it:$port/?t=${token()}" }

    fun start(): Boolean {
        if (running.get()) return true
        val bound = CountDownLatch(1)
        val ok = AtomicBoolean(false)
        thread(name = "WebRelay-accept", isDaemon = true) {
            PluginIdentity.claim()
            var socket: ServerSocket? = null
            for (candidate in DEFAULT_PORT until DEFAULT_PORT + PORT_TRIES) {
                try {
                    socket = ServerSocket().apply {
                        reuseAddress = true
                        bind(InetSocketAddress(candidate), 50)
                    }
                    port = candidate
                    break
                } catch (e: IOException) {
                    Log.w(TAG, "port $candidate: ${e.message}")
                }
            }
            if (socket == null) {
                Log.e(TAG, "no free port from $DEFAULT_PORT")
                bound.countDown()
                return@thread
            }
            running.set(true)
            serverSocket = socket
            Log.i(TAG, "listening on $port, lan=${lanIpv4()}")
            ok.set(true)

            bound.countDown()
            store.load()
            val session = synchronized(lifeLock) {

                if (!running.get()) return@thread
                store.onChanged = ::onConversationChanged
                store.onActiveChanged = ::onActiveChanged
                sweeper = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "WebRelay-sweep").apply { isDaemon = true } }
                    .also { it.scheduleWithFixedDelay({ sweepJobs() }, SWEEP_MS, SWEEP_MS, TimeUnit.MILLISECONDS) }
                loaded = true
                sessionWanted.also { sessionWanted = false }
            }

            if (session) openSession()
            acceptLoop(socket)
        }
        try {
            bound.await(START_WAIT_MS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            return false
        }
        return ok.get()
    }

    fun newSession() {
        synchronized(lifeLock) {
            if (!loaded) {
                sessionWanted = true
                return
            }
        }
        openSession()
    }

    fun freshSession() {
        if (!store.activeIsUnused()) newSession()
    }

    private fun openSession() {
        val (conversation, _) = store.create(id = null, title = null, systemPrompt = null)
        activate(conversation.id)
        Log.i(TAG, "new session ${conversation.id}")
    }

    fun showOnPages(id: String) {
        if (store.get(id)?.hidden != false) return
        val data = JSONObject().put("conversationId", id).toString()
        clients.filter { it.kind == SseClient.Kind.WORKER }.forEach { it.send("open", data) }
    }

    private fun activate(id: String) {
        val removed = store.setActive(id) ?: return
        clients.filter { it.kind == SseClient.Kind.CONVERSATION && it.key == removed }.forEach { it.close() }
        notifyFollow("list")
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        runCatching { serverSocket?.close() }
        serverSocket = null
        clients.forEach { it.close() }
        clients.clear()
        synchronized(jobLock) { pendingJobs.clear() }
        jobs.values.forEach { job -> if (job.kind == "translate") failTranslate(job, "server stopped") }
        jobs.clear()
        synchronized(lifeLock) {
            sweeper?.shutdownNow()
            sweeper = null
        }
        store.close()
        Log.i(TAG, "stopped")
    }

    fun dropRemoteClients() {
        val remote = clients.filter { !it.local }
        remote.forEach { it.close() }
        if (remote.isNotEmpty()) Log.i(TAG, "token changed: ${remote.size} remote stream(s) closed")
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (running.get()) {
            val client = try {
                socket.accept()
            } catch (e: SocketException) {
                if (running.get()) Log.w(TAG, "accept: ${e.message}")
                break
            } catch (e: IOException) {
                if (running.get()) Log.w(TAG, "accept: ${e.message}")
                continue
            }
            thread(name = "WebRelay-conn", isDaemon = true) { serve(client) }
        }
    }

    private fun serve(socket: Socket) {
        PluginIdentity.claim()
        var stream: SseClient? = null
        try {
            socket.soTimeout = 30_000
            socket.tcpNoDelay = true
            socket.keepAlive = true
            val input = socket.getInputStream().buffered()
            val output = socket.getOutputStream().buffered()
            val request = try {
                readRequest(input, output, socket.inetAddress) { hungUp(socket, input) } ?: return
            } catch (e: ApiError) {
                writeFull(output, errorReply(e.status, e.message ?: "error"))
                drain(socket, input)
                return
            }
            val reply = try {
                route(request)
            } catch (e: ApiError) {
                errorReply(e.status, e.message ?: "error")
            } catch (e: Exception) {
                Log.w(TAG, "${request.method} ${request.path}: ${e.message}", e)
                errorReply(500, e.message ?: e.javaClass.simpleName)
            }
            when (reply) {
                is Reply.Full -> writeFull(output, reply)
                is Reply.Stream -> {
                    stream = reply.client
                    socket.soTimeout = 0
                    reply.client.socket = socket
                    writeStream(output, reply.client)
                }
            }
        } catch (e: IOException) {
            if (stream == null) Log.w(TAG, "connection: ${e.message}")
        } catch (e: Exception) {
            Log.w(TAG, "connection failed: ${e.message}", e)
        } finally {
            stream?.let { onClientClosed(it) }
            runCatching { socket.close() }
        }
    }

    private fun drain(socket: Socket, input: InputStream) {
        try {
            socket.shutdownOutput()
            socket.soTimeout = DRAIN_MS
            val buf = ByteArray(16 * 1024)
            val until = System.currentTimeMillis() + DRAIN_MS
            var total = 0L
            while (total < DRAIN_BYTES && System.currentTimeMillis() < until) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
            }
        } catch (_: IOException) {
        }
    }

    private fun hungUp(socket: Socket, input: InputStream): Boolean = try {
        socket.soTimeout = 1
        input.read() == -1
    } catch (_: SocketTimeoutException) {
        false
    } catch (_: IOException) {
        true
    }

    private fun readRequest(input: InputStream, output: OutputStream, remote: InetAddress, gone: () -> Boolean): Request? {
        val requestLine = readLine(input) ?: return null
        val parts = requestLine.split(' ')
        if (parts.size < 2) throw IOException("bad request line: ${requestLine.take(80)}")
        val method = parts[0].uppercase()
        val target = parts[1]
        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
        }
        val q = target.indexOf('?')
        val path = decode(if (q >= 0) target.substring(0, q) else target)
        val query = if (q >= 0) parseQuery(target.substring(q + 1)) else emptyMap()
        val length = headers["content-length"]?.trim()?.toLongOrNull() ?: 0L
        if (length > MAX_BODY) throw ApiError(413, "Body too large")
        if (headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true) throw ApiError(411, "Length required")
        if (headers["expect"]?.equals("100-continue", ignoreCase = true) == true) {
            output.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray())
            output.flush()
        }
        val body = if (length > 0) readFully(input, length.toInt()) else ByteArray(0)
        return Request(method, path, query, headers, body, remote, gone)
    }

    private fun readLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream(80)
        while (true) {
            val b = input.read()
            if (b == -1) return if (buf.size() == 0) null else buf.toString("UTF-8")
            if (b == '\n'.code) {
                val bytes = buf.toByteArray()
                val end = if (bytes.isNotEmpty() && bytes.last() == '\r'.code.toByte()) bytes.size - 1 else bytes.size
                return String(bytes, 0, end, Charsets.UTF_8)
            }
            buf.write(b)
            if (buf.size() > 64 * 1024) throw IOException("header line too long")
        }
    }

    private fun readFully(input: InputStream, length: Int): ByteArray {
        val out = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(out, read, length - read)
            if (n < 0) throw IOException("body cut at $read of $length")
            read += n
        }
        return out
    }

    private fun parseQuery(raw: String): Map<String, String> {
        val out = HashMap<String, String>()
        for (pair in raw.split('&')) {
            if (pair.isEmpty()) continue
            val idx = pair.indexOf('=')
            val key = decodeForm(if (idx >= 0) pair.substring(0, idx) else pair)
            val value = if (idx >= 0) decodeForm(pair.substring(idx + 1)) else ""
            out[key] = value
        }
        return out
    }

    private fun decodeForm(text: String): String =
        try { URLDecoder.decode(text, "UTF-8") } catch (e: Exception) { text }

    private fun decode(text: String): String =
        try { URLDecoder.decode(text.replace("+", "%2B"), "UTF-8") } catch (e: Exception) { text }

    private fun writeFull(output: OutputStream, reply: Reply.Full) {
        val head = StringBuilder()
            .append("HTTP/1.1 ").append(reply.status).append(' ').append(reason(reply.status)).append("\r\n")
            .append("Content-Type: ").append(reply.contentType).append("\r\n")
            .append("Content-Length: ").append(reply.body.size).append("\r\n")
            .append("Connection: close\r\n")
        if (reply.headers.keys.none { it.equals("Cache-Control", ignoreCase = true) }) head.append("Cache-Control: no-store\r\n")
        reply.headers.forEach { (k, v) -> head.append(k).append(": ").append(v).append("\r\n") }
        head.append("\r\n")
        output.write(head.toString().toByteArray(Charsets.UTF_8))
        output.write(reply.body)
        output.flush()
    }

    private fun writeStream(output: OutputStream, client: SseClient) {
        output.write(("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream; charset=utf-8\r\n" +
            "Cache-Control: no-cache\r\nConnection: close\r\nX-Accel-Buffering: no\r\n\r\n").toByteArray())
        output.write(": connected\n\n".toByteArray())
        output.flush()

        val ping = if (client.kind == SseClient.Kind.WORKER) "event: ping\ndata: {}\n\n" else ": ping\n\n"
        val pollMs = if (client.local) LOCAL_POLL_MS else SSE_PING_MS
        while (running.get() && !client.closed) {
            val chunk = client.queue.poll(pollMs, TimeUnit.MILLISECONDS) ?: if (client.local) continue else ping
            if (client.closed) break
            if (chunk.isEmpty()) continue
            output.write(chunk.toByteArray(Charsets.UTF_8))
            output.flush()
        }
    }

    private fun reason(status: Int): String = when (status) {
        200 -> "OK"; 201 -> "Created"; 202 -> "Accepted"; 204 -> "No Content"
        400 -> "Bad Request"; 401 -> "Unauthorized"; 404 -> "Not Found"; 405 -> "Method Not Allowed"
        411 -> "Length Required"; 413 -> "Payload Too Large"; 500 -> "Internal Server Error"
        501 -> "Not Implemented"; 502 -> "Bad Gateway"; 503 -> "Service Unavailable"; 504 -> "Gateway Timeout"
        else -> "Status"
    }

    private fun jsonReply(status: Int, json: Any): Reply.Full =
        Reply.Full(status, "application/json; charset=utf-8", json.toString().toByteArray(Charsets.UTF_8))

    private fun errorReply(status: Int, message: String): Reply.Full =
        jsonReply(status, JSONObject().put("error", message).put("status", status))

    private fun okReply(status: String): Reply.Full = jsonReply(200, JSONObject().put("status", status))

    private fun route(req: Request): Reply {
        val path = req.path
        if (req.method == "GET" && (path == "/" || path == "/index.html")) return page()
        if (req.method == "GET" && path == "/favicon.ico") return Reply.Full(404, "text/plain", ByteArray(0))
        if (!authorized(req)) return errorReply(401, "Unauthorized")
        return when {
            path.startsWith("/api/conversations") -> conversations(req, path.removePrefix("/api/conversations"))
            path == "/api/files/upload" && req.method == "POST" -> upload(req)
            path == "/api/translate" -> translate(req)
            path == "/web/events" && req.method == "GET" -> workerStream(req)
            path == "/web/pong" && req.method == "POST" -> {
                val id = req.json().optString("workerId")
                val worker = clients.firstOrNull { it.kind == SseClient.Kind.WORKER && it.id == id && !it.closed }
                    ?: return errorReply(404, "Unknown worker")
                worker.lastSeen = System.currentTimeMillis()
                okReply("ok")
            }
            path.startsWith("/web/jobs/") && req.method == "POST" -> jobReport(req, path.removePrefix("/web/jobs/"))
            path == "/web/settings" -> settings(req)
            path == "/web/conversations" && req.method == "GET" -> jsonReply(200, store.listJson())
            path == "/web/active" && req.method == "POST" -> {

                req.json().optString("conversationId").takeIf { it.isNotEmpty() && it != "null" }?.let(::activate)
                okReply("ok")
            }
            path == "/web/state" && req.method == "GET" -> jsonReply(200, stateJson())
            path == "/web/log" && req.method == "POST" -> {

                val lines = req.json().optJSONArray("lines") ?: JSONArray()
                val from = req.remote.hostAddress.orEmpty()
                for (i in 0 until minOf(lines.length(), 100)) Log.i("WebPage", "$from ${lines.optString(i).take(500)}")
                okReply("ok")
            }
            path.startsWith("/web/files/") && req.method == "GET" -> file(path.removePrefix("/web/files/"))
            else -> errorReply(404, "Not Found")
        }
    }

    private fun authorized(req: Request): Boolean {
        if (req.isLocal && req.path.startsWith("/api/")) return true
        val expected = token()
        if (expected.isEmpty()) return false
        val given = req.query["t"]
            ?: req.header("x-relay-token")
            ?: req.header("authorization")?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }?.substring(7)?.trim()
        return given == expected
    }

    private fun page(): Reply {
        val manager = assets() ?: return errorReply(500, "no assets")
        val bytes = try {
            manager.open("web/index.html").use { it.readBytes() }
        } catch (e: IOException) {
            Log.e(TAG, "page: ${e.message}")
            return errorReply(500, "page missing: ${e.message}")
        }
        return Reply.Full(200, "text/html; charset=utf-8", bytes)
    }

    private fun stateJson(): JSONObject = JSONObject()
        .put("port", port)
        .put("lanIp", lanIpv4() ?: JSONObject.NULL)
        .put("workers", workerCount)
        .put("translateReady", translateReady)
        .put("pendingJobs", synchronized(jobLock) { pendingJobs.size })
        .put("version", 1)

    private fun conversations(req: Request, rest: String): Reply {
        val segments = rest.trim('/').split('/').filter { it.isNotEmpty() }
        when {
            segments.isEmpty() -> return when (req.method) {
                "POST" -> createConversation(req)
                "GET" -> jsonReply(200, store.listJson())
                else -> errorReply(405, "Method Not Allowed")
            }
            segments.size == 1 && segments[0] == "active" -> {
                val (id, onScreen) = store.active()
                return jsonReply(200, JSONObject().put("conversationId", id ?: JSONObject.NULL).put("isOnScreen", onScreen))
            }
            segments.size == 1 && segments[0] == "relay-settings" ->
                return jsonReply(200, JSONObject().put("prefillEnabled", false).put("startMarkers", RelayTurns.DEFAULT_MARKERS))
            segments.size == 1 && segments[0] == "stream" -> {
                val client = SseClient(SseClient.Kind.FOLLOW, "", emptyMap(), req.isLocal)
                clients += client
                return Reply.Stream(client)
            }
        }
        val id = segments[0]
        if (!store.exists(id)) return errorReply(404, "Conversation not found")
        if (segments.size == 1) {
            return when (req.method) {
                "GET" -> jsonReply(200, store.toJson(id) ?: return errorReply(404, "Conversation not found"))
                "DELETE" -> {
                    cancelJobsOf(id)
                    store.delete(id)
                    clients.filter { it.kind == SseClient.Kind.CONVERSATION && it.key == id }.forEach { it.close() }
                    notifyFollow("list")
                    Reply.Full(204, "text/plain", ByteArray(0))
                }
                else -> errorReply(405, "Method Not Allowed")
            }
        }
        return when (val action = segments[1]) {
            "stream" -> {
                if (req.method != "GET") return errorReply(405, "Method Not Allowed")
                val client = SseClient(SseClient.Kind.CONVERSATION, id, emptyMap(), req.isLocal)
                store.toJson(id)?.let { json ->
                    client.send("snapshot", JSONObject().put("seq", client.nextSeq()).put("conversation", json).toString())
                }
                clients += client
                Reply.Stream(client)
            }
            "messages" -> {
                if (req.method != "POST") return errorReply(405, "Method Not Allowed")
                val parts = req.json().optJSONArray("parts") ?: throw ApiError(400, "parts missing")
                if (parts.length() == 0) throw ApiError(400, "parts empty")
                startReply(id, parts, req.isLocal)
                jsonReply(202, JSONObject().put("status", "accepted"))
            }
            "relay-query" -> {
                if (req.method != "POST") return errorReply(405, "Method Not Allowed")
                val text = req.json().optString("text").trim()
                if (text.isEmpty()) throw ApiError(400, "text must not be empty")
                startReply(id, WebConversationStore.textParts(text), req.isLocal)
                jsonReply(202, JSONObject().put("mode", "message"))
            }
            "stop" -> {
                if (req.method != "POST") return errorReply(405, "Method Not Allowed")
                cancelJobsOf(id)
                store.stop(id)
                okReply("stopped")
            }
            "title" -> {
                if (req.method != "POST") return errorReply(405, "Method Not Allowed")
                val title = req.json().optString("title").trim()
                if (title.isEmpty()) throw ApiError(400, "Title must not be blank")
                store.setTitle(id, title)
                okReply("updated")
            }
            "regenerate", "prefill-continue", "prefill-branches" -> errorReply(501, "$action is not available in web mode")
            else -> errorReply(404, "Not Found")
        }
    }

    private fun createConversation(req: Request): Reply {
        val body = req.json()
        val requested = body.optString("conversationId").takeIf { it.isNotEmpty() && it != "null" }
        val (conversation, created) = store.create(
            id = requested,
            title = body.optString("title").takeIf { it.isNotEmpty() && it != "null" },
            systemPrompt = body.optString("customSystemPrompt").takeIf { it.isNotEmpty() && it != "null" },
        )
        if (created) notifyFollow("list")
        return jsonReply(if (created) 201 else 200, JSONObject().put("conversationId", conversation.id))
    }

    private fun startReply(conversationId: String, userParts: JSONArray, local: Boolean) {
        if (store.openReply(conversationId) != null) {

            cancelJobsOf(conversationId)
            store.stop(conversationId)
        }
        val replyId = store.appendTurn(conversationId, userParts) ?: throw ApiError(404, "Conversation not found")
        val job = Job(
            id = UUID.randomUUID().toString(),
            kind = "chat",
            conversationId = conversationId,
            messageId = replyId,
            payload = JSONObject()
                .put("conversationId", conversationId)
                .put("messageId", replyId)
                .put("system", store.systemPrompt(conversationId).ifEmpty { userSystemPrompt() })
                .put("messages", store.historyJson(conversationId, replyId) { name -> File(filesDir, name).isFile }),
            local = local,
        )
        job.payload.put("id", job.id).put("kind", job.kind)
        jobs[job.id] = job
        notifyFollow("list")
        if (!dispatch(job)) Log.i(TAG, "job ${job.id} waits for a page")
    }

    private fun upload(req: Request): Reply {
        val contentType = req.header("content-type") ?: throw ApiError(400, "Content-Type missing")
        val boundary = Regex("boundary=\"?([^\";]+)\"?").find(contentType)?.groupValues?.get(1)
            ?: throw ApiError(400, "multipart boundary missing")
        val part = firstFilePart(req.body, boundary) ?: throw ApiError(400, "No files uploaded")
        val extension = when {
            part.first.contains("image/png") || part.second.endsWith(".png", true) -> "png"
            part.first.contains("image/jpeg") || part.second.endsWith(".jpg", true) || part.second.endsWith(".jpeg", true) -> "jpg"
            part.first.contains("image/webp") || part.second.endsWith(".webp", true) -> "webp"
            else -> "bin"
        }
        filesDir.mkdirs()
        val name = "${UUID.randomUUID()}.$extension"
        File(filesDir, name).writeBytes(part.third)
        pruneFiles()
        val file = JSONObject()
            .put("id", 0)
            .put("url", "/web/files/$name")
            .put("fileName", part.second.ifEmpty { name })
            .put("mime", mimeOf(name))
            .put("size", part.third.size)
        return jsonReply(201, JSONObject().put("files", JSONArray().put(file)))
    }

    private fun firstFilePart(body: ByteArray, boundary: String): Triple<String, String, ByteArray>? {
        val delimiter = "--$boundary".toByteArray(Charsets.US_ASCII)
        var cursor = indexOf(body, delimiter, 0)
        while (cursor >= 0) {
            var headStart = cursor + delimiter.size
            if (headStart + 1 < body.size && body[headStart] == '-'.code.toByte() && body[headStart + 1] == '-'.code.toByte()) return null
            if (headStart + 1 < body.size && body[headStart] == '\r'.code.toByte() && body[headStart + 1] == '\n'.code.toByte()) headStart += 2
            val headEnd = indexOf(body, "\r\n\r\n".toByteArray(), headStart)
            if (headEnd < 0) return null
            val head = String(body, headStart, headEnd - headStart, Charsets.UTF_8)
            val dataStart = headEnd + 4
            val next = indexOf(body, delimiter, dataStart)
            if (next < 0) return null
            var dataEnd = next
            if (dataEnd >= 2 && body[dataEnd - 2] == '\r'.code.toByte() && body[dataEnd - 1] == '\n'.code.toByte()) dataEnd -= 2
            val fileName = Regex("filename=\"([^\"]*)\"").find(head)?.groupValues?.get(1)
            if (fileName != null) {
                val type = Regex("(?i)content-type:\\s*([^\\r\\n]+)").find(head)?.groupValues?.get(1)?.trim().orEmpty().lowercase()
                return Triple(type, fileName, body.copyOfRange(dataStart, dataEnd))
            }
            cursor = next
        }
        return null
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray, from: Int): Int {
        if (needle.isEmpty()) return from
        var i = maxOf(from, 0)
        val last = haystack.size - needle.size
        outer@ while (i <= last) {
            if (haystack[i] == needle[0]) {
                for (j in 1 until needle.size) if (haystack[i + j] != needle[j]) { i++; continue@outer }
                return i
            }
            i++
        }
        return -1
    }

    private fun pruneFiles() {
        val files = filesDir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() } ?: return
        if (files.size <= 300) return
        val referenced = store.referencedFiles()
        files.drop(300).filter { it.name !in referenced }.forEach { it.delete() }
    }

    private fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"; "webp" -> "image/webp"; else -> "application/octet-stream"
    }

    fun localFile(url: String): File? {
        val name = url.substringAfter("/web/files/", "").substringBefore('?')
        if (!FILE_NAME.matches(name)) return null
        return File(filesDir, name).takeIf { it.isFile }
    }

    private fun file(name: String): Reply {
        if (!FILE_NAME.matches(name)) return errorReply(404, "Not Found")
        val file = File(filesDir, name)
        if (!file.isFile) return errorReply(404, "Not Found")
        return Reply.Full(200, mimeOf(name), file.readBytes(), mapOf("Cache-Control" to "private, max-age=86400"))
    }

    private fun userSystemPrompt(): String = runCatching {
        if (!settingsFile.isFile) return@runCatching ""
        JSONObject(settingsFile.readText(Charsets.UTF_8)).optString("systemPrompt").trim()
    }.getOrDefault("")

    private fun settings(req: Request): Reply = when (req.method) {
        "GET" -> {
            val text = if (settingsFile.isFile) settingsFile.readText(Charsets.UTF_8) else "{}"
            Reply.Full(200, "application/json; charset=utf-8", text.toByteArray(Charsets.UTF_8))
        }
        "PUT", "POST" -> {
            val json = req.json()
            settingsFile.parentFile?.mkdirs()
            settingsFile.writeText(json.toString(), Charsets.UTF_8)
            okReply("saved")
        }
        else -> errorReply(405, "Method Not Allowed")
    }

    private fun translate(req: Request): Reply {
        if (req.method == "GET") {
            val worker = clients.filter { it.kind == SseClient.Kind.WORKER && it.can("translate") }.maxByOrNull { it.connectedAt }
            return jsonReply(200, JSONObject()
                .put("ok", worker != null)
                .put("model", worker?.params?.get("translateModel").orEmpty()))
        }
        if (req.method != "POST") return errorReply(405, "Method Not Allowed")
        val body = req.json()
        val text = body.optString("text")
        if (text.isBlank()) throw ApiError(400, "Missing text")
        val job = Job(UUID.randomUUID().toString(), "translate", "", "",
            JSONObject().put("system", body.optString("system")).put("text", text))
        job.payload.put("id", job.id).put("kind", job.kind)
        jobs[job.id] = job
        val started = System.currentTimeMillis()
        if (!dispatch(job)) {
            jobs.remove(job.id)
            return errorReply(502, NativeLocale.t("airrelay_web_no_page"))
        }
        val deadline = started + TRANSLATE_WAIT_MS
        synchronized(job.waiter) {
            while (job.result == null && job.error == null) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) break
                try { job.waiter.wait(minOf(left, CANCEL_POLL_MS)) } catch (_: InterruptedException) { break }

                if (job.result == null && job.error == null && req.gone()) {
                    Log.i(TAG, "translation ${job.id} cancelled by its caller")
                    break
                }
            }
        }
        jobs.remove(job.id)
        val result = job.result
        val error = job.error
        return when {
            result != null -> jsonReply(200, JSONObject()
                .put("text", result.optString("text"))
                .put("model", result.optString("model"))
                .put("usage", result.optJSONObject("usage") ?: JSONObject().put("input", 0).put("output", 0))
                .put("ms", System.currentTimeMillis() - started))
            error != null -> errorReply(502, error)
            else -> {
                job.cancelled = true
                job.worker?.send("cancel", JSONObject().put("jobId", job.id).toString())
                errorReply(504, "no reply within ${TRANSLATE_WAIT_MS / 1000}s")
            }
        }
    }

    private fun workerStream(req: Request): Reply {
        val client = SseClient(SseClient.Kind.WORKER, "", req.query, req.isLocal)
        clients += client
        client.send("hello", JSONObject()
            .put("workerId", client.id)
            .put("port", port)
            .put("lanIp", lanIpv4() ?: JSONObject.NULL)
            .put("workers", workerCount)
            .toString())
        Log.i(TAG, "page connected from ${req.remote.hostAddress} chat=${client.can("chat")} translate=${client.can("translate")}")
        onWorkersChanged?.invoke(workerCount)
        dispatchPending()
        return Reply.Stream(client)
    }

    private fun jobReport(req: Request, rest: String): Reply {
        val segments = rest.split('/')
        if (segments.size != 2) return errorReply(404, "Not Found")
        val job = jobs[segments[0]] ?: return errorReply(404, "Unknown job")
        val body = req.json()
        job.lastActivity = System.currentTimeMillis()
        job.worker?.lastSeen = job.lastActivity
        when (segments[1]) {
            "progress" -> {
                if (job.kind == "chat" && !job.cancelled) {
                    store.updateReply(job.conversationId, job.messageId, body.optString("text"), finished = false)
                }
            }
            "done" -> {
                jobs.remove(job.id)
                if (job.kind == "chat") {
                    if (!job.cancelled) store.updateReply(job.conversationId, job.messageId, body.optString("text"), finished = true)
                    notifyFollow("list")
                } else {
                    synchronized(job.waiter) {
                        job.result = body
                        job.waiter.notifyAll()
                    }
                }
            }
            "error" -> {
                jobs.remove(job.id)
                val message = body.optString("message").ifEmpty { "error" }
                Log.w(TAG, "job ${job.id} (${job.kind}) failed on the page: $message")
                if (job.kind == "chat") {
                    if (!job.cancelled) failReply(job, body.optString("text"), message)
                } else {
                    failTranslate(job, message)
                }
            }
            else -> return errorReply(404, "Not Found")
        }
        return okReply("ok")
    }

    private fun dispatch(job: Job): Boolean = synchronized(jobLock) {
        val cap = if (job.kind == "translate") "translate" else "chat"
        val worker = clients.filter { it.kind == SseClient.Kind.WORKER && !it.closed && it.can(cap) }.maxByOrNull { it.connectedAt }
        if (worker == null) {
            if (job.kind == "chat" && !pendingJobs.contains(job)) pendingJobs.addLast(job)
            return false
        }
        job.worker = worker
        job.lastActivity = System.currentTimeMillis()
        worker.send("job", job.payload.toString())
        true
    }

    private fun dispatchPending() {
        val waiting = synchronized(jobLock) { pendingJobs.toList().also { pendingJobs.clear() } }
        waiting.forEach { job -> if (jobs.containsKey(job.id)) dispatch(job) }
    }

    private fun cancelJobsOf(conversationId: String) {
        jobs.values.filter { it.kind == "chat" && it.conversationId == conversationId }.forEach { job ->
            job.cancelled = true
            jobs.remove(job.id)
            synchronized(jobLock) { pendingJobs.remove(job) }
            job.worker?.send("cancel", JSONObject().put("jobId", job.id).toString())
        }
    }

    private fun failReply(job: Job, textSoFar: String, message: String) {
        store.updateReply(job.conversationId, job.messageId, textSoFar, finished = true, error = message)
        notifyFollow("list")
        if (job.local) onReplyFailed?.invoke(message)
    }

    private fun failTranslate(job: Job, message: String) {
        synchronized(job.waiter) {
            if (job.error == null && job.result == null) job.error = message
            job.waiter.notifyAll()
        }
    }

    private fun onClientClosed(client: SseClient) {
        clients.remove(client)
        client.closed = true
        if (client.kind != SseClient.Kind.WORKER) return
        Log.i(TAG, "page disconnected (${workerCount} left)")
        onWorkersChanged?.invoke(workerCount)
        jobs.values.filter { it.worker === client }.forEach { job ->
            job.worker = null
            if (job.kind == "chat") {
                if (!dispatch(job)) Log.i(TAG, "job ${job.id} back to waiting")
            } else {
                jobs.remove(job.id)
                failTranslate(job, NativeLocale.t("airrelay_web_no_page"))
            }
        }
    }

    private fun sweepJobs() {
        if (!running.get()) return
        val now = System.currentTimeMillis()
        try {
            clients.filter { it.kind == SseClient.Kind.WORKER && !it.closed && now - it.lastSeen > WORKER_SILENT_MS }.forEach {
                Log.w(TAG, "page silent for ${(now - it.lastSeen) / 1000}s, dropped")
                it.close()
            }
            jobs.values.filter { it.kind == "chat" }.forEach { job ->
                val waiting = job.worker == null
                val expired = if (waiting) now - job.createdAt > PENDING_JOB_TTL_MS else now - job.lastActivity > STALL_MS
                if (!expired) return@forEach
                Log.w(TAG, "job ${job.id} ${if (waiting) "never taken" else "stalled"}")
                jobs.remove(job.id)
                synchronized(jobLock) { pendingJobs.remove(job) }
                job.cancelled = true
                job.worker?.send("cancel", JSONObject().put("jobId", job.id).toString())
                val reason = when {
                    !waiting -> "airrelay_web_job_stalled"
                    workerCount > 0 -> "airrelay_web_job_no_model"
                    else -> "airrelay_web_job_expired"
                }
                failReply(job, "", NativeLocale.t(reason))
            }
        } catch (e: Exception) {
            Log.w(TAG, "sweep: ${e.message}")
        }
    }

    private fun onConversationChanged(change: WebConversationStore.Change) {
        val listeners = clients.filter { it.kind == SseClient.Kind.CONVERSATION && it.key == change.id }
        for (client in listeners) {
            val node = change.node
            if (change.nodeIndex != null && node != null) {
                client.send("node_update", JSONObject()
                    .put("seq", client.nextSeq())
                    .put("conversationId", change.id)
                    .put("nodeId", change.nodeId)
                    .put("nodeIndex", change.nodeIndex)
                    .put("node", node)
                    .put("updateAt", change.updateAt)
                    .put("isGenerating", change.generating)
                    .toString())
            } else {
                val conversation = change.conversation ?: store.toJson(change.id) ?: continue
                client.send("snapshot", JSONObject().put("seq", client.nextSeq()).put("conversation", conversation).toString())
            }
        }
        if (change.nodeIndex == null || !change.generating) notifyFollow("list")
    }

    private fun onActiveChanged(id: String) {
        val data = JSONObject().put("conversationId", id).toString()
        clients.filter { it.kind != SseClient.Kind.CONVERSATION }.forEach { it.send("active", data) }
    }

    private fun notifyFollow(event: String) {
        val data = JSONObject().put("at", System.currentTimeMillis()).toString()
        clients.filter { it.kind != SseClient.Kind.CONVERSATION }.forEach { it.send(event, data) }
    }
}
