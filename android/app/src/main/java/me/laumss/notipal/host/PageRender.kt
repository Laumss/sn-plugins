package me.laumss.notipal.host

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Base64
import android.util.Size
import android.util.SizeF
import android.view.Surface
import android.view.WindowManager
import androidx.core.graphics.PathParser
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReadableMap
import com.ratta.supernote.plugincommon.data.common.trail.Element
import com.ratta.supernote.plugincommon.response.PluginAPIResponse
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import me.laumss.notipal.BuildConfig
import me.laumss.notipal.common.Dirs
import me.laumss.notipal.common.Log
import me.laumss.notipal.mosaic.MosaicLink
import me.laumss.notipal.mosaic.PenSizeSpec
import me.laumss.notipal.ui_common.Style
import org.json.JSONObject

object PageRender {

    private const val TAG = "PageImageSource"
    private const val HOST_API_CLASS = "com.ratta.supernote.pluginlib.api.HostCommonAPI"
    private const val HOST_CALLBACK_CLASS = "com.ratta.supernote.pluginlib.callback.RequestHostCallback"
    private const val FOREGROUND_RETRY_COUNT = 3
    private const val FOREGROUND_RETRY_MS = 600L

    internal val HOST_OUTPUT_DIR get() = Dirs.hostRender

    private const val INCLUDE_NOTE_TEMPLATE = false

    internal const val TRAIL_CACHE_BUSY = "host trail cache busy"
    internal const val TRAIL_CACHE_BUSY_RETRY_COUNT = 50
    internal const val TRAIL_CACHE_BUSY_RETRY_MS = 300L

    private val mainHandler = Handler(Looper.getMainLooper())
    private val renderExecutor by lazy {
        Executors.newSingleThreadExecutor { r -> Thread(r, "Inkling-page-render").apply { isDaemon = true } }
    }
    @Volatile private var pluginAppCache: Any? = null

    data class PageContext(
        val filePath: String, val pageNum: Int,
        val pageWidth: Int, val pageHeight: Int,
        val screenWidth: Int, val screenHeight: Int,
    )

    class StageTimer {
        private val start = SystemClock.elapsedRealtime()
        private var last = start
        private val parts = StringBuilder()

        @Synchronized fun mark(stage: String) {
            val now = SystemClock.elapsedRealtime()
            parts.append(' ').append(stage).append('=').append(now - last)
            last = now
        }

        @Synchronized fun summary(): String = "$parts total=${SystemClock.elapsedRealtime() - start}"
    }

    fun readCurrentContext(ctx: ReactApplicationContext, onDone: (PageContext?, String?) -> Unit) {
        val done = AtomicBoolean(false)
        val timeout = Runnable {
            if (done.compareAndSet(false, true)) onDone(null, "page context timeout")
        }
        mainHandler.postDelayed(timeout, 6_000L)
        fun finish(value: PageContext?, error: String?) {
            if (!done.compareAndSet(false, true)) return
            mainHandler.removeCallbacks(timeout)
            postMain { onDone(value, error) }
        }
        callHost(ctx, "getCurrentFilePath", emptyList()) file@ { pathValue, pathError ->
            if (done.get()) return@file
            val path = (pathValue as? String)?.takeIf { it.isNotBlank() }
                ?: return@file finish(null, pathError ?: "current file path empty")
            callHost(ctx, "getCurrentPageNum", emptyList()) page@ { pageValue, pageError ->
                if (done.get()) return@page
                val page = (pageValue as? Number)?.toInt()
                    ?: return@page finish(null, pageError ?: "current page empty")
                callHost(ctx, "getPageSize", listOf(path, page)) size@ { sizeValue, sizeError ->
                    if (done.get()) return@size
                    val size = sizeValue as? SizeF
                    if (size == null || !size.width.isFinite() || !size.height.isFinite() || size.width <= 0f || size.height <= 0f) {
                        return@size finish(null, sizeError ?: "page size unavailable")
                    }
                    val sw = HostSession.screenWidth.takeIf { it > 0 }
                        ?: ctx.resources.displayMetrics.widthPixels
                    val sh = HostSession.screenHeight.takeIf { it > 0 }
                        ?: ctx.resources.displayMetrics.heightPixels
                    finish(PageContext(path, page, size.width.toInt(), size.height.toInt(), sw, sh), null)
                }
            }
        }
    }

    fun renderCurrentPage(
        ctx: ReactApplicationContext,
        outPath: String,
        expectedContext: PageContext? = null,
        livePicturePreview: (() -> String?)? = null,
        crop: Rect? = null,
        timer: StageTimer? = null,
        onDone: (String?, String?) -> Unit,
    ) {
        val done = AtomicBoolean(false)
        fun finish(path: String?, error: String?) {
            if (!done.compareAndSet(false, true)) return
            if (error != null) {
                runCatching { File(outPath).delete() }
                Log.w(TAG, "renderCurrentPage: $error")
            }
            postMain { onDone(path, error) }
        }
        readCurrentContext(ctx) { source, error ->
            timer?.mark("context")
            if (source == null) return@readCurrentContext finish(null, error)
            if (MosaicLink.boardVisible ||
                (expectedContext != null && source != expectedContext)) {
                return@readCurrentContext finish(null, "capture context changed before render")
            }
            val rendered: (String?, String?) -> Unit = { path, renderError ->
                if (path == null) finish(null, renderError)
                else readCurrentContext(ctx) { current, contextError ->
                    timer?.mark("verify")
                    if (current != source || MosaicLink.boardVisible) {
                        finish(null, contextError ?: "capture context changed during render")
                    } else finish(path, null)
                }
            }
            if (isNotePath(source.filePath)) {
                renderNote(ctx, source.filePath, source.pageNum, source.pageWidth,
                    source.pageHeight, outPath, livePicturePreview, crop, timer, rendered)
            } else {
                renderDoc(ctx, source.filePath, source.pageNum, source.pageWidth, source.pageHeight,
                    source.screenWidth, source.screenHeight, outPath, crop, timer, rendered)
            }
        }
    }

