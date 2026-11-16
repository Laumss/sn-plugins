package me.laumss.notipal.common

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import android.os.Looper
import android.view.MotionEvent
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.ReadableArray
import com.facebook.react.bridge.WritableMap
import me.laumss.notipal.BuildConfig
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.Writer
import java.lang.reflect.Method
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

object App {
    lateinit var context: Context

    @Volatile var react: ReactApplicationContext? = null
        private set

    val ready: Boolean get() = ::context.isInitialized

    fun attach(ctx: ReactApplicationContext) {
        react = ctx
        context = ctx
    }

    @Volatile private var pluginAppCache: Any? = null

    fun pluginApp(): Any {
        pluginAppCache?.let { return it }
        val ctx = react ?: throw IllegalStateException("App.pluginApp used before attach")
        @Suppress("DEPRECATION")
        val nativeApi = ctx.catalystInstance.getNativeModule("NativePluginAPI")
            ?: throw IllegalStateException("NativePluginAPI module is empty")
        var owner: Class<*>? = nativeApi.javaClass
        while (owner != null) {
            val field = owner.declaredFields.firstOrNull { it.name == "mPluginApp" }
            if (field != null) {
                field.isAccessible = true
                val pluginApp = field.get(nativeApi)
                    ?: throw IllegalStateException("PluginAppAPI instance is empty")
                pluginAppCache = pluginApp
                return pluginApp
            }
            owner = owner.superclass
        }
        throw NoSuchFieldException("NativePluginAPI.mPluginApp")
    }
}

object AppInfo {
    @Volatile var versionName: String = ""
    @Volatile var versionCode: String = ""

    fun versionLabel(): String = when {
        versionName.isEmpty() -> ""
        versionCode.isEmpty() -> versionName
        else -> "$versionName ($versionCode)"
    }

    fun deviceLabel(): String = "${Build.MODEL} · Android ${Build.VERSION.RELEASE} · ${Build.DISPLAY}"
}

object LongFlows {
    private const val TAG = "LongFlows"

    const val MOSAIC_IMPORT = "mosaic-import"
    const val DOC_SHARE = "doc-share"
    const val RELAY_PAGES = "relay-pages"

    class Run internal constructor(val name: String)

    @Volatile private var current: Run? = null

    val busy: Boolean get() = current != null

    fun running(name: String): Boolean = current?.name == name

    fun begin(name: String): Run? = synchronized(this) {
        if (current != null) null else Run(name).also { current = it }
    }

    fun end(run: Run) = synchronized(this) {
        if (current === run) current = null
    }

    fun releaseForNewModule(name: String) = Main.post {
        synchronized(this) {
            if (current?.name == name) {
                Log.i(TAG, "module re-created: previous $name no longer blocks")
                current = null
            }
        }
    }
}

object Dirs {
    private const val TAG = "Dirs"
    private const val SDCARD = "/sdcard"
    private const val EMULATED = "/storage/emulated/0"

    @Volatile private var resolved: File? = null
    @Volatile private var failureLogged = false

    val pluginDirAvailable: Boolean get() = pluginRoot() != null

    private fun pluginRoot(): File? {
        resolved?.let { return it }
        if (App.react == null) return null
        return try {
            val app = App.pluginApp()
            val path = app.javaClass.methods.first { it.name == "getPluginPath" && it.parameterCount == 0 }.invoke(app)
            (path as? String)?.takeIf { it.isNotEmpty() }?.let(::File)?.also { resolved = it }
        } catch (error: Throwable) {

            if (!failureLogged) {
                failureLogged = true
                Log.w(TAG, "plugin dir unavailable: $error")
            }
            null
        }
    }

    val root: File
        get() = pluginRoot() ?: File(App.context.filesDir, "notipal")

    val hostRender: File get() = File(root, "host-render")

    val aiCapture: File get() = File(root, "ai-capture")

    val docScreenshots: File get() = File(root, "doc-screenshots")

    val imageTrash: File get() = File(root, "image-trash")

    val logs: File get() = File(root, "logs")

    val clipboard: File = File("$SDCARD/EXPORT/Notipal/clipboard")

    val mosaic: File = File("$SDCARD/EXPORT/Notipal/mosaic")

    fun canonical(path: String): String = when {
        path == EMULATED -> SDCARD
        path.startsWith("$EMULATED/") -> SDCARD + path.substring(EMULATED.length)
        else -> path
    }

    fun same(a: String, b: String): Boolean =
        canonical(a).trimEnd('/') == canonical(b).trimEnd('/')

