package me.laumss.notipal

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.modules.core.DeviceEventManagerModule
import me.laumss.notipal.panels.RelayInboxPanel
import me.laumss.notipal.render.PageImageSource
import me.laumss.notipal.relay.AIRelayCore
import me.laumss.notipal.relay.RelayOpenTrace
import java.io.File
import java.io.FileOutputStream

class AIRelayModule(reactContext: ReactApplicationContext) : ReactContextBaseJavaModule(reactContext), AIRelayCore.Listener {
    override fun getName() = "AIRelayModule"

    init { NotipalDirs.attach(reactContext) }

    private val core by lazy { AIRelayCore.get(reactApplicationContext).also { it.addListener(this) } }

    override fun invalidate() {
        core.removeListener(this)
        super.invalidate()
    }

    private fun withExpectedPage(
        file: String, page: Int, width: Int, height: Int, onMismatch: () -> Unit,
        onMatch: (PageImageSource.PageContext) -> Unit,
    ) {
        PageImageSource.readCurrentContext(reactApplicationContext) { source, _ ->
            if (source == null || source.filePath != file || source.pageNum != page ||
                source.pageWidth != width || source.pageHeight != height || MosaicLink.isBoardVisible()) {
                onMismatch()
            } else {
                onMatch(source)
            }
        }
    }

    @ReactMethod
    fun capturePageRegion(
        left: Int, top: Int, right: Int, bottom: Int, margin: Int, outPath: String,
        expectedFile: String, expectedPage: Int, expectedWidth: Int, expectedHeight: Int, promise: Promise,
    ) = withExpectedPage(expectedFile, expectedPage, expectedWidth, expectedHeight, { promise.resolve(null) }) { source ->
        captureRenderedRegion(left, top, right, bottom, margin, outPath, source, promise)
    }

    private fun captureRenderedRegion(
        left: Int, top: Int, right: Int, bottom: Int, margin: Int, outPath: String,
        source: PageImageSource.PageContext, promise: Promise,
    ) {
        Thread {
            val temporary = File(reactApplicationContext.cacheDir, "formula_page_${System.nanoTime()}.png")
            var bitmap: Bitmap? = null
            var cropped: Bitmap? = null
            try {
                require(right > left && bottom > top && margin >= 0) { "invalid selection bounds" }
                val rendered = PageImageSource.renderCurrentPageBlocking(reactApplicationContext,
                    temporary.absolutePath, source) ?: run { promise.resolve(null); return@Thread }
                val page = BitmapFactory.decodeFile(rendered) ?: run { promise.resolve(null); return@Thread }
                bitmap = page
                val x = (left - margin).coerceIn(0, page.width - 1)
                val y = (top - margin).coerceIn(0, page.height - 1)
                val width = ((right + margin).coerceAtMost(page.width) - x).coerceAtLeast(1)
                val height = ((bottom + margin).coerceAtMost(page.height) - y).coerceAtLeast(1)
                val region = Bitmap.createBitmap(page, x, y, width, height)
                cropped = region
                File(outPath).parentFile?.mkdirs()
                check(FileOutputStream(outPath).use { region.compress(Bitmap.CompressFormat.PNG, 100, it) })
                promise.resolve(outPath)
            } catch (error: Exception) {
                runCatching { File(outPath).delete() }
                Log.e("AIRelayModule", "live page region capture ended", error)
                promise.resolve(null)
            } finally {
                cropped?.recycle()
                if (bitmap !== cropped) bitmap?.recycle()

                runCatching { temporary.delete() }
            }
        }.apply { name = "AIRelay-live-capture"; isDaemon = true }.start()
    }

    @ReactMethod
    fun prepareLivePageForSelection(
        expectedFile: String, expectedPage: Int, expectedWidth: Int, expectedHeight: Int, promise: Promise,
    ) = withExpectedPage(expectedFile, expectedPage, expectedWidth, expectedHeight, { promise.resolve(false) }) { source ->
        Thread {
            val temporary = File(reactApplicationContext.cacheDir, "selection_sync_${System.nanoTime()}.png")
            try {
                val rendered = PageImageSource.renderCurrentPageBlocking(
                    reactApplicationContext, temporary.absolutePath, source, 15_000L)
                promise.resolve(rendered != null)
            } catch (error: Exception) {
                Log.w("AIRelayModule", "prepareLivePageForSelection ended: ${error.message}")
                promise.resolve(false)
            } finally {
                runCatching { temporary.delete() }
            }
        }.apply { name = "AIRelay-selection-sync"; isDaemon = true }.start()
    }

    @ReactMethod
    fun startInboxListening() = emitInbox()

