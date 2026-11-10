package me.laumss.notipal.panels
import me.laumss.notipal.BuildConfig
import me.laumss.notipal.*
import me.laumss.notipal.overlays.*
import me.laumss.notipal.bubbles.*

import android.content.Context
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.view.*
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.facebook.react.bridge.*
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.modules.core.DeviceEventManagerModule
import me.laumss.notipal.net.LocalSendDiscovery
import me.laumss.notipal.net.PluginIdentity
import me.laumss.notipal.relay.net.RawHttp
import me.laumss.notipal.ui_common.PanelBase
import me.laumss.notipal.ui_common.PanelScrollHost
import me.laumss.notipal.ui_common.PanelWidgets
import me.laumss.notipal.ui_common.UiUtils
import java.io.*
import java.math.BigInteger
import java.net.*
import java.security.*
import java.security.spec.PKCS8EncodedKeySpec
import java.security.cert.X509Certificate
import java.security.cert.CertificateFactory
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.net.ssl.*
import kotlin.concurrent.thread
import org.json.JSONArray
import org.json.JSONObject

class SendPanel(
    ctx: ReactApplicationContext,
    private val toolbar: FloatingToolbarModule
) : PanelBase(ctx, toolbar) {

    override val tag = "SendPanel"
    override val panelName = "send"

    companion object {
        @Volatile var currentInstance: SendPanel? = null

        fun getInstance(ctx: ReactApplicationContext, module: FloatingToolbarModule): SendPanel {
            val inst = currentInstance ?: SendPanel(ctx, module)
            currentInstance = inst
            return inst
        }

        private const val STAGED_TTL_MS = 10_000L
        private const val LINK_TYPE_URL = 4

        private class Staged(
            val text: String,
            val images: List<String>,
            val linked: List<Triple<String, Int, String>>,
            val at: Long = System.currentTimeMillis()
        )

        @Volatile private var staged: Staged? = null

        fun stageLassoData(text: String, images: List<String>, linked: List<Triple<String, Int, String>>) {
            staged = Staged(text, images, linked)
        }

        private fun takeStaged(): Staged? {
            val s = staged
            staged = null
            return s?.takeIf { System.currentTimeMillis() - it.at in 0 until STAGED_TTL_MS }
        }
    }

    private sealed class Item(val key: String) {
        class Text(val text: String) : Item("text")
        class Image(val path: String, val number: Int) : Item("image:$path")
        class FileRef(val path: String, val label: String) : Item("file:$path")
        class Link(val url: String, val label: String) : Item("url:$url")
    }

    private var pendingText: String = ""
    private var pendingImages: List<String> = emptyList()
    private var pendingLinkedFiles: List<Triple<String, Int, String>> = emptyList()
    private var dataReady = false
    private var items: List<Item> = emptyList()
    private val unchecked = HashSet<String>()
    private var selectedPeer: LocalSendModule.DiscoveredPeer? = null
    private var sending = false
    private var peerPollRunnable: Runnable? = null
    private var cameFromBubble = false
    private var restoreNoteBubbles = false
    private var clipboardSyncMode = false
    private var allowAutoSend = true
    private var tempDir: File? = null

    private var contentBox: LinearLayout? = null
    private var scrollHost: PanelScrollHost? = null
    private var peerContainer: LinearLayout? = null
    private var peerSignature: String? = null
    private var scanLabel: TextView? = null
    private var statusView: TextView? = null
    private var sendBtn: TextView? = null

    private var sessionGen = 0

    fun show(
        fromBubble: Boolean = false,
        syncClipboard: Boolean = false,
        useStaged: Boolean = false,
        allowAutoSend: Boolean = true,
        onShowResult: ((Boolean) -> Unit)? = null
    ) {

        if (!LocalSendModule.isWifiConnectedStatic(reactContext)) {
            me.laumss.notipal.ui_common.Dialog.tip(reactContext, NativeLocale.t("no_wifi"))
            onShowResult?.invoke(false)
            return
        }
        currentInstance = this
        deleteTempDir()
        sessionGen++
        pendingText = ""; pendingImages = emptyList(); pendingLinkedFiles = emptyList()
        dataReady = false; items = emptyList(); unchecked.clear()
        selectedPeer = null; sending = false
        cameFromBubble = fromBubble

        restoreNoteBubbles = fromBubble && FloatingToolbarModule.isInNoteApp()
        if (BuildConfig.ENABLE_DEBUG) {
            Log.i(tag, "show fromBubble=$fromBubble restoreNoteBubbles=$restoreNoteBubbles")
        }
        clipboardSyncMode = syncClipboard
        this.allowAutoSend = allowAutoSend
        autoSendArmed = false

        val pre = takeStaged()?.takeIf { useStaged && !syncClipboard }
        showPanel { shown ->
            if (shown) {

                if (!LocalSendModule.staticIsRunning) {
                    toolbarModule.emitEvent("startLocalSendFromNative", Arguments.createMap())
                }
                LocalSendModule.refreshPeers(reactContext)
                startPeerPolling()

                LocalSendDiscovery.setLocalSendAnnouncing(true)
                pre?.let { updateLassoData(it.text, it.images, it.linked) }
            }
            onShowResult?.invoke(shown)
        }
    }

    override fun hide() {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            stopPeerPolling()
            LocalSendDiscovery.setLocalSendAnnouncing(false)
            try { windowManager?.removeView(rootView) } catch (_: Exception) {}
            FloatingPenGuard.setFullScreenActive(false, PenGuardOwner.panel(panelName))
            rootView = null; windowManager = null
            onHide()
        }
    }

    fun deleteOnClose(dir: File) {
        tempDir = dir
    }

    private fun deleteTempDir() {
        tempDir?.deleteRecursively()
        tempDir = null
    }

    override fun onHide() {
        deleteTempDir()
        autoSendArmed = false
        contentBox = null; scrollHost = null; peerContainer = null; peerSignature = null
        scanLabel = null; statusView = null; sendBtn = null
        currentInstance = null
    }

    override fun suspendVisibility() {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            rootView?.visibility = View.GONE
            stopPeerPolling()
        }
    }

    override fun resumeVisibility() {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            rootView?.visibility = View.VISIBLE
            startPeerPolling()
        }
    }

    fun updateLassoData(
        text: String,
        imagePaths: List<String>,
        linkedFiles: List<Triple<String, Int, String>> = emptyList()
    ) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            pendingText = text
            pendingImages = imagePaths
            pendingLinkedFiles = linkedFiles
            dataReady = true
            items = buildItems()
            unchecked.clear()
            rebuildContent()
            setStatus(null)
            updateSendBtnState()

            autoSendArmed = allowAutoSend && rootView != null && !clipboardSyncMode &&
                LocalSendModule.getDefaultPeer(reactContext) != null
            maybeAutoSendToDefault()
        }
    }

    private fun buildItems(): List<Item> {
        val out = ArrayList<Item>()
        if (pendingText.isNotBlank()) out.add(Item.Text(pendingText))
        pendingImages.forEachIndexed { i, path -> out.add(Item.Image(path, i + 1)) }
        for ((path, linkType, label) in pendingLinkedFiles) {
            if (path.isBlank()) continue
            out.add(if (linkType == LINK_TYPE_URL) Item.Link(path, label) else Item.FileRef(path, label))
        }
        return out
    }

    private fun selectedItems(): List<Item> = items.filter { it.key !in unchecked }

    private var autoSendArmed = false

    private fun maybeAutoSendToDefault() {
        if (!autoSendArmed || sending || clipboardSyncMode) return
        val def = LocalSendModule.getDefaultPeer(reactContext) ?: run { autoSendArmed = false; return }
        val only = items.singleOrNull()
        if (only !is Item.Text && only !is Item.Image) { autoSendArmed = false; return }
        val live = LocalSendModule.getPeersSnapshot()
            .firstOrNull { it.fingerprint == def.fingerprint } ?: return
        autoSendArmed = false
        selectedPeer = live
        refreshPeerList()
        if (BuildConfig.ENABLE_DEBUG) Log.i(tag, "auto-send to default peer ${live.alias} (${only?.key})")
        sendSelected()
    }

    override fun buildContent(root: LinearLayout) {
        renderDsl(root) {
            header(NativeLocale.t("send_title"))

            custom { host ->
                LinearLayout(host.ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(sectionLabel(host, NativeLocale.t(
                        if (clipboardSyncMode) "sync_clipboard_btn" else "send_content")))
                    val box = LinearLayout(host.ctx).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(host.dp(20), 0, host.dp(20), host.dp(8))
                    }
                    contentBox = box

                    addView(CappedScrollView(host.ctx, (screenH * 0.26f).toInt()).apply {
                        isVerticalScrollBarEnabled = false
                        addView(box)
                    })
                    rebuildContent()
                }
            }

            custom { host ->
                LinearLayout(host.ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(PanelWidgets.divider(host, dark = false))
                    val row = LinearLayout(host.ctx).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(host.dp(28), host.dp(14), host.dp(20), host.dp(6))
                    }
                    row.addView(TextView(host.ctx).apply {
                        text = NativeLocale.t("send_devices")
                        textSize = host.sp(14f); setTextColor(Color.parseColor("#666666"))
                        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    })
                    scanLabel = TextView(host.ctx).apply {
                        text = NativeLocale.t("rescan")
                        textSize = host.sp(14f); setTextColor(Color.BLACK)
                        setPadding(host.dp(12), host.dp(8), host.dp(8), host.dp(8))
                        paint.isUnderlineText = true
                        setOnClickListener { rescan() }
                    }
                    row.addView(scanLabel)
                    addView(row)
                }
            }

            custom { host ->
                val sh = PanelScrollHost(host.ctx, overlayScrollbar = true)
                scrollHost = sh
                peerContainer = sh.content.apply {
                    setPadding(host.dp(20), host.dp(2), host.dp(20), host.dp(8))
                }
                peerSignature = null
                refreshPeerList()
                sh.view
            }

            custom { host ->
                statusView = TextView(host.ctx).apply {
                    textSize = host.sp(14f); setTextColor(Color.BLACK)
                    maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        .apply { rightMargin = host.dp(16) }
                }
                val cancel = PanelWidgets.outlinedButton(host, NativeLocale.t("cancel")) { closeAndRestore() }
                (cancel.layoutParams as LinearLayout.LayoutParams).rightMargin = host.dp(16)
                sendBtn = PanelWidgets.filledButton(host, NativeLocale.t("send_btn")) {
                    if (clipboardSyncMode) handleSyncClipboard() else sendSelected()
                }
                updateSendBtnState()
                PanelWidgets.bottomBar(host, leftFlex = statusView, rightButtons = listOf(cancel, sendBtn!!))
            }
        }
    }

    private fun sectionLabel(host: me.laumss.notipal.ui_common.PanelHost, title: String): TextView =
        TextView(host.ctx).apply {
            text = title
            textSize = host.sp(14f); setTextColor(Color.parseColor("#666666"))
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setPadding(host.dp(28), host.dp(16), host.dp(28), host.dp(8))
        }

    private fun setStatus(text: String?) {
        statusView?.text = text ?: ""
    }

    private fun rescan() {
        if (sending) return
        scanLabel?.apply { text = NativeLocale.t("peers_scanning_short"); isEnabled = false }
        LocalSendModule.probeDefaultPeer(reactContext)
        LocalSendModule.reprobeKnownPeers()
        LocalSendModule.triggerScan()
        handler.postDelayed({
            scanLabel?.apply { text = NativeLocale.t("rescan"); isEnabled = true }
            refreshPeerList()
        }, 1500)
    }

    private fun rebuildContent() {
        val box = contentBox ?: return
        box.removeAllViews()
        if (clipboardSyncMode) {
            box.addView(contentRow(
                icon = "icons/ic_tool_clipboard.xml", thumbPath = null,
                title = NativeLocale.t("sync_clipboard_btn"),
                meta = NativeLocale.t("sync_clipboard_desc"),
                checkKey = null
            ))
            return
        }
        if (!dataReady) {
            box.addView(hintText(NativeLocale.t("extracting")))
            return
        }
        if (items.isEmpty()) {
            box.addView(hintText(NativeLocale.t("send_nothing")))
            return
        }
        val checkable = items.size > 1
        for (item in items) {
            val key = if (checkable) item.key else null
            box.addView(when (item) {
                is Item.Text -> contentRow(
                    "icons/ic_send_text.xml", null,
                    item.text.replace(Regex("\\s+"), " ").trim(),
                    NativeLocale.t("send_item_text", item.text.length), key, titleLines = 2
                )
                is Item.Image -> contentRow(
                    "icons/ic_send_file.xml", item.path,
                    if (pendingImages.size > 1) NativeLocale.t("send_item_image_n", item.number)
                    else NativeLocale.t("send_item_image"),
                    fileMeta(item.path), key
                )
                is Item.FileRef -> contentRow(
                    "icons/ic_send_file.xml", null,
                    item.label.ifBlank { File(item.path).name }, fileMeta(item.path), key,
                    middleEllipsize = true
                )
                is Item.Link -> contentRow(
                    "icons/ic_send_url.xml", null,
                    item.url, NativeLocale.t("send_item_link"), key,
                    middleEllipsize = true
                )
            })
        }
    }

    private fun fileMeta(path: String): String {
        val f = File(path)
        val ext = f.extension.uppercase()
        val size = try { if (f.exists()) formatSize(f.length()) else "" } catch (_: Exception) { "" }
        return listOf(ext, size).filter { it.isNotEmpty() }.joinToString(" · ")
    }

    private fun hintText(text: String): TextView = TextView(reactContext).apply {
        this.text = text
        textSize = sp(14f); setTextColor(Color.parseColor("#999999"))
        setPadding(dp(8), dp(12), dp(8), dp(12))
    }

    private fun contentRow(
        icon: String,
        thumbPath: String?,
        title: String,
        meta: String,
        checkKey: String?,
        titleLines: Int = 1,
        middleEllipsize: Boolean = false
    ): LinearLayout {
        val row = LinearLayout(reactContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val check = if (checkKey != null) ImageView(reactContext).apply {
            layoutParams = LinearLayout.LayoutParams(dp(22), dp(22)).apply { rightMargin = dp(14) }
        } else null
        fun paintCheck() {
            val on = checkKey !in unchecked
            check?.setImageDrawable(UiUtils.loadAssetIcon(reactContext,
                if (on) "icons/ic_check_on.xml" else "icons/ic_check_off.xml", dp(22), Color.BLACK))
            row.alpha = if (on) 1f else 0.45f
        }
        if (check != null && checkKey != null) {
            paintCheck()
            row.addView(check)
            row.setOnClickListener {
                if (sending) return@setOnClickListener
                autoSendArmed = false
                if (!unchecked.remove(checkKey)) unchecked.add(checkKey)
                paintCheck()
                updateSendBtnState()
            }
        }

        val tile = FrameLayout(reactContext).apply {
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply { rightMargin = dp(14) }
            background = GradientDrawable().apply {
                setColor(Color.WHITE); setStroke(dp(1), Color.parseColor("#D0D0D0"))
                cornerRadius = dp(4).toFloat()
            }
        }
        val iconView = ImageView(reactContext).apply {
            setImageDrawable(UiUtils.loadAssetIcon(reactContext, icon, dp(24), Color.BLACK))
            scaleType = ImageView.ScaleType.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        tile.addView(iconView)
        if (thumbPath != null) loadThumbnail(thumbPath, dp(44), iconView)
        row.addView(tile)

        val info = LinearLayout(reactContext).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        info.addView(TextView(reactContext).apply {
            text = title; textSize = sp(15f); setTextColor(Color.BLACK)
            maxLines = titleLines
            if (titleLines == 1) isSingleLine = true
            ellipsize = if (middleEllipsize) android.text.TextUtils.TruncateAt.MIDDLE
                        else android.text.TextUtils.TruncateAt.END
        })
        if (meta.isNotEmpty()) info.addView(TextView(reactContext).apply {
            text = meta; textSize = sp(12f); setTextColor(Color.parseColor("#888888"))
            isSingleLine = true; ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(3), 0, 0)
        })
        row.addView(info)
        return row
    }

    private fun loadThumbnail(path: String, sizePx: Int, target: ImageView) {
        val gen = sessionGen
        thread(isDaemon = true) {
            try {
                val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(path, opts)
                var sample = 1
                while (opts.outWidth / (sample * 2) >= sizePx && opts.outHeight / (sample * 2) >= sizePx) sample *= 2
                opts.inSampleSize = sample
                opts.inJustDecodeBounds = false
                val bmp = android.graphics.BitmapFactory.decodeFile(path, opts) ?: return@thread
                handler.post {
                    if (gen != sessionGen) return@post
                    target.scaleType = ImageView.ScaleType.CENTER_CROP
                    target.setImageBitmap(bmp)
                }
            } catch (_: Exception) {
            } catch (_: OutOfMemoryError) {}
        }
    }

    private fun refreshPeerList() {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            val container = peerContainer ?: return@post
            val peers = LocalSendModule.getPeersSnapshot().sortedBy { it.alias.lowercase() }
            val defaultPeer = LocalSendModule.getDefaultPeer(reactContext)

            if (defaultPeer != null) {
                peers.firstOrNull { it.fingerprint == defaultPeer.fingerprint }?.let { live ->
                    if (live.ip != defaultPeer.ip || live.port != defaultPeer.port) {
                        LocalSendModule.setDefaultPeer(reactContext, live)
                    }
                }
            }
            if (peers.isNotEmpty() &&
                (selectedPeer == null || peers.none { it.fingerprint == selectedPeer?.fingerprint })) {
                selectedPeer = peers.firstOrNull { it.fingerprint == defaultPeer?.fingerprint }
                    ?: peers.first()
            } else if (peers.isEmpty()) {
                selectedPeer = null
            }

            val signature = peers.joinToString("|") { "${it.fingerprint}@${it.ip}:${it.port}/${it.alias}" } +
                "#${selectedPeer?.fingerprint}#${defaultPeer?.fingerprint}"
            if (signature != peerSignature) {
                peerSignature = signature
                container.removeAllViews()
                if (peers.isEmpty()) {
                    container.addView(makeEmptyView(
                        NativeLocale.t("rescan"),
                        NativeLocale.t("peers_none")
                    ))
                } else {
                    for (peer in peers) container.addView(createPeerRow(peer, defaultPeer))
                    container.addView(TextView(reactContext).apply {
                        text = NativeLocale.t("send_default_hint")
                        textSize = sp(12f); setTextColor(Color.parseColor("#999999"))
                        setPadding(dp(4), dp(10), dp(4), 0)
                    })
                }
                scrollHost?.refreshThumb()
            }
            updateSendBtnState()
            maybeAutoSendToDefault()
        }
    }

    private fun createPeerRow(
        peer: LocalSendModule.DiscoveredPeer,
        defaultPeer: LocalSendModule.DiscoveredPeer?
    ): LinearLayout {
        val isSelected = selectedPeer?.fingerprint == peer.fingerprint
        val isDefault = defaultPeer?.fingerprint == peer.fingerprint
        val row = LinearLayout(reactContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                setStroke(if (isSelected) dp(2) else dp(1),
                    if (isSelected) Color.BLACK else Color.parseColor("#D0D0D0"))
                cornerRadius = dp(6).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
            setOnClickListener {
                if (sending) return@setOnClickListener
                autoSendArmed = false
                selectedPeer = peer
                refreshPeerList()
            }

            setOnLongClickListener {
                val cur = LocalSendModule.getDefaultPeer(reactContext)
                LocalSendModule.setDefaultPeer(
                    reactContext,
                    if (cur?.fingerprint == peer.fingerprint) null else peer
                )
                refreshPeerList()
                true
            }
        }

        val icon = when (peer.deviceType) {
            "mobile" -> "icons/ic_device_phone.xml"
            "tablet" -> "icons/ic_device_tablet.xml"
            else -> "icons/ic_device_desktop.xml"
        }
        row.addView(ImageView(reactContext).apply {
            setImageDrawable(UiUtils.loadAssetIcon(reactContext, icon, dp(28), Color.BLACK))
            scaleType = ImageView.ScaleType.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { rightMargin = dp(14) }
        })

        val info = LinearLayout(reactContext).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val nameRow = LinearLayout(reactContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        nameRow.addView(TextView(reactContext).apply {
            text = peer.alias; textSize = sp(16f); setTextColor(Color.BLACK)
            typeface = Typeface.create(Typeface.DEFAULT, if (isSelected) Typeface.BOLD else Typeface.NORMAL)
            isSingleLine = true; ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
        })
        if (isDefault) {
            nameRow.addView(TextView(reactContext).apply {
                text = NativeLocale.t("peer_default_badge")
                textSize = sp(11f); setTextColor(Color.BLACK)
                setPadding(dp(6), dp(1), dp(6), dp(1))
                background = GradientDrawable().apply {
                    setColor(Color.WHITE); setStroke(dp(1), Color.BLACK); cornerRadius = dp(3).toFloat()
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { leftMargin = dp(8) }
            })
        }
        info.addView(nameRow)
        info.addView(TextView(reactContext).apply {
            text = peer.ip; textSize = sp(12f); setTextColor(Color.parseColor("#888888"))
            isSingleLine = true
            setPadding(0, dp(3), 0, 0)
        })
        row.addView(info)

        row.addView(RadioMark(reactContext, isSelected).apply {
            layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)).apply { leftMargin = dp(12) }
        })
        return row
    }

    private class RadioMark(ctx: Context, private val on: Boolean) : View(ctx) {
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(canvas: android.graphics.Canvas) {
            val r = minOf(width, height) / 2f
            val cx = width / 2f; val cy = height / 2f
            val stroke = r * if (on) 0.16f else 0.1f
            paint.style = android.graphics.Paint.Style.STROKE
            paint.strokeWidth = stroke
            paint.color = if (on) Color.BLACK else Color.parseColor("#999999")
            canvas.drawCircle(cx, cy, r - stroke / 2f, paint)
            if (on) {
                paint.style = android.graphics.Paint.Style.FILL
                canvas.drawCircle(cx, cy, r * 0.45f, paint)
            }
        }
    }

    private class CappedScrollView(ctx: Context, private val maxH: Int) : android.widget.ScrollView(ctx) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST))
        }
    }

    private fun withInternetPermission(onGranted: () -> Unit) {
        toolbar.requestPluginInternetPermission { granted ->
            if (!granted) {
                sending = false
                setStatus(NativeLocale.t("perm_required"))
                updateSendBtnState()
                return@requestPluginInternetPermission
            }
            onGranted()
        }
    }

    private fun sendSelected() {
        val peer = selectedPeer ?: return
        val chosen = selectedItems()
        if (chosen.isEmpty() || sending) return
        autoSendArmed = false
        sending = true
        setStatus(NativeLocale.t("sending"))
        updateSendBtnState()
        val needsFiles = chosen.any { it is Item.Image || it is Item.FileRef }
        val go = { withInternetPermission { thread(isDaemon = true) { runSend(peer, chosen) } } }
        if (!needsFiles) {
            go()
            return
        }
        toolbar.requestPluginFileReadPermission { granted ->
            if (!granted) {
                sending = false
                setStatus(NativeLocale.t("file_read_permission_needed"))
                updateSendBtnState()
                return@requestPluginFileReadPermission
            }
            go()
        }
    }

    private fun runSend(peer: LocalSendModule.DiscoveredPeer, chosen: List<Item>) {

        PluginIdentity.claim()
        val texts = chosen.mapNotNull {
            when (it) { is Item.Text -> it.text; is Item.Link -> it.url; else -> null }
        }
        val files = chosen.mapNotNull {
            when (it) { is Item.Image -> it.path; is Item.FileRef -> it.path; else -> null }
        }
        val steps = texts.size + if (files.isEmpty()) 0 else 1
        var step = 0
        fun progress() {
            step++
            if (steps > 1) handler.post { setStatus(NativeLocale.t("sending_progress", step, steps)) }
        }
        try {
            for (t in texts) {
                progress()
                LocalSendModule.sendTextDirect(peer.ip, peer.port, t, peer.useTls)
            }
            if (files.isNotEmpty()) {
                progress()
                LocalSendModule.sendFilesDirect(peer.ip, peer.port, files, peer.useTls)
            }
            handler.post {
                setStatus(NativeLocale.t("send_success"))
                scheduleAutoClose()
            }
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) Log.e(tag, "send failed: ${e.javaClass.simpleName}: ${e.message}")
            val msg = failureText(e, "send_rejected")
            handler.post {
                sending = false
                setStatus(msg)
                updateSendBtnState()
            }
        }
    }

    private fun failureText(e: Exception, rejectedKey: String): String = when {
        e.message?.contains("403") == true -> NativeLocale.t(rejectedKey)
        e is FileNotFoundException || e.message?.startsWith("File not found") == true ->
            NativeLocale.t("send_err_missing")
        e is SocketTimeoutException || e is ConnectException || e is NoRouteToHostException ||
            e is SSLException -> NativeLocale.t("send_err_connect")
        else -> NativeLocale.t("send_failed")
    }

    private fun handleSyncClipboard() {
        val peer = selectedPeer ?: return
        if (sending) return
        sending = true
        setStatus(NativeLocale.t("sync_packaging"))
        updateSendBtnState()
        toolbar.requestPluginFileReadPermission { readGranted ->
            if (!readGranted) {
                sending = false
                setStatus(NativeLocale.t("file_read_permission_needed"))
                updateSendBtnState()
                return@requestPluginFileReadPermission
            }
            toolbar.requestPluginFileWritePermission { writeGranted ->
                if (!writeGranted) {
                    sending = false
                    setStatus(NativeLocale.t("perm_required"))
                    updateSendBtnState()
                    return@requestPluginFileWritePermission
                }
                withInternetPermission {
                thread(isDaemon = true) {
                    try {
                        val zipPath = LocalSendModule.packageClipboardStatic(reactContext)
                        if (zipPath == null) {
                            handler.post {
                                sending = false
                                setStatus(NativeLocale.t("sync_clipboard_empty"))
                                updateSendBtnState()
                            }
                            return@thread
                        }
                        handler.post { setStatus(NativeLocale.t("sync_waiting")) }
                        try {
                            LocalSendModule.sendFileDirect(peer.ip, peer.port, zipPath, peer.useTls)
                        } finally {
                            try { File(zipPath).delete() } catch (_: Exception) {}
                        }
                        handler.post {
                            setStatus(NativeLocale.t("send_success"))
                            scheduleAutoClose()
                        }
                    } catch (e: Exception) {
                        if (BuildConfig.ENABLE_DEBUG) Log.e(tag, "sync failed: ${e.javaClass.simpleName}: ${e.message}")
                        val msg = failureText(e, "sync_rejected")
                        handler.post {
                            sending = false
                            setStatus(msg)
                            updateSendBtnState()
                        }
                    }
                }
                }
            }
        }
    }

    private fun startPeerPolling() {
        stopPeerPolling()
        peerPollRunnable = object : Runnable {
            override fun run() {

                refreshPeerList()
                handler.postDelayed(this, 3000)
            }
        }
        handler.post(peerPollRunnable!!)
    }

    private fun stopPeerPolling() {
        peerPollRunnable?.let { handler.removeCallbacks(it) }
        peerPollRunnable = null
    }

    private fun scheduleAutoClose() {
        val gen = sessionGen
        handler.postDelayed({
            if (gen != sessionGen) return@postDelayed
            closeAndRestore()
        }, 800)
    }

    private fun closeAndRestore() {
        val fromBubble = cameFromBubble
        val shouldRestoreNoteBubbles =
            restoreNoteBubbles && FloatingToolbarModule.isInNoteApp()
        if (BuildConfig.ENABLE_DEBUG) {
            Log.i(tag, "closeAndRestore fromBubble=$fromBubble " +
                "restoreNoteBubbles=$shouldRestoreNoteBubbles")
        }
        hide()
        toolbar.cancelPendingScreen()
        toolbar.requestClosePluginView()
        toolbarModule.emitEvent("onNativePanelClose",
            Arguments.createMap().apply {
                putString("panel", "send")
                putBoolean("cameFromBubble", fromBubble)
                putBoolean("restoreNoteBubbles", shouldRestoreNoteBubbles)
            })
        ScreenshotBubble.reshowIfPending()
        if (shouldRestoreNoteBubbles) {

            val gen = sessionGen
            handler.postDelayed({
                if (gen != sessionGen) return@postDelayed
                if (!FloatingToolbarModule.isInNoteApp()) return@postDelayed
                toolbarModule.restoreToolbar()
                FloatingBubbleModule.reshowLast(reactContext)
                PaletteBubbleModule.reshowLast(reactContext)
            }, 350)
        } else {
            toolbarModule.restoreToolbar()
        }
    }

    private fun updateSendBtnState() {
        val btn = sendBtn ?: return
        val count = if (clipboardSyncMode) 1 else selectedItems().size
        val enabled = selectedPeer != null && !sending && count > 0
        btn.text = when {
            clipboardSyncMode -> NativeLocale.t("sync_clipboard_btn")
            count > 1 -> NativeLocale.t("send_btn_count", count)
            else -> NativeLocale.t("send_btn")
        }
        btn.alpha = if (enabled) 1f else 0.4f
        btn.isEnabled = enabled
        scanLabel?.alpha = if (sending) 0.4f else 1f
    }
}

