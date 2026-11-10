package me.laumss.notipal.relay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import me.laumss.notipal.NativeLocale
import me.laumss.notipal.relay.net.RawHttp
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

internal object TranslationService {
    private const val TAG = "Translate"

    const val ACTION_REQUEST = "me.laumss.notipal.TRANSLATE_REQUEST"
    const val ACTION_CANCEL = "me.laumss.notipal.TRANSLATE_CANCEL"
    const val ACTION_RESULT = "me.laumss.notipal.TRANSLATE_RESULT"
    const val ACTION_PROGRESS = "me.laumss.notipal.TRANSLATE_PROGRESS"

    private const val MAX_IN_FLIGHT = 3
    private const val MAX_TEXT_IN_FLIGHT = 2
    private const val QUEUE_LIMIT = 400
    private const val HTTP_TIMEOUT_MS = 180_000
    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val PROBE_TIMEOUT_MS = 4_000
    private const val PROBE_TTL_MS = 60_000L

    private const val SEARCH_LOOK_MS = 8_000L

    private const val SEARCH_PROMPT_MS = 90_000L

    private const val TEXT_LIMIT_CHARS = 20_000

    private class Job(
        val caller: String,
        val id: String,
        val kind: String,
        val text: String,
        val file: String,
        val system: String,
        val target: String,
        val receivedAt: Long,
    ) {
        val key: String get() = "$caller/$id"
        val cancelled = AtomicBoolean(false)
        val finished = AtomicBoolean(false)
        @Volatile var socket: Socket? = null
    }

    private class Failure(val error: String, message: String) : Exception(message)

    private enum class Probe { OK, OUTDATED, UNREACHABLE, REFUSED, NOT_READY }

    private var receiver: BroadcastReceiver? = null
    @Volatile private var appContext: Context? = null

    private val lock = Any()
    private val batchQueue = ArrayDeque<Job>()
    private val textQueue = ArrayDeque<Job>()
    private var batchRunning = 0
    private var textRunning = 0

    private val active = HashMap<String, Job>()

    private val probeLock = Any()
    private var probeBase = ""
    private var probeAt = 0L
    @Volatile private var probeModel = ""

