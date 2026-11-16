package me.laumss.notipal.localsend

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import me.laumss.notipal.BuildConfig
import me.laumss.notipal.common.App
import me.laumss.notipal.common.Log
import me.laumss.notipal.common.LongFlows
import me.laumss.notipal.common.Main
import me.laumss.notipal.common.PluginIdentity
import me.laumss.notipal.common.Strings
import me.laumss.notipal.host.HostPlugins
import me.laumss.notipal.host.HostSession
import me.laumss.notipal.host.PageRender
import me.laumss.notipal.links.DocuCraftLink
import me.laumss.notipal.toolbar.Toolbar
import me.laumss.notipal.ui_common.Dialogs
import me.laumss.notipal.ui_common.Panel
import me.laumss.notipal.ui_common.Panels
import me.laumss.notipal.ui_common.ProgressPanel
import me.laumss.notipal.ui_common.ScrollHost
import me.laumss.notipal.ui_common.Style
import me.laumss.notipal.ui_common.Widgets
import java.io.File
import java.io.FileNotFoundException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException
import kotlin.concurrent.thread

class SendPanel private constructor() : Panel("send") {
    override val tag: String get() = "SendPanel"

    companion object {
        private const val STAGED_TTL_MS = 10_000L
        private const val LINK_TYPE_URL = 4

        private const val PEER_POLL_MS = 3000L

        private const val RESCAN_SETTLE_MS = 1500L

        private const val AUTO_CLOSE_MS = 800L
        private const val UNCHECKED_ALPHA = 0.45f

        private val instance = SendPanel()

        val current: SendPanel? get() = instance.takeIf { it.showing }

        private class Staged(val data: SendData, val at: Long = System.currentTimeMillis())

        @Volatile private var staged: Staged? = null

        private fun takeStaged(): Staged? {
            val s = staged
            staged = null
            return s?.takeIf { System.currentTimeMillis() - it.at in 0 until STAGED_TTL_MS }
        }

        fun open(
            fromBubble: Boolean = false,
            syncClipboard: Boolean = false,
            useStaged: Boolean = false,
            allowAutoSend: Boolean = true,
            data: SendData? = null,
            tempDir: File? = null,
            onShown: (Boolean) -> Unit = {},
        ) {
            Main.post { instance.present(fromBubble, syncClipboard, useStaged, allowAutoSend, data, tempDir, onShown) }
        }

        fun openPending(hintKey: String, onShown: (Int?) -> Unit) {
            Main.post {
                instance.pendingHintKey = hintKey
                instance.present(false, false, false, true, null, null) { shown ->
                    onShown(if (shown) instance.sessionGen else null)
                }
            }
        }

        fun fillPending(gen: Int, data: SendData, onResult: (Boolean) -> Unit) {
            Main.post {
                val panel = current
                if (panel == null || panel.sessionGen != gen) {
                    onResult(false)
                    return@post
                }
                panel.updateLassoData(data)
                onResult(true)
            }
        }

        fun setLassoData(data: SendData) {
            Main.post {
                val panel = current
                if (panel != null) panel.updateLassoData(data) else staged = Staged(data)
            }
        }

        fun openDocText(text: String) {
            if (text.isBlank()) return
            open(allowAutoSend = false, data = SendData(text, emptyList(), emptyList()))
        }
    }

    private sealed class Item(val key: String) {
        class Text(val text: String) : Item("text")
        class Image(val path: String, val number: Int) : Item("image:$path")
        class FileRef(val path: String, val label: String) : Item("file:$path")
        class Link(val url: String, val label: String) : Item("url:$url")
    }

    private val ctx: Context get() = App.context

    private var pending = SendData.EMPTY
    private var dataReady = false
    private var items: List<Item> = emptyList()
    private val unchecked = HashSet<String>()
    private var selectedPeer: DiscoveredPeer? = null
    private var sending = false
    private var cameFromBubble = false
    private var clipboardSyncMode = false
    private var allowAutoSend = true
    private var autoSendArmed = false
    private var tempDir: File? = null
    private var sessionGen = 0