    fun hostForm(path: String): String = when {
        path == SDCARD -> EMULATED
        path.startsWith("$SDCARD/") -> EMULATED + path.substring(SDCARD.length)
        else -> path
    }

    fun move(from: File, to: File): Boolean {
        if (from.renameTo(to)) return true
        return try {
            from.copyTo(to, overwrite = true)
            if (!from.delete()) {
                to.delete()
                false
            } else {
                true
            }
        } catch (error: Exception) {
            Log.w(TAG, "move failed $from -> $to", error)
            to.delete()
            false
        }
    }
}

object Prefs {
    fun of(feature: String): SharedPreferences =
        App.context.getSharedPreferences("notipal3_$feature", Context.MODE_PRIVATE)
}

object AiAccess {
    private const val TAG = "AiAccess"
    private const val KEY_ENABLED = "ai_enabled"
    private const val KEY_BANNED = "ai_banned"
    private const val BAN_FILE = "ai_disabled"

    private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    private fun prefs(): SharedPreferences = Prefs.of("settings")

    fun banned(): Boolean {
        val backup = prefs().getBoolean(KEY_BANNED, false)
        if (!Dirs.pluginDirAvailable) return backup
        val marked = File(Dirs.root, BAN_FILE).exists()
        if (!marked && backup) {
            Log.i(TAG, "ban marker gone with the plugin dir (reinstalled): AI back to default")
            prefs().edit().remove(KEY_BANNED).putBoolean(KEY_ENABLED, false).apply()
        }
        return marked
    }

    fun enabled(): Boolean = !banned() && prefs().getBoolean(KEY_ENABLED, false)

    fun setEnabled(on: Boolean) {
        if (on && banned()) return
        if (prefs().getBoolean(KEY_ENABLED, false) == on) return
        prefs().edit().putBoolean(KEY_ENABLED, on).apply()
        Log.i(TAG, "AI enabled=$on")
        notifyChanged()
    }

    fun ban() {
        try {
            val marker = File(Dirs.root, BAN_FILE)
            marker.parentFile?.mkdirs()
            marker.writeText("1")
        } catch (e: Exception) {
            Log.e(TAG, "ban marker write failed: ${e.message}", e)
        }
        prefs().edit().putBoolean(KEY_BANNED, true).putBoolean(KEY_ENABLED, false).apply()
        Log.i(TAG, "AI banned (plugin dir available=${Dirs.pluginDirAvailable})")
        notifyChanged()
    }

    fun addListener(l: () -> Unit): Cancelable {
        listeners += l
        return Cancelable { listeners -= l }
    }

    private fun notifyChanged() = Main.post {
        for (l in listeners) {
            try {
                l()
            } catch (e: Exception) {
                Log.e(TAG, "listener failed", e)
            }
        }
    }
}

object Presets {
    private const val KEY = "presets"
    private fun prefs() = Prefs.of("presets")

    fun load(): String? = prefs().getString(KEY, null)

    fun save(json: String) {
        prefs().edit().putString(KEY, json).apply()
    }
}

object RelayPrefs {
    private fun prefs(): SharedPreferences = Prefs.of("relay")

    fun phoneIp(): String = prefs().getString("phone_ip", "") ?: ""
    fun setPhoneIp(ip: String) = prefs().edit().putString("phone_ip", ip).apply()

    fun phoneFingerprint(): String = prefs().getString("phone_fingerprint", "") ?: ""
    fun setPhoneFingerprint(fingerprint: String) = prefs().edit().putString("phone_fingerprint", fingerprint).apply()

    fun phonePort(): Int = prefs().getInt("phone_port", 8080)
    fun setPhonePort(port: Int) = prefs().edit().putInt("phone_port", port).apply()

    fun clearPhonePairing() =
        prefs().edit()
            .remove("phone_ip")
            .remove("phone_fingerprint")
            .remove("phone_port")
            .apply()

    fun relayBackend(): String = prefs().getString("relay_backend", "rikkahub") ?: "rikkahub"
    fun setRelayBackend(value: String) = prefs().edit().putString("relay_backend", value).apply()

    @Synchronized
    fun webToken(): String {
        val existing = prefs().getString("web_token", "") ?: ""
        return existing.ifEmpty { newWebToken() }
    }

    @Synchronized
    fun newWebToken(): String {
        val bytes = ByteArray(5).also { java.security.SecureRandom().nextBytes(it) }
        val token = bytes.joinToString("") { "%02x".format(it) }
        prefs().edit().putString("web_token", token).apply()
        return token
    }

