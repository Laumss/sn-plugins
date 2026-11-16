package me.laumss.notipal.capture

import android.graphics.BitmapFactory
import android.graphics.Rect
import com.facebook.react.bridge.ReactApplicationContext
import me.laumss.notipal.BuildConfig
import me.laumss.notipal.common.App
import me.laumss.notipal.common.Log
import me.laumss.notipal.common.Main
import me.laumss.notipal.common.PluginIdentity
import me.laumss.notipal.common.Strings
import me.laumss.notipal.host.HostPlugins
import me.laumss.notipal.host.HostSession
import me.laumss.notipal.host.PageRender
import me.laumss.notipal.lasso.PenLasso
import me.laumss.notipal.mosaic.MosaicLink
import me.laumss.notipal.toolbar.Toolbar
import me.laumss.notipal.ui_common.BusyFrame
import me.laumss.notipal.ui_common.Dialogs
import me.laumss.notipal.ui_common.WindowHooks
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

object RegionCapture {
    private const val TAG = "RegionCaptureFlow"
    private const val REFRESH_WAIT_MS = 500L

    private const val RENDER_TIMEOUT_MS = 15_000L

    private const val SCREENCAP_TIMEOUT_S = 15L

    private const val RESTORE_DELAY_MS = 350L

    private val CROP_MODES = setOf("send", "docScreenshot", "mosaic")

    private val JPEG_MODES = setOf("ai", "send")
    private const val MOSAIC_SOURCE = "inkling-smart-lasso"