    fun renderCurrentPageBlocking(
        ctx: ReactApplicationContext, outPath: String,
        expectedContext: PageContext? = null, timeoutMs: Long = 15_000L,
        livePicturePreview: (() -> String?)? = null,
        crop: Rect? = null, timer: StageTimer? = null,
    ): String? {
        check(Looper.myLooper() != Looper.getMainLooper()) { "page rendering requires a worker" }
        val latch = java.util.concurrent.CountDownLatch(1)
        val lock = Any()
        var accepting = true
        var result: String? = null
        renderCurrentPage(ctx, outPath, expectedContext, livePicturePreview, crop, timer) { path, _ ->
            synchronized(lock) {
                if (accepting) result = path
                else runCatching { File(outPath).delete() }
                latch.countDown()
            }
        }
        try {
            latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        return synchronized(lock) {
            accepting = false
            result?.takeIf { File(it).isFile && File(it).length() > 0L }
        }
    }

    private fun renderNote(
        ctx: ReactApplicationContext,
        path: String,
        page: Int,
        pageW: Int,
        pageH: Int,
        outPath: String,
        livePicturePreview: (() -> String?)?,
        crop: Rect?,
        timer: StageTimer?,
        finish: (String?, String?) -> Unit,
    ) {
        val stem = "note_${System.nanoTime()}"
        val templatePath = if (INCLUDE_NOTE_TEMPLATE) hostOutputFile("${stem}_tpl.png") else null
        fun withTemplate(readyTemplate: String) {
            loadElements(ctx, path, page) { elements, error, release ->
                timer?.mark("elements(${elements?.size ?: 0})")
                if (elements == null) {
                    release()
                    runCatching { File(readyTemplate).delete() }
                    finish(null, error ?: "elements empty")
                    return@loadElements
                }
                withLayerPreview(ctx, path, page, stem, elements, livePicturePreview) { previewPath ->
                    timer?.mark("preview")
                    renderExecutor.execute {
                        val result = runCatching {
                            val background = readyTemplate.takeIf { it.isNotEmpty() }?.let(::decodeFile)
                            val rendered = try {
                                VectorPageRenderer.render(
                                    elements, pageW, pageH, pageW, pageH,
                                    background = background,
                                    layerPreviewPath = previewPath,
                                )
                            } finally {
                                background?.recycle()
                            }
                            timer?.mark("draw")
                            writeRegionPng(rendered, crop, outPath)
                            timer?.mark("encode")
                        }
                        release()
                        runCatching { if (readyTemplate.isNotEmpty()) File(readyTemplate).delete() }
                        runCatching { if (previewPath.isNotEmpty()) File(previewPath).delete() }
                        result.fold(
                            onSuccess = {
                                if (BuildConfig.ENABLE_DEBUG) {
                                    Log.i(TAG, "note page rendered page=$page elements=${elements.size} " +
                                        "size=${pageW}x$pageH → $outPath")
                                }
                                finish(outPath, null)
                            },
                            onFailure = { finish(null, it.message ?: it.javaClass.simpleName) },
                        )
                    }
                }
            }
        }
        if (templatePath == null) {
            withTemplate("")
        } else {
            callHost(ctx, "generateNoteTemplatePng", listOf(path, page, templatePath)) { value, _ ->
                withTemplate(if (value == true && File(templatePath).isFile) templatePath else "")
            }
        }
    }

    private fun renderDoc(
        ctx: ReactApplicationContext,
        path: String,
        page: Int,
        pageW: Int,
        pageH: Int,
        screenW: Int,
        screenH: Int,
        outPath: String,
        crop: Rect?,
        timer: StageTimer?,
        finish: (String?, String?) -> Unit,
    ) {
        val stem = "doc_${System.nanoTime()}"
        val basePath = hostOutputFile("${stem}_base.png")
        runCatching { File(basePath).delete() }

        callHost(
            ctx, "generateCurrentDocImage",
            listOf(page, basePath, SizeF(screenW.toFloat(), screenH.toFloat()), 1),
        ) { value, error ->
            timer?.mark("hostDocImage")
            val baseOk = value == true && File(basePath).isFile && File(basePath).length() > 0L
            if (!baseOk) {
                runCatching { File(basePath).delete() }
                finish(null, error ?: "doc base image failed (result=$value)")
                return@callHost
            }

            loadElements(ctx, path, page, emptyIsOk = true) { elements, elementError, release ->
                timer?.mark("elements(${elements?.size ?: 0})")
                if (elements == null) {
                    release()
                    runCatching { File(basePath).delete() }
                    finish(null, elementError ?: "current annotations unavailable")
                    return@loadElements
                }
                val list = elements
                withLayerPreview(ctx, path, page, stem, list) { previewPath ->
                    timer?.mark("preview")
                    renderExecutor.execute {
                        val result = runCatching {

                            if (crop != null && list.isEmpty() &&
                                writeBaseRegion(basePath, screenW, screenH, crop, outPath)) {
                                timer?.mark("baseRegion")
                            } else {
                                val background = decodeFile(basePath)
                                    ?: throw IllegalStateException("doc base decode failed")
                                timer?.mark("decode")
                                val rendered = try {
                                    VectorPageRenderer.render(
                                        list, pageW, pageH, screenW, screenH,
                                        background = background,
                                        layerPreviewPath = previewPath,
                                    )
                                } finally {
                                    background.recycle()
                                }
                                timer?.mark("draw")
                                writeRegionPng(rendered, crop, outPath)
                                timer?.mark("encode")
                            }
                        }
                        release()
                        runCatching { File(basePath).delete() }
                        runCatching { if (previewPath.isNotEmpty()) File(previewPath).delete() }
                        result.fold(
                            onSuccess = {
                                if (BuildConfig.ENABLE_DEBUG) {
                                    Log.i(TAG, "doc page rendered page=$page elements=${list.size} " +
                                        "page=${pageW}x$pageH target=${screenW}x$screenH → $outPath")
                                }
                                finish(outPath, null)
                            },
                            onFailure = { finish(null, it.message ?: it.javaClass.simpleName) },
                        )
                    }
                }
            }
        }
    }

    internal fun withLayerPreview(
        ctx: ReactApplicationContext,
        path: String,
        page: Int,
        stem: String,
        elements: List<Element>,
        livePicturePreview: (() -> String?)? = null,
        onReady: (String) -> Unit,
    ) {
        val needsPreview = elements.any {
            it.type == Element.TRAIL_TYPE_PICTURE &&
                (livePicturePreview != null ||
                    it.picture?.picturePath?.let { p -> p.isEmpty() || !File(p).canRead() } != false)
        }
        if (!needsPreview) return onReady("")

        if (livePicturePreview != null) {
            renderExecutor.execute {
                val preview = runCatching { livePicturePreview() }.getOrNull()
                postMain {
                    if (preview != null) onReady(preview)
                    else withLayerPreview(ctx, path, page, stem, elements, null, onReady)
                }
            }
            return
        }
        val previewPath = hostOutputFile("${stem}_layer.png")
        callHost(ctx, "generateLayerPreviewImage", listOf(path, page, 0, previewPath)) { v0, _ ->
            if (v0 == true && File(previewPath).isFile) return@callHost onReady(previewPath)
            callHost(ctx, "generateLayerPreviewImage", listOf(path, page, -1, previewPath)) { v1, e1 ->
                if (v1 == true && File(previewPath).isFile) {
                    onReady(previewPath)
                } else {
                    Log.w(TAG, "layer preview unavailable: $e1, pictures skipped")
                    File(previewPath).delete()
                    onReady("")
                }
            }
        }
    }

    fun loadPageElements(
        ctx: ReactApplicationContext,
        path: String,
        page: Int,
        onLoaded: (List<Element>?, String?, () -> Unit) -> Unit,
    ) = loadElements(ctx, path, page, emptyIsOk = true, onLoaded = onLoaded)

    private fun loadElements(
        ctx: ReactApplicationContext,
        path: String,
        page: Int,
        emptyIsOk: Boolean = false,
        attempt: Int = 0,
        onLoaded: (List<Element>?, String?, () -> Unit) -> Unit,
    ) {
        var loaded: List<Element>? = null
        val released = AtomicBoolean(false)
        val release: () -> Unit = {
            if (released.compareAndSet(false, true)) releaseElements(ctx, loaded.orEmpty())
        }
        fun deliver(elements: List<Element>?, error: String?) {
            loaded = elements
            postMain { onLoaded(elements, error, release) }
        }
        fun retryOrFail(message: String) {
            if (isForegroundDenied(message) && attempt + 1 < FOREGROUND_RETRY_COUNT) {
                mainHandler.postDelayed({
                    loadElements(ctx, path, page, emptyIsOk, attempt + 1, onLoaded)
                }, FOREGROUND_RETRY_MS)
            } else {
                deliver(null, message)
            }
        }
        runNative(ctx) {
            try {
                val pluginApp = getPluginApp(ctx)
                val checkMethod = pluginApp.javaClass.methods.firstOrNull {
                    it.name == "checkTrailCache" && it.parameterCount == 0
                }
                if (checkMethod?.invoke(pluginApp) == false) {
                    deliver(null, TRAIL_CACHE_BUSY)
                    return@runNative
                }
                val hostClass = Class.forName(HOST_API_CLASS)
                val host = hostClass.getMethod("getInstance").invoke(null)
                    ?: throw IllegalStateException("HostCommonAPI instance is empty")
                val callbackClass = Class.forName(HOST_CALLBACK_CLASS)
                val hostMethod = hostClass.methods.firstOrNull { m ->
                    m.name == "getElements" &&
                        m.parameterCount == 4 &&
                        compatible(m.parameterTypes[0], pluginApp) &&
                        compatible(m.parameterTypes[1], page) &&
                        compatible(m.parameterTypes[2], path) &&
                        m.parameterTypes[3].isAssignableFrom(callbackClass)
                } ?: throw NoSuchMethodException("HostCommonAPI.getElements/3")
                val readMethod = pluginApp.javaClass.methods.firstOrNull {
                    it.name == "readElementFromFile" && it.parameterCount == 1
                } ?: throw NoSuchMethodException("PluginApp.readElementFromFile")

                val proxy = Proxy.newProxyInstance(
                    callbackClass.classLoader, arrayOf(callbackClass),
                ) { proxyValue, method, values ->
                    when (method.name) {
                        "onResponse" -> {
                            val response = values?.firstOrNull() as? PluginAPIResponse
                            if (response == null) {
                                deliver(null, "host callback empty")
                            } else if (!response.isSuccess) {
                                retryOrFail(response.error?.message ?: response.toString())
                            } else {
                                val tempPath = response.result as? String
                                if (tempPath.isNullOrEmpty()) {
                                    if (emptyIsOk) deliver(emptyList(), null)
                                    else deliver(null, "element path empty")
                                } else {
                                    try {

                                        @Suppress("UNCHECKED_CAST")
                                        val elements = readMethod.invoke(pluginApp, tempPath) as? List<Element>
                                        if (elements == null) deliver(null, "elements deserialize empty")
                                        else deliver(elements, null)
                                    } catch (e: Throwable) {
                                        deliver(null, messageOf(e))
                                    }
                                }
                            }
                            null
                        }
                        "toString" -> "InklingElementCallback(getElements)"
                        "hashCode" -> System.identityHashCode(proxyValue)
                        "equals" -> proxyValue === values?.firstOrNull()
                        else -> null
                    }
                }
                hostMethod.invoke(host, pluginApp, page, path, proxy)
            } catch (error: Throwable) {
                retryOrFail(messageOf(error))
            }
        }
    }

    private fun releaseElements(ctx: ReactApplicationContext, elements: List<Element>) {
        if (elements.isEmpty()) return
        try {
            ctx.runOnNativeModulesQueueThread {
                try {
                    val pluginApp = pluginAppCache ?: getPluginApp(ctx)
                    val methods = pluginApp.javaClass.methods.filter {
                        it.name == "removeTrail" && it.parameterCount == 1
                    }

                    val byElement = methods.firstOrNull { it.parameterTypes[0] == Element::class.java }
                    val byUuid = methods.firstOrNull { it.parameterTypes[0] == String::class.java }
                    elements.forEach { element ->
                        if (byElement != null) byElement.invoke(pluginApp, element)
                        else if (byUuid != null) byUuid.invoke(pluginApp, element.uuid)
                    }
                } catch (error: Throwable) {
                    Log.w(TAG, "releaseElements: ${messageOf(error)}")
                } finally {
                    elements.forEach { runCatching { it.recycle() } }
                }
            }
        } catch (error: Throwable) {
            elements.forEach { runCatching { it.recycle() } }
            Log.w(TAG, "releaseElements queue: ${messageOf(error)}")
        }
    }

    internal fun callHost(
        ctx: ReactApplicationContext,
        methodName: String,
        args: List<Any?>,
        attempt: Int = 0,
        callback: (Any?, String?) -> Unit,
    ) {
        fun retryOrFail(message: String) {
            if (isForegroundDenied(message) && attempt + 1 < FOREGROUND_RETRY_COUNT) {
                mainHandler.postDelayed({ callHost(ctx, methodName, args, attempt + 1, callback) }, FOREGROUND_RETRY_MS)
            } else {
                postMain { callback(null, message) }
            }
        }
        runNative(ctx) {
            try {
                val pluginApp = getPluginApp(ctx)
                val hostClass = Class.forName(HOST_API_CLASS)
                val host = hostClass.getMethod("getInstance").invoke(null)
                    ?: throw IllegalStateException("HostCommonAPI instance is empty")
                val callbackClass = Class.forName(HOST_CALLBACK_CLASS)
                val hostMethod = hostClass.methods.firstOrNull { method ->
                    method.name == methodName &&
                        method.parameterCount == args.size + 2 &&
                        compatible(method.parameterTypes.first(), pluginApp) &&
                        args.indices.all { index -> compatible(method.parameterTypes[index + 1], args[index]) } &&
                        method.parameterTypes.last().isAssignableFrom(callbackClass)
                } ?: throw NoSuchMethodException("HostCommonAPI.$methodName/${args.size}")

                val proxy = Proxy.newProxyInstance(
                    callbackClass.classLoader, arrayOf(callbackClass),
                ) { proxyValue, method, values ->
                    when (method.name) {
                        "onResponse" -> {
                            val response = values?.firstOrNull() as? PluginAPIResponse
                            when {
                                response == null -> postMain { callback(null, "host callback empty") }
                                response.isSuccess -> postMain { callback(response.result, null) }
                                else -> retryOrFail(response.error?.message ?: response.toString())
                            }
                            null
                        }
                        "toString" -> "InklingHostCallback($methodName)"
                        "hashCode" -> System.identityHashCode(proxyValue)
                        "equals" -> proxyValue === values?.firstOrNull()
                        else -> null
                    }
                }
                hostMethod.invoke(host, *(listOf(pluginApp) + args + proxy).toTypedArray())
            } catch (error: Throwable) {
                retryOrFail(messageOf(error))
            }
        }
    }

    internal fun getPluginApp(ctx: ReactApplicationContext): Any {
        pluginAppCache?.let { return it }
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

    private fun runNative(ctx: ReactApplicationContext, block: () -> Unit) {
        ctx.runOnNativeModulesQueueThread(block)
    }

    fun resizeLassoRectAfterInsert(
        ctx: ReactApplicationContext,
        rect: ReadableMap,
        onDone: (Boolean, String?) -> Unit,
    ) {
        val delays = longArrayOf(120L, 300L, 600L, 1000L)
        val finished = AtomicBoolean(false)
        fun finish(ok: Boolean, error: String?) {
            if (finished.compareAndSet(false, true)) postMain { onDone(ok, error) }
        }
        fun attempt(index: Int) {
            mainHandler.postDelayed({
                callHost(ctx, "resizeLassoRect", listOf(rect, null)) { value, error ->
                    val ok = error == null && (value == null || value == true)
                    Log.i(TAG, "[CLIP-DBG/NATIVE] resizeLassoRect attempt=${index + 1} " +
                        "ok=$ok error=${error ?: "none"}")
                    if (ok) finish(true, null)
                    else if (index + 1 < delays.size) attempt(index + 1)
                    else finish(false, error)
                }
            }, delays[index])
        }
        attempt(0)
    }

    private fun postMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    private fun compatible(type: Class<*>, value: Any?): Boolean {
        if (value == null) return !type.isPrimitive
        val boxed = when (type) {
            java.lang.Integer.TYPE -> Integer::class.java
            java.lang.Double.TYPE -> java.lang.Double::class.java
            java.lang.Float.TYPE -> java.lang.Float::class.java
            java.lang.Long.TYPE -> java.lang.Long::class.java
            java.lang.Boolean.TYPE -> java.lang.Boolean::class.java
            java.lang.Short.TYPE -> java.lang.Short::class.java
            java.lang.Byte.TYPE -> java.lang.Byte::class.java
            java.lang.Character.TYPE -> java.lang.Character::class.java
            else -> type
        }
        return boxed.isInstance(value)
    }

    internal fun samePath(first: String?, second: String): Boolean {
        if (first.isNullOrEmpty()) return false
        fun canonical(path: String) = runCatching { File(path).canonicalPath }.getOrDefault(path)
        return canonical(first) == canonical(second)
    }

    private fun isForegroundDenied(message: String?): Boolean =
        message?.contains("not allowed to use this API", ignoreCase = true) == true

    private fun messageOf(error: Throwable): String {
        val actual = if (error is InvocationTargetException && error.targetException != null) error.targetException else error
        return actual.message ?: actual.javaClass.simpleName
    }

    private fun isNotePath(path: String): Boolean =
        path.endsWith(".note", ignoreCase = true) || path.endsWith(".mark", ignoreCase = true)

    private fun hostOutputFile(name: String): String {
        val dir = HOST_OUTPUT_DIR
        if (!dir.exists()) dir.mkdirs()
        return File(dir, name).absolutePath
    }

    private fun decodeFile(path: String): Bitmap? = try {
        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 })
    } catch (_: Throwable) { null }

