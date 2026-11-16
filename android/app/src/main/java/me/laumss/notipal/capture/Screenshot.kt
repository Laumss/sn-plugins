package me.laumss.notipal.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.SizeF
import android.widget.Toast
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReactApplicationContext
import com.ratta.supernote.plugincommon.data.common.trail.Element
import me.laumss.notipal.BuildConfig
import me.laumss.notipal.common.App
import me.laumss.notipal.common.Dirs
import me.laumss.notipal.common.FeatureModule
import me.laumss.notipal.common.Log
import me.laumss.notipal.common.Main
import me.laumss.notipal.common.PluginIdentity
import me.laumss.notipal.common.Prefs
import me.laumss.notipal.common.Strings
import me.laumss.notipal.host.GrayPngWriter
import me.laumss.notipal.host.HostPlugins
import me.laumss.notipal.host.HostSession
import me.laumss.notipal.host.NotePageCoordinates
import me.laumss.notipal.host.PageDisplayFrames
import me.laumss.notipal.host.PageRender
import me.laumss.notipal.host.SupernoteTextMarks
import me.laumss.notipal.host.VectorPageRenderer
import me.laumss.notipal.image.CropResult
import me.laumss.notipal.image.ImageHooks
import me.laumss.notipal.image.ScreenshotDirs
import me.laumss.notipal.lasso.LassoHooks
import me.laumss.notipal.mosaic.MosaicLink
import me.laumss.notipal.toolbar.SettingKey
import me.laumss.notipal.toolbar.Settings
import me.laumss.notipal.toolbar.Toolbar
import me.laumss.notipal.ui_common.Dialogs
import me.laumss.notipal.ui_common.Style
import me.laumss.notipal.ui_common.WindowHooks
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object CaptureHooks {

    @Volatile var send: CaptureSend? = null

    @Volatile var aiRegion: ((path: String, box: Rect, wholeImage: Boolean) -> IntArray?)? = null
}

object CaptureSettings {

    val SEND_HD = SettingKey("capture_send_hd", false)
}

interface CaptureSend {

    fun ready(): Boolean

    fun hasDefaultPeer(): Boolean

    fun sendDirect(path: String, onDone: (Boolean) -> Unit)

    fun openPending(onShown: (Int?) -> Unit)

    fun fillPending(gen: Int, path: String?, onResult: (Boolean) -> Unit)

    fun openPanel(paths: List<String>, fromBubble: Boolean)
}

object ScreenshotStore {
    private const val TAG = "ScreenshotStore"

    private val STAGING_DIR: File get() = Dirs.docScreenshots
    private val QUEUE_DIR: File get() = ScreenshotDirs.queue
    private val QUEUE_META_FILE: File get() = File(QUEUE_DIR, "queue_meta.json")
    private val HISTORY_DIR: File get() = ScreenshotDirs.history
    private val SESSION_FILE: File get() = File(STAGING_DIR, "stitch_session.json")
    private val STITCH_IMAGES_DIR: File get() = File(STAGING_DIR, "stitch_images")
    private const val MAX_HISTORY = 20