    fun capture(mode: String, fromBubble: Boolean, box: Rect, expectedPage: PageRender.PageContext? = null) {
        val crop = Rect(box).takeIf { mode in CROP_MODES }
        val timer = PageRender.StageTimer()
        val mosaicVisible = MosaicLink.boardVisible

        val busy = if (mosaicVisible) null else BusyFrame(Rect(box))
        fun finish(result: () -> Unit) {
            Main.post {
                busy?.dismiss()
                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "[CAPTURE-TIMING] mode=$mode${timer.summary()}")
                result()
            }
        }
        fun retry() = finish {
            Dialogs.tip(Strings.get("capture_current_page_retry"))
            closeAndRestore(fromBubble, null)
        }
        val ctx = App.react
        if (ctx == null) {
            Log.w(TAG, "capture mode=$mode: no React context")
            retry()
            return
        }
        thread(isDaemon = false, name = "RegionCapture") {

            PluginIdentity.claim()
            try {
                run(ctx, mode, fromBubble, box, crop, expectedPage, mosaicVisible, busy, timer, { finish(it) }, { retry() })
            } catch (e: Exception) {
                Log.e(TAG, "capture mode=$mode failed: ${e.message}", e)
                retry()
            } catch (e: OutOfMemoryError) {
                Log.e(TAG, "capture mode=$mode out of memory", e)
                retry()
            }
        }
    }

    private fun run(
        ctx: ReactApplicationContext,
        mode: String,
        fromBubble: Boolean,
        box: Rect,
        crop: Rect?,
        expectedPage: PageRender.PageContext?,
        mosaicVisible: Boolean,
        busy: BusyFrame?,
        timer: PageRender.StageTimer,
        finish: (() -> Unit) -> Unit,
        retry: () -> Unit,
    ) {
        if (expectedPage != null && mosaicVisible) {
            retry()
            return
        }

        var mosaicCrop = false

        val landscape = HostSession.screenWidth > HostSession.screenHeight
        val srcPath = if (mosaicVisible) {
            mosaicRegion(ctx, box, mode in JPEG_MODES, timer)?.also { mosaicCrop = true } ?: run {
                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "Mosaic did not render the region: screenshot")
                screencapRegion(ctx, crop, busy, timer)
            }
        } else if (landscape) {
            Log.i(TAG, "landscape ${HostSession.screenWidth}x${HostSession.screenHeight}: screenshot mode=$mode")
            screencapRegion(ctx, crop, busy, timer)
        } else {
            (if (mode == "docScreenshot" && HostSession.isDocumentContext())
                docScreenshotRegion(ctx, box, timer) else null)
                ?: renderPage(ctx, expectedPage, crop, busy, timer) ?: if (mode == "ai") {

                null
            } else {
                Log.w(TAG, "page render unavailable, falling back to screencap")
                screencapRegion(ctx, crop, busy, timer)
            }
        }
        if (srcPath == null) {
            retry()
            return
        }
        when (mode) {
            "send" -> finish {
                PenLasso.finishRegionCapture()
                val sender = CaptureHooks.send
                if (sender == null) {
                    Log.w(TAG, "send: LocalSend not available yet")
                    Dialogs.tip(Strings.get("feature_not_ready"))
                    File(srcPath).delete()
                    closeAndRestore(fromBubble, null)
                } else {
                    sender.openPanel(listOf(srcPath), fromBubble)
                }
            }
            "docScreenshot" -> {
                val saved = ScreenshotStore.moveToHistory(srcPath)
                timer.mark("save")
                finish {
                    Dialogs.tip(Strings.get(if (saved != null) "added_to_doc_screenshots" else "screenshot_save_failed"))
                    closeAndRestore(fromBubble, null)
                }
            }
            "mosaic" -> {
                val queued = MosaicLink.enqueueImage(srcPath, MOSAIC_SOURCE)
                File(srcPath).delete()
                timer.mark("enqueue")
                finish {
                    if (!queued) {
                        Dialogs.tip(Strings.get("mosaic_insert_failed"))
                        closeAndRestore(fromBubble, null)
                    } else if (mosaicVisible) {

                        closeAndRestore(fromBubble, null)
                    } else {
                        closeAndRestore(fromBubble, null, restoreUi = false)
                        HostPlugins.openMosaicPluginForPending()
                    }
                }
            }
            else -> {
                val hook = CaptureHooks.aiRegion
                val bbox = try {
                    if (hook == null) {
                        Log.w(TAG, "ai: relay not available yet")
                        null
                    } else {
                        hook(srcPath, Rect(box), mosaicCrop)
                    }
                } finally {

                    File(srcPath).delete()
                }
                finish {
                    if (hook == null) Dialogs.tip(Strings.get("feature_not_ready"))
                    closeAndRestore(fromBubble, bbox)
                }
            }
        }
    }

    private fun mosaicRegion(
        ctx: ReactApplicationContext, box: Rect, jpeg: Boolean, timer: PageRender.StageTimer,
    ): String? {
        val rendered = MosaicLink.captureRegion(box, jpeg, "region")
        timer.mark("mosaic")
        if (rendered == null) return null
        val source = File(rendered)
        val outPath = "${ctx.cacheDir.absolutePath}/region_mosaic_${System.nanoTime()}.${source.extension.ifEmpty { "png" }}"
        return try {
            source.copyTo(File(outPath), overwrite = true)
            outPath
        } catch (e: Exception) {
            Log.e(TAG, "Mosaic region copy failed: ${e.message}", e)
            null
        } finally {
            source.delete()
        }
    }

    private fun docScreenshotRegion(ctx: ReactApplicationContext, box: Rect, timer: PageRender.StageTimer): String? {
        val outPath = "${ctx.cacheDir.absolutePath}/region_hd_${System.nanoTime()}.png"
        val ok = DocShotHd.renderRegionBlocking(ctx, box, outPath)
        timer.mark(if (ok) "hd" else "hdUnavailable")
        return outPath.takeIf { ok }
    }

    private fun renderPage(
        ctx: ReactApplicationContext,
        expectedPage: PageRender.PageContext?,
        crop: Rect?,
        busy: BusyFrame?,
        timer: PageRender.StageTimer,
    ): String? {
        val outPath = "${ctx.cacheDir.absolutePath}/region_page_${System.nanoTime()}.png"
        return PageRender.renderCurrentPageBlocking(
            ctx, outPath, expectedPage, RENDER_TIMEOUT_MS,
            livePicturePreview = { cleanScreencap(ctx, busy) },
            crop = crop, timer = timer,
        ).also { if (it == null) timer.mark("renderFailed") }
    }

    private fun cleanScreencap(
        ctx: ReactApplicationContext,
        busy: BusyFrame?,
        timer: PageRender.StageTimer? = null,
    ): String? {
        busy?.setVisible(false)
        MosaicLink.waitBeforeScreencap(REFRESH_WAIT_MS, "region")
        timer?.mark("wait")
        return runScreencap(ctx).also { busy?.setVisible(true) }
    }

    private fun screencapRegion(
        ctx: ReactApplicationContext,
        crop: Rect?,
        busy: BusyFrame?,
        timer: PageRender.StageTimer,
    ): String? {
        val full = cleanScreencap(ctx, busy, timer)
        timer.mark("screencap")
        if (full == null || crop == null) return full
        return try {
            val bitmap = BitmapFactory.decodeFile(full) ?: return null
            val outPath = "${ctx.cacheDir.absolutePath}/region_crop_${System.nanoTime()}.png"
            PageRender.writeRegionPng(bitmap, crop, outPath)
            outPath
        } catch (e: Exception) {
            Log.e(TAG, "screencap crop failed: ${e.message}", e)
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

            if (!proc.waitFor(SCREENCAP_TIMEOUT_S, TimeUnit.SECONDS)) {
                proc.destroyForcibly()
                Log.w(TAG, "screencap timed out after ${SCREENCAP_TIMEOUT_S}s")
                File(outPath).delete()
                return null
            }
            val exit = proc.exitValue()
            val f = File(outPath)
            if (exit == 0 && f.exists() && f.length() > 500) outPath else null
        } catch (e: Exception) {
            Log.e(TAG, "screencap EX: ${e.message}", e)
            null
        }
    }

    fun closeAndRestore(fromBubble: Boolean, bbox: IntArray?, restoreUi: Boolean = true) {
        PenLasso.finishRegionCapture()
        WindowHooks.lexiRelease("docCapture")
        Capture.emitRegionClosed(fromBubble, bbox)
        Main.handler.postDelayed({
            if (restoreUi) Toolbar.restore("region capture closed")
        }, RESTORE_DELAY_MS)
    }
}
