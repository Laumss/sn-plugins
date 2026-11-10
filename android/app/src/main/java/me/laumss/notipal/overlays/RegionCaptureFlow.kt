package me.laumss.notipal.overlays
import me.laumss.notipal.NotipalDirs
import me.laumss.notipal.BuildConfig

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReactApplicationContext
import me.laumss.notipal.FloatingToolbarModule
import me.laumss.notipal.MosaicLink
import me.laumss.notipal.NativeLocale
import me.laumss.notipal.bubbles.FloatingBubbleModule
import me.laumss.notipal.bubbles.PaletteBubbleModule
import me.laumss.notipal.panels.DocScreenshotService
import me.laumss.notipal.panels.SendPanel
import me.laumss.notipal.ui_common.Dialog
import me.laumss.notipal.ui_common.ScreenScale
import me.laumss.notipal.relay.AIRelayCore
import me.laumss.notipal.render.PageImageSource
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import kotlin.concurrent.thread

object RegionCaptureFlow {

    private const val TAG = "RegionCaptureFlow"
    private val STAGE_DIR get() = NotipalDirs.aiCapture
    private const val REFRESH_WAIT_MS = 500L

    private const val RENDER_TIMEOUT_MS = 15_000L

    private val CROP_MODES = setOf("send", "docScreenshot", "mosaic")

    private val handler = Handler(Looper.getMainLooper())