    fun ensureRegistered(context: Context) {
        if (receiver != null) return
        val app = context.applicationContext
        val r = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent == null) return
                when (intent.action) {
                    ACTION_REQUEST -> onRequest(intent)
                    ACTION_CANCEL -> onCancel(
                        intent.getStringExtra("caller").orEmpty(),
                        intent.getStringExtra("id"),
                    )
                }
            }
        }
        val filter = IntentFilter(ACTION_REQUEST).apply { addAction(ACTION_CANCEL) }
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                app.registerReceiver(r, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                app.registerReceiver(r, filter)
            }
        } catch (e: Exception) {
            Log.e(TAG, "receiver registration failed: ${e.message}", e)
            return
        }
        receiver = r
        appContext = app

        AIRelayCore.get(context)
        Log.i(TAG, "receiver registered")
    }

    private fun onRequest(intent: Intent) {
        val job = Job(
            caller = intent.getStringExtra("caller").orEmpty(),
            id = intent.getStringExtra("id").orEmpty(),
            kind = intent.getStringExtra("kind").orEmpty(),
            text = intent.getStringExtra("text").orEmpty(),
            file = intent.getStringExtra("file").orEmpty(),
            system = intent.getStringExtra("system").orEmpty(),
            target = intent.getStringExtra("target").orEmpty(),
            receivedAt = System.currentTimeMillis(),
        )
        if (job.caller.isEmpty() || job.id.isEmpty()) {
            Log.w(TAG, "request without caller/id dropped (kind=${job.kind})")
            return
        }
        when (job.kind) {
            "ping" -> thread(name = "Translate-ping", isDaemon = true) { runPing(job) }
            "text", "batch" -> enqueue(job)
            else -> finish(job, ok = false, error = "failed", message = "unknown kind '${job.kind}'")
        }
    }

    private fun enqueue(job: Job) {
        val rejected = synchronized(lock) {
            val queued = batchQueue.size + textQueue.size
            if (queued >= QUEUE_LIMIT || active.containsKey(job.key)) {
                true
            } else {
                active[job.key] = job
                if (job.kind == "batch") batchQueue.addLast(job) else textQueue.addLast(job)
                pumpLocked()
                false
            }
        }
        if (rejected) {
            Log.w(TAG, "${job.key} rejected: queue full or duplicate id")
            finish(job, ok = false, error = "busy", message = "queue full or duplicate id")
        }
    }

    private fun pumpLocked() {
        while (textRunning < MAX_TEXT_IN_FLIGHT) {
            val job = textQueue.removeFirstOrNull() ?: break
            textRunning++
            start(job)
        }
        while (batchRunning < MAX_IN_FLIGHT) {
            val job = batchQueue.removeFirstOrNull() ?: break
            batchRunning++
            start(job)
        }
    }

    private fun start(job: Job) {
        thread(name = "Translate-${job.caller}", isDaemon = true) {
            try {
                runJob(job)
            } finally {
                synchronized(lock) {
                    if (job.kind == "batch") batchRunning-- else textRunning--
                    active.remove(job.key)
                    pumpLocked()
                }
            }
        }
    }

    private fun onCancel(caller: String, id: String?) {
        if (caller.isEmpty()) return
        val hit = synchronized(lock) {
            val jobs = active.values.filter { it.caller == caller && (id == null || it.id == id) }
            jobs.forEach { job ->
                job.cancelled.set(true)
                if (batchQueue.remove(job) || textQueue.remove(job)) active.remove(job.key)
            }
            jobs
        }
        if (hit.isEmpty()) return
        Log.i(TAG, "cancel $caller/${id ?: "*"}: ${hit.size} request(s)")
        hit.forEach { job ->

            job.socket?.let { runCatching { it.close() } }
            finish(job, ok = false, error = "cancelled", message = "cancelled by caller")
        }
    }

    private fun runPing(job: Job) {
        val endpoint = endpoint()
        if (endpoint == null) {

            searchPhone(job.key)
            finish(job, ok = false, error = "no_phone", message = "no paired phone", phone = false)
            return
        }
        val result = probe(endpoint.baseUrl, force = true)
        if (result == Probe.UNREACHABLE) searchPhone(job.key)
        when (result) {
            Probe.OK -> finish(job, ok = true, phone = true, model = probeModel)
            Probe.OUTDATED -> finish(job, ok = false, error = "phone_outdated",
                message = "RikkaHub has no /api/translate", phone = false)
            Probe.NOT_READY -> finish(job, ok = false, error = "failed",
                message = NativeLocale.t("translate_not_ready"), phone = false)
            else -> finish(job, ok = false, error = if (result == Probe.UNREACHABLE) "no_phone" else "failed",
                message = "phone ${endpoint.host}:${endpoint.port} ${result.name.lowercase()}", phone = false)
        }
    }

    private fun runJob(job: Job) {
        if (job.cancelled.get()) return
        try {
            val input = when (job.kind) {
                "batch" -> {
                    val f = File(job.file)
                    if (job.file.isEmpty() || !f.isFile) throw Failure("failed", "batch file missing: ${job.file}")
                    f.readText(Charsets.UTF_8)
                }
                else -> job.text
            }
            if (input.isBlank()) throw Failure("failed", "empty text")
            if (job.kind == "text" && input.length > TEXT_LIMIT_CHARS) {
                throw Failure("failed", "text too long (${input.length} chars); send it as a batch file")
            }
            val (endpoint, probed) = reach(job)
            if (endpoint == null) throw Failure("no_phone", "no paired phone")
            when (probed) {
                Probe.OK -> Unit
                Probe.OUTDATED -> throw Failure("phone_outdated", "RikkaHub has no /api/translate")
                Probe.UNREACHABLE -> throw Failure("no_phone", "phone ${endpoint.host}:${endpoint.port} unreachable")
                Probe.REFUSED -> throw Failure("failed", "phone refused /api/translate (auth on?)")
                Probe.NOT_READY -> throw Failure("failed", NativeLocale.t("translate_not_ready"))
            }
            val system = job.system.ifBlank { defaultSystemPrompt(job.target) }
            val body = JSONObject().put("system", system).put("text", input)
            val reply = postOnce(job, "${endpoint.baseUrl}/api/translate", body)
            val text = reply.optString("text")
            if (text.isEmpty()) throw Failure("failed", "reply without text")
            val model = reply.optString("model")
            if (job.kind == "batch") {
                val out = File(job.file + ".out")
                val tmp = File(job.file + ".out.tmp")
                tmp.writeText(text, Charsets.UTF_8)
                if (!tmp.renameTo(out)) {
                    out.delete()
                    if (!tmp.renameTo(out)) throw Failure("failed", "cannot write ${out.path}")
                }
                Log.i(TAG, "${job.key} batch done: ${input.length} → ${text.length} chars, " +
                    "${System.currentTimeMillis() - job.receivedAt}ms, model=$model")
                finish(job, ok = true, file = out.path, model = model)
            } else {
                Log.i(TAG, "${job.key} text done: ${input.length} → ${text.length} chars, " +
                    "${System.currentTimeMillis() - job.receivedAt}ms, model=$model")
                finish(job, ok = true, text = text, model = model)
            }
        } catch (e: Failure) {
            if (job.cancelled.get()) return
            Log.w(TAG, "${job.key} ${e.error}: ${e.message}")
            finish(job, ok = false, error = e.error, message = e.message.orEmpty())
        } catch (e: Exception) {
            if (job.cancelled.get()) return
            Log.w(TAG, "${job.key} failed: ${e.message}", e)
            finish(job, ok = false, error = "failed", message = e.message ?: e.javaClass.simpleName)
        }
    }

    private fun postOnce(job: Job, url: String, body: JSONObject): JSONObject {
        val bytes = body.toString().toByteArray(Charsets.UTF_8)
        if (job.cancelled.get()) throw Failure("cancelled", "cancelled")
        val (status, text) = try {
            post(job, url, bytes)
        } catch (e: IOException) {
            if (job.cancelled.get()) throw Failure("cancelled", "cancelled")

            if (e is SocketTimeoutException && job.socket?.isConnected == true) {
                throw Failure("timeout", "no reply within ${HTTP_TIMEOUT_MS / 1000}s")
            }
            invalidateProbe()

            searchPhone(job.key)
            throw Failure("no_phone", "connection failed: ${e.message}")
        }
        return when {
            status in 200..299 -> try {
                JSONObject(text)
            } catch (e: Exception) {
                throw Failure("failed", "reply is not JSON: ${text.take(120)}")
            }
            status == 404 -> {
                invalidateProbe()
                throw Failure("phone_outdated", "HTTP 404")
            }

            status == 504 -> throw Failure("timeout", errorText(text))
            else -> throw Failure("failed", "HTTP $status: ${errorText(text)}")
        }
    }

    private fun post(job: Job, url: String, bytes: ByteArray): Pair<Int, String> =
        RawHttp.request(
            method = "POST",
            url = url,
            headers = mapOf("Content-Type" to "application/json; charset=utf-8"),
            body = bytes,
            connectTimeoutMs = CONNECT_TIMEOUT_MS,
            readTimeoutMs = HTTP_TIMEOUT_MS,
            onSocket = { socket ->
                job.socket = socket

                if (job.cancelled.get()) runCatching { socket.close() }
            },
        ).use { resp -> resp.status to resp.body.readBytes().toString(Charsets.UTF_8) }

    private fun errorText(body: String): String =
        runCatching { JSONObject(body).optString("error") }.getOrNull()?.takeIf { it.isNotEmpty() }
            ?: body.take(200)

    private fun reach(job: Job): Pair<AIRelayCore.PhoneEndpoint?, Probe> {
        val first = endpoint()
        if (first != null) {
            val result = probe(first.baseUrl, force = false)
            if (result != Probe.UNREACHABLE) return first to result
        }
        val ctx = appContext ?: return first to Probe.UNREACHABLE

        if (AIRelayCore.get(ctx).isWebMode()) return first to Probe.UNREACHABLE
        Log.i(TAG, "${job.key}: ${if (first == null) "no paired phone" else "${first.host}:${first.port} unreachable"}, searching")
        progress(job, "searching")
        searchPhone(job.key)
        val found = AIRelayCore.get(ctx).awaitPhone(
            previous = first,
            lookMs = SEARCH_LOOK_MS,
            promptMs = SEARCH_PROMPT_MS,
            cancelled = { job.cancelled.get() },
            onPrompt = { progress(job, "confirm") },
        )
        if (found == null) {

            if (first != null && !job.cancelled.get()) {
                val again = probe(first.baseUrl, force = true)
                if (again != Probe.UNREACHABLE) return first to again
            }
            Log.i(TAG, "${job.key}: no phone found")
            return first to Probe.UNREACHABLE
        }
        Log.i(TAG, "${job.key}: phone at ${found.host}:${found.port}")
        return found to probe(found.baseUrl, force = true)
    }

    private fun searchPhone(reason: String) {
        val ctx = appContext ?: return
        AIRelayCore.get(ctx).searchPhone("translate $reason")
    }

    private fun progress(job: Job, state: String) {
        val ctx = appContext ?: return
        try {
            ctx.sendBroadcast(Intent(ACTION_PROGRESS).apply {
                setPackage(ctx.packageName)
                putExtra("caller", job.caller)
                putExtra("id", job.id)
                putExtra("state", state)
            })
        } catch (e: Exception) {
            Log.w(TAG, "progress ${job.key} failed: ${e.message}")
        }
    }

    private fun endpoint(): AIRelayCore.PhoneEndpoint? {
        val ctx = appContext ?: return null
        return AIRelayCore.get(ctx).queryEndpoint()
    }

    private fun probe(base: String, force: Boolean): Probe {
        synchronized(probeLock) {
            val now = System.currentTimeMillis()
            if (!force && base == probeBase && now - probeAt < PROBE_TTL_MS) return Probe.OK
            val result = probeOnce(base)
            if (result == Probe.OK) {
                probeBase = base
                probeAt = now
            } else {
                probeBase = ""
                probeAt = 0L
            }
            return result
        }
    }

    private fun probeOnce(base: String): Probe =
        try {
            RawHttp.request(
                method = "GET",
                url = "$base/api/translate",
                connectTimeoutMs = PROBE_TIMEOUT_MS,
                readTimeoutMs = PROBE_TIMEOUT_MS,
            ).use { resp ->
                val text = resp.body.readBytes().toString(Charsets.UTF_8)
                when {
                    resp.isSuccess -> {
                        val json = runCatching { JSONObject(text) }.getOrNull()
                        probeModel = json?.optString("model").orEmpty()

                        if (json?.optBoolean("ok", true) == false) Probe.NOT_READY else Probe.OK
                    }
                    resp.status == 404 -> Probe.OUTDATED
                    else -> {
                        Log.w(TAG, "probe $base → HTTP ${resp.status}: ${text.take(120)}")
                        Probe.REFUSED
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "probe $base failed: ${e.message}")
            Probe.UNREACHABLE
        }

    private fun invalidateProbe() = synchronized(probeLock) {
        probeBase = ""
        probeAt = 0L
    }

    private fun finish(
        job: Job,
        ok: Boolean,
        text: String? = null,
        file: String? = null,
        error: String? = null,
        message: String? = null,
        phone: Boolean? = null,
        model: String? = null,
    ) {
        if (!job.finished.compareAndSet(false, true)) return
        val ctx = appContext ?: return
        try {
            ctx.sendBroadcast(Intent(ACTION_RESULT).apply {
                setPackage(ctx.packageName)
                putExtra("caller", job.caller)
                putExtra("id", job.id)
                putExtra("kind", job.kind)
                putExtra("ok", ok)
                putExtra("ms", System.currentTimeMillis() - job.receivedAt)
                text?.let { putExtra("text", it) }
                file?.let { putExtra("file", it) }
                error?.let { putExtra("error", it) }
                message?.let { putExtra("message", it) }
                phone?.let { putExtra("phone", it) }
                model?.takeIf { it.isNotEmpty() }?.let { putExtra("model", it) }
            })
        } catch (e: Exception) {
            Log.e(TAG, "send result ${job.key} failed: ${e.message}", e)
        }
    }

    private fun languageName(code: String): String = when (code.trim()) {
        "", "zh", "zh-Hans", "zh-CN" -> "Simplified Chinese"
        "zh-Hant", "zh-TW", "zh-HK" -> "Traditional Chinese"
        "en" -> "English"
        "ja" -> "Japanese"
        "ko" -> "Korean"
        "fr" -> "French"
        "de" -> "German"
        "es" -> "Spanish"
        "ru" -> "Russian"
        else -> code.trim()
    }

    private fun defaultSystemPrompt(target: String): String {
        val lang = languageName(target)
        return "You are a translation engine. Translate the user's text into $lang. " +
            "Output only the translation, with no explanations, notes or quotes around it. " +
            "Keep line breaks, inline code, numbers, URLs and proper nouns as they are. " +
            "If the text is already in $lang, return it unchanged."
    }
}