class LocalSendModule(reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    init {
        startInboxWatcher()
        registerNetworkListener()
    }

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private fun registerNetworkListener() {
        val cm = reactApplicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) {
                if (!staticIsRunning) return
                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "WiFi network lost – stopping LocalSend server")
                forceCloseAll()
                sendEvent("onLocalSendStopped", Arguments.createMap())
            }

            override fun onAvailable(network: Network) {

                FloatingToolbarModule.currentInstance?.requestLocalSendForActiveSession("WiFi available")
            }
        }
        try {
            val req = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            cm.registerNetworkCallback(req, cb)
            networkCallback = cb
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "WiFi network callback registered")
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) Log.w(TAG, "Failed to register network callback: ${e.message}")
        }
    }

    override fun invalidate() {
        val cm = reactApplicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        networkCallback?.let { try { cm?.unregisterNetworkCallback(it) } catch (_: Exception) {} }
        networkCallback = null

        forceCloseAll()
        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "invalidate: LocalSend server stopped")
        super.invalidate()
    }

    companion object {
        private const val TAG = "LocalSendModule"
        private const val PROTOCOL_VERSION = "2.0"
        private const val DEFAULT_PORT = 53317
        private const val API_BASE = "/api/localsend/v2"
        private const val API_BASE_V1 = "/api/localsend/v1"

        @Volatile private var staticServerSocket: ServerSocket? = null
        @Volatile @JvmStatic var staticIsRunning = false

        fun forceCloseAll() {
            staticIsRunning = false
            try { staticServerSocket?.close() } catch (_: Exception) {}
            staticServerSocket = null
            LocalSendDiscovery.stopLocalSendDiscovery()
        }

        data class PendingText(val id: String, val text: String, val fileName: String)
        private val pendingTexts = ConcurrentHashMap<String, PendingText>()

        fun addPendingText(text: String, fileName: String): PendingText {
            val pt = PendingText(
                id = UUID.randomUUID().toString().substring(0, 8),
                text = text,
                fileName = fileName
            )
            pendingTexts[pt.id] = pt
            return pt
        }

        fun ackPendingText(id: String) {
            pendingTexts.remove(id)
        }

        fun drainPendingTexts(): List<PendingText> {
            val copy = pendingTexts.values.toList()
            pendingTexts.clear()
            return copy
        }

        @Volatile @JvmStatic
        var staticReceiveDir: String = "/sdcard/LocalSend"

        @Volatile @JvmStatic
        var staticDeviceAlias: String = "Supernote"

        @Volatile @JvmStatic
        var staticDeviceFingerprint: String = ""

        @Volatile @JvmStatic
        var staticDiscoveredPeers: ConcurrentHashMap<String, DiscoveredPeer> = ConcurrentHashMap()

        private const val PEER_TTL_MS = 15_000L

        private const val PREPARE_UPLOAD_READ_TIMEOUT_MS = 75_000

        @Volatile @JvmStatic var staticServerPort = DEFAULT_PORT

        data class StoredSecurityContext(
            val keyAlias: String,
            val privateKey: String,
            val publicKey: String,
            val certificate: String,
            val certificateHash: String
        )

        private const val INBOX_DIR = "/sdcard/INBOX"
        private val IMAGE_EXTS_RECV = setOf("jpg", "jpeg", "png", "bmp", "gif", "webp")

        @JvmStatic
        fun getReceivedImageFiles(): List<ReceivedFileInfo> = try {
            (File(INBOX_DIR).listFiles() ?: emptyArray())
                .filter { it.isFile && !it.name.startsWith(".") && IMAGE_EXTS_RECV.contains(it.extension.lowercase()) }
                .map { ReceivedFileInfo(it.name, it.absolutePath, it.length(), it.lastModified(), true) }
                .sortedByDescending { it.modified }
        } catch (_: Exception) { emptyList() }

        private var inboxObserver: FileObserver? = null

        @JvmStatic
        fun startInboxWatcher() {
            if (inboxObserver != null) return
            File(INBOX_DIR).mkdirs()
            @Suppress("DEPRECATION")

            inboxObserver = object : FileObserver(INBOX_DIR, CLOSE_WRITE or MOVED_TO) {
                override fun onEvent(event: Int, path: String?) {
                    if (path == null) return
                    val ext = path.substringAfterLast('.', "").lowercase()
                    if (IMAGE_EXTS_RECV.contains(ext)) {
                        ImagePickerPanel.currentInstance?.onFileReceived()
                    }
                    if (DocLinkPanel.DOC_EXTS.contains(ext)) {
                        DocLinkPanel.currentInstance?.onFileReceived()
                    }
                }
            }
            inboxObserver!!.startWatching()
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "INBOX watcher started: $INBOX_DIR")
        }

        @JvmStatic
        fun getPeersSnapshot(): List<DiscoveredPeer> {
            val now = System.currentTimeMillis()
            staticDiscoveredPeers.entries.removeIf {
                now - it.value.lastSeen > PEER_TTL_MS ||
                    LocalSendDiscovery.isLocalAddress(it.value.ip) ||
                    it.value.fingerprint == staticDeviceFingerprint
            }
            return staticDiscoveredPeers.values.toList()
        }

        private const val DEFAULT_PEER_KEY = "localsend_default_peer"

        @JvmStatic
        fun getDefaultPeer(ctx: Context): DiscoveredPeer? {
            return try {
                val json = ctx.getSharedPreferences(NotipalPrefs.PRESETS_FILE, 0)
                    .getString(DEFAULT_PEER_KEY, null) ?: return null
                val o = JSONObject(json)
                val fp = o.optString("fingerprint", "")
                if (fp.isEmpty()) null else DiscoveredPeer(
                    alias = o.optString("alias", "?"),
                    ip = o.optString("ip", ""),
                    port = o.optInt("port", DEFAULT_PORT),
                    deviceType = o.optString("deviceType", "desktop"),
                    fingerprint = fp,
                    useTls = o.optBoolean("useTls", true)
                )
            } catch (_: Exception) { null }
        }

        @JvmStatic
        fun setDefaultPeer(ctx: Context, peer: DiscoveredPeer?) {
            val prefs = ctx.getSharedPreferences(NotipalPrefs.PRESETS_FILE, 0)
            if (peer == null) {
                prefs.edit().remove(DEFAULT_PEER_KEY).apply()
                return
            }
            val o = JSONObject().apply {
                put("alias", peer.alias)
                put("ip", peer.ip)
                put("port", peer.port)
                put("deviceType", peer.deviceType)
                put("fingerprint", peer.fingerprint)
                put("useTls", peer.useTls)
            }
            prefs.edit().putString(DEFAULT_PEER_KEY, o.toString()).apply()
        }

        @JvmStatic
        fun isWifiConnectedStatic(ctx: Context): Boolean {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return false
            val net = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        }

        @JvmStatic
        fun packageClipboardStatic(ctx: Context): String? {
            val prefs = ctx.getSharedPreferences(NotipalPrefs.PRESETS_FILE, 0)
            val clipsJson = prefs.getString(NotipalPrefs.CLIP_SLOTS_KEY, null) ?: return null
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "[SYNC-DBG] packageClipboard raw preset_99: $clipsJson")
            val clips = JSONObject(clipsJson)

            val filteredClips = JSONObject()
            val keys = clips.keys()
            while (keys.hasNext()) {
                val slot = keys.next()
                val path = clips.optString(slot, "")
                val fileExists = path.isNotEmpty() && File(path).exists()
                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "[SYNC-DBG] packageClipboard slot=$slot path='$path' exists=$fileExists")
                if (fileExists) filteredClips.put(slot, path)
            }
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "[SYNC-DBG] packageClipboard filtered: $filteredClips (${filteredClips.length()} slots)")
            if (filteredClips.length() == 0) return null

            val zipFile = File(ctx.cacheDir, "clipboard_sync_${System.currentTimeMillis()}.zip")
            ZipOutputStream(zipFile.outputStream().buffered()).use { zos ->
                zos.putNextEntry(ZipEntry("clips.json"))
                zos.write(filteredClips.toString().toByteArray(Charsets.UTF_8))
                zos.closeEntry()

                val added = HashSet<String>()
                val keys2 = filteredClips.keys()
                while (keys2.hasNext()) {
                    val sticker = File(filteredClips.getString(keys2.next()))
                    val files = sticker.parentFile?.listFiles()?.filter { it.isFile && it.name.startsWith(sticker.name) }.orEmpty()
                    for (f in files) {
                        if (!added.add(f.name)) continue
                        zos.putNextEntry(ZipEntry("stickers/${f.name}"))
                        f.inputStream().buffered().use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
                }
                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "[SYNC-DBG] packageClipboard files=${added.size}")
            }
            return zipFile.absolutePath
        }

        @JvmStatic
        fun probeDefaultPeer(ctx: Context) {
            val def = getDefaultPeer(ctx) ?: return
            if (def.ip.isEmpty()) return
            kotlin.concurrent.thread(isDaemon = true, name = "LocalSend-ProbeDefault") {
                probeHostStatic(def.ip, def.port)
            }
        }

        @Volatile private var reprobeRunning = false

        @JvmStatic
        fun refreshPeers(ctx: Context) {
            val known = staticDiscoveredPeers.values.map { it.ip to it.port }.distinct()
            staticDiscoveredPeers.clear()
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "refreshPeers: cleared, reprobe ${known.size} known")
            probeDefaultPeer(ctx)
            reprobe(known)
            triggerScan()
        }

        @JvmStatic
        fun reprobeKnownPeers() {
            reprobe(staticDiscoveredPeers.values.map { it.ip to it.port }.distinct())
        }

        private fun reprobe(known: List<Pair<String, Int>>) {
            if (reprobeRunning) return
            if (known.isEmpty()) return
            kotlin.concurrent.thread(isDaemon = true, name = "NativePanel-Reprobe") {
                reprobeRunning = true
                try {
                    for ((ip, port) in known) probeHostStatic(ip, port)
                } finally {
                    reprobeRunning = false
                }
            }
        }

        @JvmStatic
        private fun isImageFileStatic(name: String): Boolean {
            val ext = name.substringAfterLast('.', "").lowercase()
            return ext in listOf("jpg", "jpeg", "png", "bmp", "gif", "webp")
        }

        data class ReceivedFileInfo(
            val name: String, val path: String, val size: Long,
            val modified: Long, val isImage: Boolean
        )

        private val staticTrustAllSsl: SSLContext by lazy {
            val tm = arrayOf<TrustManager>(object : X509TrustManager {
                override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
                override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
                override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
            })
            SSLContext.getInstance("TLS").apply { init(null, tm, SecureRandom()) }
        }
        private val staticHostnameVerifier = HostnameVerifier { _, _ -> true }

        private const val SECURITY_PREFS = "quicktoolbar_presets"
        private const val SECURITY_CONTEXT_KEY = "ls_security_context"
        private const val KEY_ALIAS = "inkling_localsend_tls_pem_v1"
        private val KEYSTORE_PASSWORD = "inkling".toCharArray()

        @Volatile private var staticServerTlsContext: SSLContext? = null
        @Volatile private var staticStoredSecurityContext: StoredSecurityContext? = null

        @Synchronized
        fun ensureSecurityContext(ctx: android.content.Context): StoredSecurityContext {
            staticStoredSecurityContext?.let { return it }

            val prefs = ctx.getSharedPreferences(SECURITY_PREFS, 0)
            val stored = prefs.getString(SECURITY_CONTEXT_KEY, null)
                ?.let { parseStoredSecurityContext(it) }
                ?.takeIf { it.keyAlias == KEY_ALIAS && it.privateKey.contains("BEGIN PRIVATE KEY") && it.certificate.contains("BEGIN CERTIFICATE") }

            val context = stored ?: loadOrGenerateSecurityContext()
            if (stored == null) {
                prefs.edit().putString(SECURITY_CONTEXT_KEY, securityContextToJson(context)).apply()
            }
            staticStoredSecurityContext = context
            return context
        }

        @JvmStatic
        fun initServerTlsContext(ctx: android.content.Context) {
            if (staticServerTlsContext != null) return
            try {
                val securityContext = ensureSecurityContext(ctx)
                val privateKey = parsePrivateKeyPem(securityContext.privateKey)
                val certificate = parseCertificatePem(securityContext.certificate)
                val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                    load(null)
                    setKeyEntry(securityContext.keyAlias, privateKey, KEYSTORE_PASSWORD, arrayOf(certificate))
                }
                val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
                kmf.init(ks, KEYSTORE_PASSWORD)
                val keyManagers = kmf.keyManagers.map { km ->
                    if (km is X509KeyManager) AliasKeyManager(km, securityContext.keyAlias) else km
                }.toTypedArray()
                staticServerTlsContext = SSLContext.getInstance("TLS").apply {
                    init(keyManagers, null, SecureRandom())
                }
                if (BuildConfig.ENABLE_DEBUG) {
                    Log.i(TAG, "[LS-DIAG] server TLS context initialized fingerprint=${securityContext.certificateHash}")
                }
            } catch (e: Exception) {
                if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "[LS-DIAG] server TLS init exception", e)
            }
        }

        @JvmStatic
        fun serverProtocol(): String = if (staticServerTlsContext != null) "https" else "http"

        private fun parseStoredSecurityContext(raw: String): StoredSecurityContext? {
            return try {
                val o = JSONObject(raw)
                StoredSecurityContext(
                    keyAlias = o.optString("keyAlias", KEY_ALIAS),
                    privateKey = o.optString("privateKey", ""),
                    publicKey = o.optString("publicKey", ""),
                    certificate = o.optString("certificate", ""),
                    certificateHash = o.optString("certificateHash", "")
                ).takeIf { it.certificateHash.isNotEmpty() }
            } catch (_: Exception) {
                null
            }
        }

        private fun securityContextToJson(context: StoredSecurityContext): String =
            JSONObject().apply {
                put("keyAlias", context.keyAlias)
                put("privateKey", context.privateKey)
                put("publicKey", context.publicKey)
                put("certificate", context.certificate)
                put("certificateHash", context.certificateHash)
            }.toString()

        private fun loadOrGenerateSecurityContext(): StoredSecurityContext {
            val keyPair = KeyPairGenerator.getInstance("RSA").apply {
                initialize(2048, SecureRandom())
            }.generateKeyPair()
            val cert = generateSelfSignedCertificate(keyPair)

            return StoredSecurityContext(
                keyAlias = KEY_ALIAS,
                privateKey = pemBlock("PRIVATE KEY", keyPair.private.encoded),
                publicKey = pemBlock("PUBLIC KEY", cert.publicKey.encoded),
                certificate = pemBlock("CERTIFICATE", cert.encoded),
                certificateHash = staticSha256Hex(cert.encoded)
            )
        }

        private fun parsePrivateKeyPem(pem: String): PrivateKey {
            val der = parsePemBlock(pem)
            return KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(der))
        }

        private fun parseCertificatePem(pem: String): X509Certificate {
            val der = parsePemBlock(pem)
            return CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(der)) as X509Certificate
        }

        private fun parsePemBlock(pem: String): ByteArray {
            val content = pem.replace("\r\n", "\n")
                .split("\n")
                .filter { it.isNotBlank() && !it.startsWith("-----") }
                .joinToString("")
            return android.util.Base64.decode(content, android.util.Base64.DEFAULT)
        }

        private fun generateSelfSignedCertificate(keyPair: KeyPair): X509Certificate {
            val now = System.currentTimeMillis()
            val notBefore = Date(now - TimeUnit.DAYS.toMillis(1))
            val notAfter = Date(now + TimeUnit.DAYS.toMillis(365L * 10L))
            val serial = BigInteger(64, SecureRandom()).abs().add(BigInteger.ONE)
            val algorithm = derSequence(
                derOid("1.2.840.113549.1.1.11"),
                derNull()
            )
            val name = derName("LocalSend User")
            val tbs = derSequence(
                derInteger(serial),
                algorithm,
                name,
                derSequence(derTime(notBefore), derTime(notAfter)),
                name,
                keyPair.public.encoded
            )
            val sig = Signature.getInstance("SHA256withRSA").apply {
                initSign(keyPair.private)
                update(tbs)
            }.sign()
            val certDer = derSequence(tbs, algorithm, derBitString(sig))
            return CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(certDer)) as X509Certificate
        }

        private fun pemBlock(type: String, der: ByteArray): String {
            val base64 = android.util.Base64.encodeToString(
                der,
                android.util.Base64.NO_WRAP
            )
            val body = base64.chunked(64).joinToString("\n")
            return "-----BEGIN $type-----\n$body\n-----END $type-----\n"
        }

        private fun derSequence(vararg parts: ByteArray): ByteArray =
            derTagged(0x30, parts.flatMap { it.asIterable() }.toByteArray())

        private fun derSet(vararg parts: ByteArray): ByteArray =
            derTagged(0x31, parts.flatMap { it.asIterable() }.toByteArray())

        private fun derTagged(tag: Int, value: ByteArray): ByteArray =
            byteArrayOf(tag.toByte()) + derLength(value.size) + value

        private fun derLength(length: Int): ByteArray {
            if (length < 128) return byteArrayOf(length.toByte())
            val bytes = mutableListOf<Byte>()
            var n = length
            while (n > 0) {
                bytes.add(0, (n and 0xff).toByte())
                n = n ushr 8
            }
            return byteArrayOf((0x80 or bytes.size).toByte()) + bytes.toByteArray()
        }

        private fun derInteger(value: BigInteger): ByteArray =
            derTagged(0x02, value.toByteArray())

        private fun derNull(): ByteArray = byteArrayOf(0x05, 0x00)

        private fun derOid(oid: String): ByteArray {
            val parts = oid.split(".").map { it.toLong() }
            val encoded = mutableListOf<Byte>()
            encoded.add((parts[0] * 40 + parts[1]).toByte())
            for (part in parts.drop(2)) {
                val stack = mutableListOf<Byte>()
                var n = part
                stack.add((n and 0x7f).toByte())
                n = n ushr 7
                while (n > 0) {
                    stack.add(0, ((n and 0x7f) or 0x80).toByte())
                    n = n ushr 7
                }
                encoded.addAll(stack)
            }
            return derTagged(0x06, encoded.toByteArray())
        }

        private fun derUtf8(value: String): ByteArray =
            derTagged(0x0c, value.toByteArray(Charsets.UTF_8))

        private fun derName(commonName: String): ByteArray =
            derSequence(
                derSet(
                    derSequence(
                        derOid("2.5.4.3"),
                        derUtf8(commonName)
                    )
                )
            )

        private fun derTime(date: Date): ByteArray {
            val text = SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.format(date)
            return derTagged(0x17, text.toByteArray(Charsets.US_ASCII))
        }

        private fun derBitString(value: ByteArray): ByteArray =
            derTagged(0x03, byteArrayOf(0x00) + value)

        private class AliasKeyManager(
            private val delegate: X509KeyManager,
            private val alias: String
        ) : X509ExtendedKeyManager() {
            override fun getClientAliases(keyType: String?, issuers: Array<java.security.Principal>?): Array<String>? =
                if (isRsaKeyType(keyType)) arrayOf(alias) else delegate.getClientAliases(keyType, issuers)

            override fun chooseClientAlias(
                keyType: Array<out String>?,
                issuers: Array<java.security.Principal>?,
                socket: Socket?
            ): String? =
                if (keyType?.any { isRsaKeyType(it) } == true) alias else delegate.chooseClientAlias(keyType, issuers, socket)

            override fun getServerAliases(keyType: String?, issuers: Array<java.security.Principal>?): Array<String> =
                if (isRsaKeyType(keyType)) arrayOf(alias) else emptyArray()

            override fun chooseServerAlias(
                keyType: String?,
                issuers: Array<java.security.Principal>?,
                socket: Socket?
            ): String? =
                if (isRsaKeyType(keyType)) alias else delegate.chooseServerAlias(keyType, issuers, socket)

            override fun getCertificateChain(alias: String?): Array<java.security.cert.X509Certificate>? =
                delegate.getCertificateChain(this.alias)

            override fun getPrivateKey(alias: String?): PrivateKey? =
                delegate.getPrivateKey(this.alias)

            override fun chooseEngineClientAlias(
                keyType: Array<out String>?,
                issuers: Array<java.security.Principal>?,
                engine: SSLEngine?
            ): String? =
                if (keyType?.any { isRsaKeyType(it) } == true) alias else null

            override fun chooseEngineServerAlias(
                keyType: String?,
                issuers: Array<java.security.Principal>?,
                engine: SSLEngine?
            ): String? =
                if (isRsaKeyType(keyType)) alias else null

            private fun isRsaKeyType(keyType: String?): Boolean {
                val normalized = keyType?.uppercase(Locale.US) ?: return false
                return normalized == "RSA" || normalized.startsWith("RSA_")
            }
        }

        private class PrereadSocket(private val delegate: Socket, prefix: ByteArray) : Socket() {
            private val input: InputStream =
                SequenceInputStream(ByteArrayInputStream(prefix), delegate.getInputStream())

            override fun getInputStream(): InputStream = input
            override fun getOutputStream(): OutputStream = delegate.getOutputStream()
            override fun getInetAddress(): InetAddress? = delegate.inetAddress
            override fun getLocalAddress(): InetAddress = delegate.localAddress
            override fun getPort(): Int = delegate.port
            override fun getLocalPort(): Int = delegate.localPort
            override fun getRemoteSocketAddress(): SocketAddress? = delegate.remoteSocketAddress
            override fun getLocalSocketAddress(): SocketAddress? = delegate.localSocketAddress
            override fun isConnected(): Boolean = delegate.isConnected
            override fun isBound(): Boolean = delegate.isBound
            override fun isClosed(): Boolean = delegate.isClosed
            override fun isInputShutdown(): Boolean = delegate.isInputShutdown
            override fun isOutputShutdown(): Boolean = delegate.isOutputShutdown
            override fun getSoTimeout(): Int = delegate.soTimeout
            override fun setSoTimeout(timeout: Int) { delegate.soTimeout = timeout }
            override fun getTcpNoDelay(): Boolean = delegate.tcpNoDelay
            override fun setTcpNoDelay(on: Boolean) { delegate.tcpNoDelay = on }
            override fun getKeepAlive(): Boolean = delegate.keepAlive
            override fun setKeepAlive(on: Boolean) { delegate.keepAlive = on }
            override fun getSoLinger(): Int = delegate.soLinger
            override fun setSoLinger(on: Boolean, linger: Int) = delegate.setSoLinger(on, linger)
            override fun getReuseAddress(): Boolean = delegate.reuseAddress
            override fun setReuseAddress(on: Boolean) { delegate.reuseAddress = on }
            override fun getReceiveBufferSize(): Int = delegate.receiveBufferSize
            override fun setReceiveBufferSize(size: Int) { delegate.receiveBufferSize = size }
            override fun getSendBufferSize(): Int = delegate.sendBufferSize
            override fun setSendBufferSize(size: Int) { delegate.sendBufferSize = size }
            override fun shutdownInput() = delegate.shutdownInput()
            override fun shutdownOutput() = delegate.shutdownOutput()
            override fun close() = delegate.close()
            override fun toString(): String = "PrereadSocket($delegate)"
        }

        @JvmStatic
        private fun wrapServerTlsSocket(tls: SSLContext, socket: Socket, consumed: ByteArray): SSLSocket {
            val host = (socket.remoteSocketAddress as? InetSocketAddress)?.address?.hostAddress ?: ""
            return tls.socketFactory.createSocket(
                PrereadSocket(socket, consumed), host, socket.port, true
            ) as SSLSocket
        }

        private fun staticOpenConn(url: String, connectTimeout: Int = 10000, readTimeout: Int = 30000): HttpURLConnection {

            PluginIdentity.claim()
            val conn = URL(url).openConnection() as HttpURLConnection
            if (conn is HttpsURLConnection) {
                conn.sslSocketFactory = staticTrustAllSsl.socketFactory
                conn.hostnameVerifier = staticHostnameVerifier
            }
            conn.connectTimeout = connectTimeout
            conn.readTimeout = readTimeout
            return conn
        }

        private class UploadBody(val length: Long, val open: () -> InputStream) {
            constructor(bytes: ByteArray) : this(bytes.size.toLong(), { ByteArrayInputStream(bytes) })
            constructor(file: File) : this(file.length(), { FileInputStream(file) })
        }

        private fun staticHttpPostJson(url: String, jsonBody: String, readTimeout: Int = 30000): String =
            staticPost(url, "application/json; charset=utf-8", UploadBody(jsonBody.toByteArray(Charsets.UTF_8)), readTimeout)

        private fun staticHttpPostBinary(url: String, data: UploadBody) {
            staticPost(url, "application/octet-stream", data, 60000)
        }

        private fun staticPost(url: String, contentType: String, data: UploadBody, readTimeout: Int): String {
            if (url.startsWith("http://")) {
                return data.open().use { input ->
                    RawHttp.request(
                        "POST", url, mapOf("Content-Type" to contentType),
                        connectTimeoutMs = 10000, readTimeoutMs = readTimeout,
                        bodyStream = input, bodyLength = data.length,
                    ).use { resp ->
                        val text = resp.body.readBytes().toString(Charsets.UTF_8)
                        if (!resp.isSuccess) throw IOException("HTTP ${resp.status}: ${text.ifEmpty { "no body" }}")
                        text
                    }
                }
            }
            val conn = staticOpenConn(url, readTimeout = readTimeout)
            try {
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", contentType)
                conn.setFixedLengthStreamingMode(data.length)
                conn.doOutput = true
                conn.outputStream.use { out -> data.open().use { it.copyTo(out) } }
                val code = conn.responseCode
                if (code !in 200..299) {
                    val err = try { conn.errorStream?.bufferedReader()?.readText() } catch (_: Exception) { null }
                    throw IOException("HTTP $code: ${err ?: "no body"}")
                }
                return conn.inputStream.bufferedReader().readText()
            } finally {
                conn.disconnect()
            }
        }

        private fun staticSha256Hex(data: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

        private fun staticSha256Hex(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        private fun buildSingleFileJson(
            fileId: String, fileName: String, size: Long, fileType: String,
            sha: String, preview: String? = null
        ): JSONObject = JSONObject().apply {
            put(fileId, JSONObject().apply {
                put("id", fileId); put("fileName", fileName)
                put("size", size); put("fileType", fileType)
                put("hash", sha)
                put("sha256", sha)
                if (preview != null) put("preview", preview)
            })
        }

        private fun staticGuessMimeType(name: String): String {
            val ext = name.substringAfterLast('.', "").lowercase()
            return when (ext) {
                "txt" -> "text/plain"; "jpg", "jpeg" -> "image/jpeg"; "png" -> "image/png"
                "gif" -> "image/gif"; "webp" -> "image/webp"; "bmp" -> "image/bmp"
                "pdf" -> "application/pdf"
                else -> "application/octet-stream"
            }
        }

        @JvmStatic
        @Throws(Exception::class)
        fun sendTextDirect(ip: String, port: Int, text: String, useTls: Boolean = true): String {
            val textBytes = text.toByteArray(Charsets.UTF_8)
            val fileId = "text-${UUID.randomUUID().toString().substring(0, 8)}"
            val filesJson = buildSingleFileJson(
                fileId, "message.txt", textBytes.size.toLong(), "text/plain",
                staticSha256Hex(textBytes), preview = text
            )
            return doUploadDirect(ip, port, filesJson, mapOf(fileId to UploadBody(textBytes)), useTls)
        }

        @JvmStatic
        @Throws(Exception::class)
        fun sendFileDirect(ip: String, port: Int, filePath: String, useTls: Boolean = true): String {
            val file = File(filePath)
            if (!file.exists()) throw Exception("File not found: $filePath")
            val upload = UploadBody(file)
            val fileId = "file-${UUID.randomUUID().toString().substring(0, 8)}"
            val filesJson = buildSingleFileJson(
                fileId, file.name, upload.length, staticGuessMimeType(file.name),
                staticSha256Hex(file)
            )
            return doUploadDirect(ip, port, filesJson, mapOf(fileId to upload), useTls)
        }

        @JvmStatic
        @Throws(Exception::class)
        fun sendFilesDirect(ip: String, port: Int, filePaths: List<String>, useTls: Boolean = true): String {
            if (filePaths.size == 1) return sendFileDirect(ip, port, filePaths[0], useTls)
            val filesJson = JSONObject()
            val data = LinkedHashMap<String, UploadBody>()
            for (path in filePaths) {
                val file = File(path)
                if (!file.exists()) throw FileNotFoundException("File not found: $path")
                val upload = UploadBody(file)
                val fileId = "file-${UUID.randomUUID().toString().substring(0, 8)}"
                val one = buildSingleFileJson(
                    fileId, file.name, upload.length, staticGuessMimeType(file.name), staticSha256Hex(file)
                )
                filesJson.put(fileId, one.getJSONObject(fileId))
                data[fileId] = upload
            }
            return doUploadDirect(ip, port, filesJson, data, useTls)
        }

        private fun localSendInfo(alias: String, fingerprint: String, port: Int): JSONObject = JSONObject().apply {
            put("alias", alias)
            put("version", PROTOCOL_VERSION)
            put("deviceModel", "Supernote")
            put("deviceType", "mobile")
            put("fingerprint", fingerprint)
            put("port", port)
            put("protocol", serverProtocol())
            put("download", false)
        }

        private fun doUploadDirect(
            ip: String, port: Int, filesJson: JSONObject, fileData: Map<String, UploadBody>,
            useTls: Boolean = true
        ): String {
            val prepareBody = JSONObject().apply {
                put("info", localSendInfo(staticDeviceAlias, staticDeviceFingerprint, staticServerPort))
                put("files", filesJson)
            }
            try {

                val scheme = if (useTls) "https" else "http"
                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "[SEND-DBG] doUploadDirect $scheme://$ip:$port")
                val prepareStart = System.currentTimeMillis()
                val prepareResp = staticHttpPostJson("$scheme://$ip:$port$API_BASE/prepare-upload",
                    prepareBody.toString(), readTimeout = PREPARE_UPLOAD_READ_TIMEOUT_MS)
                if (BuildConfig.ENABLE_DEBUG) {
                    Log.i(TAG, "[SEND-DBG] prepare-upload in ${System.currentTimeMillis() - prepareStart}ms response: $prepareResp")
                }
                val prepareJson = JSONObject(prepareResp)
                val sessionId = prepareJson.optString("sessionId", "")
                val tokenMap = prepareJson.optJSONObject("files")
                if (sessionId.isEmpty()) { if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "[SEND-DBG] auto-accepted"); return "auto" }
                if (tokenMap == null || tokenMap.length() == 0) {

                    if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "[SEND-DBG] no tokens issued, consumed at prepare-upload; done")
                    return sessionId
                }
                for ((fileId, data) in fileData) {
                    val token = tokenMap?.optString(fileId, "") ?: ""
                    if (token.isEmpty()) {
                        throw IOException("prepare-upload returned no token for $fileId: $prepareResp")
                    }
                    val path = "$API_BASE/upload?sessionId=$sessionId&fileId=$fileId&token=$token"
                    if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "[SEND-DBG] uploading $fileId size=${data.length}")
                    val uploadStart = System.currentTimeMillis()
                    staticHttpPostBinary("$scheme://$ip:$port$path", data)
                    if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "[SEND-DBG] upload $fileId done in ${System.currentTimeMillis() - uploadStart}ms")
                }
                return sessionId
            } catch (e: Exception) {
                if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "[SEND-DBG] doUploadDirect FAILED: ${e.javaClass.simpleName}: ${e.message}")
                throw e
            }
        }

        @JvmStatic
        fun triggerScan() {
            if (scanRunningStatic) return
            thread(isDaemon = true, name = "NativePanel-Scan") {
                scanRunningStatic = true
                try {
                    val localIp = getLocalIpStatic()
                    if (localIp == "0.0.0.0") return@thread
                    val localIps = LocalSendDiscovery.localIps()
                    val subnet = localIp.substringBeforeLast('.')
                    val executor = Executors.newFixedThreadPool(50)
                    for (i in 1..254) {
                        val ip = "$subnet.$i"
                        if (ip in localIps) continue
                        executor.submit { probeHostStatic(ip, DEFAULT_PORT) }
                    }
                    executor.shutdown()
                    executor.awaitTermination(6, TimeUnit.SECONDS)
                } catch (e: Exception) {
                    if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "triggerScan error", e)
                } finally {
                    scanRunningStatic = false
                }
            }
        }

        @Volatile private var scanRunningStatic = false

        private fun getLocalIpStatic(): String = LocalSendDiscovery.localIps().firstOrNull() ?: "0.0.0.0"

        private fun probeHostStatic(ip: String, port: Int) {
            if (LocalSendDiscovery.isLocalAddress(ip)) return
            for (useTls in listOf(true, false)) {
                try {
                    val body = if (useTls) {
                        val conn = staticOpenConn("https://$ip:$port$API_BASE/info", 1500, 1500)
                        try {
                            if (conn.responseCode == 200) conn.inputStream.bufferedReader().readText() else null
                        } finally { conn.disconnect() }
                    } else {
                        RawHttp.request("GET", "http://$ip:$port$API_BASE/info", connectTimeoutMs = 1500, readTimeoutMs = 1500)
                            .use { if (it.status == 200) it.body.readBytes().toString(Charsets.UTF_8) else null }
                    }
                    if (body != null) {
                        registerPeerFromJson(body, ip, port, useTls)
                        return
                    }
                } catch (_: Exception) {}
            }
        }

        private fun registerPeerFromJson(body: String, ip: String, port: Int, useTls: Boolean = true) {
            if (LocalSendDiscovery.isLocalAddress(ip)) return
            val data = JSONObject(body)
            val fp = data.optString("fingerprint", "")
            if (fp.isNotEmpty() && fp != staticDeviceFingerprint) {
                staticDiscoveredPeers[fp] = DiscoveredPeer(
                    data.optString("alias", "Unknown"), ip,
                    data.optInt("port", port), data.optString("deviceType", "desktop"), fp,
                    useTls = useTls
                )
            }
        }
    }

    private var serverSocket: ServerSocket?
        get() = staticServerSocket
        set(v) { staticServerSocket = v }
    private var isRunning: Boolean
        get() = staticIsRunning
        set(v) { staticIsRunning = v }
    private var serverPort = DEFAULT_PORT
    private var deviceAlias = "Supernote-${(1000..9999).random()}"
    private var deviceFingerprint = UUID.randomUUID().toString().replace("-", "")
    private var receiveDir = "/sdcard/LocalSend"
    private var pin = ""

    private val uploadSessions = ConcurrentHashMap<String, UploadSession>()
    private var activeUploadSession: String? = null

    data class DiscoveredPeer(
        val alias: String,
        val ip: String,
        val port: Int,
        val deviceType: String,
        val fingerprint: String,
        val lastSeen: Long = System.currentTimeMillis(),
        val useTls: Boolean = true
    )

    private val discoveredPeers: ConcurrentHashMap<String, DiscoveredPeer>
        get() = staticDiscoveredPeers

    override fun getName(): String = "LocalSendModule"

    @ReactMethod
    fun startServer(config: ReadableMap, promise: Promise) {
        if (isRunning) {
            promise.resolve("already_running")
            return
        }

        forceCloseAll()

        try {
            deviceAlias = config.getString("alias") ?: "Supernote"
            serverPort = if (config.hasKey("port")) config.getInt("port") else DEFAULT_PORT
            receiveDir = config.getString("dest") ?: "/sdcard/LocalSend"
            pin = config.getString("pin") ?: ""

            staticReceiveDir = receiveDir
            staticDeviceAlias = deviceAlias
            val securityContext = ensureSecurityContext(reactApplicationContext)
            deviceFingerprint = securityContext.certificateHash
            staticDeviceFingerprint = deviceFingerprint

            File(receiveDir).mkdirs()

            initServerTlsContext(reactApplicationContext)

            val sock = bindServerSocket()
            isRunning = true
            thread(isDaemon = true, name = "LocalSend-Server") { acceptLoop(sock) }

            if (BuildConfig.ENABLE_DEBUG) {
                Log.i(TAG, "startServer: bound HTTP port=$serverPort, localIp=${getLocalIp()}, receiveDir=$receiveDir")
            }

            LocalSendDiscovery.startLocalSendDiscovery(
                reactApplicationContext,
                announcementProvider = { buildLocalSendAnnouncement() },
                onLocalSendAnnouncement = { senderIp, dto -> handleLocalSendAnnouncement(senderIp, dto) },
            )
            LocalSendDiscovery.requestLocalSendAnnounce("server start")

            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "LocalSend server started on ${getLocalIp()}:$serverPort")
            promise.resolve("started")
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "Failed to start server", e)
            isRunning = false
            promise.reject("START_FAILED", e.message)
        }
    }

    @ReactMethod
    fun stopServer(promise: Promise) {
        forceCloseAll()
        serverPort = DEFAULT_PORT
        staticServerPort = DEFAULT_PORT
        uploadSessions.clear()
        activeUploadSession = null
        discoveredPeers.clear()
        promise.resolve("stopped")
    }

    @ReactMethod
    fun isWifiConnected(promise: Promise) = promise.resolve(isWifiConnectedStatic(reactApplicationContext))

    @ReactMethod
    fun getServerStatus(promise: Promise) {
        val map = Arguments.createMap()
        map.putBoolean("running", isRunning)
        map.putString("ip", getLocalIp())
        map.putInt("port", serverPort)
        map.putString("alias", deviceAlias)
        map.putString("receiveDir", receiveDir)
        map.putInt("activeSessions", uploadSessions.size)
        promise.resolve(map)
    }

    @ReactMethod
    fun addListener(eventName: String) {}

    @ReactMethod
    fun removeListeners(count: Int) {}

    @ReactMethod
    fun flushPendingTexts(promise: Promise) {
        val pending = drainPendingTexts()
        val result = Arguments.createArray()
        for (pt in pending) {
            result.pushMap(Arguments.createMap().apply {
                putString("_pendingId", pt.id)
                putString("text", pt.text)
                putString("fileName", pt.fileName)
            })
        }
        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "flushPendingTexts: returning ${pending.size} unacked text(s)")
        promise.resolve(result)
    }

    @ReactMethod
    fun ackPendingText(id: String) {
        Companion.ackPendingText(id)
        if (BuildConfig.ENABLE_DEBUG) Log.d(TAG, "ackPendingText: id=$id, remaining=${pendingTexts.size}")
    }

    private fun bindServerSocket(): ServerSocket {
        val sock = ServerSocket()
        sock.reuseAddress = true
        for (attempt in 0..9) {
            val tryPort = serverPort + attempt
            try {
                sock.bind(InetSocketAddress(tryPort))
                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "[LS-DIAG] bind OK on tcp/$tryPort")
                serverPort = tryPort
                staticServerPort = tryPort
                serverSocket = sock
                return sock
            } catch (e: java.net.BindException) {
                if (BuildConfig.ENABLE_DEBUG) Log.w(TAG, "[LS-DIAG] bind failed on tcp/$tryPort (${e.message}), trying next")
            }
        }
        sock.close()
        throw java.net.BindException("No available port in range $serverPort..${serverPort + 9}")
    }

    private fun acceptLoop(sock: ServerSocket) {

        PluginIdentity.claim()
        while (isRunning) {
            try {
                val client = sock.accept()
                if (BuildConfig.ENABLE_DEBUG) {
                    Log.i(TAG, "[LS-DIAG] accepted tcp connection from ${client.remoteSocketAddress} on local=${client.localSocketAddress}")
                }
                thread(isDaemon = true) { handleClient(client) }
            } catch (e: IOException) {
                if (isRunning && BuildConfig.ENABLE_DEBUG) Log.e(TAG, "Socket accept error", e)
                if (sock.isClosed) break
            }
        }
    }

    private fun handleClient(socket: Socket) {
        var effectiveSocket: Socket = socket
        try {
            socket.soTimeout = 30000
            val remoteIp = (socket.remoteSocketAddress as? InetSocketAddress)?.address?.hostAddress ?: "0.0.0.0"

            val raw = socket.getInputStream()
            val firstByte = raw.read()
            if (firstByte < 0) return

            val input: BufferedInputStream
            val output: BufferedOutputStream
            if (firstByte == 0x16) {
                val tls = staticServerTlsContext
                if (tls == null) {
                    if (BuildConfig.ENABLE_DEBUG) {
                        Log.w(TAG, "[RECV-DBG] TLS ClientHello from $remoteIp but no server TLS context; closing")
                    }
                    return
                }
                val sslSocket = try {

                    (wrapServerTlsSocket(tls, socket, byteArrayOf(0x16))).apply {
                        useClientMode = false
                        startHandshake()
                    }
                } catch (e: Exception) {
                    if (BuildConfig.ENABLE_DEBUG) {
                        Log.w(TAG, "[RECV-DBG] TLS handshake failed from $remoteIp: ${e.javaClass.simpleName}: ${e.message}" +
                            (e.cause?.let { " (cause: ${it.javaClass.simpleName}: ${it.message})" } ?: ""))
                    }
                    return
                }
                effectiveSocket = sslSocket
                input = BufferedInputStream(sslSocket.inputStream)
                output = BufferedOutputStream(sslSocket.outputStream)
                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "[RECV-DBG] TLS session established with $remoteIp")
            } else {
                input = BufferedInputStream(
                    SequenceInputStream(ByteArrayInputStream(byteArrayOf(firstByte.toByte())), raw)
                )
                output = BufferedOutputStream(socket.outputStream)
            }

            val requestLine = readLine(input) ?: return
            if (BuildConfig.ENABLE_DEBUG) Log.d(TAG, "[RECV-DBG] request: $requestLine from $remoteIp")
            val parts = requestLine.split(" ")
            if (parts.size < 3) return

            val method = parts[0]
            val rawPath = parts[1]

            val headers = mutableMapOf<String, String>()
            var line = readLine(input)
            while (!line.isNullOrEmpty()) {
                val colonIdx = line.indexOf(':')
                if (colonIdx > 0) {
                    headers[line.substring(0, colonIdx).trim().lowercase()] =
                        line.substring(colonIdx + 1).trim()
                }
                line = readLine(input)
            }

            val contentLength = headers["content-length"]?.toIntOrNull() ?: 0

            val qIdx = rawPath.indexOf('?')
            val path = if (qIdx >= 0) rawPath.substring(0, qIdx) else rawPath
            val queryString = if (qIdx >= 0) rawPath.substring(qIdx + 1) else ""
            val queryParams = parseQuery(queryString)

            val apiPath = when {
                path.startsWith("$API_BASE/") -> path.removePrefix(API_BASE)
                path.startsWith("$API_BASE_V1/") -> path.removePrefix(API_BASE_V1)
                else -> path
            }

            when {
                method == "GET" && apiPath == "/info" ->
                    handleInfo(output)

                method == "POST" && apiPath == "/register" -> {
                    val body = readBody(input, contentLength)
                    handleRegister(output, body, remoteIp)
                }

                method == "POST" && apiPath == "/prepare-upload" -> {
                    val body = readBody(input, contentLength)
                    handlePrepareUpload(output, body, remoteIp, queryParams)
                }

                method == "POST" && apiPath == "/upload" ->
                    handleUpload(output, input, remoteIp, queryParams, contentLength)

                method == "POST" && apiPath == "/cancel" ->
                    handleCancel(output, queryParams)

                else ->
                    sendHttpResponse(output, 404, """{"error":"Not found"}""")
            }
        } catch (e: SSLException) {
            if (BuildConfig.ENABLE_DEBUG) Log.d(TAG, "[RECV-DBG] TLS error from client, ignoring: ${e.message}")
        } catch (e: java.net.SocketTimeoutException) {

            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "[RECV-DBG] idle connection from ${socket.inetAddress?.hostAddress} closed after read timeout")
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "Client handler error", e)
        } finally {
            try { effectiveSocket.close() } catch (_: Exception) {}
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun handleInfo(output: BufferedOutputStream) {
        val info = deviceInfoJson()
        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "[LS-DIAG] /info response body=$info")
        sendHttpResponse(output, 200, info.toString())
    }

    private fun deviceInfoJson() = localSendInfo(deviceAlias, deviceFingerprint, serverPort)

    private fun handleRegister(output: BufferedOutputStream, body: String, remoteIp: String) {
        try {
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "[LS-DIAG] /register from $remoteIp body=${body.take(500)}")
            val data = JSONObject(body)
            val peerAlias = data.optString("alias", "Unknown")
            val peerPort = data.optInt("port", DEFAULT_PORT)
            val peerDeviceType = data.optString("deviceType", "desktop")
            val peerFingerprint = data.optString("fingerprint", remoteIp)
            val peerProtocol = data.optString("protocol", "https")

            discoveredPeers[peerFingerprint] = DiscoveredPeer(
                alias = peerAlias,
                ip = remoteIp,
                port = peerPort,
                deviceType = peerDeviceType,
                fingerprint = peerFingerprint,
                useTls = peerProtocol != "http"
            )

            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "Device registered: $peerAlias from $remoteIp")

            val response = deviceInfoJson()
            sendHttpResponse(output, 200, response.toString())
        } catch (e: Exception) {
            sendHttpResponse(output, 400, """{"error":"Invalid body"}""")
        }
    }

    private fun handlePrepareUpload(
        output: BufferedOutputStream, body: String,
        remoteIp: String, params: Map<String, String>
    ) {
        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "[RECV-DBG] handlePrepareUpload from $remoteIp body=${body.take(200)}")

        if (pin.isNotEmpty()) {
            val pinParam = params["pin"] ?: ""
            if (pinParam != pin) {
                val msg = if (pinParam.isEmpty()) "PIN required" else "Invalid PIN"
                sendHttpResponse(output, 401, """{"error":"$msg"}""")
                return
            }
        }

        activeUploadSession?.let { sid ->
            uploadSessions[sid]?.let { sess ->
                if (sess.isValid()) {
                    sendHttpResponse(output, 409, """{"error":"Blocked by another session"}""")
                    return
                }
            }
            activeUploadSession = null
        }

        try {
            val data = JSONObject(body)
            val files = data.optJSONObject("files")
            val info = data.optJSONObject("info")
            if (files == null || files.length() == 0) {
                sendHttpResponse(output, 400, """{"error":"Invalid body"}""")
                return
            }

            val senderAlias = info?.optString("alias", "Unknown") ?: "Unknown"

            val allPreviews = mutableListOf<String>()
            val keys0 = files.keys()
            while (keys0.hasNext()) {
                val fileId = keys0.next()
                val fileData = files.getJSONObject(fileId)
                val preview = fileData.optString("preview", "")
                val fileType = fileData.optString("fileType", "")
                if (preview.isNotEmpty() && fileType.startsWith("text/")) {
                    allPreviews.add(preview)
                }
            }

            val insertPanelShowing = ImagePickerPanel.currentInstance?.isShowing == true
            if (allPreviews.isNotEmpty() && allPreviews.size == files.length()) {
                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "Text message(s) from [$senderAlias] via preview field: ${allPreviews.size}")
                val combined = allPreviews.joinToString("\n")
                deliverLocalSendText(combined, "message.txt")

                val textSessionId = UUID.randomUUID().toString().replace("-", "").substring(0, 22)
                val response = JSONObject().apply {
                    put("sessionId", textSessionId)
                    put("files", JSONObject())
                }
                sendHttpResponse(output, 200, response.toString())
                return
            }

            val sessionId = UUID.randomUUID().toString().replace("-", "").substring(0, 22)
            val session = UploadSession(sessionId, remoteIp, senderAlias = senderAlias)

            val tokens = JSONObject()
            val fileNames = mutableListOf<String>()

            val keys = files.keys()
            while (keys.hasNext()) {
                val fileId = keys.next()
                val fileData = files.getJSONObject(fileId)
                val fileName = fileData.optString("fileName", "unknown")
                val fileSize = fileData.optLong("size", 0)
                val fileType = fileData.optString("fileType", "application/octet-stream")
                val sha256 = fileData.optString("sha256", "")

                val token = UUID.randomUUID().toString().replace("-", "").substring(0, 22)
                session.files[fileId] = FileInfo(fileId, fileName, fileSize, fileType, sha256)
                session.tokens[fileId] = token
                session.received[fileId] = false
                tokens.put(fileId, token)
                fileNames.add(fileName)
            }

            val isClipboardSync = fileNames.any {
                it.startsWith("clipboard_sync_") && it.endsWith(".zip")
            }

            val isSingleImage = fileNames.size == 1 && isImageFile(fileNames.first())

            if (isClipboardSync) {
                val latch = java.util.concurrent.CountDownLatch(1)
                var accepted = false
                val msg = NativeLocale.t("sync_clipboard_ask").replace("%s", senderAlias)
                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "[SYNC-DBG] confirm for prepare-upload")
                me.laumss.notipal.ui_common.Dialog.confirmResult(reactApplicationContext, msg) { ok ->

                    if (latch.count == 0L) return@confirmResult
                    accepted = ok
                    latch.countDown()
                }
                if (!latch.await(30, TimeUnit.SECONDS)) latch.countDown()
                if (!accepted) {
                    if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "Clipboard sync rejected by user from [$senderAlias]")
                    sendHttpResponse(output, 403, """{"error":"Rejected by user"}""")
                    return
                }
            }

            if (isSingleImage) {

                if (FloatingToolbarModule.currentInstance?.canPromptReceivedImage() != true) {
                    if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "Image from [$senderAlias] accepted to inbox " +
                        "(inNote=${FloatingToolbarModule.isInNoteApp()} doc=${FloatingToolbarModule.isDocumentContext()} " +
                        "panel=$insertPanelShowing)")
                    session.insertImagesImmediately = false
                } else {
                    val latch = java.util.concurrent.CountDownLatch(1)
                    var choice = me.laumss.notipal.ui_common.Dialog.ImageReceiveChoice.REJECT
                    val msg = NativeLocale.t("image_receive_ask").replace("%s", senderAlias)
                    val toolbar = FloatingToolbarModule.currentInstance
                    if (toolbar == null) {
                        choice = me.laumss.notipal.ui_common.Dialog.ImageReceiveChoice.KEEP
                        latch.countDown()
                    } else toolbar.prepareForImageReceive { ready ->
                        if (ready && toolbar.canPromptReceivedImage()) {
                            Log.i(TAG, "[INSERT-DBG/Kt] lasso settled; showing image receive dialog")
                            me.laumss.notipal.ui_common.Dialog.chooseImageReceive(reactApplicationContext, msg) {
                                if (latch.count == 0L) return@chooseImageReceive
                                choice = it
                                latch.countDown()
                            }
                        } else {
                            choice = me.laumss.notipal.ui_common.Dialog.ImageReceiveChoice.KEEP
                            latch.countDown()
                        }
                    }
                    if (!latch.await(60, TimeUnit.SECONDS)) {

                        latch.countDown()
                        choice = me.laumss.notipal.ui_common.Dialog.ImageReceiveChoice.REJECT
                    }
                    if (choice == me.laumss.notipal.ui_common.Dialog.ImageReceiveChoice.REJECT) {
                        sendHttpResponse(output, 403, """{"error":"Image rejected by user"}""")
                        return
                    }
                    session.insertImagesImmediately =
                        choice == me.laumss.notipal.ui_common.Dialog.ImageReceiveChoice.INSERT_NOW
                }
            }

            uploadSessions[sessionId] = session
            activeUploadSession = sessionId

            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "Accepted transfer from [$senderAlias]: ${fileNames.size} files")
            fileNames.forEach { if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "  - $it") }

            val response = JSONObject().apply {
                put("sessionId", sessionId)
                put("files", tokens)
            }
            sendHttpResponse(output, 200, response.toString())
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "prepare-upload error", e)
            sendHttpResponse(output, 400, """{"error":"Invalid body"}""")
        }
    }

    private fun handleUpload(
        output: BufferedOutputStream, input: BufferedInputStream,
        remoteIp: String, params: Map<String, String>, contentLength: Int
    ) {
        val sessionId = params["sessionId"] ?: ""
        val fileId = params["fileId"] ?: ""
        val token = params["token"] ?: ""

        if (sessionId.isEmpty() || fileId.isEmpty() || token.isEmpty()) {
            sendHttpResponse(output, 400, """{"error":"Missing parameters"}""")
            return
        }

        val session = uploadSessions[sessionId]
        if (session == null || !session.isValid()) {
            sendHttpResponse(output, 403, """{"error":"Invalid token or IP address"}""")
            return
        }
        if (session.tokens[fileId] != token) {
            sendHttpResponse(output, 403, """{"error":"Invalid token or IP address"}""")
            return
        }
        if (session.senderIp != remoteIp) {
            sendHttpResponse(output, 403, """{"error":"Invalid token or IP address"}""")
            return
        }

        val fileInfo = session.files[fileId]
        if (fileInfo == null) {
            sendHttpResponse(output, 400, """{"error":"Invalid file"}""")
            return
        }

        val ext = fileInfo.fileName.substringAfterLast('.', "").lowercase()

        val saveDir = when {
            isClipboardSyncFile(fileInfo.fileName) -> File(reactApplicationContext.cacheDir, "clipboard-sync")
            ext == "snplg" -> File("/sdcard/MyStyle")
            else -> File(receiveDir)
        }
        saveDir.mkdirs()
        val destFile = safeFileName(fileInfo.fileName, saveDir)

        if (BuildConfig.ENABLE_DEBUG) {
            Log.i(TAG, "[LS-DIAG] receive target dir=${saveDir.absolutePath} exists=${saveDir.exists()} canWrite=${saveDir.canWrite()} dest=$destFile")
            Log.i(TAG, "Receiving file: ${fileInfo.fileName} -> $destFile")
        }

        try {
            var received = 0L
            val sha = MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(65536)
            val total = if (contentLength > 0) contentLength.toLong() else fileInfo.size

            FileOutputStream(destFile).use { fos ->
                while (received < total) {
                    val toRead = minOf(buf.size.toLong(), total - received).toInt()
                    val n = input.read(buf, 0, toRead)
                    if (n <= 0) break
                    fos.write(buf, 0, n)
                    sha.update(buf, 0, n)
                    received += n
                }
            }

            if (received < total) throw IOException("short read: $received of $total bytes")

            if (fileInfo.sha256.isNotEmpty()) {
                val computed = sha.digest().joinToString("") { "%02x".format(it) }
                if (!computed.equals(fileInfo.sha256, ignoreCase = true)) {
                    if (BuildConfig.ENABLE_DEBUG) Log.w(TAG, "SHA256 mismatch for ${fileInfo.fileName}")
                }
            }

            session.received[fileId] = true
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "File received: ${fileInfo.fileName} -> $destFile ($received bytes)")

            if (isClipboardSyncFile(fileInfo.fileName)) {
                handleClipboardSyncReceive(destFile, session.senderAlias ?: remoteIp)
            } else if (isTextFile(fileInfo.fileName) || fileInfo.fileType.startsWith("text/")) {

                val textContent = destFile.readText(Charsets.UTF_8)
                deliverLocalSendText(textContent, fileInfo.fileName)
            } else {

                if (isImageFile(fileInfo.fileName)) {
                    if (session.insertImagesImmediately &&
                        (MosaicLink.isBoardVisible() ||
                            (FloatingToolbarModule.isInNoteApp() && !FloatingToolbarModule.isDocumentContext()))) {
                        val toolbar = FloatingToolbarModule.currentInstance
                        if (toolbar != null) {
                            Handler(Looper.getMainLooper()).post {
                                Handler(Looper.getMainLooper()).postDelayed({
                                    toolbar.requestInsertImage(destFile.absolutePath, deleteAfterInsert = destFile.absolutePath)
                                }, 300)
                            }
                        }
                    }
                }
            }

            if (session.received.values.all { it }) {
                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "Session $sessionId complete!")
                activeUploadSession = null
            }

            sendHttpResponse(output, 200, "")
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "File receive error", e)
            if (destFile.exists()) destFile.delete()

            uploadSessions.remove(sessionId)
            if (activeUploadSession == sessionId) activeUploadSession = null
            sendHttpResponse(output, 500, """{"error":"Unknown error by receiver"}""")
        }
    }

    private fun handleCancel(output: BufferedOutputStream, params: Map<String, String>) {
        val sessionId = params["sessionId"] ?: ""
        if (sessionId.isNotEmpty()) {
            uploadSessions.remove(sessionId)
            if (activeUploadSession == sessionId) {
                activeUploadSession = null
            }
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "Session cancelled: $sessionId")
        }
        sendHttpResponse(output, 200, "")
    }

    private fun buildLocalSendAnnouncement(): JSONObject = deviceInfoJson().put("announce", true)

    private fun handleLocalSendAnnouncement(senderIp: String, dto: JSONObject) {
        try {
            val fp = dto.optString("fingerprint", "")
            if (fp.isEmpty() || fp == deviceFingerprint) return
            val peerAlias = dto.optString("alias", "Unknown")
            val peerPort = dto.optInt("port", DEFAULT_PORT)
            val peerDeviceType = dto.optString("deviceType", "desktop")
            val peerProtocol = dto.optString("protocol", "https")
            val isAnnounce = dto.optBoolean("announce", dto.optBoolean("announcement", false))

            if (BuildConfig.ENABLE_DEBUG && discoveredPeers[fp] == null) {
                Log.i(TAG, "[LS-DIAG] multicast discovered peer $peerAlias @ $senderIp:$peerPort protocol=$peerProtocol announce=$isAnnounce")
            }

            discoveredPeers[fp] = DiscoveredPeer(
                alias = peerAlias, ip = senderIp, port = peerPort,
                deviceType = peerDeviceType, fingerprint = fp,
                useTls = peerProtocol != "http"
            )
            if (isAnnounce) {
                thread(isDaemon = true, name = "LocalSend-AnnounceReply") {
                    respondToAnnouncement(senderIp, peerPort, peerProtocol)
                }
            }
        } catch (_: Exception) {}
    }

    private fun respondToAnnouncement(ip: String, port: Int, peerProtocol: String) {
        val body = deviceInfoJson()
        try {
            val scheme = if (peerProtocol == "http") "http" else "https"
            staticHttpPostJson("$scheme://$ip:$port$API_BASE/register", body.toString())
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "[LS-DIAG] register response sent to $peerProtocol://$ip:$port")
        } catch (e: Exception) {
            try {
                body.put("announce", false)
                LocalSendDiscovery.sendUnicast(ip, body)
                if (BuildConfig.ENABLE_DEBUG) {
                    Log.i(TAG, "[LS-DIAG] register HTTP to $ip failed (${e.message}); UDP response sent instead")
                }
            } catch (_: Exception) {}
        }
    }

    private fun textGoesToClipboard(): Boolean =
        FloatingToolbarModule.isDocumentContext() ||
            (!FloatingToolbarModule.isInNoteApp() && !MosaicLink.isBoardVisible()) ||
            ImagePickerPanel.currentInstance?.isShowing == true

    @ReactMethod
    fun copyTextToClipboard(text: String) {
        Handler(Looper.getMainLooper()).post {
            try {
                val cm = reactApplicationContext.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("Notipal", text))
            } catch (e: Exception) {
                Log.w(TAG, "copyTextToClipboard failed: ${e.message}")
            }
        }
    }

    private fun deliverLocalSendText(text: String, fileName: String) {
        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "deliverLocalSendText: file=$fileName len=${text.length}")

        val onCanvas = FloatingToolbarModule.currentInstance?.isReceivedTextPromptSurface() == true
        Handler(Looper.getMainLooper()).post {
            when {
                textGoesToClipboard() -> {
                    if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "deliverLocalSendText: to clipboard " +
                        "(inNote=${FloatingToolbarModule.isInNoteApp()} doc=${FloatingToolbarModule.isDocumentContext()})")
                    copyTextToClipboard(text)
                }
                FloatingBubbleModule.isTextReceiverShowing() || FloatingToolbarModule.isTextReceiveModeActive() ->
                    sendEvent("onTextReceived", Arguments.createMap().apply {
                        putString("text", text)
                        putString("fileName", fileName)
                    })
                onCanvas && !FloatingToolbarModule.anyOtherNotipalUiShowing() -> {

                    copyTextToClipboard(text)
                    promptOpenTextReceiver(text)
                }
                else -> {
                    if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "deliverLocalSendText: to clipboard (other UI or not on canvas)")
                    copyTextToClipboard(text)
                }
            }
        }
    }

    private fun promptOpenTextReceiver(text: String) {
        me.laumss.notipal.ui_common.Dialog.confirmResult(
            reactApplicationContext, NativeLocale.t("text_recv_ask")
        ) { confirmed ->
            if (confirmed) {
                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "text_recv_ask confirmed: requesting text receiver bubble")
                sendEvent("onOpenTextReceiverRequested", Arguments.createMap().apply { putString("text", text) })
            }
        }
    }

    private fun sendEvent(eventName: String, params: WritableMap) {
        if (eventName == "onTextReceived") {

            val text = params.getString("text") ?: ""
            val fileName = params.getString("fileName") ?: "message.txt"
            val pt = addPendingText(text, fileName)
            if (BuildConfig.ENABLE_DEBUG) Log.d(TAG, "sendEvent → $eventName buffered id=${pt.id}, trying emit")
            params.putString("_pendingId", pt.id)
        }

        if (!reactApplicationContext.hasActiveCatalystInstance()) {
            if (BuildConfig.ENABLE_DEBUG) Log.w(TAG, "sendEvent → $eventName: bridge unavailable, will flush on next activation")
            return
        }

        try {
            reactApplicationContext
                .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                .emit(eventName, params)
            if (BuildConfig.ENABLE_DEBUG) Log.d(TAG, "sendEvent → $eventName OK")
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) Log.w(TAG, "sendEvent → $eventName FAILED (bridge transition): ${e.message}")
        }

    }

    private fun getLocalIp(): String = getLocalIpStatic()

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) {
                if (sb.isNotEmpty() && sb.last() == '\r') sb.deleteCharAt(sb.length - 1)
                return sb.toString()
            }
            sb.append(b.toChar())
        }
    }

    private fun readBody(input: InputStream, length: Int): String {
        if (length <= 0) return ""
        val buf = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(buf, read, length - read)
            if (n <= 0) break
            read += n
        }
        return String(buf, 0, read)
    }

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isEmpty()) return emptyMap()
        return query.split("&").mapNotNull {
            val kv = it.split("=", limit = 2)
            if (kv.size == 2) {
                try {
                    java.net.URLDecoder.decode(kv[0], "UTF-8") to
                        java.net.URLDecoder.decode(kv[1], "UTF-8")
                } catch (e: Exception) { null }
            } else null
        }.toMap()
    }

    private fun sendHttpResponse(output: BufferedOutputStream, status: Int, body: String) {
        val statusText = when (status) {
            200 -> "OK"
            204 -> "No Content"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            403 -> "Forbidden"
            404 -> "Not Found"
            409 -> "Conflict"
            500 -> "Internal Server Error"
            else -> "Unknown"
        }

        val bodyBytes = body.toByteArray()
        val header = buildString {
            append("HTTP/1.1 $status $statusText\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Content-Length: ${bodyBytes.size}\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        output.write(header.toByteArray())
        if (bodyBytes.isNotEmpty()) {
            output.write(bodyBytes)
        }
        output.flush()
    }

    private fun safeFileName(name: String, dir: File): File {
        var target = File(dir, File(name).name)
        if (!target.exists()) return target
        val dotIdx = name.lastIndexOf('.')
        val stem = if (dotIdx > 0) name.substring(0, dotIdx) else name
        val ext = if (dotIdx > 0) name.substring(dotIdx) else ""
        var counter = 1
        while (target.exists()) {
            target = File(dir, "${stem}(${counter})${ext}")
            counter++
        }
        return target
    }

    private fun isImageFile(name: String): Boolean = isImageFileStatic(name)

    private fun isTextFile(name: String): Boolean {
        return name == "message.txt"
    }

    private fun handleClipboardSyncReceive(zipFile: File, senderAlias: String) {
        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "Clipboard sync received from [$senderAlias], importing (user already confirmed in prepare-upload)")
        thread(isDaemon = true) { doImportClipboardSync(zipFile) }
    }

    private fun isClipboardSyncFile(name: String): Boolean = name.startsWith("clipboard_sync_") && name.endsWith(".zip")

    private fun doImportClipboardSync(zipFile: File) {
        val stage = File(reactApplicationContext.cacheDir, "clipboard-sync-${System.nanoTime()}")
        try {
            stage.mkdirs()
            var clipsJson: String? = null
            ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (entry.name == "clips.json") {
                        clipsJson = zis.readBytes().toString(Charsets.UTF_8)
                        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "[SYNC-DBG] clips.json raw: $clipsJson")
                    } else if (entry.name.startsWith("stickers/")) {
                        val name = File(entry.name.removePrefix("stickers/")).name
                        if (name.isNotEmpty()) File(stage, name).outputStream().buffered().use { out -> zis.copyTo(out) }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            val sent = JSONObject(clipsJson ?: error("clips.json missing"))
            val clipDir = File(NotipalDirs.CLIPBOARD).apply { mkdirs() }

            val next = JSONObject()
            for (i in 1..FloatingToolbarModule.CLIP_SLOT_COUNT) {
                val key = i.toString()
                val name = sent.optString(key, "").takeIf { it.isNotEmpty() && it != "null" }?.let { File(it).name }
                next.put(key, if (name != null && File(stage, name).isFile) File(clipDir, name).absolutePath else JSONObject.NULL)
            }

            val prefs = reactApplicationContext.getSharedPreferences(NotipalPrefs.PRESETS_FILE, 0)
            val old = prefs.getString(NotipalPrefs.CLIP_SLOTS_KEY, null)?.let { runCatching { JSONObject(it) }.getOrNull() }
            var removed = 0
            for (i in 1..FloatingToolbarModule.CLIP_SLOT_COUNT) {
                val path = old?.optString(i.toString(), "")?.takeIf { it.startsWith("/") } ?: continue
                removed += deleteClipFiles(File(path))
            }
            var moved = 0
            for (file in stage.listFiles().orEmpty()) {
                if (NotipalDirs.move(file, File(clipDir, file.name))) moved++
                else Log.w(TAG, "[SYNC-DBG] clip file not moved: ${file.name}")
            }
            prefs.edit().putString(NotipalPrefs.CLIP_SLOTS_KEY, next.toString()).apply()
            Log.i(TAG, "Clipboard sync imported slots=$next filesIn=$moved oldFilesDeleted=$removed")
            Handler(Looper.getMainLooper()).post {
                me.laumss.notipal.ui_common.Dialog.tip(
                    reactApplicationContext, NativeLocale.t("sync_clipboard_ok"), isSuccess = true,
                )
                refreshToolbarClipIcons()
                sendEvent("clipsChanged", Arguments.createMap())
            }
        } catch (e: Exception) {
            Log.e(TAG, "doImportClipboardSync failed, clipboard unchanged", e)
        } finally {
            zipFile.delete()
            stage.deleteRecursively()
        }
    }

    private fun deleteClipFiles(sticker: File): Int =
        sticker.parentFile?.listFiles()?.count { it.isFile && it.name.startsWith(sticker.name) && it.delete() } ?: 0

    private fun refreshToolbarClipIcons() {
        val prefs = reactApplicationContext.getSharedPreferences(NotipalPrefs.PRESETS_FILE, 0)
        val clipsJson = prefs.getString(NotipalPrefs.CLIP_SLOTS_KEY, null) ?: return
        try {
            val obj = JSONObject(clipsJson)
            val filled = JSONArray()
            for (i in 1..FloatingToolbarModule.CLIP_SLOT_COUNT) {
                val path = obj.optString(i.toString(), "")
                val fileExists = path.isNotEmpty() && File(path).exists()
                filled.put(fileExists)
                if (BuildConfig.ENABLE_DEBUG) Log.d(TAG, "[SYNC-DBG] refreshClipIcons slot $i: path='$path' exists=$fileExists")
            }
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "[SYNC-DBG] refreshClipIcons sending: $filled")
            FloatingToolbarModule.currentInstance?.updateTitleClips(filled.toString())
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) Log.w(TAG, "refreshToolbarClipIcons: ${e.message}")
        }
    }

    data class FileInfo(
        val id: String,
        val fileName: String,
        val size: Long,
        val fileType: String,
        val sha256: String
    )

    data class UploadSession(
        val sessionId: String,
        val senderIp: String,
        var senderAlias: String = "",
        val createdAt: Long = System.currentTimeMillis(),
        val timeout: Long = 600_000L,
        val files: MutableMap<String, FileInfo> = mutableMapOf(),
        val tokens: MutableMap<String, String> = mutableMapOf(),
        val received: MutableMap<String, Boolean> = mutableMapOf(),
        var insertImagesImmediately: Boolean = false
    ) {
        fun isValid(): Boolean = (System.currentTimeMillis() - createdAt) < timeout
    }
}