    fun autoOpenDetail(): Boolean = prefs().getBoolean("auto_open_detail", true)
    fun setAutoOpenDetail(value: Boolean) = prefs().edit().putBoolean("auto_open_detail", value).apply()

    fun openMini(): Boolean = prefs().getBoolean("open_mini", true)
    fun setOpenMini(value: Boolean) = prefs().edit().putBoolean("open_mini", value).apply()

    fun phoneFollows(): Boolean = prefs().getBoolean("phone_follows", true)
    fun setPhoneFollows(value: Boolean) = prefs().edit().putBoolean("phone_follows", value).apply()

    @Synchronized
    fun conversationCreation(peer: String, kind: String, ttlMs: Long = Long.MAX_VALUE): Pair<String, Long> {
        val key = "conversation_creation:$peer:$kind"
        val store = prefs()
        val now = System.currentTimeMillis()
        val id = store.getString(key, null)
        val createdAt = store.getLong("$key:at", 0)
        if (id != null && now - createdAt < ttlMs) return id to createdAt
        val nextId = java.util.UUID.randomUUID().toString()
        check(store.edit().putString(key, nextId).putLong("$key:at", now).commit()) {
            "Could not persist conversation creation ID"
        }
        return nextId to now
    }
}

object Log {
    fun d(tag: String, msg: String) {
        if (!BuildConfig.ENABLE_DEBUG) return
        if (BuildConfig.DEV_BUILD) android.util.Log.d(tag, msg) else LogFile.add('D', tag, msg, null)
    }

    fun i(tag: String, msg: String) {
        if (BuildConfig.DEV_BUILD) android.util.Log.i(tag, msg) else LogFile.add('I', tag, msg, null)
    }

    fun w(tag: String, msg: String, t: Throwable? = null) {
        if (BuildConfig.DEV_BUILD) android.util.Log.w(tag, msg, t) else LogFile.add('W', tag, msg, t)
    }

    fun e(tag: String, msg: String, t: Throwable? = null) {
        if (BuildConfig.DEV_BUILD) android.util.Log.e(tag, msg, t) else LogFile.add('E', tag, msg, t)
    }

    internal fun js(time: Long, level: Char, msg: String) {
        LogFile.add(time, android.os.Process.myTid(), level, JS_TAG, msg)
    }

    private const val JS_TAG = "ReactNativeJS"

    fun export(): File = LogExport.export()
}

private object LogFile {
    private const val TAG = "LogFile"
    private const val FILE_NAME = "notipal.log"
    private const val MAX_BYTES = 2L * 1024 * 1024
    private const val QUEUE_CAPACITY = 20_000
    private const val DIR_POLL_MS = 200L
    private const val WRITE_RETRY_MS = 1000L

    private class Entry(
        val time: Long,
        val tid: Int,
        val level: Char,
        val tag: String,
        val msg: String,
        val done: CountDownLatch? = null,
    )

    private val queue = LinkedBlockingQueue<Entry>(QUEUE_CAPACITY)
    private val dropped = AtomicInteger()
    @Volatile private var writerStarted = false

    fun add(level: Char, tag: String, msg: String, tr: Throwable?) {
        val text = if (tr == null) msg else msg + "\n" + android.util.Log.getStackTraceString(tr)
        add(System.currentTimeMillis(), android.os.Process.myTid(), level, tag, text)
    }

    fun add(time: Long, tid: Int, level: Char, tag: String, msg: String) {
        ensureWriter()
        if (!queue.offer(Entry(time, tid, level, tag, msg))) dropped.incrementAndGet()
    }