    private var pendingHintKey = "extracting"
    private var loadingHintKey = "extracting"

    private var contentLabel: TextView? = null
    private var contentBox: LinearLayout? = null
    private var scrollHost: ScrollHost? = null
    private var peerContainer: LinearLayout? = null
    private var peerSignature: String? = null
    private var scanLabel: TextView? = null
    private var statusView: TextView? = null
    private var sendBtn: TextView? = null

    private val peerPoll = object : Runnable {
        override fun run() {

            refreshPeerList()
            Main.handler.postDelayed(this, PEER_POLL_MS)
        }
    }

    private fun present(
        fromBubble: Boolean,
        syncClipboard: Boolean,
        useStaged: Boolean,
        allowAutoSend: Boolean,
        data: SendData?,
        tempDir: File?,
        onShown: (Boolean) -> Unit,
    ) {
        if (!LocalSend.isWifiConnected()) {
            Dialogs.tip(Strings.get("no_wifi"))
            onShown(false)
            return
        }
        deleteTempDir()
        sessionGen++
        pending = SendData.EMPTY
        dataReady = false
        items = emptyList()
        unchecked.clear()
        selectedPeer = null
        sending = false
        cameFromBubble = fromBubble
        if (BuildConfig.ENABLE_DEBUG) Log.i(tag, "show fromBubble=$fromBubble sync=$syncClipboard staged=$useStaged")
        clipboardSyncMode = syncClipboard
        this.allowAutoSend = allowAutoSend
        autoSendArmed = false
        val pre = takeStaged()?.takeIf { useStaged && !syncClipboard }

        val wasShowing = showing
        loadingHintKey = pendingHintKey
        pendingHintKey = "extracting"
        Panels.open(this) { shown ->
            if (shown) {
                if (wasShowing) refreshAll()
                if (!LocalSend.running) LocalSend.start(showNetworkTip = true)
                LocalSendClient.refreshPeers()
                startPeerPolling()

                LocalSendDiscovery.setLocalSendAnnouncing(true)
                tempDir?.let { this.tempDir = it }
                pre?.let { updateLassoData(it.data) }
                data?.let { updateLassoData(it) }
            }
            onShown(shown)
        }
    }

    private fun deleteTempDir() {
        tempDir?.deleteRecursively()
        tempDir = null
    }

    override fun onHide() {
        stopPeerPolling()
        LocalSendDiscovery.setLocalSendAnnouncing(false)
        deleteTempDir()
        autoSendArmed = false
        contentLabel = null; contentBox = null; scrollHost = null; peerContainer = null; peerSignature = null
        scanLabel = null; statusView = null; sendBtn = null
    }

    override fun suspendVisibility() {
        super.suspendVisibility()
        Main.post { stopPeerPolling() }
    }

    override fun resumeVisibility() {
        super.resumeVisibility()
        Main.post { if (showing) startPeerPolling() }
    }

    private fun updateLassoData(data: SendData) {
        pending = data
        dataReady = true
        items = buildItems()
        unchecked.clear()
        rebuildContent()
        setStatus(null)
        updateSendBtnState()
        autoSendArmed = allowAutoSend && showing && !clipboardSyncMode && LocalSendClient.defaultPeer() != null
        maybeAutoSendToDefault()
    }

    private fun buildItems(): List<Item> {
        val out = ArrayList<Item>()
        if (pending.text.isNotBlank()) out.add(Item.Text(pending.text))
        pending.images.forEachIndexed { i, path -> out.add(Item.Image(path, i + 1)) }
        for (f in pending.linked) {
            if (f.path.isBlank()) continue
            out.add(if (f.linkType == LINK_TYPE_URL) Item.Link(f.path, f.label) else Item.FileRef(f.path, f.label))
        }
        return out
    }

    private fun selectedItems(): List<Item> = items.filter { it.key !in unchecked }