    internal fun clampCrop(crop: Rect, width: Int, height: Int): Rect {
        val x = crop.left.coerceIn(0, width - 1)
        val y = crop.top.coerceIn(0, height - 1)
        val w = crop.width().coerceIn(1, width - x)
        val h = crop.height().coerceIn(1, height - y)
        return Rect(x, y, x + w, y + h)
    }

    internal fun writeRegionPng(bitmap: Bitmap, crop: Rect?, outPath: String) {
        val region = crop?.let { clampCrop(it, bitmap.width, bitmap.height) }
        val out = try {
            region?.let { Bitmap.createBitmap(bitmap, it.left, it.top, it.width(), it.height()) } ?: bitmap
        } catch (error: Throwable) {
            bitmap.recycle()
            throw error
        }
        if (out !== bitmap) bitmap.recycle()
        writePng(out, outPath)
    }

    private fun writeBaseRegion(basePath: String, width: Int, height: Int, crop: Rect, outPath: String): Boolean {
        @Suppress("DEPRECATION")
        val decoder = runCatching { BitmapRegionDecoder.newInstance(basePath, false) }.getOrNull() ?: return false
        return try {
            if (decoder.width != width || decoder.height != height) return false
            val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
            val region = decoder.decodeRegion(clampCrop(crop, width, height), options) ?: return false
            val opaque = Bitmap.createBitmap(region.width, region.height, Bitmap.Config.ARGB_8888)
            Canvas(opaque).apply { drawColor(Style.paper); drawBitmap(region, 0f, 0f, null) }
            region.recycle()
            writePng(opaque, outPath)
            true
        } catch (error: Exception) {
            Log.w(TAG, "base region decode failed: ${messageOf(error)}")
            false
        } finally {
            decoder.recycle()
        }
    }