    fun flush(timeoutMs: Long): Boolean {
        ensureWriter()
        val done = CountDownLatch(1)
        if (!queue.offer(Entry(0, 0, ' ', "", "", done), timeoutMs, TimeUnit.MILLISECONDS)) return false
        return done.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    fun files(): List<File> {
        if (!App.ready) return emptyList()
        val dir = Dirs.logs
        return listOf(File(dir, "$FILE_NAME.1"), File(dir, FILE_NAME)).filter { it.isFile }
    }

    private fun ensureWriter() {
        if (writerStarted) return
        synchronized(this) {
            if (writerStarted) return
            writerStarted = true
            Thread(::writeLoop, "NotipalLogFile").apply {
                isDaemon = true
                priority = Thread.MIN_PRIORITY
            }.start()
        }
    }

    private fun writeLoop() {

        PluginIdentity.claim()
        val dir = awaitDir()
        val file = File(dir, FILE_NAME)
        val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
        val pid = android.os.Process.myPid()
        val myTid = android.os.Process.myTid()
        var out: Writer? = null
        var failureLogged = false
        while (true) {
            var entry: Entry? = queue.take()
            try {
                val w = out ?: run {
                    dir.mkdirs()
                    FileOutputStream(file, true).bufferedWriter().also { out = it }
                }
                while (entry != null) {
                    val done = entry.done
                    if (done != null) {
                        w.flush()
                        done.countDown()
                    } else {
                        write(w, entry, stamp, pid)
                    }
                    entry = queue.poll()
                }
                val lost = dropped.getAndSet(0)
                if (lost > 0) {
                    write(w, Entry(System.currentTimeMillis(), myTid, 'W', TAG, "dropped $lost log lines: queue full"), stamp, pid)
                }
                w.flush()
                if (file.length() >= MAX_BYTES) {
                    w.close()
                    out = null
                    val old = File(dir, "$FILE_NAME.1")
                    old.delete()
                    if (!file.renameTo(old)) file.delete()
                }
            } catch (e: Exception) {

                if (!failureLogged) {
                    failureLogged = true
                    android.util.Log.w(TAG, "cannot write ${file.path}: $e")
                }
                try {
                    out?.close()
                } catch (closeError: Exception) {
                    android.util.Log.w(TAG, "close after write failure: $closeError")
                }
                out = null

                entry?.done?.countDown()
                Thread.sleep(WRITE_RETRY_MS)
            }
        }
    }

    private fun awaitDir(): File {
        while (!App.ready) Thread.sleep(DIR_POLL_MS)
        return Dirs.logs
    }

    private fun write(w: Writer, e: Entry, stamp: SimpleDateFormat, pid: Int) {
        val prefix = String.format(Locale.US, "%s %5d %5d %c %-8s: ", stamp.format(Date(e.time)), pid, e.tid, e.level, e.tag)
        for (line in e.msg.split('\n')) {
            w.write(prefix)
            w.write(line)
            w.write("\n")
        }
    }
}

private object LogExport {
    private const val TAG = "LogExport"
    private const val EXPORT_DIR = "/sdcard/EXPORT"
    private const val FLUSH_TIMEOUT_MS = 2000L

    private const val STAMP_LEN = 18
    private val STAMP = Regex("""\d\d-\d\d \d\d:\d\d:\d\d\.\d\d\d""")

    private val HOST_PACKAGES = listOf(
        "com.ratta.supernote.pluginhost",
        "com.ratta.supernote.note",
        "com.supernote.document",
    )

    fun export(): File {

        PluginIdentity.claim()
        val flushed = BuildConfig.DEV_BUILD || LogFile.flush(FLUSH_TIMEOUT_MS)
        val logFiles = LogFile.files()
        val now = Date()
        val dir = File(EXPORT_DIR)
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("cannot create $EXPORT_DIR")
        val file = File(dir, "Notipal_log_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(now)}.txt")
        try {
            file.bufferedWriter().use { out ->
                out.write(header(now, logFiles, flushed))
                val proc = ProcessBuilder("logcat", "-d", "-v", "threadtime").redirectErrorStream(true).start()
                try {
                    val readers = logFiles.map { it.bufferedReader() }
                    try {
                        proc.inputStream.bufferedReader().use { logcat ->
                            merge(
                                Cursor(logcat.lineSequence().iterator()),
                                Cursor(readers.asSequence().flatMap { it.lineSequence() }.iterator()),
                                out,
                            )
                        }
                    } finally {
                        readers.forEach { r -> runCatching { r.close() }.onFailure { Log.w(TAG, "close log file", it) } }
                    }
                    val code = proc.waitFor()
                    if (code != 0) out.write("\n# logcat exit code $code\n")
                } finally {
                    proc.destroy()
                }
            }
        } catch (e: Exception) {
            file.delete()
            throw e
        }
        Log.i(TAG, "exported ${file.path} (${file.length()} bytes)")
        return file
    }

    private fun merge(logcat: Cursor, notipal: Cursor, out: Writer) {
        while (true) {
            val src = when {
                logcat.line == null && notipal.line == null -> return
                notipal.line == null -> logcat
                logcat.line == null -> notipal
                logcat.key <= notipal.key -> logcat
                else -> notipal
            }
            out.write(src.take())
            out.write("\n")
        }
    }

    private class Cursor(private val lines: Iterator<String>) {
        var line: String? = null
            private set
        var key = ""
            private set