    private fun maybeAutoSendToDefault() {
        if (!autoSendArmed || sending || clipboardSyncMode) return
        val def = LocalSendClient.defaultPeer() ?: run { autoSendArmed = false; return }
        val only = items.singleOrNull()
        if (only !is Item.Text && only !is Item.Image) {
            autoSendArmed = false
            return
        }
        val live = LocalSendClient.peersSnapshot().firstOrNull { it.fingerprint == def.fingerprint } ?: return
        autoSendArmed = false
        selectedPeer = live
        refreshPeerList()
        if (BuildConfig.ENABLE_DEBUG) Log.i(tag, "auto-send to default peer ${live.alias} (${only.key})")
        sendSelected()
    }

    override fun build(root: LinearLayout) {
        root.addView(Widgets.panelHeader(ctx, Strings.get("send_title")))

        root.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val label = sectionLabel(contentTitle())
            contentLabel = label
            addView(label)
            val box = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(Style.dp(20), 0, Style.dp(20), Style.dp(8))
            }
            contentBox = box

            addView(CappedScrollView(ctx, (screenH * 0.26f).toInt()).apply {
                isVerticalScrollBarEnabled = false
                addView(box)
            })
            rebuildContent()
        })

        root.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(Widgets.divider(ctx, dark = false))
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(Style.dp(28), Style.dp(14), Style.dp(20), Style.dp(6))
            }
            row.addView(TextView(ctx).apply {
                text = Strings.get("send_devices")
                textSize = Style.sp(14f); setTextColor(Style.subtle)
                typeface = Style.bold
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            val scan = TextView(ctx).apply {
                text = Strings.get("rescan")
                textSize = Style.sp(14f); setTextColor(Style.ink)
                setPadding(Style.dp(12), Style.dp(8), Style.dp(8), Style.dp(8))
                paint.isUnderlineText = true
                setOnClickListener { rescan() }
            }
            scanLabel = scan
            row.addView(scan)
            addView(row)
        })

        val sh = ScrollHost(ctx, ScrollHost.Mode.FREE, overlayBar = true)
        scrollHost = sh
        peerContainer = sh.content.apply {
            setPadding(Style.dp(20), Style.dp(2), Style.dp(20), Style.dp(8))
        }
        peerSignature = null
        root.addView(sh)
        refreshPeerList()

        val status = TextView(ctx).apply {
            textSize = Style.sp(14f); setTextColor(Style.ink)
            maxLines = 2; ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { rightMargin = Style.dp(16) }
        }
        statusView = status
        val cancel = Widgets.outlinedButton(ctx, Strings.get("cancel")) { closeAndRestore() }
        (cancel.layoutParams as LinearLayout.LayoutParams).rightMargin = Style.dp(16)
        val send = Widgets.filledButton(ctx, Strings.get("send_btn")) {
            if (clipboardSyncMode) handleSyncClipboard() else sendSelected()
        }
        sendBtn = send
        updateSendBtnState()
        root.addView(Widgets.bottomBar(ctx, leftFlex = status, rightButtons = listOf(cancel, send)))
    }

    private fun contentTitle(): String = Strings.get(if (clipboardSyncMode) "sync_clipboard_btn" else "send_content")

    private fun refreshAll() {
        contentLabel?.text = contentTitle()
        rebuildContent()
        peerSignature = null
        refreshPeerList()
        setStatus(null)
        updateSendBtnState()
    }

    private fun sectionLabel(title: String): TextView =
        TextView(ctx).apply {
            text = title
            textSize = Style.sp(14f); setTextColor(Style.subtle)
            typeface = Style.bold
            setPadding(Style.dp(28), Style.dp(16), Style.dp(28), Style.dp(8))
        }

    private fun setStatus(text: String?) {
        statusView?.text = text ?: ""
    }

    private fun rescan() {
        if (sending) return
        scanLabel?.apply { text = Strings.get("peers_scanning_short"); isEnabled = false }
        LocalSendClient.probeDefaultPeer()
        LocalSendClient.reprobeKnownPeers()
        LocalSendClient.triggerScan()
        Main.handler.postDelayed({
            scanLabel?.apply { text = Strings.get("rescan"); isEnabled = true }
            refreshPeerList()
        }, RESCAN_SETTLE_MS)
    }

    private fun rebuildContent() {
        val box = contentBox ?: return
        box.removeAllViews()
        if (clipboardSyncMode) {
            box.addView(contentRow(
                icon = "icons/ic_tool_clipboard.xml", thumbPath = null,
                title = Strings.get("sync_clipboard_btn"),
                meta = Strings.get("sync_clipboard_desc"),
                checkKey = null,
            ))
            return
        }
        if (!dataReady) {
            box.addView(hintText(Strings.get(loadingHintKey)))
            return
        }
        if (items.isEmpty()) {
            box.addView(hintText(Strings.get("send_nothing")))
            return
        }
        val checkable = items.size > 1
        for (item in items) {
            val key = if (checkable) item.key else null
            box.addView(when (item) {
                is Item.Text -> contentRow(
                    "icons/ic_send_text.xml", null,
                    item.text.replace(Regex("\\s+"), " ").trim(),
                    Strings.get("send_item_text", item.text.length), key, titleLines = 2,
                )
                is Item.Image -> contentRow(
                    "icons/ic_send_file.xml", item.path,
                    if (pending.images.size > 1) Strings.get("send_item_image_n", item.number)
                    else Strings.get("send_item_image"),
                    fileMeta(item.path), key,
                )
                is Item.FileRef -> contentRow(
                    "icons/ic_send_file.xml", null,
                    item.label.ifBlank { File(item.path).name }, fileMeta(item.path), key,
                    middleEllipsize = true,
                )
                is Item.Link -> contentRow(
                    "icons/ic_send_url.xml", null,
                    item.url, Strings.get("send_item_link"), key,
                    middleEllipsize = true,
                )
            })
        }
    }

    private fun fileMeta(path: String): String {
        val f = File(path)
        val ext = f.extension.uppercase()
        val size = try {
            if (f.exists()) Widgets.formatSize(f.length()) else ""
        } catch (e: SecurityException) {
            Log.w(tag, "file size unreadable: ${e.message}")
            ""
        }
        return listOf(ext, size).filter { it.isNotEmpty() }.joinToString(" · ")
    }

    private fun hintText(text: String): TextView = TextView(ctx).apply {
        this.text = text
        textSize = Style.sp(14f); setTextColor(Style.emptyText)
        setPadding(Style.dp(8), Style.dp(12), Style.dp(8), Style.dp(12))
    }

    private fun contentRow(
        icon: String,
        thumbPath: String?,
        title: String,
        meta: String,
        checkKey: String?,
        titleLines: Int = 1,
        middleEllipsize: Boolean = false,
    ): LinearLayout {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Style.dp(8), Style.dp(8), Style.dp(8), Style.dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        val check = if (checkKey != null) ImageView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(Style.dp(22), Style.dp(22)).apply { rightMargin = Style.dp(14) }
        } else {
            null
        }
        fun paintCheck() {
            val on = checkKey !in unchecked
            check?.setImageDrawable(Style.iconPx(
                if (on) "icons/ic_check_on.xml" else "icons/ic_check_off.xml", Style.dp(22), Style.ink))
            row.alpha = if (on) 1f else UNCHECKED_ALPHA
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

        val tile = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(Style.dp(44), Style.dp(44)).apply { rightMargin = Style.dp(14) }
            background = Style.box(Style.paper, Style.highlight, 1f, 4f)
        }
        val iconView = ImageView(ctx).apply {
            setImageDrawable(Style.iconPx(icon, Style.dp(24), Style.ink))
            scaleType = ImageView.ScaleType.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        tile.addView(iconView)
        if (thumbPath != null) loadThumbnail(thumbPath, Style.dp(44), iconView)
        row.addView(tile)

        val info = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        info.addView(TextView(ctx).apply {
            text = title; textSize = Style.sp(15f); setTextColor(Style.ink)
            maxLines = titleLines
            if (titleLines == 1) isSingleLine = true
            ellipsize = if (middleEllipsize) TextUtils.TruncateAt.MIDDLE else TextUtils.TruncateAt.END
        })
        if (meta.isNotEmpty()) info.addView(TextView(ctx).apply {
            text = meta; textSize = Style.sp(12f); setTextColor(Style.hint)
            isSingleLine = true; ellipsize = TextUtils.TruncateAt.END
            setPadding(0, Style.dp(3), 0, 0)
        })
        row.addView(info)
        return row
    }

    private fun loadThumbnail(path: String, sizePx: Int, target: ImageView) {
        val gen = sessionGen
        thread(isDaemon = true, name = "SendPanel-thumb") {
            try {
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, opts)
                var sample = 1
                while (opts.outWidth / (sample * 2) >= sizePx && opts.outHeight / (sample * 2) >= sizePx) sample *= 2
                opts.inSampleSize = sample
                opts.inJustDecodeBounds = false
                val bmp = BitmapFactory.decodeFile(path, opts) ?: return@thread
                Main.post {
                    if (gen != sessionGen) return@post
                    target.scaleType = ImageView.ScaleType.CENTER_CROP
                    target.setImageBitmap(bmp)
                }
            } catch (e: Exception) {
                Log.w(tag, "thumbnail failed: ${e.message}")
            } catch (e: OutOfMemoryError) {
                Log.w(tag, "thumbnail out of memory: $path")
            }
        }
    }

    private fun refreshPeerList() {
        Main.post {
            val container = peerContainer ?: return@post
            val peers = LocalSendClient.peersSnapshot().sortedBy { it.alias.lowercase() }
            val defaultPeer = LocalSendClient.defaultPeer()

            if (defaultPeer != null) {
                peers.firstOrNull { it.fingerprint == defaultPeer.fingerprint }?.let { live ->
                    if (live.ip != defaultPeer.ip || live.port != defaultPeer.port) {
                        LocalSendClient.setDefaultPeer(live)
                    }
                }
            }
            if (peers.isNotEmpty() &&
                (selectedPeer == null || peers.none { it.fingerprint == selectedPeer?.fingerprint })) {
                selectedPeer = peers.firstOrNull { it.fingerprint == defaultPeer?.fingerprint } ?: peers.first()
            } else if (peers.isEmpty()) {
                selectedPeer = null
            }

            val signature = peers.joinToString("|") { "${it.fingerprint}@${it.ip}:${it.port}/${it.alias}" } +
                "#${selectedPeer?.fingerprint}#${defaultPeer?.fingerprint}"
            if (signature != peerSignature) {
                peerSignature = signature
                container.removeAllViews()
                if (peers.isEmpty()) {
                    container.addView(Widgets.empty(ctx, Strings.get("rescan"), Strings.get("peers_none")))
                } else {
                    for (peer in peers) container.addView(createPeerRow(peer, defaultPeer))
                    container.addView(TextView(ctx).apply {
                        text = Strings.get("send_default_hint")
                        textSize = Style.sp(12f); setTextColor(Style.emptyText)
                        setPadding(Style.dp(4), Style.dp(10), Style.dp(4), 0)
                    })
                }
                scrollHost?.refreshThumb()
            }
            updateSendBtnState()
            maybeAutoSendToDefault()
        }
    }

    private fun createPeerRow(peer: DiscoveredPeer, defaultPeer: DiscoveredPeer?): LinearLayout {
        val isSelected = selectedPeer?.fingerprint == peer.fingerprint
        val isDefault = defaultPeer?.fingerprint == peer.fingerprint
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Style.dp(14), Style.dp(12), Style.dp(14), Style.dp(12))
            background = Style.box(
                Style.paper,
                if (isSelected) Style.ink else Style.highlight,
                if (isSelected) 2f else 1f,
                6f,
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = Style.dp(8) }
            setOnClickListener {
                if (sending) return@setOnClickListener
                autoSendArmed = false
                selectedPeer = peer
                refreshPeerList()
            }

            setOnLongClickListener {
                val cur = LocalSendClient.defaultPeer()
                LocalSendClient.setDefaultPeer(if (cur?.fingerprint == peer.fingerprint) null else peer)
                refreshPeerList()
                true
            }
        }

        val icon = when (peer.deviceType) {
            "mobile" -> "icons/ic_device_phone.xml"
            "tablet" -> "icons/ic_device_tablet.xml"
            else -> "icons/ic_device_desktop.xml"
        }
        row.addView(ImageView(ctx).apply {
            setImageDrawable(Style.iconPx(icon, Style.dp(28), Style.ink))
            scaleType = ImageView.ScaleType.CENTER
            layoutParams = LinearLayout.LayoutParams(Style.dp(40), Style.dp(40)).apply { rightMargin = Style.dp(14) }
        })

        val info = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val nameRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        nameRow.addView(TextView(ctx).apply {
            text = peer.alias; textSize = Style.sp(16f); setTextColor(Style.ink)
            typeface = if (isSelected) Style.bold else Style.normal
            isSingleLine = true; ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT, 1f,
            )
        })
        if (isDefault) {
            nameRow.addView(TextView(ctx).apply {
                text = Strings.get("peer_default_badge")
                textSize = Style.sp(11f); setTextColor(Style.ink)
                setPadding(Style.dp(6), Style.dp(1), Style.dp(6), Style.dp(1))
                background = Style.box(Style.paper, Style.ink, 1f, 3f)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { leftMargin = Style.dp(8) }
            })
        }
        info.addView(nameRow)
        info.addView(TextView(ctx).apply {
            text = peer.ip; textSize = Style.sp(12f); setTextColor(Style.hint)
            isSingleLine = true
            setPadding(0, Style.dp(3), 0, 0)
        })
        row.addView(info)

        row.addView(RadioMark(ctx, isSelected).apply {
            layoutParams = LinearLayout.LayoutParams(Style.dp(24), Style.dp(24)).apply { leftMargin = Style.dp(12) }
        })
        return row
    }

    private class RadioMark(ctx: Context, private val on: Boolean) : View(ctx) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(canvas: Canvas) {
            val r = minOf(width, height) / 2f
            val cx = width / 2f
            val cy = height / 2f
            val stroke = r * if (on) 0.16f else 0.1f
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = stroke
            paint.color = if (on) Style.ink else Style.emptyText
            canvas.drawCircle(cx, cy, r - stroke / 2f, paint)
            if (on) {
                paint.style = Paint.Style.FILL
                canvas.drawCircle(cx, cy, r * 0.45f, paint)
            }
        }
    }

    private class CappedScrollView(ctx: Context, private val maxH: Int) : ScrollView(ctx) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST))
        }
    }

    private fun withInternetPermission(onGranted: () -> Unit) {
        HostPlugins.requestPermission(HostPlugins.INTERNET) { granted ->
            if (!granted) {
                sending = false
                setStatus(Strings.get("perm_required"))
                updateSendBtnState()
                return@requestPermission
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
        setStatus(Strings.get("sending"))
        updateSendBtnState()
        val needsFiles = chosen.any { it is Item.Image || it is Item.FileRef }
        val go = { withInternetPermission { thread(isDaemon = true, name = "SendPanel-send") { runSend(peer, chosen) } } }
        if (!needsFiles) {
            go()
            return
        }
        HostPlugins.requestPermission(HostPlugins.FILE_READ) { granted ->
            if (!granted) {
                sending = false
                setStatus(Strings.get("file_read_permission_needed"))
                updateSendBtnState()
                return@requestPermission
            }
            go()
        }
    }

    private fun runSend(peer: DiscoveredPeer, chosen: List<Item>) {

        PluginIdentity.claim()
        val texts = chosen.mapNotNull {
            when (it) {
                is Item.Text -> it.text
                is Item.Link -> it.url
                else -> null
            }
        }
        val files = chosen.mapNotNull {
            when (it) {
                is Item.Image -> it.path
                is Item.FileRef -> it.path
                else -> null
            }
        }
        val steps = texts.size + if (files.isEmpty()) 0 else 1
        var step = 0
        fun progress() {
            step++
            val now = step
            if (steps > 1) Main.post { setStatus(Strings.get("sending_progress", now, steps)) }
        }
        try {
            for (t in texts) {
                progress()
                LocalSendClient.sendTextDirect(peer.ip, peer.port, t, peer.useTls)
            }
            if (files.isNotEmpty()) {
                progress()
                LocalSendClient.sendFilesDirect(peer.ip, peer.port, files, peer.useTls)
            }
            Main.post {
                setStatus(Strings.get("send_success"))
                scheduleAutoClose()
            }
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) Log.e(tag, "send failed: ${e.javaClass.simpleName}: ${e.message}")
            val msg = failureText(e, "send_rejected")
            Main.post {
                sending = false
                setStatus(msg)
                updateSendBtnState()
            }
        }
    }

    private fun failureText(e: Exception, rejectedKey: String): String = when {
        e.message?.contains("403") == true -> Strings.get(rejectedKey)
        e is FileNotFoundException || e.message?.startsWith("File not found") == true ->
            Strings.get("send_err_missing")
        e is SocketTimeoutException || e is ConnectException || e is NoRouteToHostException ||
            e is SSLException -> Strings.get("send_err_connect")
        else -> Strings.get("send_failed")
    }

    private fun handleSyncClipboard() {
        val peer = selectedPeer ?: return
        if (sending) return
        sending = true
        setStatus(Strings.get("sync_packaging"))
        updateSendBtnState()
        HostPlugins.requestPermission(HostPlugins.FILE_READ) { readGranted ->
            if (!readGranted) {
                sending = false
                setStatus(Strings.get("file_read_permission_needed"))
                updateSendBtnState()
                return@requestPermission
            }
            HostPlugins.requestPermission(HostPlugins.FILE_WRITE) { writeGranted ->
                if (!writeGranted) {
                    sending = false
                    setStatus(Strings.get("perm_required"))
                    updateSendBtnState()
                    return@requestPermission
                }
                withInternetPermission {
                    thread(isDaemon = true, name = "SendPanel-sync") { runSyncSend(peer) }
                }
            }
        }
    }

    private fun runSyncSend(peer: DiscoveredPeer) {
        PluginIdentity.claim()
        try {
            val zip = me.laumss.notipal.clipboard.ClipboardIo.packageSync()
            if (zip == null) {
                Main.post {
                    sending = false
                    setStatus(Strings.get("sync_clipboard_empty"))
                    updateSendBtnState()
                }
                return
            }
            Main.post { setStatus(Strings.get("sync_waiting")) }
            try {
                LocalSendClient.sendFileDirect(peer.ip, peer.port, zip.absolutePath, peer.useTls)
            } finally {
                if (!zip.delete()) Log.w(tag, "sync package not deleted: ${zip.name}")
            }
            Main.post {
                setStatus(Strings.get("send_success"))
                scheduleAutoClose()
            }
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) Log.e(tag, "sync failed: ${e.javaClass.simpleName}: ${e.message}")
            val msg = failureText(e, "sync_rejected")
            Main.post {
                sending = false
                setStatus(msg)
                updateSendBtnState()
            }
        }
    }

    private fun startPeerPolling() {
        stopPeerPolling()
        Main.handler.post(peerPoll)
    }

    private fun stopPeerPolling() {
        Main.handler.removeCallbacks(peerPoll)
    }

    private fun scheduleAutoClose() {
        val gen = sessionGen
        Main.handler.postDelayed({
            if (gen != sessionGen) return@postDelayed
            closeAndRestore()
        }, AUTO_CLOSE_MS)
    }

    private fun closeAndRestore() {
        if (BuildConfig.ENABLE_DEBUG) Log.i(tag, "closeAndRestore fromBubble=$cameFromBubble")
        hide()
    }

    private fun updateSendBtnState() {
        val btn = sendBtn ?: return
        val count = if (clipboardSyncMode) 1 else selectedItems().size
        val enabled = selectedPeer != null && !sending && count > 0
        btn.text = when {
            clipboardSyncMode -> Strings.get("sync_clipboard_btn")
            count > 1 -> Strings.get("send_btn_count", count)
            else -> Strings.get("send_btn")
        }
        btn.alpha = if (enabled) 1f else Style.DISABLED_ALPHA
        btn.isEnabled = enabled
        scanLabel?.alpha = if (sending) Style.DISABLED_ALPHA else 1f
    }
}