    private fun writePng(bitmap: Bitmap, outPath: String) {
        try {
            File(outPath).parentFile?.mkdirs()
            val ok = FileOutputStream(outPath).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            check(ok && File(outPath).length() > 0L) { "PNG encode failed" }
        } finally {
            bitmap.recycle()
        }
    }
}

object VectorPageRenderer {

    private const val MARKER_WASH_ALPHA = 0.45f

    private const val GEOMETRY_PEN_WIDTH_SCALE = 100f
    private const val FIVE_STAR_THICKNESS_SCALE = 100f
    private const val TITLE_DARK_GRAY = 0x9D
    private const val TITLE_LIGHT_GRAY = 0xC9
    private const val TITLE_HATCH_STEP = 10f
    private const val LINK_STROKE_WIDTH = 3f
    private const val LINK_ICON_SIZE = 42f
    private const val LINK_ICON_OFFSET = 50f
    private const val TEXT_FRAME_RADIUS = 8f
    private const val LINK_ICON_PATH_1 =
        "M17.408,14.592a5.855,5.855 45,0 1,0 8.28l-2.3,2.3 -2.3,2.3a5.855,5.855 0,0 1,-8.28,0a5.855,5.855 0,0 1,0,-8.28l2.3,-2.3 2.3,-2.3"
    private const val LINK_ICON_PATH_2 =
        "M14.592,17.408a5.855,5.855 0,0 1,0,-8.28l2.3,-2.3 2.3,-2.3a5.855,5.855 0,0 1,8.28,0a5.855,5.855 0,0 1,0,8.28l-2.3,2.3 -2.3,2.3"

    private const val TAG = "VectorPageRenderer"