    fun cropAndSave(srcPath: String, crop: CropResult, destPath: String, hd: Boolean = true): Boolean {
        var source: Bitmap? = null
        var cropped: Bitmap? = null
        return try {

            val rect = Rect(crop.offsetX, crop.offsetY, crop.offsetX + crop.width, crop.offsetY + crop.height)
            if (hd && DocShotHd.cropAndSave(srcPath, rect, destPath)) return true
            val t0 = System.currentTimeMillis()
            if (copyIfWholePng(srcPath, crop, destPath)) return true
            val output = StitchLayout.pendingCrop(srcPath, rect) ?: decodeRegion(srcPath, rect) ?: run {
                val decoded = BitmapFactory.decodeFile(srcPath) ?: return false
                source = decoded
                Bitmap.createBitmap(decoded, crop.offsetX, crop.offsetY, crop.width, crop.height)
            }
            cropped = output
            val t1 = System.currentTimeMillis()
            FileOutputStream(destPath).use { fos ->
                if (!output.compress(Bitmap.CompressFormat.PNG, 100, fos)) return false
            }
            if (BuildConfig.ENABLE_DEBUG) {
                Log.i(TAG, "cropAndSave ${crop.width}x${crop.height} decode=${t1 - t0}ms encode=${System.currentTimeMillis() - t1}ms")
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "cropAndSave failed: ${e.message}", e)
            false
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "cropAndSave out of memory", e)
            false
        } finally {
            if (cropped !== source) cropped?.recycle()
            source?.recycle()
        }
    }

    private fun decodeRegion(srcPath: String, rect: Rect): Bitmap? {
        val decoder = try {
            @Suppress("DEPRECATION")
            android.graphics.BitmapRegionDecoder.newInstance(srcPath, false)
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "region decoder unavailable: ${e.message}")
            null
        } ?: return null
        return try {
            decoder.decodeRegion(rect, null)
        } catch (e: IllegalArgumentException) {
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "region decode refused: ${e.message}")
            null
        } finally {
            decoder.recycle()
        }
    }

    private fun copyIfWholePng(srcPath: String, crop: CropResult, destPath: String): Boolean {
        if (crop.offsetX != 0 || crop.offsetY != 0) return false
        val size = StitchLayout.resultSize(srcPath) ?: run {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(srcPath, bounds)
            if (bounds.outMimeType != "image/png") return false
            bounds.outWidth to bounds.outHeight
        }
        if (crop.width != size.first || crop.height != size.second) return false
        if (!StitchLayout.awaitWritten(srcPath)) return false
        val t0 = System.currentTimeMillis()
        File(srcPath).copyTo(File(destPath), overwrite = true)
        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "whole image copied ${size.first}x${size.second} in ${System.currentTimeMillis() - t0}ms")
        return true
    }

    private fun ensureDirs(): Boolean {
        return try {
            for (dir in listOf(STAGING_DIR, QUEUE_DIR, HISTORY_DIR, STITCH_IMAGES_DIR)) {
                if (!dir.exists() && !dir.mkdirs()) return false
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "ensureDirs failed: ${e.message}", e)
            false
        }
    }

    private fun deleteQuietly(path: String, what: String) {
        if (!File(path).delete()) Log.w(TAG, "$what not deleted: $path")
    }

    fun stageToQueue(srcPath: String, crop: CropResult, insertNext: Boolean = false): String? {
        if (!ensureDirs()) return null
        val ts = System.currentTimeMillis()
        val dest = File(QUEUE_DIR, "$ts.png").path
        if (!cropAndSave(srcPath, crop, dest)) return null
        if (insertNext && !markInsertNext("$ts.png")) {
            deleteQuietly(dest, "unmarked queue file")
            return null
        }
        return dest
    }

    fun saveToHistory(srcPath: String, crop: CropResult): String? {
        if (!ensureDirs()) return null
        val ts = System.currentTimeMillis()
        val dest = File(HISTORY_DIR, "$ts.png").path
        val ok = cropAndSave(srcPath, crop, dest)
        if (ok) pruneHistory()
        return if (ok) dest else null
    }

    fun moveToHistory(pngPath: String): String? {
        val src = File(pngPath)
        val dest = File(HISTORY_DIR, "${System.currentTimeMillis()}.png")
        try {
            if (!ensureDirs()) return null
            src.copyTo(dest, overwrite = true)
            pruneHistory()
            return dest.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "moveToHistory failed: ${e.message}", e)
            dest.delete()
            return null
        } finally {
            src.delete()
        }
    }

    private fun loadQueueMeta(): MutableSet<String> {
        return try {
            val f = QUEUE_META_FILE
            if (!f.exists()) return mutableSetOf()
            val arr = JSONObject(f.readText()).optJSONArray("insertNext") ?: return mutableSetOf()
            val set = mutableSetOf<String>()
            for (i in 0 until arr.length()) set.add(arr.getString(i))
            set
        } catch (e: Exception) {
            Log.w(TAG, "queue meta unreadable: ${e.message}")
            mutableSetOf()
        }
    }

    private fun saveQueueMeta(set: Set<String>): Boolean {
        return try {
            if (!ensureDirs()) return false
            val json = JSONObject().apply {
                put("insertNext", JSONArray().apply { for (name in set) put(name) })
            }
            QUEUE_META_FILE.writeText(json.toString())
            true
        } catch (e: Exception) {
            Log.e(TAG, "saveQueueMeta failed: ${e.message}", e)
            false
        }
    }

    @Synchronized
    private fun markInsertNext(fileName: String): Boolean {
        val set = loadQueueMeta()
        set.add(fileName)
        return saveQueueMeta(set)
    }

    @Synchronized
    fun unmarkInsertNext(fileName: String) {
        val set = loadQueueMeta()
        if (set.remove(fileName)) saveQueueMeta(set)
    }

    fun firstInsertNextFile(): File? {
        return try {
            val set = loadQueueMeta()
            if (set.isEmpty()) return null
            val dir = QUEUE_DIR
            if (!dir.exists()) return null
            (dir.listFiles() ?: emptyArray())
                .filter { it.name.endsWith(".png") && set.contains(it.name) }
                .minByOrNull { it.name.removeSuffix(".png").toLongOrNull() ?: Long.MAX_VALUE }
        } catch (e: Exception) {
            Log.e(TAG, "firstInsertNextFile failed: ${e.message}", e)
            null
        }
    }

    private fun pruneHistory() {
        try {
            val files = HISTORY_DIR.listFiles()?.filter { it.name.endsWith(".png") }
                ?.sortedBy { it.name.removeSuffix(".png").toLongOrNull() ?: 0L } ?: return
            if (files.size > MAX_HISTORY) {
                files.take(files.size - MAX_HISTORY).forEach { it.delete() }
            }
        } catch (e: Exception) {
            Log.w(TAG, "history prune failed: ${e.message}")
        }
    }

    @Synchronized
    fun hasActiveSession(): Boolean {
        return try {
            SESSION_FILE.exists()
        } catch (e: Exception) {
            Log.e(TAG, "hasActiveSession failed: ${e.message}", e)
            false
        }
    }

    @Synchronized
    fun loadSession(): StitchSessionData? {
        return try {
            val f = SESSION_FILE
            if (!f.exists()) return null
            val json = JSONObject(f.readText())
            val imgs = json.getJSONArray("images")
            val imageList = mutableListOf<StitchImage>()
            for (i in 0 until imgs.length()) {
                val obj = imgs.getJSONObject(i)
                val img = StitchImage(
                    path = obj.getString("path"),
                    width = obj.getInt("width"),
                    height = obj.getInt("height"),
                    trimTop = obj.optInt("trimTop", 0),
                    overlap = obj.optInt("overlap", 0),
                )
                if (!File(img.path).exists()) return null
                imageList.add(img)
            }
            val p = json.getJSONObject("params")
            val params = StitchParams(
                mode = if (p.optString("mode") == StitchParams.MODE_GRID) StitchParams.MODE_GRID else StitchParams.MODE_STRIP,
                cols = p.optInt("cols", 2).coerceIn(2, 4),
                gridOrder = p.optString("gridOrder", "row"),
                cropTop = p.optDouble("cropTop", 0.0).toFloat(),
                cropBottom = p.optDouble("cropBottom", 0.0).toFloat(),
                cropLeft = p.optDouble("cropLeft", 0.0).toFloat(),
                cropRight = p.optDouble("cropRight", 0.0).toFloat(),
            )
            StitchSessionData(imageList, params, json.optLong("createdAt", 0))
        } catch (e: Exception) {
            Log.e(TAG, "loadSession failed: ${e.message}")
            null
        }
    }

    private fun saveSession(session: StitchSessionData): Boolean {
        return try {
            if (!ensureDirs()) return false
            val json = JSONObject().apply {
                put("createdAt", session.createdAt)
                put("params", JSONObject().apply {
                    put("mode", session.params.mode)
                    put("cols", session.params.cols)
                    put("gridOrder", session.params.gridOrder)
                    put("cropTop", session.params.cropTop.toDouble())
                    put("cropBottom", session.params.cropBottom.toDouble())
                    put("cropLeft", session.params.cropLeft.toDouble())
                    put("cropRight", session.params.cropRight.toDouble())
                })
                put("images", JSONArray().apply {
                    for (img in session.images) {
                        put(JSONObject().apply {
                            put("path", img.path)
                            put("width", img.width)
                            put("height", img.height)
                            put("trimTop", img.trimTop)
                            put("overlap", img.overlap)
                        })
                    }
                })
            }
            SESSION_FILE.writeText(json.toString())
            true
        } catch (e: Exception) {
            Log.e(TAG, "saveSession failed: ${e.message}", e)
            false
        }
    }

    @Synchronized
    fun startSession(imagePath: String, width: Int, height: Int): StitchSessionData? {
        return try {
            if (!ensureDirs()) return null
            val ts = System.currentTimeMillis()
            val dest = File(STITCH_IMAGES_DIR, "${ts}_0.png").path
            File(imagePath).copyTo(File(dest), overwrite = true)
            DocShotHd.follow(imagePath, dest)
            val session = StitchSessionData(
                images = mutableListOf(StitchImage(dest, width, height)),
                params = StitchParams(),
                createdAt = ts
            )
            if (saveSession(session)) session else {
                deleteQuietly(dest, "stitch image")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "startSession failed: ${e.message}", e)
            null
        }
    }

    @Synchronized
    fun addImage(imagePath: String, width: Int, height: Int): StitchSessionData? {
        return try {
            val session = loadSession() ?: return null
            if (!ensureDirs()) return null
            val ts = System.currentTimeMillis()
            val idx = session.images.size
            val dest = File(STITCH_IMAGES_DIR, "${ts}_$idx.png").path
            File(imagePath).copyTo(File(dest), overwrite = true)
            DocShotHd.follow(imagePath, dest)
            val added = StitchImage(dest, width, height)
            session.images.lastOrNull()?.let { prev ->
                StitchAligner.align(prev, added)?.let { seam ->
                    added.trimTop = seam.trimTop
                    added.overlap = seam.overlap
                }
            }
            session.images.add(added)
            if (saveSession(session)) session else {
                deleteQuietly(dest, "stitch image")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "addImage failed: ${e.message}", e)
            null
        }
    }

    @Synchronized
    fun clearSession(): Boolean {
        return try {
            var ok = true
            val sessionFile = SESSION_FILE
            if (sessionFile.exists() && !sessionFile.delete()) ok = false
            STITCH_IMAGES_DIR.listFiles()?.forEach { if (!it.delete()) ok = false }
            ok
        } catch (e: Exception) {
            Log.e(TAG, "clearSession failed: ${e.message}", e)
            false
        }
    }

    @Synchronized
    fun updateSession(session: StitchSessionData): Boolean = saveSession(session)

    fun getImageDimensions(path: String): Pair<Int, Int>? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, opts)
        return if (opts.outWidth > 0 && opts.outHeight > 0) Pair(opts.outWidth, opts.outHeight) else null
    }
}