        init { advance() }

        fun take(): String = line.orEmpty().also { advance() }

        private fun advance() {
            val l = if (lines.hasNext()) lines.next() else null
            line = l
            if (l != null && l.length >= STAMP_LEN && STAMP.matches(l.substring(0, STAMP_LEN))) {
                key = l.substring(0, STAMP_LEN)
            }
        }
    }

    private fun header(now: Date, logFiles: List<File>, flushed: Boolean): String = buildString {
        appendLine("# Notipal log export ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(now)}")
        val build = if (BuildConfig.DEV_BUILD) "dev build, Notipal logs in logcat" else "release build, Notipal logs from its log file"
        val debug = if (BuildConfig.ENABLE_DEBUG) "" else ", debug logs compiled out"
        appendLine("# plugin ${AppInfo.versionLabel().ifEmpty { "?" }}, $build$debug")
        appendLine("# device ${AppInfo.deviceLabel()}")
        appendLine("# process ${android.os.Process.myPid()} ${processName()}")
        for (pkg in HOST_PACKAGES) appendLine("# pid ${pidOf(pkg) ?: "-"} $pkg")
        for (f in logFiles) appendLine("# log file ${f.path} ${f.length()} bytes")
        if (!flushed) appendLine("# log file flush timed out, the newest Notipal lines may be missing")
        appendLine()
    }

    private fun processName(): String =
        runCatching { File("/proc/self/cmdline").readText().substringBefore('\u0000') }.getOrDefault("?")

    private fun pidOf(pkg: String): String? = runCatching {
        val p = ProcessBuilder("pidof", pkg).start()
        val out = p.inputStream.bufferedReader().use { it.readText().trim() }
        p.waitFor()
        out.ifEmpty { null }
    }.getOrNull()
}

class NotipalLogModule(ctx: ReactApplicationContext) : FeatureModule(ctx, "NotipalLog") {

    @ReactMethod
    fun append(lines: ReadableArray) {
        for (i in 0 until lines.size()) {
            val s = lines.getString(i) ?: continue
            val a = s.indexOf('\t')
            val b = if (a > 0) s.indexOf('\t', a + 1) else -1
            val time = if (b > a + 1) s.substring(0, a).toLongOrNull() else null
            if (time == null) {
                Log.js(System.currentTimeMillis(), 'I', s)
            } else {
                Log.js(time, s[a + 1], s.substring(b + 1))
            }
        }
    }

    @ReactMethod(isBlockingSynchronousMethod = true)
    fun buildFlags(): WritableMap = Arguments.createMap().apply {
        putBoolean("devBuild", BuildConfig.DEV_BUILD)
        putBoolean("debug", BuildConfig.ENABLE_DEBUG)
    }
}

object Bitmaps {
    private const val TAG = "Bitmaps"

    const val FILTER_NONE = 0
    const val FILTER_ENHANCE = 1
    const val FILTER_TEXT_BW = 2

    private val nativeReady: Boolean by lazy {
        try {
            System.loadLibrary("inkfilter_jni")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "loadLibrary failed: ${t.message}")
            false
        }
    }

    @JvmStatic
    private external fun nativeProcess(bitmap: Bitmap, filter: Int, density: Int): Int

    fun process(bitmap: Bitmap, filter: Int, density: Int): Boolean {
        val d = density.coerceIn(20, 100)
        if (filter == FILTER_NONE && d >= 100) return true
        if (nativeReady) {
            val rc = try {
                nativeProcess(bitmap, filter, d)
            } catch (t: Throwable) {
                Log.e(TAG, "nativeProcess threw: ${t.message}")
                -100
            }
            if (rc == 0) return true
            Log.e(TAG, "nativeProcess rc=$rc, falling back to density-only")
        }
        fallbackDensity(bitmap, d)
        return filter == FILTER_NONE
    }