    fun capture(
        ctx: ReactApplicationContext,
        toolbar: FloatingToolbarModule,
        mode: String,
        fromBubble: Boolean,
        left: Int, top: Int, right: Int, bottom: Int,
        expectedPage: PageImageSource.PageContext? = null,
    ) {
        val box = Rect(left, top, right, bottom)
        val crop = box.takeIf { mode in CROP_MODES }
        val timer = PageImageSource.StageTimer()
        val mosaicVisible = MosaicLink.isBoardVisible()

        val busy = if (mosaicVisible) null else BusyFrame(ctx, box)
        fun finish(result: () -> Unit) {
            handler.post {
                busy?.dismiss()
                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "[CAPTURE-TIMING] mode=$mode${timer.summary()}")
                result()
            }
        }
        fun retry() = finish {
            Dialog.tip(ctx, NativeLocale.t("capture_current_page_retry"))
            emitCloseAndRestore(toolbar, ctx, fromBubble, null)
        }
        thread(isDaemon = false) {
            if (expectedPage != null && mosaicVisible) {
                retry()
                return@thread
            }

            val srcPath = if (mosaicVisible) {
                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "Mosaic visible: screenshot instead of page render")
                screencapRegion(ctx, crop, busy, timer)
            } else {
                renderPage(ctx, expectedPage, crop, busy, timer) ?: if (mode == "ai") {

                    null
                } else {
                    if (BuildConfig.ENABLE_DEBUG) Log.w(TAG, "page render unavailable, falling back to screencap")
                    screencapRegion(ctx, crop, busy, timer)
                }
            }
            if (srcPath == null) {
                retry()
                return@thread
            }
            when (mode) {
                "send" -> finish {
                    toolbar.finishRegionCapture()
                    val sendPanel = SendPanel.getInstance(ctx, toolbar)
                    sendPanel.show(fromBubble)
                    sendPanel.resumeVisibility()
                    sendPanel.updateLassoData("", listOf(srcPath))
                }
                "docScreenshot" -> {
                    val saved = DocScreenshotService.moveToHistory(srcPath)
                    timer.mark("save")
                    finish {
                        Dialog.tip(ctx, NativeLocale.t(
                            if (saved != null) "added_to_doc_screenshots" else "screenshot_save_failed"))
                        emitCloseAndRestore(toolbar, ctx, fromBubble, null)
                    }
                }
                "mosaic" -> {
                    val queued = MosaicLink.enqueueImage(srcPath, "inkling-smart-lasso")
                    File(srcPath).delete()
                    timer.mark("enqueue")
                    finish {
                        if (!queued) {
                            Dialog.tip(ctx, NativeLocale.t("mosaic_insert_failed"))
                            emitCloseAndRestore(toolbar, ctx, fromBubble, null)
                        } else if (mosaicVisible) {

                            emitCloseAndRestore(toolbar, ctx, fromBubble, null)
                        } else {
                            emitCloseAndRestore(toolbar, ctx, fromBubble, null, restoreUi = false)
                            toolbar.openMosaicPluginForPending()
                        }
                    }
                }
                else -> {
                    val bbox = stageAndBroadcast(ctx, srcPath, left, top, right, bottom)
                    finish { emitCloseAndRestore(toolbar, ctx, fromBubble, bbox) }
                }
            }
        }
    }

    private fun renderPage(
        ctx: ReactApplicationContext,
        expectedPage: PageImageSource.PageContext?,
        crop: Rect?,
        busy: BusyFrame?,
        timer: PageImageSource.StageTimer,
    ): String? {
        val outPath = "${ctx.cacheDir.absolutePath}/region_page_${System.nanoTime()}.png"
        return PageImageSource.renderCurrentPageBlocking(
            ctx, outPath, expectedPage, RENDER_TIMEOUT_MS,
            livePicturePreview = { cleanScreencap(ctx, busy) },
            crop = crop, timer = timer,
        ).also { if (it == null) timer.mark("renderFailed") }
    }

    private fun cleanScreencap(ctx: ReactApplicationContext, busy: BusyFrame?): String? {
        busy?.setVisible(false)
        try { Thread.sleep(REFRESH_WAIT_MS) } catch (_: InterruptedException) {}
        return runScreencap(ctx).also { busy?.setVisible(true) }
    }

    private fun screencapRegion(
        ctx: ReactApplicationContext,
        crop: Rect?,
        busy: BusyFrame?,
        timer: PageImageSource.StageTimer,
    ): String? {
        val full = cleanScreencap(ctx, busy)
        timer.mark("screencap")
        if (full == null || crop == null) return full
        return try {
            val bitmap = BitmapFactory.decodeFile(full) ?: return null
            val outPath = "${ctx.cacheDir.absolutePath}/region_crop_${System.nanoTime()}.png"
            PageImageSource.writeRegionPng(bitmap, crop, outPath)
            outPath
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "screencap crop failed: ${e.message}", e)
            null
        } finally {
            File(full).delete()
            timer.mark("crop")
        }
    }

    private fun runScreencap(ctx: ReactApplicationContext): String? {
        return try {
            val ts = System.currentTimeMillis()
            val outPath = "${ctx.cacheDir.absolutePath}/region_screenshot_$ts.png"
            val proc = Runtime.getRuntime().exec(arrayOf("screencap", "-p", outPath))
            val exit = proc.waitFor()
            val f = File(outPath)
            if (exit == 0 && f.exists() && f.length() > 500) outPath else null
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "screencap EX: ${e.message}", e)
            null
        }
    }

    private fun decodeDims(path: String): Pair<Int, Int>? {
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, opts)
            if (opts.outWidth > 0 && opts.outHeight > 0) opts.outWidth to opts.outHeight else null
        } catch (_: Exception) { null }
    }

    private fun stageAndBroadcast(
        ctx: ReactApplicationContext, srcPath: String,
        left: Int, top: Int, right: Int, bottom: Int,
    ): IntArray? {
        val core = AIRelayCore.get(ctx)
        if (!core.isEnabled()) {
            if (BuildConfig.ENABLE_DEBUG) Log.w(TAG, "stageAndBroadcast ignored: AIRelay disabled")
            return null
        }
        return try {
            val dir = File(STAGE_DIR)
            if (!dir.exists()) dir.mkdirs()

            val dims = decodeDims(srcPath) ?: return null
            val ts = System.currentTimeMillis()
            val destImg = "$STAGE_DIR/$ts.png"
            val destMask = "$STAGE_DIR/$ts.mask.json"

            File(srcPath).copyTo(File(destImg), overwrite = true)

            val polyArr = JSONArray()
            for (p in listOf(left to top, right to top, right to bottom, left to bottom)) {
                polyArr.put(JSONObject().apply { put("x", p.first); put("y", p.second) })
            }
            val boxObj = JSONObject().apply {
                put("x", left); put("y", top)
                put("w", right - left); put("h", bottom - top)
            }
            val mask = JSONObject().apply {
                put("imageWidth", dims.first); put("imageHeight", dims.second)
                put("boundingBox", boxObj); put("polygon", polyArr); put("ts", ts)
            }
            FileOutputStream(destMask).use { it.write(mask.toString().toByteArray(Charsets.UTF_8)) }

            core.start()

            if (core.submitImageQuery(destImg, destMask, "") == AIRelayCore.ImageSubmit.SENDING) intArrayOf(left, top, right, bottom)
            else null
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "stageAndBroadcast failed: ${e.message}", e)
            null
        }
    }

    fun emitCloseAndRestore(
        toolbar: FloatingToolbarModule,
        ctx: ReactApplicationContext,
        fromBubble: Boolean,
        bbox: IntArray?,
        restoreUi: Boolean = true,
    ) {
        toolbar.finishRegionCapture()
        me.laumss.notipal.LexiLink.release("docCapture")
        toolbar.emitEvent("onNativePanelClose",
            Arguments.createMap().apply {
                putString("panel", "lassoScreenshot")
                putBoolean("cameFromBubble", fromBubble)
                if (bbox != null) {
                    putMap("screenshotBbox", Arguments.createMap().apply {
                        putInt("left", bbox[0])
                        putInt("top", bbox[1])
                        putInt("right", bbox[2])
                        putInt("bottom", bbox[3])
                    })
                }
            })
        handler.postDelayed({
            if (!restoreUi) return@postDelayed

            toolbar.restoreToolbar()
            if (fromBubble) {
                FloatingBubbleModule.reshowLast(ctx)
                PaletteBubbleModule.reshowLast(ctx)
            }
        }, 350)
    }
}