object ScreenshotFlow {
    private const val TAG = "Screenshot"

    internal const val PREFS = "screenshot"
    private const val STITCH_SESSION_ACTIVE_KEY = "stitch_session_active"
    private const val STITCH_ARMED_AT_KEY = "stitch_armed_at"

    private const val STITCH_RECENT_MS = 15 * 60 * 1000L

    private const val DOC_CAPTURE_HIDE_WAIT_MS = 400L

    private const val DOC_RENDER_TIMEOUT_MS = 8_000L
    private const val SCREENCAP_TIMEOUT_S = 15L

    private const val REASON_CAPTURE = "doc-capture"

    private const val LEXI_OWNER = "docCapture"

    private var capturePending = false

    private var stitchCommitPending = false

    @Volatile private var knownStitchSessionActive = false

    private var yielding = false

    internal fun install() {
        knownStitchSessionActive = Prefs.of(PREFS).getBoolean(STITCH_SESSION_ACTIVE_KEY, knownStitchSessionActive)
    }

    private fun toast(key: String) {
        Main.post { Toast.makeText(App.context, Strings.get(key), Toast.LENGTH_LONG).show() }
    }

    fun start() {
        Main.post {
            if (stitchCommitPending || capturePending) {
                Log.i(TAG, "[CROP] capture ignored while another screenshot operation is pending")
                return@post
            }
            capturePending = true

            yielding = true
            Toolbar.yieldFor(REASON_CAPTURE)
            WindowHooks.lexiHold(LEXI_OWNER)
            Log.i(TAG, "[CROP] handleDocScreenshotCrop: starting capture")
            val cacheDir = App.context.cacheDir.absolutePath
            thread(isDaemon = false, name = "Screenshot-capture") {

                PluginIdentity.claim()
                try {
                    shoot(cacheDir)
                } catch (e: Exception) {
                    Log.e(TAG, "[CROP] screencap error: ${e.message}", e)
                    Main.post { captureFailed() }
                } catch (e: OutOfMemoryError) {
                    Log.e(TAG, "[CROP] screencap out of memory", e)
                    Main.post { captureFailed() }
                }
            }
        }
    }

    private fun captureFailed() {
        capturePending = false
        toast("screencap_failed")
        restoreAfterCropFlow()
    }

    private fun shoot(cacheDir: String) {
        val ts = System.currentTimeMillis()
        val outPath = "$cacheDir/screenshot_crop_$ts.png"

        val react = App.react
        val (completed, exitCode) = if (HostSession.isDocumentContext() && react != null &&
            DocShotHd.renderScreenBlocking(react, outPath, DOC_RENDER_TIMEOUT_MS)) {
            true to 0
        } else {

            MosaicLink.waitBeforeScreencap(DOC_CAPTURE_HIDE_WAIT_MS, "doc-crop")
            val process = Runtime.getRuntime().exec(arrayOf("screencap", "-p", outPath))
            val done = process.waitFor(SCREENCAP_TIMEOUT_S, TimeUnit.SECONDS)
            if (!done) process.destroyForcibly()
            done to (if (done) process.exitValue() else -1)
        }
        val file = File(outPath)
        Log.i(TAG, "[CROP] capture completed=$completed exit=$exitCode size=${file.length()}")
        if (!completed || exitCode != 0 || !file.exists() || file.length() <= 500) {
            Log.e(TAG, "[CROP] screencap failed")
            Main.post { captureFailed() }
            return
        }
        val dims = ScreenshotStore.getImageDimensions(outPath)
        if (dims == null) {
            Log.e(TAG, "[CROP] screencap decode failed")
            Main.post { captureFailed() }
            return
        }
        val imgW = dims.first
        val imgH = dims.second
        discardStaleStitchSession("doc crop enter")
        val mayHaveSession = knownStitchSessionActive && isRecentLongScreenshot()
        if (!mayHaveSession) {
            Main.post {
                capturePending = false
                openCropPanel(outPath, imgW, imgH)
            }
            return
        }
        Main.post {
            HostPlugins.requestStoragePermissions(
                what = "save captured stitch image",
                onGranted = {
                    thread(isDaemon = true, name = "Screenshot-stitch") {
                        PluginIdentity.claim()
                        val activeSession = ScreenshotStore.loadSession()
                        if (activeSession == null || activeSession.images.isEmpty()) {
                            Main.post {
                                capturePending = false
                                setKnownStitchSessionActive(false)
                                openCropPanel(outPath, imgW, imgH)
                            }
                            return@thread
                        }
                        val updated = ScreenshotStore.addImage(outPath, imgW, imgH)
                        Main.post {
                            capturePending = false
                            if (updated != null && updated.images.size >= 2) {
                                setKnownStitchSessionActive(true)
                                openStitchPanel(updated)
                            } else {
                                toast("screenshot_save_failed")
                                restoreAfterCropFlow()
                            }
                        }
                    }
                },
                onDenied = {
                    capturePending = false
                    restoreAfterCropFlow()
                },
            )
        }
    }

    private fun endYield() {
        if (!yielding) return
        yielding = false
        Toolbar.resume(REASON_CAPTURE)
    }

    private fun restoreAfterCropFlow() {
        WindowHooks.lexiRelease(LEXI_OWNER)
        endYield()
        Main.handler.postDelayed({ Toolbar.restore("crop flow done") }, HostSession.RESTORE_DELAY_SHORT)
    }

    private fun setKnownStitchSessionActive(active: Boolean) {
        knownStitchSessionActive = active
        val edit = Prefs.of(PREFS).edit().putBoolean(STITCH_SESSION_ACTIVE_KEY, active)
        if (active) edit.putLong(STITCH_ARMED_AT_KEY, System.currentTimeMillis())
        else edit.remove(STITCH_ARMED_AT_KEY)
        edit.apply()
    }

    private fun stitchArmedAt(): Long = Prefs.of(PREFS).getLong(STITCH_ARMED_AT_KEY, 0L)

    private fun isRecentLongScreenshot(): Boolean {
        if (!knownStitchSessionActive) return false
        val armedAt = stitchArmedAt()
        if (armedAt <= 0L) return false
        val age = System.currentTimeMillis() - armedAt
        if (age !in 0 until STITCH_RECENT_MS) return false
        return ScreenshotStore.hasActiveSession()
    }