    private fun fallbackDensity(bitmap: Bitmap, density: Int) {
        if (density >= 100) return
        val a = density / 100f
        val offset = 255f * (1 - a)
        val src = bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: return
        val canvas = Canvas(bitmap)

        canvas.drawARGB(255, 255, 255, 255)
        val paint = Paint().apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
                a, 0f, 0f, 0f, offset,
                0f, a, 0f, 0f, offset,
                0f, 0f, a, 0f, offset,
                0f, 0f, 0f, 1f, 0f
            )))
        }
        canvas.drawBitmap(src, 0f, 0f, paint)
        src.recycle()
    }

    fun powerOfTwoSampleSize(outW: Int, outH: Int, reqW: Int, reqH: Int): Int {
        var sample = 1
        if (outH > reqH || outW > reqW) {
            val halfH = outH / 2; val halfW = outW / 2
            while (halfH / sample >= reqH && halfW / sample >= reqW) sample *= 2
        }
        return sample
    }

    fun cropRegion(
        srcPath: String,
        refW: Double,
        refH: Double,
        left: Double,
        top: Double,
        right: Double,
        bottom: Double,
        outPath: String,
    ): String {
        if (!File(srcPath).isFile) throw IOException("source not found: $srcPath")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(srcPath, bounds)
        val srcW = bounds.outWidth
        val srcH = bounds.outHeight
        if (srcW <= 0 || srcH <= 0) throw IOException("bad source dimensions: ${srcW}x$srcH")
        val sx = if (refW > 0) srcW / refW else 1.0
        val sy = if (refH > 0) srcH / refH else 1.0
        val rect = Rect(
            (left * sx).toInt().coerceIn(0, srcW),
            (top * sy).toInt().coerceIn(0, srcH),
            (right * sx).toInt().coerceIn(0, srcW),
            (bottom * sy).toInt().coerceIn(0, srcH),
        )
        if (rect.width() <= 0 || rect.height() <= 0) throw IOException("degenerate crop rect: $rect (src=${srcW}x$srcH)")
        var cropped: Bitmap? = null
        var decoder: BitmapRegionDecoder? = null
        try {
            @Suppress("DEPRECATION")
            decoder = BitmapRegionDecoder.newInstance(srcPath, false)
            val bitmap = decoder?.decodeRegion(rect, null) ?: throw IOException("decodeRegion returned null")
            cropped = bitmap
            File(outPath).parentFile?.mkdirs()
            FileOutputStream(outPath).use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) throw IOException("PNG encode failed")
            }
            return outPath
        } finally {
            cropped?.recycle()
            decoder?.recycle()
        }
    }
}

object TouchInput {
    fun isFinger(event: MotionEvent): Boolean {
        val index = event.actionIndex.coerceIn(0, event.pointerCount - 1)
        return event.getToolType(index) == MotionEvent.TOOL_TYPE_FINGER
    }
}

object Http {
    private const val TAG = "Http"
    private const val CRLF = "\r\n"

    class Response(
        val status: Int,
        val headers: Map<String, String>,
        val body: InputStream,
        private val socket: Socket,
    ) : AutoCloseable {
        val isSuccess: Boolean get() = status in 200..299

        override fun close() {
            closeQuietly(body)
            closeQuietly(socket)
        }
    }

    private fun closeQuietly(c: AutoCloseable) {
        try {
            c.close()
        } catch (e: Exception) {
            Log.d(TAG, "close: $e")
        }
    }

    private data class Target(val host: String, val port: Int, val path: String)

    private fun parse(url: String): Target {
        val uri = URI(url)
        require(uri.scheme == null || uri.scheme.equals("http", ignoreCase = true)) {
            "Http only speaks plain http, got: ${uri.scheme}"
        }
        val host = uri.host ?: throw IOException("No host in URL: $url")
        val port = if (uri.port > 0) uri.port else 80
        val path = buildString {
            append(uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/")
            uri.rawQuery?.let { append('?').append(it) }
        }
        return Target(host, port, path)
    }

    fun request(
        method: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
        connectTimeoutMs: Int = 5_000,
        readTimeoutMs: Int = 30_000,

        onSocket: ((Socket) -> Unit)? = null,

        bodyStream: InputStream? = null,
        bodyLength: Long = 0L,
    ): Response {
        val target = parse(url)

        PluginIdentity.claim()
        val socket = Socket()
        onSocket?.invoke(socket)
        try {
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(target.host, target.port), connectTimeoutMs)
            socket.soTimeout = readTimeoutMs

            writeRequest(socket.getOutputStream(), method, target, headers, body, bodyStream, bodyLength)

            val input = socket.getInputStream().buffered()
            val (status, respHeaders) = readHead(input)
            val stream = wrapBody(input, respHeaders, method, status)
            return Response(status, respHeaders, stream, socket)
        } catch (e: Exception) {
            closeQuietly(socket)
            throw e
        }
    }