    @ReactMethod
    fun isRelayEnabled(promise: Promise) {
        promise.resolve(core.isEnabled())
    }

    @ReactMethod
    fun isWebBackend(promise: Promise) {
        promise.resolve(core.isWebMode())
    }

    @ReactMethod
    fun setRelayEnabled(enabled: Boolean, promise: Promise) {
        if (enabled) RelayOpenTrace.mark("turning relay on")
        promise.resolve(core.setEnabledByUser(enabled))
        if (enabled) RelayOpenTrace.mark("relay on")
    }

    override fun onInboxChanged(messages: List<me.laumss.notipal.relay.model.RelayMessage>) = emitInbox()

    override fun onInsertionRequested(request: AIRelayCore.InsertionRequest) {
        val emitter = reactApplicationContext.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
        when (request) {
            is AIRelayCore.InsertionRequest.Text -> emitter.emit("onTextFromRelay", request.text)
            is AIRelayCore.InsertionRequest.Blocks -> emitter.emit(
                if (request.replace) "onReplaceFromRelay" else "onBlocksFromRelay",
                Arguments.createMap().apply {
                    putString("text", request.text)
                    putString("blocks", request.blocks)
                    putBoolean("savedSelection", request.savedSelection)
                },
            )
        }
    }

    private fun emitInbox() {
        reactApplicationContext.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
            .emit("onInboxFromRelay", core.inboxIdsJson())
    }

    @ReactMethod
    fun sendInsertRequest(id: String) = core.insertMessage(id)

    @ReactMethod
    fun openRelayMain(promise: Promise) = openAirRelay(null, promise)

    @ReactMethod
    fun openRelayDetail(id: String, promise: Promise) = openAirRelay(id, promise)

    private fun openAirRelay(detailId: String?, promise: Promise) {
        val toolbar = FloatingToolbarModule.currentInstance
        if (toolbar == null) {
            promise.resolve(false)
            return
        }

        if (LexiLink.isOpen()) {
            Log.i("AIRelayModule", "not opening AIRelay${detailId?.let { " detail $it" }.orEmpty()}: Lexi window open")
            promise.resolve(false)
            return
        }
        toolbar.openPanel(detailId?.let { "airRelayDetail:$it" } ?: "airRelay")
        promise.resolve(true)
    }

    @ReactMethod
    fun sendQueryWithResult(text: String, promise: Promise) =
        core.submitQuery(text) { promise.resolve(it.name.lowercase()) }

    @ReactMethod

    fun pingRelay(promise: Promise) = promise.resolve(core.awaitConnected(3_000L))

    @ReactMethod
    fun sendFormulaSessionReset() = core.resetFormulaSession()

    @ReactMethod
    fun sendImageQueryKind(imagePath: String, maskPath: String, prompt: String, kind: String, promise: Promise) =
        promise.resolve(when (core.submitImageQuery(imagePath, maskPath, prompt, kind)) {
            AIRelayCore.ImageSubmit.STAGED -> "staged"
            AIRelayCore.ImageSubmit.SENDING -> "sent"
            AIRelayCore.ImageSubmit.NOT_CONNECTED -> "not_connected"
            AIRelayCore.ImageSubmit.DISABLED -> "disabled"
        })

    @ReactMethod
    fun stageText(text: String, promise: Promise) = promise.resolve(core.stageText(text))

    private val main = Handler(Looper.getMainLooper())

    @ReactMethod
    fun openWindow(forPage: Boolean, promise: Promise) {
        val toolbar = FloatingToolbarModule.currentInstance ?: return promise.resolve(false)
        RelayOpenTrace.markOrBegin("openWindow from JS")
        toolbar.openRelayWindow(forPage) { shown ->
            if (!shown) RelayOpenTrace.end("no window")
            promise.resolve(shown)
        }
    }

    @ReactMethod
    fun closeWindow() {
        main.post { RelayInboxPanel.showing()?.closeFromJs() }
    }

    @ReactMethod
    fun setWindowStatus(text: String) {
        main.post { RelayInboxPanel.showing()?.setStatus(text) }
    }

    @ReactMethod
    fun flashWindowStatus(text: String, durationMs: Double) {
        main.post { RelayInboxPanel.showing()?.flashStatus(text, durationMs.toLong()) }
    }

    @ReactMethod
    fun setWindowSelectionMode(mode: String, switchable: Boolean) {
        main.post { RelayInboxPanel.setSelectionMode(mode, switchable) }
    }

    @ReactMethod
    fun setWindowAside(aside: Boolean) {
        main.post { RelayInboxPanel.showing()?.setAside(aside) }
    }

    @ReactMethod fun addListener(eventName: String) {}
    @ReactMethod fun removeListeners(count: Int) {}
}