    private fun discardStaleStitchSession(reason: String) {
        if (isRecentLongScreenshot()) {
            Log.i(TAG, "[CROP] keep stitch session reason=$reason ageMs=${System.currentTimeMillis() - stitchArmedAt()}")
            return
        }
        val had = knownStitchSessionActive || ScreenshotStore.hasActiveSession()
        if (!had) return
        Log.i(TAG, "[CROP] discard stale stitch session reason=$reason armedAt=${stitchArmedAt()}")
        ScreenshotStore.clearSession()
        setKnownStitchSessionActive(false)
    }

    private fun openCropPanel(
        screenshotPath: String, imgW: Int, imgH: Int,
        fromStitch: Boolean = false,
        onShown: ((Boolean) -> Unit)? = null,
    ) {
        val hasStitch = fromStitch || isRecentLongScreenshot()
        CropPanel.openWithFooter(
            path = screenshotPath,
            hasStitchSession = hasStitch,
            onConfirm = { crop, stayOpen ->
                Log.i(TAG, "[CROP] crop confirm (insertNext): ${crop.width}x${crop.height} multi=$stayOpen")
                HostPlugins.requestStoragePermissions(
                    what = "stage screenshot for insert",
                    onGranted = {
                        thread(isDaemon = true, name = "Screenshot-queue") {
                            PluginIdentity.claim()
                            val result = ScreenshotStore.stageToQueue(screenshotPath, crop, insertNext = true)
                            val clearedSession = result != null && fromStitch && ScreenshotStore.clearSession()
                            Main.post {
                                if (clearedSession) setKnownStitchSessionActive(false)
                                if (result == null) toast("screenshot_save_failed")
                                if (!stayOpen) restoreAfterCropFlow()
                            }
                        }
                    },
                    onDenied = { if (!stayOpen) restoreAfterCropFlow() },
                )
            },
            onLongScreenshot = { onLongScreenshot(screenshotPath, imgW, imgH, fromStitch) },
            onSendToOtherDevices = { crop -> sendCrop(screenshotPath, crop, fromStitch) },
            onAddToHistory = { crop, stayOpen ->
                Log.i(TAG, "[CROP] add to history: ${crop.width}x${crop.height} multi=$stayOpen")
                HostPlugins.requestStoragePermissions(
                    what = "save screenshot history",
                    onGranted = {
                        thread(isDaemon = true, name = "Screenshot-history") {
                            PluginIdentity.claim()
                            val result = ScreenshotStore.saveToHistory(screenshotPath, crop)
                            val clearedSession = result != null && fromStitch && ScreenshotStore.clearSession()
                            Main.post {
                                if (clearedSession) setKnownStitchSessionActive(false)
                                if (result == null) toast("screenshot_save_failed")
                                if (!stayOpen) restoreAfterCropFlow()
                            }
                        }
                    },
                    onDenied = { if (!stayOpen) restoreAfterCropFlow() },
                )
            },
            onCancel = {
                Log.i(TAG, "[CROP] crop cancel")
                if (fromStitch || knownStitchSessionActive) {
                    HostPlugins.requestStoragePermissions(
                        what = "clear long screenshot",
                        onGranted = {
                            thread(isDaemon = true, name = "Screenshot-clear") {
                                PluginIdentity.claim()
                                val cleared = ScreenshotStore.clearSession()
                                Main.post {
                                    if (cleared) setKnownStitchSessionActive(false)
                                    restoreAfterCropFlow()
                                }
                            }
                        },
                        onDenied = { restoreAfterCropFlow() },
                    )
                } else {
                    restoreAfterCropFlow()
                }
            },
            onShown = { shown ->

                if (shown) endYield()
                onShown?.invoke(shown)
                if (!shown) {
                    toast("screenshot_panel_failed")
                    restoreAfterCropFlow()
                }
            },
        )
    }

    private fun onLongScreenshot(screenshotPath: String, imgW: Int, imgH: Int, fromStitch: Boolean) {
        if (fromStitch) {
            Log.i(TAG, "[CROP] long screenshot: session kept, returning to capture")
            setKnownStitchSessionActive(true)
            CropPanel.close()
            restoreAfterCropFlow()
            return
        }
        if (stitchCommitPending) return
        stitchCommitPending = true

        CropPanel.beginHandoff()
        val handoff = CropPanel.handoffToken()
        fun closeCrop() {
            if (handoff == null || !CropPanel.finishHandoff(handoff, close = true)) CropPanel.close()
        }
        Log.i(TAG, "[CROP] long screenshot: committing stitch session")
        HostPlugins.requestStoragePermissions(
            what = "start long screenshot",
            onGranted = {
                thread(isDaemon = true, name = "Screenshot-session") {
                    PluginIdentity.claim()
                    val existing = ScreenshotStore.loadSession()
                    val session = if (existing != null) {
                        ScreenshotStore.addImage(screenshotPath, imgW, imgH)
                    } else {
                        ScreenshotStore.startSession(screenshotPath, imgW, imgH)
                    }
                    Main.post {
                        stitchCommitPending = false
                        if (session != null) {
                            setKnownStitchSessionActive(true)
                            if (existing != null && session.images.size >= 2) {
                                openStitchPanel(session) { shown ->
                                    if (shown) StitchPanel.afterFirstDraw { closeCrop() } else closeCrop()
                                }
                            } else {
                                closeCrop()
                                restoreAfterCropFlow()
                            }
                        } else {
                            closeCrop()
                            toast("screenshot_save_failed")
                            restoreAfterCropFlow()
                        }
                    }
                }
            },
            onDenied = {
                stitchCommitPending = false
                closeCrop()
                restoreAfterCropFlow()
            },
        )
    }