    private fun writeRequest(
        out: OutputStream,
        method: String,
        target: Target,
        headers: Map<String, String>,
        body: ByteArray?,
        bodyStream: InputStream?,
        bodyLength: Long,
    ) {
        val head = StringBuilder()
        head.append(method).append(' ').append(target.path).append(" HTTP/1.1").append(CRLF)
        val hostHeader =
            if (target.port == 80) target.host else "${target.host}:${target.port}"
        head.append("Host: ").append(hostHeader).append(CRLF)

        head.append("Connection: close").append(CRLF)

        head.append("Accept-Encoding: identity").append(CRLF)
        headers.forEach { (k, v) -> head.append(k).append(": ").append(v).append(CRLF) }
        if (bodyStream != null) {
            head.append("Content-Length: ").append(bodyLength).append(CRLF)
        } else if (body != null) {
            head.append("Content-Length: ").append(body.size).append(CRLF)
        }
        head.append(CRLF)

        out.write(head.toString().toByteArray(Charsets.UTF_8))
        if (bodyStream != null) bodyStream.copyTo(out) else body?.let { out.write(it) }
        out.flush()
    }

    private fun readHead(input: InputStream): Pair<Int, Map<String, String>> {
        val statusLine = readLine(input) ?: throw IOException("Empty response")
        val parts = statusLine.split(' ', limit = 3)
        if (parts.size < 2) throw IOException("Malformed status line: $statusLine")
        val status = parts[1].toIntOrNull()
            ?: throw IOException("Malformed status code: $statusLine")

        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            val name = line.substring(0, idx).trim().lowercase()
            headers[name] = line.substring(idx + 1).trim()
        }
        return status to headers
    }

    private fun readLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream(64)
        while (true) {
            val b = input.read()
            if (b == -1) return if (buf.size() == 0) null else buf.toString("UTF-8")
            if (b == '\n'.code) {
                val bytes = buf.toByteArray()
                val end = if (bytes.isNotEmpty() && bytes.last() == '\r'.code.toByte()) {
                    bytes.size - 1
                } else {
                    bytes.size
                }
                return String(bytes, 0, end, Charsets.UTF_8)
            }
            buf.write(b)
        }
    }

    private fun wrapBody(
        input: InputStream,
        headers: Map<String, String>,
        method: String,
        status: Int,
    ): InputStream {
        if (method == "HEAD" || status == 204 || status == 304 || status in 100..199) {
            return EmptyStream
        }
        if (headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true) {
            return ChunkedInputStream(input)
        }
        val length = headers["content-length"]?.trim()?.toLongOrNull()
        return if (length != null) FixedLengthInputStream(input, length) else input
    }

    private object EmptyStream : InputStream() {
        override fun read(): Int = -1
    }

    private class FixedLengthInputStream(
        private val source: InputStream,
        private var remaining: Long,
    ) : InputStream() {
        override fun read(): Int {
            if (remaining <= 0) return -1
            val b = source.read()
            if (b >= 0) remaining--
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0) return -1
            val toRead = minOf(len.toLong(), remaining).toInt()
            val n = source.read(b, off, toRead)
            if (n > 0) remaining -= n
            return n
        }

        override fun available(): Int = minOf(source.available().toLong(), remaining).toInt()
    }

    private class ChunkedInputStream(private val source: InputStream) : InputStream() {
        private var chunkRemaining = 0L
        private var finished = false
        private var sawFirstChunk = false

        private fun ensureChunk(): Boolean {
            if (finished) return false
            if (chunkRemaining > 0) return true

            if (sawFirstChunk) readLine(source)

            val sizeLine = readLine(source) ?: run { finished = true; return false }
            val size = sizeLine.substringBefore(';').trim().toLongOrNull(16)
            if (size == null) {
                Log.w(TAG, "Malformed chunk size: $sizeLine")
                finished = true
                return false
            }
            sawFirstChunk = true
            if (size == 0L) {
                while (true) {
                    val line = readLine(source) ?: break
                    if (line.isEmpty()) break
                }
                finished = true
                return false
            }
            chunkRemaining = size
            return true
        }

        override fun read(): Int {
            if (!ensureChunk()) return -1
            val b = source.read()
            if (b >= 0) chunkRemaining--
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (!ensureChunk()) return -1
            val toRead = minOf(len.toLong(), chunkRemaining).toInt()
            val n = source.read(b, off, toRead)
            if (n > 0) chunkRemaining -= n
            return n
        }

        override fun available(): Int =
            minOf(source.available().toLong(), chunkRemaining).toInt()
    }

    private fun get(url: String, timeoutMs: Int): String? =
        request(
            method = "GET",
            url = url,
            connectTimeoutMs = timeoutMs,
            readTimeoutMs = timeoutMs,
        ).use { resp ->
            if (resp.isSuccess) {
                resp.body.readBytes().toString(Charsets.UTF_8)
            } else {
                Log.w(TAG, "GET $url → ${resp.status}")
                null
            }
        }

    fun getJsonOrNull(url: String, timeoutMs: Int = 5_000): JSONObject? =
        try {
            get(url, timeoutMs)?.let { JSONObject(it) }
        } catch (e: Exception) {
            Log.w(TAG, "GET $url failed: ${e.message}")
            null
        }

    fun getJson(url: String, timeoutMs: Int = 5_000): JSONObject =
        JSONObject(get(url, timeoutMs) ?: throw IOException("GET $url was not successful"))

    fun delete(url: String, timeoutMs: Int = 5_000): Boolean =
        try {
            request(
                method = "DELETE",
                url = url,
                connectTimeoutMs = timeoutMs,
                readTimeoutMs = timeoutMs,
            ).use { it.isSuccess }
        } catch (e: Exception) {
            Log.w(TAG, "DELETE $url failed: ${e.message}")
            false
        }

    fun postJson(url: String, body: JSONObject, timeoutMs: Int = 30_000): String =
        post(
            url = url,
            body = body.toString().toByteArray(Charsets.UTF_8),
            contentType = "application/json",
            timeoutMs = timeoutMs,
        )

    fun post(
        url: String,
        body: ByteArray,
        contentType: String,
        timeoutMs: Int = 30_000,
    ): String =
        request(
            method = "POST",
            url = url,
            headers = mapOf("Content-Type" to contentType),
            body = body,

            connectTimeoutMs = minOf(timeoutMs, 10_000),
            readTimeoutMs = timeoutMs,
        ).use { resp ->
            val text = resp.body.readBytes().toString(Charsets.UTF_8)
            if (!resp.isSuccess) {
                Log.w(TAG, "POST $url → ${resp.status}: ${text.take(200)}")
                throw IOException("POST $url returned HTTP ${resp.status}: ${text.take(200)}")
            }
            text
        }
}