    fun render(
        elements: List<Element>,
        pageW: Int,
        pageH: Int,
        targetW: Int,
        targetH: Int,
        background: Bitmap? = null,
        colorMode: Boolean = false,
        layerPreviewPath: String = "",
    ): Bitmap {
        require(pageW > 0 && pageH > 0) { "invalid page size: ${pageW}x$pageH" }
        require(targetW > 0 && targetH > 0) { "invalid target size: ${targetW}x$targetH" }
        val scaleX = targetW.toFloat() / pageW
        val scaleY = targetH.toFloat() / pageH
        val bitmap = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Style.paper)
            if (background != null) {
                canvas.drawBitmap(
                    background,
                    null,
                    Rect(0, 0, targetW, targetH),
                    Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG),
                )
            }

            var layerPreview: Bitmap? = null
            if (layerPreviewPath.isNotEmpty() && File(layerPreviewPath).exists()) {
                layerPreview = decodeSampledBitmap(layerPreviewPath, targetW, targetH)
                Log.i(TAG, "layer preview for pictures: " +
                    "${layerPreview?.width}x${layerPreview?.height} from $layerPreviewPath")
            }
            try {

                canvas.save()
                canvas.scale(scaleX, scaleY)
                drawElementLayers(
                    canvas = canvas,
                    elements = elements,
                    pageW = pageW,
                    pageH = pageH,
                    scale = 1f,
                    colorMode = colorMode,
                    layerPreview = layerPreview,
                )
                canvas.restore()
            } finally {
                layerPreview?.recycle()
            }
            return bitmap
        } catch (error: Throwable) {
            bitmap.recycle()
            throw error
        }
    }

    internal fun drawElements(
        canvas: Canvas,
        elements: List<Element>,
        pageW: Int,
        pageH: Int,
        layerPreview: Bitmap? = null,
        contours: NotePageCoordinates? = null,
    ) = drawElementLayers(canvas, elements, pageW, pageH, 1f, colorMode = false, layerPreview = layerPreview, contours = contours)

    fun renderCard(element: Element, rect: Rect, pageW: Int, pageH: Int, layerPreview: Bitmap?): Bitmap? {
        val bitmap = Bitmap.createBitmap(rect.width(), rect.height(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.translate(-rect.left.toFloat(), -rect.top.toFloat())
        val drawn = try {
            when (element.type) {
                Element.TRAIL_TYPE_TILE -> { drawTitleBackground(canvas, element, 1f); true }
                Element.TRAIL_TYPE_LINK -> { drawLink(canvas, element, 1f, colorMode = false); true }
                Element.TRAIL_TYPE_PICTURE -> drawPicture(canvas, element, pageW, pageH, 1f, layerPreview)
                else -> false
            }
        } catch (error: Throwable) {
            bitmap.recycle()
            throw error
        }
        if (drawn) return bitmap
        bitmap.recycle()
        return null
    }

    private fun drawElementLayers(
        canvas: Canvas,
        elements: List<Element>,
        pageW: Int,
        pageH: Int,
        scale: Float,
        colorMode: Boolean,
        layerPreview: Bitmap?,
        contours: NotePageCoordinates? = null,
    ) {
        val titleStyleByTrail = HashMap<Int, Int>()
        elements.forEach { element ->
            if (element.type != Element.TRAIL_TYPE_TILE) return@forEach
            val title = element.title ?: return@forEach
            if (title.style !in 1..4) return@forEach
            title.controlTrailNums.orEmpty().forEach { trailNum ->
                if (trailNum >= 0) titleStyleByTrail[trailNum] = title.style
            }
            drawTitleBackground(canvas, element, scale)
        }

        elements.forEach { element ->
            val titleStyle = titleStyleByTrail[element.trailNumInPage]
                ?: titleStyleByTrail[element.trailNum]
            val titleColorOverride = titleStyle?.let(::titleInkColor)
            when (element.type) {
                Element.TRAIL_TYPE_STROKE -> drawStroke(
                    canvas, element, scale, colorMode, titleColorOverride, contours,
                )
                Element.TRAIL_TYPE_GEO -> drawGeometry(
                    canvas, element, scale, colorMode, titleColorOverride,
                )
                Element.TRAIL_TYPE_FIVE_STAR -> drawFiveStar(
                    canvas, element, pageW, pageH, scale, colorMode, titleColorOverride,
                )
                Element.TRAIL_TYPE_TEXT,
                Element.TRAIL_TYPE_TEXT_DIGEST_QUOTE,
                Element.TRAIL_TYPE_TEXT_DIGEST_CREATE -> drawText(
                    canvas, element, scale, colorMode, titleColorOverride,
                )
                Element.TRAIL_TYPE_PICTURE -> drawPicture(
                    canvas, element, pageW, pageH, scale, layerPreview,
                )
            }
        }

        elements.forEach { element ->
            if (element.type == Element.TRAIL_TYPE_LINK) {
                drawLink(canvas, element, scale, colorMode)
            }
        }
    }

    private fun drawTitleBackground(canvas: Canvas, element: Element, scale: Float) {
        val title = element.title ?: return
        val rect = RectF(
            title.x * scale,
            title.y * scale,
            (title.x + title.width) * scale,
            (title.y + title.height) * scale,
        )
        if (rect.width() <= 0f || rect.height() <= 0f) return
        when (title.style) {
            1 -> canvas.drawRect(rect, Paint().apply {
                style = Paint.Style.FILL
                color = Style.ink
            })
            2 -> canvas.drawRect(rect, Paint().apply {
                style = Paint.Style.FILL
                color = Style.rgb(TITLE_DARK_GRAY, TITLE_DARK_GRAY, TITLE_DARK_GRAY)
            })
            3 -> canvas.drawRect(rect, Paint().apply {
                style = Paint.Style.FILL
                color = Style.rgb(TITLE_LIGHT_GRAY, TITLE_LIGHT_GRAY, TITLE_LIGHT_GRAY)
            })
            4 -> {
                canvas.drawRect(rect, Paint().apply {
                    style = Paint.Style.FILL
                    color = Style.paper
                })
                val hatchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = (1.5f * scale).coerceAtLeast(1f)
                    strokeCap = Paint.Cap.SQUARE
                    color = Style.ink
                }
                canvas.save()
                canvas.clipRect(rect)
                var x = rect.left - rect.height()
                val step = TITLE_HATCH_STEP * scale
                while (x < rect.right) {
                    canvas.drawLine(x, rect.bottom, x + rect.height(), rect.top, hatchPaint)
                    x += step
                }
                canvas.restore()
            }
        }
    }

    private fun titleInkColor(style: Int): Int = when (style) {
        1, 2 -> Style.paper
        else -> Style.ink
    }

    private fun drawStroke(
        canvas: Canvas,
        element: Element,
        scale: Float,
        colorMode: Boolean,
        colorOverride: Int? = null,
        coordinates: NotePageCoordinates? = null,
    ) {
        val contours = element.contoursSrc ?: return
        if (contours.isEmpty()) return
        val path = Path().apply { fillType = Path.FillType.WINDING }
        contours.forEach { points ->
            if (points == null || points.size < 6) return@forEach
            path.moveTo(points[0] * scale, points[1] * scale)
            var index = 2
            while (index + 1 < points.size) {
                path.lineTo(points[index] * scale, points[index + 1] * scale)
                index += 2
            }
            path.close()
        }
        if (coordinates != null && (coordinates.scaleX != 1f || coordinates.scaleY != 1f)) {
            path.transform(Matrix().apply { setScale(coordinates.scaleX, coordinates.scaleY) })
        }

        val stroke = element.stroke
        val color = colorOverride ?: resolveColor(stroke?.penColor ?: 0, colorMode)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            if (colorOverride != null) {
                this.color = colorOverride
            } else if (stroke?.penType == PenSizeSpec.MARKER) {
                this.color = washColor(color)
                xfermode = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
            } else {
                this.color = color
            }
        }
        canvas.drawPath(path, paint)
    }

    private fun drawGeometry(
        canvas: Canvas,
        element: Element,
        scale: Float,
        colorMode: Boolean,
        colorOverride: Int? = null,
    ) {
        val geometry = element.geometry ?: return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = (geometry.penWidth / GEOMETRY_PEN_WIDTH_SCALE * scale)
                .coerceAtLeast(1f)
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = colorOverride ?: resolveColor(geometry.penColor, colorMode)
        }

        when (geometry.type) {
            "GEO_circle", "GEO_ellipse" -> {
                val center = geometry.ellipseCenterPoint ?: return
                val cx = center.x * scale
                val cy = center.y * scale
                val rx = geometry.ellipseMajorAxisRadius * scale
                val ry = geometry.ellipseMinorAxisRadius * scale
                canvas.save()
                canvas.rotate(geometry.ellipseAngle.toFloat(), cx, cy)
                canvas.drawOval(RectF(cx - rx, cy - ry, cx + rx, cy + ry), paint)
                canvas.restore()
            }
            else -> {
                val points = geometry.points ?: return
                if (points.size < 2) return
                val path = Path().apply {
                    moveTo(points[0].x * scale, points[0].y * scale)
                    for (index in 1 until points.size) {
                        lineTo(points[index].x * scale, points[index].y * scale)
                    }
                    if (geometry.type == "GEO_polygon" || geometry.type == "curvePolygon") close()
                }
                canvas.drawPath(path, paint)
            }
        }
    }

    private fun drawFiveStar(
        canvas: Canvas,
        element: Element,
        pageW: Int,
        pageH: Int,
        scale: Float,
        colorMode: Boolean,
        colorOverride: Int? = null,
    ) {
        val points = element.fiveStar?.points ?: return
        if (points.size < 6) return
        val first = emrToPage(
            points[0].toFloat(), points[1].toFloat(), pageW, pageH, element.maxX, element.maxY,
        ) ?: return
        val path = Path().apply { moveTo(first.x * scale, first.y * scale) }
        var index = 2
        while (index + 1 < points.size) {
            val point = emrToPage(
                points[index].toFloat(), points[index + 1].toFloat(), pageW, pageH,
                element.maxX, element.maxY,
            ) ?: return
            path.lineTo(point.x * scale, point.y * scale)
            index += 2
        }
        path.close()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = (element.thickness / FIVE_STAR_THICKNESS_SCALE * scale)
                .coerceAtLeast(1f)
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = colorOverride ?: resolveColor(0, colorMode)
        }
        canvas.drawPath(path, paint)
    }

    private fun drawText(
        canvas: Canvas,
        element: Element,
        scale: Float,
        colorMode: Boolean,
        textColorOverride: Int? = null,
    ) {
        val textBox = element.textBox ?: return
        val content = textBox.textContentFull ?: return
        if (content.isEmpty()) return
        val rect = textBox.textRect ?: return
        val frameRect = RectF(
            rect.left * scale,
            rect.top * scale,
            rect.right * scale,
            rect.bottom * scale,
        )
        if (frameRect.width() <= 0f || frameRect.height() <= 0f) return

        val frameStyle = textBox.textFrameStyle
        val frameWidth = ((textBox.textFrameWidth.takeIf { it > 0 } ?: 2) * scale)
            .coerceAtLeast(1f)
        drawTextFrame(canvas, frameRect, frameStyle, frameWidth, scale, textBox, colorMode)

        val framed = frameStyle in 1..3
        val paddingX = if (framed) maxOf(8f * scale, frameWidth * 1.5f) else 0f
        val paddingY = if (framed) maxOf(4f * scale, frameWidth) else 0f
        val textLeft = frameRect.left + paddingX
        val textTop = frameRect.top + paddingY
        val width = Math.round(frameRect.width() - paddingX * 2f)
        if (width <= 0) return

        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textColorOverride ?: resolveColor(textBox.textColor, colorMode)
            textSize = (textBox.fontSize * scale).coerceAtLeast(1f)
            letterSpacing = textBox.letterSpacing
            typeface = resolveTypeface(textBox.fontPath, textBox.textBold, textBox.textItalics)
        }
        if (element.type == Element.TRAIL_TYPE_TEXT_DIGEST_CREATE) {
            drawDigestCreateMarker(canvas, frameRect, content, textPaint, scale)
            return
        }
        val alignment = when (textBox.textAlign) {
            1 -> Layout.Alignment.ALIGN_CENTER
            2 -> Layout.Alignment.ALIGN_OPPOSITE
            else -> Layout.Alignment.ALIGN_NORMAL
        }
        val spacingMultiplier = textBox.lineSpacingMultiplier.takeIf { it > 0f } ?: 1f
        val spacingExtra = textBox.lineSpacingExtra * scale
        @Suppress("DEPRECATION")
        val layout = StaticLayout(
            content, textPaint, width, alignment, spacingMultiplier, spacingExtra, false,
        )
        canvas.save()
        canvas.translate(textLeft, textTop)
        layout.draw(canvas)
        canvas.restore()
    }

    private fun drawDigestCreateMarker(
        canvas: Canvas,
        rect: RectF,
        content: String,
        textPaint: TextPaint,
        scale: Float,
    ) {
        val leftBracket = "["
        val rightBracket = "]"
        val gap = maxOf(3f * scale, textPaint.textSize * 0.12f)
        val leftWidth = textPaint.measureText(leftBracket)
        val contentWidth = textPaint.measureText(content)
        val rightWidth = textPaint.measureText(rightBracket)
        val totalWidth = leftWidth + gap + contentWidth + gap + rightWidth
        val startX = rect.centerX() - totalWidth / 2f
        val metrics = textPaint.fontMetrics
        val baseline = rect.centerY() - (metrics.ascent + metrics.descent) / 2f
        val contentX = startX + leftWidth + gap
        val rightX = contentX + contentWidth + gap

        canvas.drawText(leftBracket, startX, baseline, textPaint)
        canvas.drawText(content, contentX, baseline, textPaint)
        canvas.drawText(rightBracket, rightX, baseline, textPaint)

        val underlineY = baseline + maxOf(2f * scale, textPaint.textSize * 0.07f)
        canvas.drawLine(
            contentX,
            underlineY,
            contentX + contentWidth,
            underlineY,
            Paint(textPaint).apply {
                style = Paint.Style.STROKE
                strokeWidth = maxOf(1.5f * scale, textPaint.textSize * 0.04f)
                strokeCap = Paint.Cap.SQUARE
            },
        )
    }

    private fun drawTextFrame(
        canvas: Canvas,
        rect: RectF,
        frameStyle: Int,
        frameWidth: Float,
        scale: Float,
        textBox: com.ratta.supernote.plugincommon.data.common.trail.TextBox,
        colorMode: Boolean,
    ) {
        val radius = TEXT_FRAME_RADIUS * scale
        if (frameStyle == 2) {
            canvas.drawRoundRect(rect, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = resolveColor(textBox.textFrameFillColor, colorMode)
            })
        }
        if (frameStyle in 1..3) {
            val inset = frameWidth / 2f
            val strokeRect = RectF(rect).apply { inset(inset, inset) }
            canvas.drawRoundRect(strokeRect, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = frameWidth
                color = resolveColor(textBox.textFrameStrokeColor, colorMode)
            })
        }
    }

    private fun drawLink(
        canvas: Canvas,
        element: Element,
        scale: Float,
        colorMode: Boolean,
    ) {
        val link = element.link ?: return
        val rect = RectF(
            link.x * scale,
            link.y * scale,
            (link.x + link.width) * scale,
            (link.y + link.height) * scale,
        )
        if (rect.width() <= 0f || rect.height() <= 0f) return

        val strokeWidth = (LINK_STROKE_WIDTH * scale).coerceAtLeast(1f)
        val decorationPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            this.strokeWidth = strokeWidth
            strokeCap = Paint.Cap.SQUARE
            strokeJoin = Paint.Join.MITER
            color = resolveColor(0, colorMode)
            if (link.style == 2) {
                pathEffect = DashPathEffect(floatArrayOf(8f * scale, 4f * scale), 0f)
            }
        }

        if (link.category == 0) {
            val content = link.showText.takeIf { it.isNotBlank() } ?: link.fullText.orEmpty()
            if (content.isNotEmpty()) {
                val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = resolveColor(0, colorMode)
                    textSize = (link.fontSize * scale).takeIf { it > 0f }
                        ?: (36f * scale).coerceAtLeast(1f)
                    typeface = resolveTypeface(link.fontPath, link.bold, link.italic)
                    isFakeBoldText = true
                }
                val metrics = textPaint.fontMetrics
                val baseline = rect.centerY() - (metrics.ascent + metrics.descent) / 2f
                canvas.drawText(content, rect.left + 10f * scale, baseline, textPaint)
            }
        }

        when (link.style) {
            0 -> canvas.drawLine(
                rect.left,
                rect.bottom - 2f * scale,
                rect.right,
                rect.bottom - 2f * scale,
                decorationPaint,
            )
            1, 2 -> canvas.drawRect(
                rect.left + 1f * scale,
                rect.top + 1f * scale,
                rect.right - 2f * scale,
                rect.bottom - 2f * scale,
                decorationPaint,
            )
        }

        val iconSize = LINK_ICON_SIZE * scale
        drawChainIcon(
            canvas,
            rect.right - LINK_ICON_OFFSET * scale,
            rect.bottom - LINK_ICON_OFFSET * scale,
            iconSize,
            colorMode,
        )
    }

    private fun drawChainIcon(
        canvas: Canvas,
        left: Float,
        top: Float,
        size: Float,
        colorMode: Boolean,
    ) {
        val first = PathParser.createPathFromPathData(LINK_ICON_PATH_1) ?: return
        val second = PathParser.createPathFromPathData(LINK_ICON_PATH_2) ?: return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = resolveColor(0, colorMode)
        }
        canvas.save()
        canvas.translate(left, top)
        canvas.scale(size / 32f, size / 32f)
        canvas.drawPath(first, paint)
        canvas.drawPath(second, paint)
        canvas.restore()
    }

    private fun resolveTypeface(pathValue: String?, bold: Int, italic: Int): Typeface {
        val style = when {
            bold == 1 && italic == 1 -> Typeface.BOLD_ITALIC
            bold == 1 -> Typeface.BOLD
            italic == 1 -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        val path = pathValue.orEmpty()
        return if (path.isNotEmpty() && File(path).exists()) {
            try {
                Typeface.create(Typeface.createFromFile(path), style)
            } catch (_: Exception) {
                Typeface.create(Typeface.DEFAULT, style)
            }
        } else {
            Typeface.create(Typeface.DEFAULT, style)
        }
    }

    private fun drawPicture(
        canvas: Canvas,
        element: Element,
        pageW: Int,
        pageH: Int,
        scale: Float,
        layerPreview: Bitmap?,
    ): Boolean {
        val picture = element.picture ?: return false
        val rect = picture.rect ?: return false

        val left = rect.left.toFloat()
        val top = rect.top.toFloat()
        val right = rect.right.toFloat()
        val bottom = rect.bottom.toFloat()
        val destination = RectF(left * scale, top * scale, right * scale, bottom * scale)
        if (destination.width() <= 0f || destination.height() <= 0f) {
            Log.w(TAG, "picture degenerate rect: l=${rect.left} t=${rect.top} r=${rect.right} b=${rect.bottom}")
            return false
        }

        val path = picture.picturePath.orEmpty()
        if (path.isNotEmpty() && File(path).exists()) {
            val bitmap = decodeSampledBitmap(
                path,
                destination.width().toInt().coerceAtLeast(1),
                destination.height().toInt().coerceAtLeast(1),
            )
            if (bitmap != null) {
                try {
                    canvas.drawBitmap(
                        bitmap, null, destination,
                        Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG),
                    )
                } finally {
                    bitmap.recycle()
                }
                return true
            }
            Log.w(TAG, "picture decode failed: $path")
        } else {
            Log.i(TAG, "picture path unreadable ($path), using layer preview crop")
        }

        val preview = layerPreview ?: return false
        val px = preview.width.toFloat() / pageW
        val py = preview.height.toFloat() / pageH
        val src = Rect(
            (left * px).toInt().coerceIn(0, preview.width),
            (top * py).toInt().coerceIn(0, preview.height),
            (right * px).toInt().coerceIn(0, preview.width),
            (bottom * py).toInt().coerceIn(0, preview.height),
        )
        if (src.width() <= 0 || src.height() <= 0) {
            Log.w(TAG, "picture preview crop empty, skipped")
            return false
        }
        canvas.drawBitmap(
            preview, src, destination,
            Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG),
        )
        return true
    }

    internal fun decodeSampledBitmap(path: String, targetW: Int, targetH: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (
            bounds.outWidth / (sample * 2) >= targetW &&
            bounds.outHeight / (sample * 2) >= targetH
        ) {
            sample *= 2
        }
        return BitmapFactory.decodeFile(
            path,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            },
        )
    }

    private fun emrToPage(
        x: Float,
        y: Float,
        pageW: Int,
        pageH: Int,
        elementMaxX: Int,
        elementMaxY: Int,
    ): PointF? {
        if (pageW <= 1 || pageH <= 1) return null
        val fallback = fallbackEmrMax(pageW, pageH)
        val maxX = elementMaxX.takeIf { it > 0 }?.toFloat() ?: fallback?.first ?: return null
        val maxY = elementMaxY.takeIf { it > 0 }?.toFloat() ?: fallback?.second ?: return null
        val sourceX = x / (maxX / (pageH - 1))
        val sourceY = y / (maxY / (pageW - 1))
        return PointF(pageW - 1f - sourceY, sourceX)
    }

    private fun fallbackEmrMax(pageW: Int, pageH: Int): Pair<Float, Float>? = when (pageW to pageH) {
        1920 to 2560 -> 21632f to 16224f
        1404 to 1872 -> 15819f to 11864f
        2560 to 3414 -> 28854f to 21632f
        1872 to 2496 -> 21098f to 15819f
        2560 to 1920 -> 16224f to 21632f
        1872 to 1404 -> 11864f to 15819f
        3414 to 2560 -> 21632f to 28854f
        2496 to 1872 -> 15819f to 21098f
        else -> null
    }

    private fun resolveColor(raw: Int, colorMode: Boolean): Int {
        val value = raw.toLong() and 0xFFFFFFFFL
        return if (value <= 0xFF) {
            val gray = value.toInt()
            Style.rgb(gray, gray, gray)
        } else if (colorMode) {
            (0xFF000000L or (value and 0x00FFFFFFL)).toInt()
        } else {
            val color = value.toInt()
            val luminance = (
                Color.red(color) * 77 + Color.green(color) * 150 + Color.blue(color) * 29
            ) shr 8
            Style.rgb(luminance, luminance, luminance)
        }
    }

    private fun washColor(color: Int): Int {
        val red = (255 - (255 - Color.red(color)) * MARKER_WASH_ALPHA).toInt()
        val green = (255 - (255 - Color.green(color)) * MARKER_WASH_ALPHA).toInt()
        val blue = (255 - (255 - Color.blue(color)) * MARKER_WASH_ALPHA).toInt()
        return Style.rgb(red, green, blue)
    }
}