    private fun sendCrop(screenshotPath: String, crop: CropResult, fromStitch: Boolean) {
        val handoff = CropPanel.handoffToken() ?: return
        fun resumeCrop(reason: String) {
            if (CropPanel.finishHandoff(handoff, close = false)) Log.i(TAG, "[CROP-SEND] resume crop token=$handoff reason=$reason")
        }
        fun clearSentStitchSession() {
            if (!fromStitch) return
            thread(isDaemon = true, name = "Screenshot-clear") {
                PluginIdentity.claim()
                val cleared = ScreenshotStore.clearSession()
                if (cleared) Main.post { setKnownStitchSessionActive(false) }
            }
        }

        fun cropInBackground(onCropped: (String?) -> Unit) {
            thread(isDaemon = true, name = "Screenshot-send") {
                PluginIdentity.claim()
                val started = System.currentTimeMillis()
                val croppedPath = "${App.context.cacheDir.absolutePath}/doc_crop_send_$started.png"

                val hd = Settings.get(CaptureSettings.SEND_HD)
                val saved = ScreenshotStore.cropAndSave(screenshotPath, crop, croppedPath, hd)
                Log.i(TAG, "[CROP-SEND] crop saved=$saved hd=$hd elapsedMs=${System.currentTimeMillis() - started}")
                Main.post {
                    if (saved) {
                        onCropped(croppedPath)
                    } else {
                        File(croppedPath).delete()
                        toast("screenshot_save_failed")
                        onCropped(null)
                    }
                }
            }
        }
        Log.i(TAG, "[CROP-SEND] begin token=$handoff size=${crop.width}x${crop.height}")
        val sender = CaptureHooks.send
        if (sender == null) {
            Log.w(TAG, "[CROP-SEND] LocalSend not available yet")
            Dialogs.tip(Strings.get("feature_not_ready"))
            resumeCrop("unavailable")
            return
        }
        if (!sender.ready()) {
            resumeCrop("not ready")
            return
        }
        if (sender.hasDefaultPeer()) {

            if (!CropPanel.finishHandoff(handoff, close = true)) return
            Log.i(TAG, "[CROP-SEND] direct send in background token=$handoff")
            restoreAfterCropFlow()
            cropInBackground { path ->
                if (path == null) return@cropInBackground
                sender.sendDirect(path) { sent ->
                    Log.i(TAG, "[CROP-SEND] direct send done sent=$sent")
                    if (sent) clearSentStitchSession()
                }
            }
            return
        }
        sender.openPending { gen ->
            if (gen == null) {
                resumeCrop("panel")
                return@openPending
            }

            if (!CropPanel.finishHandoff(handoff, close = false)) Log.w(TAG, "[CROP-SEND] crop handoff already ended token=$handoff")
            Log.i(TAG, "[CROP-SEND] send panel open, cropping token=$handoff gen=$gen")
            cropInBackground { path ->

                sender.fillPending(gen, path) { filled ->
                    if (path == null) return@fillPending
                    if (filled) {
                        clearSentStitchSession()
                    } else {
                        Log.i(TAG, "[CROP-SEND] send panel gone before the image was ready; dropped")
                        File(path).delete()
                    }
                }
            }
        }
    }

    private fun openStitchPanel(session: StitchSessionData, onShown: ((Boolean) -> Unit)? = null) {
        StitchPanel.open(
            session = session,
            onConfirm = { finalSession ->
                Log.i(TAG, "[CROP] stitch confirm: compositing ${finalSession.images.size} images...")
                thread(isDaemon = false, name = "Screenshot-compose") {
                    PluginIdentity.claim()

                    if (!ScreenshotStore.updateSession(finalSession)) {
                        Main.post {
                            StitchPanel.close()
                            toast("screenshot_save_failed")
                            restoreAfterCropFlow()
                        }
                        return@thread
                    }
                    val outPath = "${App.context.cacheDir.absolutePath}/stitch_result_${System.currentTimeMillis()}.png"
                    val size = StitchLayout.render(finalSession, outPath)
                    Main.post {
                        if (size != null) {

                            openCropPanel(outPath, size.first, size.second, fromStitch = true) { shown ->
                                if (!shown) StitchPanel.close()
                            }
                        } else {
                            StitchPanel.close()
                            Log.e(TAG, "[CROP] stitch composite failed")
                            toast("screenshot_save_failed")
                            restoreAfterCropFlow()
                        }
                    }
                }
            },
            onContinue = { current ->
                Log.i(TAG, "[CROP] stitch continue: ${current.images.size} images kept")
                thread(isDaemon = true, name = "Screenshot-session") {
                    PluginIdentity.claim()
                    val saved = ScreenshotStore.updateSession(current)
                    Main.post {
                        StitchPanel.close()
                        if (saved) setKnownStitchSessionActive(true) else toast("screenshot_save_failed")
                        restoreAfterCropFlow()
                    }
                }
            },
            onCancel = {
                Log.i(TAG, "[CROP] stitch cancel: clearing session")
                thread(isDaemon = true, name = "Screenshot-clear") {
                    PluginIdentity.claim()
                    val cleared = ScreenshotStore.clearSession()
                    Main.post {
                        if (cleared) setKnownStitchSessionActive(false)
                        restoreAfterCropFlow()
                    }
                }
            },
            onShown = { shown ->
                if (shown) endYield()
                onShown?.invoke(shown)
                if (!shown) {
                    toast("screenshot_panel_failed")
                    restoreAfterCropFlow()
                }
            },
        )
    }
}

object Capture {
    @Volatile private var module: CaptureModule? = null

    fun install() {
        ImageHooks.documentTap = { ScreenshotFlow.start() }
        ImageHooks.pendingScreenshot = { ScreenshotStore.firstInsertNextFile() }
        ImageHooks.screenshotUsed = { name -> ScreenshotStore.unmarkInsertNext(name) }
        ImageHooks.crop = { path, onResult -> CropPanel.open(path, onResult) }
        LassoHooks.regionCapture = { mode, fromBubble, box -> RegionCapture.capture(mode, fromBubble, box) }
        LassoHooks.regionClosed = { fromBubble -> RegionCapture.closeAndRestore(fromBubble, null) }
        LassoHooks.regionLeft = { fromBubble -> emitRegionClosed(fromBubble, null) }
        ScreenshotFlow.install()
    }

    internal fun attach(owner: CaptureModule) {
        module = owner
    }

    internal fun detach(owner: CaptureModule) {
        if (module === owner) module = null
    }

    internal fun emitRegionClosed(fromBubble: Boolean, bbox: IntArray?) {
        val owner = module ?: return
        owner.emit("capture:regionClosed", Arguments.createMap().apply {
            putBoolean("fromBubble", fromBubble)
            if (bbox != null) {
                putMap("bbox", Arguments.createMap().apply {
                    putInt("left", bbox[0])
                    putInt("top", bbox[1])
                    putInt("right", bbox[2])
                    putInt("bottom", bbox[3])
                })
            }
        })
    }
}

class CaptureModule(ctx: ReactApplicationContext) : FeatureModule(ctx, "NotipalCapture") {

    init {
        Capture.attach(this)
    }

    override fun invalidate() {
        Capture.detach(this)
        super.invalidate()
    }
}

internal object DocShotHd {
    private const val TAG = "DocShotHd"
    private const val BAND_HEIGHT = 1024
    private const val TRAIL_CACHE_BUSY_RETRIES = 20

    private const val TWIN_WAIT_MS = 30_000L

    private const val KEEP_RECENT_TWINS = 3