object DocShare {
    private const val TAG = "DocShare"

    val busy: Boolean get() = LongFlows.running(LongFlows.DOC_SHARE)

    internal fun releaseForNewModule() = LongFlows.releaseForNewModule(LongFlows.DOC_SHARE)

    internal fun onToolTap() {
        Main.post { share() }
    }

    private fun share() {

        if (!HostSession.isDocumentContext()) return
        val run = LongFlows.begin(LongFlows.DOC_SHARE) ?: return
        fun fail(messageKey: String) {
            LongFlows.end(run)
            Dialogs.result(Strings.get(messageKey)) { Toolbar.restore("doc share failed") }
        }
        HostPlugins.requestPermission(HostPlugins.FILE_READ) { granted ->
            if (!granted) {
                fail("file_read_permission_needed")
                return@requestPermission
            }
            val react = App.react
            if (react == null) {
                Log.w(TAG, "[DOC-SHARE] no React context")
                fail("doc_share_failed")
                return@requestPermission
            }
            PageRender.callHost(react, "getCurrentFilePath", emptyList()) { pathValue, pathError ->
                val path = (pathValue as? String)?.takeIf { File(it).isFile } ?: run {
                    Log.w(TAG, "[DOC-SHARE] no readable document: value=$pathValue error=$pathError")
                    fail("doc_share_failed")
                    return@callHost
                }
                val annotated = path.endsWith(".pdf", ignoreCase = true) && File("$path.mark").isFile
                if (annotated && DocuCraftLink.installed()) {
                    exportAnnotated(path, run)
                } else {
                    LongFlows.end(run)
                    openPanel(path)
                }
            }
        }
    }