internal class PageDisplayFrame(val width: Float, val height: Float, val source: String) {
    fun coordinates(pageWidth: Float, pageHeight: Float): NotePageCoordinates =
        NotePageCoordinates(pageWidth, pageHeight, width, height)

    override fun toString(): String = "${width.toInt()}x${height.toInt()}($source)"
}

internal object PageDisplayFrames {
    private const val TAG = "PageDisplayFrame"

    fun screenRotation(ctx: Context): Int = try {
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        wm.defaultDisplay.rotation
    } catch (_: Exception) {
        Surface.ROTATION_0
    }

    fun isLandscape(rotation: Int): Boolean =
        rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270

    fun naturalScreen(ctx: Context): Size = try {
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val (w, h) = if (Build.VERSION.SDK_INT >= 30) {
            wm.currentWindowMetrics.bounds.let { it.width() to it.height() }
        } else {
            @Suppress("DEPRECATION")
            Point().also { wm.defaultDisplay.getRealSize(it) }.let { it.x to it.y }
        }
        Size(min(w, h), max(w, h))
    } catch (_: Exception) {
        Size(ctx.resources.displayMetrics.widthPixels, ctx.resources.displayMetrics.heightPixels)
            .let { Size(min(it.width, it.height), max(it.width, it.height)) }
    }