object PluginIdentity {
    private const val TAG = "PluginIdentity"

    const val PLUGIN_ID = "19uq35yjd3tr3zjr"

    private class Api(val instance: Any, val get: Method?, val set: Method)

    private val api: Api? by lazy {
        try {
            val cls = Class.forName("android.os.PluginSecurityManager")
            val instance = cls.getMethod("getInstance").invoke(null)
                ?: throw IllegalStateException("getInstance returned null")
            val get = runCatching { cls.getMethod("getCurrentPluginId") }.getOrNull()
            val set = cls.getMethod("setCurrentPluginId", String::class.java)
            Api(instance, get, set)
        } catch (e: Throwable) {

            Log.i(TAG, "no PluginSecurityManager: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    @Volatile private var failureLogged = false

    private val setHere = ThreadLocal<Boolean>()

    fun claim() {
        if (Looper.myLooper() == Looper.getMainLooper()) return
        val a = api ?: return
        try {
            val getter = a.get
            if (getter == null) {
                if (setHere.get() == true) return
                a.set.invoke(a.instance, PLUGIN_ID)
                setHere.set(true)
                return
            }
            val current = getter.invoke(a.instance) as? String
            if (current.isNullOrEmpty() || current == PLUGIN_ID) return
            a.set.invoke(a.instance, PLUGIN_ID)
            Log.i(TAG, "thread '${Thread.currentThread().name}' was plugin $current, now Notipal")
        } catch (e: Throwable) {
            logFailure(e)
        }
    }

    fun <T> asNotipal(block: () -> T): T {
        val a = api ?: return block()
        val getter = a.get ?: return block()
        val previous = try {
            getter.invoke(a.instance) as? String
        } catch (e: Throwable) {
            logFailure(e)
            return block()
        }
        if (previous.isNullOrEmpty() || previous == PLUGIN_ID) return block()
        try {
            a.set.invoke(a.instance, PLUGIN_ID)
        } catch (e: Throwable) {
            logFailure(e)
            return block()
        }
        try {
            return block()
        } finally {
            try {
                a.set.invoke(a.instance, previous)
            } catch (e: Throwable) {
                logFailure(e)
            }
        }
    }

    private fun logFailure(e: Throwable) {
        if (failureLogged) return
        failureLogged = true
        val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
        Log.w(TAG, "cannot set the thread's plugin id: ${cause.javaClass.simpleName}: ${cause.message}")
    }
}