    private fun exportAnnotated(path: String, run: LongFlows.Run) {
        val root = File(App.context.cacheDir, "notipal_doc_share")
        root.deleteRecursively()
        val dir = File(root, System.nanoTime().toString())
        if (!dir.mkdirs()) {
            LongFlows.end(run)
            openPanel(path)
            return
        }
        val output = File(dir, File(path).name)
        val progress = ProgressPanel()
        var cancelExport: () -> Unit = {}
        var finished = false
        fun sendOriginal(messageKey: String) {
            dir.deleteRecursively()
            progress.hide()
            Dialogs.result(Strings.get(messageKey)) { Main.post { openPanel(path) } }
        }
        progress.open(
            title = Strings.get("doc_share_localsend"),
            status = Strings.get("doc_share_exporting"),
            onCancel = {
                if (!finished) {
                    finished = true
                    LongFlows.end(run)
                    cancelExport()
                    progress.hide()
                    Toolbar.restore("doc share cancelled")
                }
            },
        ) shown@{ shown ->
            if (!shown) {
                finished = true
                LongFlows.end(run)
                dir.deleteRecursively()
                openPanel(path)
                return@shown
            }
            cancelExport = DocuCraftLink.exportAnnotated(App.context, path, output.absolutePath, { DocuCraftLink.show() }) { failure ->
                if (finished) return@exportAnnotated
                finished = true
                LongFlows.end(run)
                when {
                    failure == null && output.length() > 0L -> openPanel(output.absolutePath, dir, progress)
                    failure == DocuCraftLink.ExportFailure.OUTDATED -> sendOriginal("doc_share_docucraft_outdated")
                    else -> sendOriginal("doc_share_export_failed")
                }
            }
        }
    }

    private fun openPanel(path: String, tempDir: File? = null, progress: ProgressPanel? = null) {
        SendPanel.open(
            allowAutoSend = false,
            data = SendData("", emptyList(), listOf(LinkedFile(path, -1, ""))),
            tempDir = tempDir,
        ) { shown ->
            if (!shown) {
                tempDir?.deleteRecursively()
                progress?.hide()
                Toolbar.restore("doc share panel not shown")
            }
        }
    }
}