    fun expected(pageWidth: Float, pageHeight: Float, screenLandscape: Boolean, natural: Size): SizeF {
        val short = natural.width.toFloat()
        val long = natural.height.toFloat()
        val pageLandscape = pageWidth > pageHeight
        val base = if (pageLandscape) SizeF(long, short) else SizeF(short, long)
        if (pageLandscape == screenLandscape) return base
        val grow = long / short
        return SizeF(base.width * grow, base.height * grow)
    }

    fun resolve(ctx: Context, hostValue: Any?, hostError: String?, pageWidth: Float, pageHeight: Float, label: String): PageDisplayFrame {
        val rotation = screenRotation(ctx)
        val landscape = isLandscape(rotation)
        val natural = naturalScreen(ctx)
        val computed = expected(pageWidth, pageHeight, landscape, natural)
        val host = when (hostValue) {
            is SizeF -> hostValue
            is Size -> SizeF(hostValue.width.toFloat(), hostValue.height.toFloat())
            else -> null
        }?.takeIf { it.width.isFinite() && it.height.isFinite() && it.width > 0f && it.height > 0f }
        val frame = if (host != null) PageDisplayFrame(host.width, host.height, "host")
            else PageDisplayFrame(computed.width, computed.height, "screen-rotation")
        val agrees = host != null && sameFrame(host, computed)
        Log.i(TAG, "[$label] display frame=$frame rotation=$rotation landscape=$landscape " +
            "screen=${natural.width}x${natural.height} page=${pageWidth.toInt()}x${pageHeight.toInt()} " +
            "computed=${computed.width.toInt()}x${computed.height.toInt()} host=${host?.let { "${it.width.toInt()}x${it.height.toInt()}" } ?: "none"}" +
            (if (hostError != null) " hostError=$hostError" else "") +
            (if (host != null && !agrees) " MISMATCH: host display size differs from the screen-rotation rule" else ""))
        return frame
    }

    private fun sameFrame(a: SizeF, b: SizeF): Boolean =
        abs(a.width - b.width) <= 2f && abs(a.height - b.height) <= 2f
}

internal class NotePageCoordinates(
    pageWidth: Float, pageHeight: Float, displayWidth: Float, displayHeight: Float,
) {
    init {
        require(listOf(pageWidth, pageHeight, displayWidth, displayHeight).all { it.isFinite() && it > 0f })
    }
    val scaleX = pageWidth / displayWidth
    val scaleY = pageHeight / displayHeight

    fun contour(value: Float, index: Int): Float = value * if (index % 2 == 0) scaleX else scaleY

    companion object {

        fun pagePixels(pageWidth: Float, pageHeight: Float): NotePageCoordinates =
            NotePageCoordinates(pageWidth, pageHeight, pageWidth, pageHeight)
    }
}

internal object SupernoteTextMarks {
    private val GRAYSCALE_HIGHLIGHT = Style.pageHighlightGray