    private const val KEEP_COMPOSITES = 4
    private const val SIDECAR_SUFFIX = ".hd.json"
    private const val TWIN_DIR = "doc_shot_hd"

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "Notipal-doc-hd").apply { isDaemon = true } }

    class Piece(val imagePath: String, val src: Rect, val dst: RectF)

    private class Twin(
        val path: String, val k: Float, val originX: Int, val originY: Int,
        val width: Int, val height: Int, val screenW: Int, val screenH: Int,
    )

    private class Job(val twinPath: String) {
        val done = CountDownLatch(1)

        var finished = false
        var twin: Twin? = null
    }

    private val lock = Any()

    private val jobs = HashMap<String, Job>()

    private val composites = LinkedHashMap<String, List<Piece>>()

    private class Layout(val screenW: Int, val screenH: Int, val ptW: Float, val ptH: Float) {
        val scale = min(screenW / ptW, screenH / ptH)
        val offsetX = ((screenW - (ptW * scale).toInt()) / 2).toFloat()
        val offsetY = ((screenH - (ptH * scale).toInt()) / 2).toFloat()
        val k = DocHd.hdScale(ptW, ptH) / scale

        override fun toString() = "screen=${screenW}x$screenH pt=${ptW}x$ptH scale=$scale offset=$offsetX,$offsetY k=$k"
    }

    private class Scene(
        val marks: List<SupernoteTextMarks.Mark>,
        val elements: List<Element>,
        val pageW: Float,
        val pageH: Float,
        val layer: Bitmap?,
        val contours: NotePageCoordinates,
    )

    fun renderScreenBlocking(ctx: ReactApplicationContext, outPath: String, timeoutMs: Long): Boolean {
        check(Looper.myLooper() != Looper.getMainLooper()) { "doc page rendering requires a worker" }
        val temp = File("$outPath.render")
        val latch = CountDownLatch(1)
        val state = Any()
        var accepted = true
        var written = false
        start(ctx, outPath, null, temp.path) { ok ->
            synchronized(state) {
                if (accepted && ok && temp.renameTo(File(outPath))) written = true else temp.delete()
            }
            latch.countDown()
        }
        try {
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) Log.w(TAG, "[DOC-HD] page render timed out after ${timeoutMs}ms")
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        val result = synchronized(state) {
            accepted = false
            written
        }
        if (!result) discard(outPath)
        return result
    }

    fun renderRegionBlocking(ctx: ReactApplicationContext, box: Rect, outPath: String): Boolean {
        check(Looper.myLooper() != Looper.getMainLooper()) { "doc region rendering requires a worker" }
        val key = "region-${System.nanoTime()}"
        start(ctx, key, Rect(box), null) {}
        return try {
            cropAndSave(key, box, outPath)
        } finally {
            discard(key)
        }
    }

    private fun start(
        ctx: ReactApplicationContext,
        key: String,
        window: Rect?,
        previewPath: String?,
        onPreview: (Boolean) -> Unit,
    ) {
        val dir = File(ctx.cacheDir, TWIN_DIR)
        val job = Job(File(dir, "${System.nanoTime()}.gray").path)
        synchronized(lock) { jobs[key] = job }
        val started = SystemClock.elapsedRealtime()
        val name = File(key).name
        val previewReported = AtomicBoolean(previewPath == null)
        fun reportPreview(ok: Boolean) {
            if (!previewReported.compareAndSet(false, true)) return
            if (ok) Log.i(TAG, "[DOC-HD] $name: page image ready elapsedMs=${SystemClock.elapsedRealtime() - started}")
            onPreview(ok)
        }
        worker.execute {
            PluginIdentity.claim()
            runCatching { prune(dir) }.onFailure { Log.w(TAG, "[DOC-HD] prune failed: ${it.message}") }
        }

        fun finish(twin: Twin?, reason: String?) = worker.execute {
            PluginIdentity.claim()
            reportPreview(false)
            if (twin == null) {
                File(job.twinPath).delete()
                Log.i(TAG, "[DOC-HD] $name: no hd image ($reason)")
            } else {
                Log.i(TAG, "[DOC-HD] $name: hd image ${twin.width}x${twin.height} k=${twin.k} " +
                    "origin=${twin.originX},${twin.originY} elapsedMs=${SystemClock.elapsedRealtime() - started}")
            }
            val paths = synchronized(lock) {
                job.twin = twin
                job.finished = true
                jobs.filterValues { it === job }.keys.toList()
            }
            if (twin != null) paths.forEach { writeSidecar(it, twin) }
            job.done.countDown()
        }

        PageRender.readCurrentContext(ctx) { source, contextError ->
            if (source == null) return@readCurrentContext finish(null, contextError ?: "page context unavailable")
            if (!source.filePath.endsWith(".pdf", ignoreCase = true)) {
                return@readCurrentContext finish(null, "not a pdf: ${File(source.filePath).name}")
            }

            PageRender.callHost(ctx, "getPageDisplaySize", emptyList()) { displayValue, displayError ->
                loadElements(ctx, source, 0) { elements, elementError, release ->
                    if (elements == null) {
                        release()
                        return@loadElements finish(null, elementError ?: "annotations unavailable")
                    }
                    val stem = "doc_hd_${System.nanoTime()}"
                    PageRender.withLayerPreview(ctx, source.filePath, source.pageNum, stem, elements) { layerPath ->
                        worker.execute {
                            PluginIdentity.claim()
                            val rendered = try {
                                runCatching {
                                    render(ctx, source, window, elements, layerPath, displayValue, displayError,
                                        previewPath, { reportPreview(true) }, job.twinPath)
                                }
                            } finally {
                                release()
                                if (layerPath.isNotEmpty()) runCatching { File(layerPath).delete() }
                            }
                            finish(rendered.getOrNull(), rendered.exceptionOrNull()?.let { messageOf(it) })
                        }
                    }
                }
            }
        }
    }

    fun follow(source: String, copy: String) {
        var twin: Twin? = null
        var pending = false
        synchronized(lock) {
            val job = jobs[source]
            if (job != null) {
                jobs[copy] = job
                if (job.finished) twin = job.twin else pending = true
            }
        }
        if (pending) return
        (twin ?: readSidecar(source))?.let { writeSidecar(copy, it) }
    }

    fun registerComposite(outPath: String, pieces: List<Piece>) {
        synchronized(lock) {
            composites[outPath] = pieces
            while (composites.size > KEEP_COMPOSITES) composites.remove(composites.keys.first())
        }
    }

    fun discard(screenshotPath: String) {
        val orphan = synchronized(lock) {
            val job = jobs.remove(screenshotPath) ?: return@synchronized null
            job.takeIf { it.finished && jobs.values.none { other -> other === it } }
        }
        sidecar(screenshotPath).delete()
        orphan?.let { File(it.twinPath).delete() }
    }

    fun cropAndSave(srcPath: String, crop: Rect, destPath: String): Boolean {
        check(Looper.myLooper() != Looper.getMainLooper()) { "hd crop requires a worker" }
        if (crop.isEmpty) return false
        val composite = synchronized(lock) { composites[srcPath] }
        val parts: List<Pair<Piece, Twin>> = if (composite == null) {
            val twin = twinFor(srcPath) ?: return false
            val whole = Rect(0, 0, twin.screenW, twin.screenH)
            listOf(Piece(srcPath, whole, RectF(whole)) to twin)
        } else {
            val area = RectF(crop)
            composite.filter { RectF.intersects(it.dst, area) }
                .map { piece -> piece to (twinFor(piece.imagePath) ?: return false) }
        }
        if (parts.isEmpty()) return false
        val started = SystemClock.elapsedRealtime()
        return try {
            compose(parts, crop, destPath)
            Log.i(TAG, "[DOC-HD] crop ${File(srcPath).name} $crop pieces=${parts.size} -> ${File(destPath).length()} bytes " +
                "elapsedMs=${SystemClock.elapsedRealtime() - started}")
            true
        } catch (e: Throwable) {
            Log.w(TAG, "[DOC-HD] crop from hd image failed, using the screenshot: ${messageOf(e)}")
            File(destPath).delete()
            false
        }
    }

    private fun render(
        ctx: Context,
        source: PageRender.PageContext,
        window: Rect?,
        elements: List<Element>,
        layerPath: String,
        displayValue: Any?,
        displayError: String?,
        previewPath: String?,
        onPreview: () -> Unit,
        twinPath: String,
    ): Twin = withPage(source.filePath, source.pageNum) { page ->
        val layout = Layout(source.screenWidth, source.screenHeight, page.width.toFloat(), page.height.toFloat())

        var pageW = source.pageWidth.toFloat()
        var pageH = source.pageHeight.toFloat()
        if (pageW != pageH && (pageW > pageH) != (layout.screenW > layout.screenH)) pageW = pageH.also { pageH = pageW }
        val label = "DOC-HD page=${source.pageNum + 1}"
        val marks = runCatching { SupernoteTextMarks.read(File("${source.filePath}.mark")).marks }
            .onFailure { Log.w(TAG, "[$label] text marks unreadable: ${it.message}") }
            .getOrDefault(emptyList())
            .filter { it.page == source.pageNum }
        val scene = Scene(
            marks, elements, pageW, pageH,
            layerPath.takeIf { it.isNotEmpty() }?.let { DocHd.decodeLayer(it, SizeF(pageW, pageH)) },
            PageDisplayFrames.resolve(ctx, displayValue, displayError, pageW, pageH, label).coordinates(pageW, pageH),
        )
        try {
            if (previewPath != null) {
                GrayPngWriter(File(previewPath), layout.screenW, layout.screenH).use { png ->
                    drawFrame(page, layout, scene, 1f, 0, 0, layout.screenW, layout.screenH) { band, rows -> png.writeRows(band, rows) }
                    png.finish()
                }
                onPreview()
            }
            val screen = Rect(0, 0, layout.screenW, layout.screenH)
            val area = Rect(window ?: screen)
            check(area.intersect(screen)) { "window $window outside the screen" }
            val k = layout.k
            val originX = floor(area.left * k).toInt()
            val originY = floor(area.top * k).toInt()
            val width = (ceil(area.right * k).toInt() - originX).coerceAtLeast(1)
            val height = (ceil(area.bottom * k).toInt() - originY).coerceAtLeast(1)
            val out = File(twinPath).apply { parentFile?.mkdirs() }
            try {
                val pixels = IntArray(width)
                val row = ByteArray(width)
                BufferedOutputStream(FileOutputStream(out), 1 shl 16).use { stream ->
                    drawFrame(page, layout, scene, k, originX, originY, width, height) { band, rows ->
                        for (r in 0 until rows) {
                            band.getPixels(pixels, 0, width, 0, r, width, 1)
                            for (x in 0 until width) row[x] = luma(pixels[x]).roundToInt().toByte()
                            stream.write(row)
                        }
                    }
                }
            } catch (e: Throwable) {
                out.delete()
                throw e
            }
            Log.i(TAG, "[$label] rendered ${width}x$height origin=$originX,$originY $layout " +
                "pageSize=${pageW.toInt()}x${pageH.toInt()} elements=${elements.size} marks=${marks.size}")
            Twin(twinPath, k, originX, originY, width, height, layout.screenW, layout.screenH)
        } finally {
            scene.layer?.recycle()
        }
    }

    private fun drawFrame(
        page: PdfRenderer.Page, layout: Layout, scene: Scene, factor: Float,
        originX: Int, originY: Int, width: Int, height: Int,
        sink: (Bitmap, Int) -> Unit,
    ) {
        val pdfScale = layout.scale * factor
        val matrix = Matrix()
        val band = Bitmap.createBitmap(width, min(BAND_HEIGHT, height), Bitmap.Config.ARGB_8888)
        try {
            var top = 0
            while (top < height) {
                val rows = min(band.height, height - top)
                val y0 = (originY + top).toFloat()
                band.eraseColor(Style.paper)
                matrix.setScale(pdfScale, pdfScale)
                matrix.postTranslate(layout.offsetX * factor - originX, layout.offsetY * factor - y0)
                page.render(band, Rect(0, 0, width, rows), matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                val canvas = Canvas(band)
                canvas.clipRect(0, 0, width, rows)
                canvas.translate(-originX.toFloat(), -y0)
                if (scene.marks.isNotEmpty()) {
                    val save = canvas.save()
                    canvas.translate(layout.offsetX * factor, layout.offsetY * factor)
                    canvas.scale(pdfScale, pdfScale)
                    SupernoteTextMarks.draw(canvas, scene.marks, grayscale = true)
                    canvas.restoreToCount(save)
                }
                if (scene.elements.isNotEmpty()) {
                    val save = canvas.save()
                    canvas.scale(factor * layout.screenW / scene.pageW, factor * layout.screenH / scene.pageH)
                    VectorPageRenderer.drawElements(canvas, scene.elements, scene.pageW.toInt(), scene.pageH.toInt(),
                        scene.layer, scene.contours)
                    canvas.restoreToCount(save)
                }
                sink(band, rows)
                top += rows
            }
        } finally {
            band.recycle()
        }
    }

    private fun luma(p: Int): Float =
        (((p shr 16) and 255) * 299 + ((p shr 8) and 255) * 587 + (p and 255) * 114) / 1000f

    private class Mapping(piece: Piece, twin: Twin, scale: Float, originX: Int, originY: Int) {
        private val fx = piece.dst.width() / piece.src.width()
        private val fy = piece.dst.height() / piece.src.height()
        val ax = fx * scale / twin.k
        val ay = fy * scale / twin.k
        val bx = snap(ax, (piece.dst.left + (twin.originX / twin.k - piece.src.left) * fx) * scale - originX)
        val by = snap(ay, (piece.dst.top + (twin.originY / twin.k - piece.src.top) * fy) * scale - originY)

        private fun snap(a: Float, b: Float) = if (abs(a - 1f) < 1e-4f) b.roundToInt().toFloat() else b
    }

    private fun compose(parts: List<Pair<Piece, Twin>>, crop: Rect, destPath: String) {
        val scale = parts.maxOf { it.second.k }

        val originX = floor(crop.left * scale).toInt()
        val originY = floor(crop.top * scale).toInt()
        val outW = max(1, ceil(crop.right * scale).toInt() - originX)
        val outH = max(1, ceil(crop.bottom * scale).toInt() - originY)

        val area = RectF(crop)
        for ((piece, twin) in parts) {
            val visible = RectF(piece.dst)
            if (!visible.intersect(area)) continue
            val t = twinRect(piece, twin, visible)
            check(t.left >= -1f && t.top >= -1f && t.right <= twin.width + 1f && t.bottom <= twin.height + 1f) {
                "crop $crop reaches outside the hd image of ${File(piece.imagePath).name}"
            }
        }
        val mappings = parts.map { (piece, twin) -> Mapping(piece, twin, scale, originX, originY) }
        val files = parts.map { it.second.path }.distinct().associateWith { RandomAccessFile(it, "r") }
        val band = Bitmap.createBitmap(outW, min(BAND_HEIGHT, outH), Bitmap.Config.ARGB_8888)
        var patch: Bitmap? = null
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        try {
            GrayPngWriter(File(destPath), outW, outH).use { png ->
                var top = 0
                while (top < outH) {
                    val rows = min(band.height, outH - top)
                    band.eraseColor(Style.paper)
                    val canvas = Canvas(band)

                    val bandArea = RectF(
                        crop.left.toFloat(), max(crop.top.toFloat(), (originY + top) / scale),
                        crop.right.toFloat(), min(crop.bottom.toFloat(), (originY + top + rows) / scale),
                    )
                    for ((index, part) in parts.withIndex()) {
                        val (piece, twin) = part
                        val visible = RectF(piece.dst)
                        if (!visible.intersect(bandArea)) continue
                        val t = twinRect(piece, twin, visible)
                        val ti = Rect(floor(t.left).toInt() - 1, floor(t.top).toInt() - 1, ceil(t.right).toInt() + 1, ceil(t.bottom).toInt() + 1)
                        if (!ti.intersect(0, 0, twin.width, twin.height)) continue
                        val previous = patch
                        val region = if (previous != null && previous.width >= ti.width() && previous.height >= ti.height()) {
                            previous
                        } else {
                            val w = max(ti.width(), previous?.width ?: 0)
                            val h = max(ti.height(), previous?.height ?: 0)
                            previous?.recycle()
                            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { patch = it }
                        }
                        readRegion(files.getValue(twin.path), twin, ti, region)
                        val m = mappings[index]
                        val save = canvas.save()
                        canvas.clipRect(
                            visible.left * scale - originX, visible.top * scale - originY - top,
                            visible.right * scale - originX, visible.bottom * scale - originY - top,
                        )
                        canvas.drawBitmap(region, Rect(0, 0, ti.width(), ti.height()),
                            RectF(m.ax * ti.left + m.bx, m.ay * ti.top + m.by - top,
                                m.ax * ti.right + m.bx, m.ay * ti.bottom + m.by - top),
                            paint)
                        canvas.restoreToCount(save)
                    }
                    png.writeRows(band, rows)
                    top += rows
                }
                png.finish()
            }
        } finally {
            band.recycle()
            patch?.recycle()
            files.values.forEach { runCatching { it.close() } }
        }
    }

    private fun twinRect(piece: Piece, twin: Twin, visible: RectF): RectF {
        val sx = piece.src.width() / piece.dst.width()
        val sy = piece.src.height() / piece.dst.height()
        fun tx(cx: Float) = (piece.src.left + (cx - piece.dst.left) * sx) * twin.k - twin.originX
        fun ty(cy: Float) = (piece.src.top + (cy - piece.dst.top) * sy) * twin.k - twin.originY
        return RectF(tx(visible.left), ty(visible.top), tx(visible.right), ty(visible.bottom))
    }

    private fun readRegion(file: RandomAccessFile, twin: Twin, region: Rect, patch: Bitmap) {
        val w = region.width()
        val bytes = ByteArray(w)
        val pixels = IntArray(w)
        for (y in 0 until region.height()) {
            file.seek((region.top + y).toLong() * twin.width + region.left)
            file.readFully(bytes, 0, w)
            for (x in 0 until w) {
                val g = bytes[x].toInt() and 255
                pixels[x] = (0xFF shl 24) or (g shl 16) or (g shl 8) or g
            }
            patch.setPixels(pixels, 0, w, 0, y, w, 1)
        }
    }

    private fun twinFor(path: String): Twin? {
        val job = synchronized(lock) { jobs[path] } ?: return readSidecar(path)
        val ready = try {
            job.done.await(TWIN_WAIT_MS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!ready) {
            Log.w(TAG, "[DOC-HD] hd image of ${File(path).name} not ready after ${TWIN_WAIT_MS}ms, using the screenshot")
            return null
        }
        return synchronized(lock) { job.twin }?.takeIf { File(it.path).isFile }
    }

    private fun sidecar(path: String) = File(path + SIDECAR_SUFFIX)

    private fun writeSidecar(path: String, twin: Twin) {
        runCatching {
            sidecar(path).writeText(JSONObject()
                .put("twin", twin.path).put("k", twin.k.toDouble())
                .put("originX", twin.originX).put("originY", twin.originY)
                .put("width", twin.width).put("height", twin.height)
                .put("screenW", twin.screenW).put("screenH", twin.screenH)
                .toString())
        }.onFailure { Log.w(TAG, "[DOC-HD] sidecar for ${File(path).name} not written: ${it.message}") }
    }

    private fun readSidecar(path: String): Twin? = runCatching {
        val file = sidecar(path)
        if (!file.isFile) return null
        val o = JSONObject(file.readText())
        Twin(o.getString("twin"), o.getDouble("k").toFloat(), o.getInt("originX"), o.getInt("originY"),
            o.getInt("width"), o.getInt("height"), o.getInt("screenW"), o.getInt("screenH"))
    }.getOrNull()?.takeIf { File(it.path).isFile }

    private fun prune(dir: File) {
        val keep = HashSet<String>()
        ScreenshotStore.loadSession()?.images?.forEach { image -> readSidecar(image.path)?.let { keep.add(it.path) } }
        synchronized(lock) { jobs.values.forEach { if (!it.finished) keep.add(it.twinPath) } }
        dir.listFiles { f -> f.name.endsWith(".gray") }
            ?.sortedByDescending { it.lastModified() }
            ?.filter { it.path !in keep }
            ?.drop(KEEP_RECENT_TWINS)
            ?.forEach { it.delete() }
        synchronized(lock) {
            jobs.values.removeAll { it.finished && it.twin?.let { twin -> File(twin.path).isFile } != true }
        }
    }

    private fun loadElements(
        ctx: ReactApplicationContext,
        page: PageRender.PageContext,
        attempt: Int,
        onLoaded: (List<Element>?, String?, () -> Unit) -> Unit,
    ) {
        PageRender.loadPageElements(ctx, page.filePath, page.pageNum) { elements, error, release ->
            if (elements == null && error == PageRender.TRAIL_CACHE_BUSY && attempt + 1 < TRAIL_CACHE_BUSY_RETRIES) {
                release()
                main.postDelayed({ loadElements(ctx, page, attempt + 1, onLoaded) }, PageRender.TRAIL_CACHE_BUSY_RETRY_MS)
            } else {
                onLoaded(elements, error, release)
            }
        }
    }

    private fun <T> withPage(pdfPath: String, pageNum: Int, block: (PdfRenderer.Page) -> T): T =
        ParcelFileDescriptor.open(File(pdfPath), ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                require(pageNum in 0 until renderer.pageCount) { "page ${pageNum + 1} out of range" }
                renderer.openPage(pageNum).use(block)
            }
        }

    private fun messageOf(error: Throwable): String = error.message ?: error.javaClass.simpleName
}

internal object DocHd {
    private const val HD_DPI = 600f
    const val HD_MAX_LONG_SIDE = 7200

    fun hdScale(ptW: Float, ptH: Float): Float = min(HD_DPI / 72f, HD_MAX_LONG_SIDE / max(ptW, ptH))

    fun decodeLayer(path: String, pageSize: SizeF?): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val target = pageSize?.width?.toInt()?.coerceAtLeast(1) ?: bounds.outWidth
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= target) sample *= 2
        return runCatching { BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample }) }.getOrNull()
    }
}