private class BusyFrame(ctx: Context, private val box: Rect) {
    private val main = Handler(Looper.getMainLooper())
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val label = TextView(ctx).apply {
        text = NativeLocale.t("region_capture_busy")
        textSize = 24f
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.BLACK)
        val pad = ScreenScale.dp(ctx, 12)
        setPadding(pad * 2, pad, pad * 2, pad)
    }
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = ScreenScale.dp(ctx, 2f).toFloat()
        pathEffect = DashPathEffect(
            floatArrayOf(ScreenScale.dp(ctx, 16).toFloat(), ScreenScale.dp(ctx, 10).toFloat()), 0f)
    }
    private val root = object : FrameLayout(ctx) {
        init { setWillNotDraw(false) }

        override fun onDraw(canvas: Canvas) = canvas.drawRect(box, framePaint)

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val w = label.measuredWidth
            val h = label.measuredHeight
            val x = (box.centerX() - w / 2).coerceIn(0, (width - w).coerceAtLeast(0))
            val y = (box.centerY() - h / 2).coerceIn(0, (height - h).coerceAtLeast(0))
            label.layout(x, y, x + w, y + h)
        }
    }
    private var shown = false

    init {
        root.addView(label, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        onMain {
            @Suppress("DEPRECATION")
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply { gravity = Gravity.TOP or Gravity.START }
            shown = runCatching { wm.addView(root, lp) }.isSuccess
        }
    }

    fun setVisible(visible: Boolean) = onMain {
        root.visibility = if (visible) View.VISIBLE else View.INVISIBLE
    }

    fun dismiss() = onMain {
        if (shown) runCatching { wm.removeView(root) }
        shown = false
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }
}