    data class Rect(val x0: Float, val y0: Float, val x1: Float, val y1: Float)
    data class Mark(val page: Int, val type: Int, val rgb: Int, val opacity: Float,
                    val id: String, val contents: String, val rects: List<Rect>)
    data class Snapshot(val present: Boolean, val marks: List<Mark>)

    fun draw(canvas: Canvas, marks: List<Mark>, grayscale: Boolean = false) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val path = Path()
        for (mark in marks) {
            if (grayscale) {
                paint.color = if (mark.type == 0) GRAYSCALE_HIGHLIGHT else Style.ink
            } else {
                paint.color = 0xff000000.toInt() or mark.rgb
                paint.alpha = (mark.opacity * 255).toInt().coerceIn(0, 255)
            }

            paint.xfermode = if (mark.type == 0) PorterDuffXfermode(PorterDuff.Mode.DARKEN) else null
            paint.style = if (mark.type == 0) Paint.Style.FILL else Paint.Style.STROKE
            for (rect in mark.rects) {
                val height = rect.y1 - rect.y0
                if (mark.type == 0) {
                    val margin = height / 4.2425f
                    path.reset()
                    path.moveTo(rect.x0, rect.y1)
                    path.cubicTo(rect.x0 - margin, rect.y1 - margin, rect.x0 - margin, rect.y0 + margin, rect.x0, rect.y0)
                    path.lineTo(rect.x1, rect.y0)
                    path.cubicTo(rect.x1 + margin, rect.y0 + margin, rect.x1 + margin, rect.y1 - margin, rect.x1, rect.y1)
                    path.close()
                    canvas.drawPath(path, paint)
                } else {
                    paint.strokeWidth = height / 16f
                    val y = rect.y1 - height / 7f
                    canvas.drawLine(rect.x0, y, rect.x1, y, paint)
                }
            }
        }
    }

    fun read(file: File): Snapshot {
        if (!file.isFile) return Snapshot(false, emptyList())
        RandomAccessFile(file, "r").use { input ->
            require(input.length() >= 12) { "Truncated Supernote mark file" }
            fun uint(): Long = Integer.toUnsignedLong(Integer.reverseBytes(input.readInt()))
            fun block(offset: Long): String {
                require(offset >= 0 && offset <= input.length() - 4) { "Invalid mark record offset" }
                input.seek(offset)
                val size = uint()
                require(size <= 64L * 1024 * 1024 && size <= input.length() - input.filePointer) { "Invalid mark record size" }
                val bytes = ByteArray(size.toInt())
                input.readFully(bytes)
                return bytes.toString(Charsets.UTF_8)
            }
            fun pointer(record: String, key: String): Long? =
                Regex("<$key:(\\d+)>").find(record)?.groupValues?.get(1)?.toLong()
            input.seek(input.length() - 8)
            val magic = ByteArray(4).also { input.readFully(it) }
            require(magic.contentEquals("tail".toByteArray())) { "Unsupported Supernote mark footer" }
            val footer = block(uint())
            val feature = pointer(footer, "FILE_FEATURE") ?: error("Mark feature record missing")
            val highlight = pointer(block(feature), "HIGHLIGHTINFO") ?: 0
            if (highlight == 0L) return Snapshot(true, emptyList())
            val encoded = block(highlight)
            if (encoded.isBlank()) return Snapshot(true, emptyList())
            return Snapshot(true, parse(String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8)))
        }
    }

    fun parse(json: String): List<Mark> {
        val pages = JSONObject(json)
        val result = ArrayList<Mark>()
        for (key in pages.keys()) {
            val entries = pages.getJSONArray(key)
            for (index in 0 until entries.length()) {
                val entry = entries.getJSONObject(index)
                val type = entry.getInt("annotationType")
                require(type in 0..1) { "Unsupported text annotation type: $type" }
                val page = entry.optInt("page", key.toInt())
                require(page >= 0) { "Invalid annotation page" }
                val rectList = entry.getJSONArray("mupdfRectList")
                val rects = (0 until rectList.length()).map { r ->
                    val rect = rectList.getJSONObject(r)
                    Rect(rect.getDouble("x0").toFloat(), rect.getDouble("y0").toFloat(),
                        rect.getDouble("x1").toFloat(), rect.getDouble("y1").toFloat()).also {
                        require(listOf(it.x0, it.y0, it.x1, it.y1).all(Float::isFinite) && it.x1 > it.x0 && it.y1 > it.y0) {
                            "Invalid text annotation bounds"
                        }
                    }
                }
                if (rects.isEmpty()) continue

                val color = entry.optInt("colorType", if (type == 0) 0 else 4)
                val rgb = when (color) { 0 -> 0xffff00; 1 -> 0x00ff00; 2 -> 0xff0000; 3 -> 0x0000ff; else -> 0x000000 }
                val opacity = if (type == 1) 1f else when (color) { 0, 1 -> .4f; 2 -> .31f; 3 -> .5f; else -> .3f }
                val id = "notipal:text:$page:${entry.optLong("time", index.toLong())}:$index"
                result.add(Mark(page, type, rgb, opacity, id, entry.optString("annotationContent", ""), rects))
            }
        }
        return result.sortedBy { it.page }
    }
}

internal class GrayPngWriter(file: File, private val width: Int, private val height: Int) : Closeable {
    private val out = DataOutputStream(BufferedOutputStream(FileOutputStream(file), 64 * 1024))
    private val deflater = Deflater(6)
    private val idat = object : OutputStream() {
        private val buffer = ByteArrayOutputStream(65536)
        private fun drain() { if (buffer.size() > 0) { chunk("IDAT", buffer.toByteArray()); buffer.reset() } }
        override fun write(value: Int) { buffer.write(value); if (buffer.size() >= 65536) drain() }
        override fun write(b: ByteArray, off: Int, len: Int) { buffer.write(b, off, len); if (buffer.size() >= 65536) drain() }
        override fun close() = drain()
    }
    private val compressed = DeflaterOutputStream(idat, deflater, 65536)
    private val pixels = IntArray(width)
    private val row = ByteArray(1 + width)
    private var written = 0
    private var closed = false

    init {
        require(width > 0 && height > 0) { "invalid PNG size ${width}x$height" }
        out.write(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))
        val header = ByteArrayOutputStream().also { b ->
            DataOutputStream(b).apply { writeInt(width); writeInt(height); write(byteArrayOf(8, 0, 0, 0, 0)) }
        }.toByteArray()
        chunk("IHDR", header)
    }

    private fun chunk(type: String, bytes: ByteArray) {
        val tag = type.toByteArray(Charsets.US_ASCII)
        out.writeInt(bytes.size); out.write(tag); out.write(bytes)
        val crc = CRC32(); crc.update(tag); crc.update(bytes); out.writeInt(crc.value.toInt())
    }

    fun writeRows(bitmap: Bitmap, rows: Int) {
        check(!closed && written + rows <= height) { "PNG rows overflow" }
        require(bitmap.width == width && rows <= bitmap.height)
        for (y in 0 until rows) {
            bitmap.getPixels(pixels, 0, width, 0, y, width, 1)
            row[0] = 0
            for (x in 0 until width) {
                val p = pixels[x]
                row[x + 1] = ((((p ushr 16) and 255) * 299 + ((p ushr 8) and 255) * 587 + (p and 255) * 114) / 1000).toByte()
            }
            compressed.write(row)
        }
        written += rows
    }

    fun finish() {
        check(written == height) { "PNG incomplete: $written/$height rows" }
        compressed.close()
        chunk("IEND", byteArrayOf())
        out.flush()
        closed = true
        out.close()
        deflater.end()
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { out.close() }
        deflater.end()
    }

    companion object {
        fun write(file: File, bitmap: Bitmap) {
            GrayPngWriter(file, bitmap.width, bitmap.height).use {
                it.writeRows(bitmap, bitmap.height)
                it.finish()
            }
        }
    }
}
