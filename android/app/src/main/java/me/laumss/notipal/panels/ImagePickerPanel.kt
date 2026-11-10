package me.laumss.notipal.panels

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.util.Log
import android.util.LruCache
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.facebook.react.bridge.ReactApplicationContext
import me.laumss.notipal.BuildConfig
import me.laumss.notipal.FloatingToolbarModule
import me.laumss.notipal.HostPenSignals
import me.laumss.notipal.ImageFilter
import me.laumss.notipal.MosaicLink
import me.laumss.notipal.NativeLocale
import me.laumss.notipal.net.PluginIdentity
import me.laumss.notipal.ui_common.BitmapSampling
import me.laumss.notipal.ui_common.BrowseDirs
import me.laumss.notipal.ui_common.FolderCoverView
import me.laumss.notipal.ui_common.PanelBase
import me.laumss.notipal.ui_common.PanelWidgets
import me.laumss.notipal.ui_common.snux.EmptyState
import me.laumss.notipal.ui_common.snux.InkScrollView
import me.laumss.notipal.ui_common.snux.LineIcon
import me.laumss.notipal.ui_common.snux.MasonryLayout
import me.laumss.notipal.ui_common.snux.SideNav
import me.laumss.notipal.ui_common.snux.Snux
import me.laumss.notipal.ui_common.snux.TopBar
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.concurrent.thread
import kotlin.math.roundToInt

class ImagePickerPanel(
    ctx: ReactApplicationContext,
    private val toolbar: FloatingToolbarModule,
) : PanelBase(ctx, toolbar) {

    override val tag = "ImagePickerPanel"
    override val panelName = "image"
    override val fullScreen = true

    companion object {
        @Volatile var currentInstance: ImagePickerPanel? = null

        fun getInstance(ctx: ReactApplicationContext, module: FloatingToolbarModule): ImagePickerPanel =
            currentInstance ?: ImagePickerPanel(ctx, module).also { currentInstance = it }

        const val SOURCE_RECENT = "recent"
        const val SOURCE_SHOTS = "shots"
        const val SOURCE_RECEIVED = "received"
        private const val SOURCE_TRASH = "trash"
        private const val FOLDERS = "folders"
        private const val FOLDER_PREFIX = "folder:"

        private val FOLDER_KEYS = BrowseDirs.KEYS
        private val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "bmp", "gif", "webp")

        private const val SYSTEM_SCREENSHOTS = "/sdcard/SCREENSHOT"
        private const val RECENT_LIMIT = 60

        private const val DELETE_ARM_MS = 3_000L

        private const val IMAGE_PAD = 5

        private const val TILE_TEXT = 82.5f * Snux.INKHUB_U
        private const val CARD_TEXT = 26 * Snux.INKHUB_U
        private const val GRID_GAP = 18
        private const val GRID_ROW_GAP = 22

        private const val ROWS_TALL = 4.5f
        private const val ROWS_WIDE = 2.5f

        private const val MIN_RATIO = 0.4f
        private const val MAX_RATIO = 1.8f

        private val decoder = Executors.newSingleThreadExecutor { r -> Thread(r, "ImagePicker-thumbs").apply { isDaemon = true } }

        @Volatile private var thumbGeneration = 0
        private val thumbs = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
        }

        private val aspects = LruCache<String, Float>(4096)
    }

    private enum class Kind { QUEUED, HISTORY, RECEIVED, FILE, FOLDER, TRASHED }

    private data class Entry(
        val path: String,
        val name: String,
        val size: Long,
        val modified: Long,
        val kind: Kind,
        val childCount: Int = 0,
        val previews: List<String> = emptyList(),
        val ratio: Float = 1f,
    )

    private class Tile(val image: FrameLayout, val badge: TextView, val entry: Entry, val card: View, val load: () -> Unit) {
        var loaded = false

        var paintedOrder: Int? = null
    }

    private var source = SOURCE_RECENT

    private var folderPath = ""
    private var foldersOpen = false

    private var resetScroll = true
    private var entries: List<Entry> = emptyList()
    private var loading = false
    private var readBlocked = false
    private var readPermissionAsked = false

    private val selection = LinkedHashMap<String, Entry>()
    private var counts: Map<String, Int> = emptyMap()
    private var loadGeneration = 0
    private var countsGeneration = 0

    private val lists = HashMap<String, List<Entry>>()

    private var gridStale = false
    private var deleteArmedAt = 0L

    private var purgeArmedAt = 0L
    private var deleting = false

    private lateinit var nav: SideNav
    private lateinit var topBar: TopBar.Handle
    private lateinit var gridBox: FrameLayout
    private lateinit var gridScroll: InkScrollView
    private lateinit var grid: LinearLayout
    private lateinit var masonry: MasonryLayout
    private lateinit var emptyView: EmptyState
    private lateinit var deleteCell: FrameLayout
    private lateinit var cropCell: FrameLayout
    private lateinit var insertCell: FrameLayout
    private lateinit var restoreCell: FrameLayout
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    private val tiles = ArrayList<Tile>()

    private fun u(v: Number) = Snux.u(reactContext, v)
    private fun t(key: String) = NativeLocale.t(key)

    fun show(initialSource: String = SOURCE_RECENT, onResult: ((Boolean) -> Unit)? = null) {
        ScreenshotBubble.pendingReshow = false
        ScreenshotBubble.hide()
        currentInstance = this
        source = initialSource
        folderPath = ""
        foldersOpen = false
        resetScroll = true
        entries = emptyList()
        lists.clear()
        gridStale = false
        selection.clear()
        deleteArmedAt = 0L
        purgeArmedAt = 0L
        deleting = false
        readPermissionAsked = false
        showPanel { shown ->
            if (shown) {
                renderNav()
                refreshCounts()
                reload()
            }
            onResult?.invoke(shown)
        }
    }

    fun onFileReceived() {
        handler.post {
            if (rootView != null && (source == SOURCE_RECEIVED || source == SOURCE_RECENT)) reload(keepScroll = true)
        }
    }

    override fun onHide() {
        tiles.clear()
        lists.clear()
        thumbGeneration++
        thumbs.evictAll()
        loadGeneration++
        currentInstance = null
    }

    override fun buildFullScreenContent(): View {
        val ctx = reactContext
        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            isClickable = true
        }
        deleteCell = TopBar.iconCell(ctx, LineIcon.Glyph.TRASH, t("delete")) { deleteSelected() }
        cropCell = TopBar.iconCell(ctx, LineIcon.Glyph.CROP, t("crop_and_insert")) { cropSelected() }
        insertCell = TopBar.iconCell(ctx, LineIcon.Glyph.INSERT, t("insert")) { insertSelected() }
        restoreCell = TopBar.iconCell(ctx, LineIcon.Glyph.RESTORE, t("picker_restore")) { restoreSelected() }
        topBar = TopBar.build(ctx, t("close"), onClose = ::onLeftKey, onAction = ::onBarAction,
            actionLabels = listOf(t("picker_purge_all")),
            trailing = listOf(deleteCell, restoreCell, cropCell, insertCell))
        topBar.setTitle(t("image_panel_title"))
        column.addView(topBar.view)

        nav = SideNav(ctx, ::onNav)
        val body = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        body.addView(nav, LinearLayout.LayoutParams(u(Snux.SIDEBAR), LinearLayout.LayoutParams.MATCH_PARENT))

        body.addView(View(ctx).apply { setBackgroundColor(Color.BLACK) },
            LinearLayout.LayoutParams(Snux.px(ctx, 2).coerceAtLeast(1), LinearLayout.LayoutParams.MATCH_PARENT))
        body.addView(buildPane(), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        column.addView(body, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        return column
    }

    private fun buildPane(): View {
        val ctx = reactContext
        val inset = u(Snux.PANE_INSET)
        val pane = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }

        gridScroll = InkScrollView(ctx).apply { onScrolled = ::loadVisibleThumbs }
        grid = gridScroll.content.apply { setPadding(0, 0, inset, u(24)) }
        masonry = MasonryLayout(ctx)
        grid.addView(masonry, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        gridScroll.pageItems = { List(masonry.childCount) { masonry.getChildAt(it) } }
        emptyView = EmptyState(ctx).apply { visibility = View.GONE }
        gridBox = FrameLayout(ctx).apply {
            setPadding(inset, u(24), 0, 0)
            addView(gridScroll, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(emptyView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
                .apply { rightMargin = inset })

            addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
                if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) post { render() }
            }
        }
        pane.addView(gridBox, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        return pane
    }

    private fun renderNav() {
        if (rootView == null) return
        fun count(id: String) = counts[id]?.toString()
        val top = mutableListOf<SideNav.Entry>(
            SideNav.Entry.Item(SOURCE_RECENT, LineIcon.Glyph.CLOCK, t("picker_recent")),
            SideNav.Entry.Dots,
            SideNav.Entry.Item(SOURCE_SHOTS, LineIcon.Glyph.SHOT, t("picker_shots"), count(SOURCE_SHOTS)),
            SideNav.Entry.Dots,
            SideNav.Entry.Item(SOURCE_RECEIVED, LineIcon.Glyph.RECEIVE, t("picker_received"), count(SOURCE_RECEIVED)),
            SideNav.Entry.Rule,
            SideNav.Entry.Item(FOLDERS, LineIcon.Glyph.FOLDER, t("picker_folders"), open = foldersOpen),
        )
        if (foldersOpen) FOLDER_KEYS.forEach { key ->
            top += SideNav.Entry.Dots
            top += SideNav.Entry.Sub(FOLDER_PREFIX + key, folderName(key))
        }
        top += SideNav.Entry.Rule
        val bottom = listOf(
            SideNav.Entry.Rule,
            SideNav.Entry.Item(SOURCE_TRASH, LineIcon.Glyph.TRASH, t("picker_trash"), count(SOURCE_TRASH)),
        )
        nav.setEntries(top, bottom)
        markNav()
    }

    private fun markNav() {
        nav.setActive(if (source.startsWith(FOLDER_PREFIX) && !foldersOpen) FOLDERS else source)
    }

    private fun folderName(key: String): String = File(BrowseDirs.pathForKey(key)).name

    private fun onNav(id: String) {
        when {
            id == FOLDERS -> {
                foldersOpen = !foldersOpen

                if (foldersOpen && !source.startsWith(FOLDER_PREFIX)) {
                    openSource(FOLDER_PREFIX + FOLDER_KEYS.first(), navChanged = true)
                    return
                }
                renderNav()
            }
            id == source && !id.startsWith(FOLDER_PREFIX) -> Unit
            else -> openSource(id)
        }
    }

    private fun openSource(id: String, navChanged: Boolean = false) {

        if ((id == SOURCE_TRASH) != (source == SOURCE_TRASH)) {
            selection.clear()
            deleteArmedAt = 0L
        }
        purgeArmedAt = 0L
        source = id
        folderPath = if (id.startsWith(FOLDER_PREFIX)) BrowseDirs.pathForKey(id.removePrefix(FOLDER_PREFIX)) else ""
        if (navChanged) renderNav() else markNav()
        reload()
    }

    private class Loaded(val entries: List<Entry>, val blocked: Boolean, val missing: Set<String>)

    private fun listKey(src: String, dir: String) = "$src|$dir"

    private fun reload(keepScroll: Boolean = false) {
        val generation = ++loadGeneration
        val src = source
        val dir = folderPath
        val key = listKey(src, dir)
        val picked = selection.keys.toList()
        loading = true
        if (keepScroll) {

            refreshCounts()
            renderSelection()
        } else {
            resetScroll = true
            val known = lists[key]
            entries = known.orEmpty()
            if (known != null) {
                render()
            } else {
                gridStale = true
                renderSelection()
            }
        }
        thread(isDaemon = true, name = "ImagePicker-load") {
            PluginIdentity.claim()
            var blocked = false
            val list = try {
                load(src, dir).map { if (it.kind == Kind.FOLDER) it else it.copy(ratio = aspectOf(it)) }
            } catch (e: SecurityException) {
                Log.w(tag, "read blocked for $src: ${e.message}")
                blocked = true
                emptyList()
            }
            val loaded = Loaded(list, blocked, picked.filterTo(HashSet()) { !File(it).exists() })
            handler.post {
                if (generation != loadGeneration || rootView == null) return@post
                loading = false
                readBlocked = loaded.blocked
                val fresh = sorted(loaded.entries)

                val changed = gridStale || fresh.isEmpty() || fresh != entries
                entries = fresh
                if (!loaded.blocked) lists[key] = fresh
                loaded.missing.forEach { selection.remove(it) }
                if (loaded.blocked) askReadPermission()
                if (BuildConfig.ENABLE_DEBUG) Log.i(tag, "source $src: ${entries.size} entries")
                if (changed) {
                    render()
                } else {
                    tiles.forEach { paintTile(it) }
                    renderSelection()
                }
            }
        }
    }

    private fun refreshCounts() {
        val generation = ++countsGeneration
        thread(isDaemon = true, name = "ImagePicker-counts") {
            PluginIdentity.claim()
            ImageTrash.purgeExpired()
            val fresh = sourceCounts()
            handler.post {
                if (generation != countsGeneration || rootView == null || fresh == counts) return@post
                counts = fresh
                renderNav()
            }
        }
    }

    private fun load(src: String, dir: String): List<Entry> = when {
        src == SOURCE_RECENT -> (shots() + received() + images(File(SYSTEM_SCREENSHOTS), Kind.FILE))
            .sortedByDescending { it.modified }
            .take(RECENT_LIMIT)
        src == SOURCE_SHOTS -> shots()
        src == SOURCE_RECEIVED -> received()
        src == SOURCE_TRASH -> ImageTrash.list().map { Entry(it.path, it.name, it.size, it.deletedAt, Kind.TRASHED) }
        else -> folder(File(dir))
    }

    private fun shots(): List<Entry> =
        screenshots(File(DocScreenshotService.QUEUE_DIR), Kind.QUEUED) + screenshots(File(DocScreenshotService.HISTORY_DIR), Kind.HISTORY)

    private fun screenshots(dir: File, kind: Kind): List<Entry> {
        val stamp = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        return (dir.listFiles() ?: emptyArray()).filter { it.isFile && it.name.endsWith(".png") }.map { file ->
            val taken = file.name.removeSuffix(".png").toLongOrNull()
            Entry(file.absolutePath, taken?.let { stamp.format(Date(it)) } ?: file.name, file.length(),
                taken ?: file.lastModified(), kind)
        }
    }

    private fun received(): List<Entry> =
        LocalSendModule.getReceivedImageFiles().map { Entry(it.path, it.name, it.size, it.modified, Kind.RECEIVED) }

    private fun isImage(file: File) = !file.name.startsWith(".") && file.extension.lowercase() in IMAGE_EXTS

    private fun images(dir: File, kind: Kind): List<Entry> =
        (dir.listFiles() ?: emptyArray()).filter { it.isFile && isImage(it) }
            .map { Entry(it.absolutePath, it.name, it.length(), it.lastModified(), kind) }

    private fun isSubFolder(file: File) = file.isDirectory && !file.name.startsWith(".")

    private fun holdsImage(dir: File): Boolean {
        val inside = try { dir.listFiles() } catch (_: SecurityException) { null } ?: return false
        return inside.any { it.isFile && isImage(it) } || inside.any { isSubFolder(it) && holdsImage(it) }
    }

    private fun folder(dir: File): List<Entry> {
        val children = dir.listFiles() ?: return emptyList()
        val folders = children.filter { isSubFolder(it) }.mapNotNull { sub ->
            val inside = try { sub.listFiles() } catch (_: SecurityException) { null } ?: emptyArray()
            val previews = inside.filter { it.isFile && isImage(it) }.sortedBy { it.name }.take(4).map { it.absolutePath }
            if (previews.isEmpty() && inside.none { isSubFolder(it) && holdsImage(it) }) return@mapNotNull null
            Entry(sub.absolutePath, sub.name, 0L, sub.lastModified(), Kind.FOLDER, inside.size, previews)
        }
        return folders + children.filter { it.isFile && isImage(it) }
            .map { Entry(it.absolutePath, it.name, it.length(), it.lastModified(), Kind.FILE) }
    }

    private fun aspectOf(entry: Entry): Float {
        val key = "${entry.path}@${entry.size}@${entry.modified}"
        aspects.get(key)?.let { return it }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            BitmapFactory.decodeFile(entry.path, bounds)
        } catch (_: Exception) {
            return 1f
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return 1f
        return (bounds.outHeight.toFloat() / bounds.outWidth).also { aspects.put(key, it) }
    }

    private fun sourceCounts(): Map<String, Int> = mapOf(
        SOURCE_SHOTS to shots().size,
        SOURCE_RECEIVED to received().size,
        SOURCE_TRASH to ImageTrash.list().size,
    )

    private fun sorted(list: List<Entry>): List<Entry> {
        return list.sortedWith(compareBy<Entry> { it.kind != Kind.FOLDER }.thenBy { it.kind != Kind.QUEUED }
            .thenByDescending { it.modified })
    }

    private fun askReadPermission() {
        if (readPermissionAsked) return
        readPermissionAsked = true
        toolbar.requestPluginFileReadPermission { granted ->
            if (granted) handler.post { if (rootView != null) reload(keepScroll = true) }
        }
    }

    private fun render() {
        if (rootView == null) return
        renderSelection()
        val offset = if (resetScroll) 0 else gridScroll.scrollOffset
        resetScroll = false
        gridStale = false
        thumbGeneration++
        masonry.removeAllViews()
        tiles.clear()
        emptyView.visibility = if (entries.isEmpty() && !loading) View.VISIBLE else View.GONE
        if (emptyView.visibility == View.VISIBLE) showEmpty()
        val w = gridScroll.width - grid.paddingLeft - grid.paddingRight
        val h = gridScroll.viewportHeight
        if (w <= 0 || h <= 0) return
        masonry.columns = if (w >= u(860)) 4 else 3
        masonry.gap = u(GRID_GAP)
        masonry.rowGap = u(GRID_ROW_GAP)
        val colW = masonry.columnWidth(w)
        val pad = u(IMAGE_PAD)
        val pitch = (h / if (h >= w) ROWS_TALL else ROWS_WIDE).toInt()

        val folderH = (pitch - masonry.rowGap - u(TILE_TEXT)).coerceAtMost(colW * 4 / 3).coerceAtLeast(u(80))
        for (entry in entries) {

            val boxH = if (entry.kind == Kind.FOLDER) folderH
                else ((colW - 2 * pad) * entry.ratio.coerceIn(MIN_RATIO, MAX_RATIO)).roundToInt() + 2 * pad
            masonry.addView(tile(entry, colW, boxH))
        }
        gridScroll.scrollToOffset(offset)
    }

    private fun inSubFolder(): Boolean =
        source.startsWith(FOLDER_PREFIX) && folderPath != BrowseDirs.pathForKey(source.removePrefix(FOLDER_PREFIX))

    private fun onLeftKey() {
        if (selection.isNotEmpty()) {
            clearSelection()
            return
        }
        if (!inSubFolder()) {
            closeAndRestore()
            return
        }
        folderPath = File(folderPath).parent ?: BrowseDirs.pathForKey(source.removePrefix(FOLDER_PREFIX))
        reload()
    }

    private fun loadVisibleThumbs() {
        if (rootView == null || tiles.isEmpty()) return

        val top = gridScroll.scrollOffset - masonry.top
        val bottom = top + 2 * gridScroll.viewportHeight
        for (tile in tiles) {
            if (tile.loaded || tile.card.height == 0) continue
            if (tile.card.bottom >= top && tile.card.top <= bottom) {
                tile.loaded = true
                tile.load()
            }
        }
    }

    private fun showEmpty() {
        when {
            readBlocked -> emptyView.set(LineIcon.Glyph.FOLDER, t("picker_read_blocked_title"), t("file_read_permission_needed"))
            source == SOURCE_RECENT -> emptyView.set(LineIcon.Glyph.CLOCK, t("picker_empty_recent_title"), t("picker_empty_recent_desc"))
            source == SOURCE_SHOTS -> emptyView.set(LineIcon.Glyph.SHOT, t("picker_empty_shots_title"), t("picker_empty_shots_desc"))
            source == SOURCE_RECEIVED -> emptyView.set(LineIcon.Glyph.RECEIVE, t("picker_empty_received_title"), t("picker_empty_received_desc"))
            source == SOURCE_TRASH -> emptyView.set(LineIcon.Glyph.TRASH, t("picker_empty_trash_title"), t("picker_empty_trash_desc"))
            else -> emptyView.set(LineIcon.Glyph.FOLDER, t("picker_empty_folder_title"), t("picker_empty_folder_desc"))
        }
    }

    private fun tile(entry: Entry, colW: Int, imageH: Int): View {
        val ctx = reactContext
        val pad = u(IMAGE_PAD)
        val innerW = colW - 2 * pad
        val innerH = imageH - 2 * pad
        val box = FrameLayout(ctx).apply { setPadding(pad, pad, pad, pad) }
        val load: () -> Unit
        if (entry.kind == Kind.FOLDER) {
            val cover = FolderCoverView(ctx)
            box.addView(cover, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            val (slotW, slotH) = FolderCoverView.slotSize(ctx, innerW, innerH)
            val previews = entry.previews.take(cover.slots.size)
            previews.indices.forEach { cover.slots[it].visibility = View.VISIBLE }
            load = { previews.forEachIndexed { i, path -> loadThumb(path, slotW, slotH, cover.slots[i]) } }
        } else {
            val image = ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
            box.addView(image, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            load = { loadThumb(entry.path, innerW, innerH, image) }
        }

        val badge = TextView(ctx).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, u(20).toFloat())
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.BLACK)
                setStroke(u(2).coerceAtLeast(1), Color.WHITE)
            }

            visibility = View.INVISIBLE
        }
        box.addView(badge, FrameLayout.LayoutParams(u(38), u(38), Gravity.TOP or Gravity.END).apply {
            topMargin = u(4); rightMargin = u(4)
        })
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(box, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, imageH))
            addView(Snux.oneLine(Snux.text(ctx, entry.name, CARD_TEXT, bold = true)), LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = Snux.px(ctx, 12) })
            addView(Snux.oneLine(Snux.text(ctx, metaOf(entry), CARD_TEXT)), LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            setOnClickListener { onTile(entry) }
        }
        val tile = Tile(box, badge, entry, card, load)
        tiles += tile
        paintTile(tile)
        return card
    }

    private fun metaOf(entry: Entry): String {
        val size = PanelWidgets.formatSize(entry.size)
        return when (entry.kind) {
            Kind.FOLDER -> NativeLocale.itemCount(entry.childCount)
            Kind.QUEUED -> "${t("picker_queued")} · $size"
            Kind.HISTORY -> size
            Kind.TRASHED -> "${NativeLocale.t("picker_deleted_at", dateFormat.format(Date(entry.modified)))} · $size"
            else -> "${dateFormat.format(Date(entry.modified))} · $size"
        }
    }

    private fun paintTile(tile: Tile): Boolean {
        if (tile.entry.kind == Kind.FOLDER) return false
        val order = selection.keys.indexOf(tile.entry.path)
        val was = tile.paintedOrder
        if (order == was) return false
        tile.paintedOrder = order
        val picked = order >= 0
        if (was == null || picked != (was >= 0)) tile.image.background = GradientDrawable().apply {
            setColor(Color.WHITE)
            setStroke(u(if (picked) 4f else 1.5f).coerceAtLeast(1), Color.BLACK)
        }
        tile.badge.visibility = if (picked) View.VISIBLE else View.INVISIBLE
        if (picked) tile.badge.text = (order + 1).toString()
        return true
    }

    private fun loadThumb(path: String, w: Int, h: Int, view: ImageView) {
        val key = "$path@${w}x$h"
        view.tag = key
        thumbs.get(key)?.let { view.setImageBitmap(it); return }
        val generation = thumbGeneration
        decoder.execute {
            PluginIdentity.claim()
            if (generation != thumbGeneration) return@execute
            val bitmap = decodeThumb(path, w, h) ?: return@execute
            thumbs.put(key, bitmap)
            handler.post { if (view.tag == key) view.setImageBitmap(bitmap) }
        }
    }

    private fun decodeThumb(path: String, w: Int, h: Int): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply {
            inSampleSize = BitmapSampling.powerOfTwoSampleSize(bounds.outWidth, bounds.outHeight, w, h)
        })
    } catch (_: Exception) {
        null
    } catch (_: OutOfMemoryError) {
        null
    }

    private fun onTile(entry: Entry) {
        if (deleting || gridStale) return
        if (entry.kind == Kind.FOLDER) {
            folderPath = entry.path
            reload()
            return
        }
        val started = SystemClock.elapsedRealtime()
        if (selection.remove(entry.path) == null) selection[entry.path] = entry
        deleteArmedAt = 0L
        purgeArmedAt = 0L
        val painted = tiles.count { paintTile(it) }
        renderSelection()
        Log.i(tag, "pick: $painted of ${tiles.size} cards repainted, ${SystemClock.elapsedRealtime() - started}ms")
        afterNextDraw { Log.i(tag, "pick drawn +${SystemClock.elapsedRealtime() - started}ms") }
    }

    private fun clearSelection() {
        selection.clear()
        deleteArmedAt = 0L
        tiles.forEach { paintTile(it) }
        renderSelection()
    }

    private fun renderSelection() {
        val n = selection.size
        val now = System.currentTimeMillis()
        val trash = source == SOURCE_TRASH
        val armed = n > 0 && now - deleteArmedAt < DELETE_ARM_MS
        val purgeArmed = trash && n == 0 && now - purgeArmedAt < DELETE_ARM_MS
        topBar.setTitle(when {
            armed -> t("picker_purge_again")
            purgeArmed -> t("picker_purge_all_again")
            n > 0 -> NativeLocale.t("picker_selected", n)
            trash -> t("picker_trash")
            else -> t("image_panel_title")
        })
        topBar.setCloseGlyph(if (n > 0 || inSubFolder()) LineIcon.Glyph.BACK else LineIcon.Glyph.CLOSE)
        topBar.setAction(if (trash && n == 0 && entries.isNotEmpty()) t("picker_purge_all") else null, enabled = !deleting)

        deleteCell.contentDescription = t(if (trash) "picker_purge" else "delete")
        restoreCell.visibility = if (trash) View.VISIBLE else View.GONE
        cropCell.visibility = if (trash) View.GONE else View.VISIBLE
        insertCell.visibility = if (trash) View.GONE else View.VISIBLE
        setIconState(deleteCell, n > 0 && !deleting, inverse = armed && !deleting)
        setIconState(restoreCell, n > 0 && !deleting)
        setIconState(cropCell, n == 1 && !deleting)
        setIconState(insertCell, n > 0 && !deleting)
    }

    private fun onBarAction() {
        if (selection.isNotEmpty() || source != SOURCE_TRASH || deleting || entries.isEmpty()) return
        val now = System.currentTimeMillis()
        if (now - purgeArmedAt >= DELETE_ARM_MS) {
            purgeArmedAt = now
            renderSelection()
            handler.postDelayed({ if (rootView != null) renderSelection() }, DELETE_ARM_MS)
            return
        }
        purgeArmedAt = 0L
        val all = entries.map { it.path }
        runFileJob("ImagePicker-purge", all, "picker_delete_failed") { ImageTrash.purge(it) }
    }

    private fun setIconState(cell: FrameLayout, enabled: Boolean, inverse: Boolean = false) {
        cell.isEnabled = enabled
        cell.setBackgroundColor(if (inverse) Color.BLACK else Color.TRANSPARENT)
        (cell.getChildAt(0) as? LineIcon)?.apply {
            isEnabled = enabled
            this.inverse = inverse
        }
    }

    private fun insertSelected() {
        if (selection.isEmpty() || deleting) return
        val items = selection.values.map {
            FloatingToolbarModule.InsertItem(it.path, deleteAfter = it.kind == Kind.RECEIVED, fromQueue = it.kind == Kind.QUEUED)
        }
        Log.i(tag, "insert ${items.size} image(s) from $source")
        hide()
        toolbar.startMultiImageInsert(items)
    }

    private fun cropSelected() {
        val entry = selection.values.singleOrNull() ?: return
        val path = entry.path
        val deleteAfter = if (entry.kind == Kind.RECEIVED) path else null
        val crop = CropPanel.getInstance(reactContext, toolbar)
        crop.show(path) { result -> insertCropped(path, result, deleteAfter) }
        crop.runAfterFirstDraw { if (isShowing) hide() }
    }

    private fun insertCropped(path: String, crop: CropPanel.CropResult, deleteAfter: String?) {
        val toMosaic = MosaicLink.isBoardVisible()
        if (!toMosaic) HostPenSignals.beginInsertImageGuard()

        fun abort(reason: String) {
            Log.e(tag, "crop failed: $reason")
            handler.post {
                HostPenSignals.endInsertImageGuard()
                toolbarModule.restoreToolbar()
            }
        }
        thread(isDaemon = true, name = "ImagePicker-crop") {
            PluginIdentity.claim()
            try {
                val src = BitmapFactory.decodeFile(path) ?: run { abort("decode returned null"); return@thread }
                val cropped = Bitmap.createBitmap(src, crop.offsetX, crop.offsetY, crop.width, crop.height)
                val output = if (crop.filter != ImageFilter.FILTER_NONE || crop.density < 100) {
                    val work = cropped.copy(Bitmap.Config.ARGB_8888, true)
                    if (cropped != src) cropped.recycle()
                    ImageFilter.process(work, crop.filter, crop.density)
                    work
                } else cropped
                val outPath = "${reactContext.cacheDir.absolutePath}/crop_${System.currentTimeMillis()}.png"
                java.io.FileOutputStream(outPath).use { output.compress(Bitmap.CompressFormat.PNG, 100, it) }
                if (output != src) output.recycle()
                src.recycle()
                handler.post {
                    if (toMosaic) toolbar.enqueueImageToMosaic(outPath, "inkling-insert-image", deleteAfter)
                    else toolbar.requestInsertImage(outPath, deleteAfter)
                }
            } catch (e: Throwable) {
                abort(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private fun deleteSelected() {
        if (deleting || selection.isEmpty()) return
        val picked = selection.values.toList()
        if (source != SOURCE_TRASH) {
            runFileJob("ImagePicker-trash", picked.map { it.path }, "picker_delete_failed") { path ->
                ImageTrash.moveIn(path).also { ok ->
                    if (ok && picked.any { it.path == path && it.kind == Kind.QUEUED }) {
                        DocScreenshotService.unmarkInsertNext(File(path).name)
                    }
                }
            }
            return
        }
        val now = System.currentTimeMillis()
        if (now - deleteArmedAt >= DELETE_ARM_MS) {
            deleteArmedAt = now
            renderSelection()
            handler.postDelayed({ if (rootView != null) renderSelection() }, DELETE_ARM_MS)
            return
        }
        deleteArmedAt = 0L
        runFileJob("ImagePicker-purge", picked.map { it.path }, "picker_delete_failed") { ImageTrash.purge(it) }
    }

    private fun restoreSelected() {
        if (deleting || selection.isEmpty() || source != SOURCE_TRASH) return

        val toHistory = setOf(
            File(DocScreenshotService.QUEUE_DIR).absolutePath,
            "/sdcard/SCREENSHOT/.plugin_staging/queue", "/sdcard/SCREENSHOT/.plugin_history",
            "/storage/emulated/0/SCREENSHOT/.plugin_staging/queue", "/storage/emulated/0/SCREENSHOT/.plugin_history",
        )
        runFileJob("ImagePicker-restore", selection.keys.toList(), "picker_restore_failed") { path ->
            ImageTrash.restore(path) { origin ->
                if (File(origin).parent in toHistory) File(DocScreenshotService.HISTORY_DIR, File(origin).name).path else origin
            }
        }
    }

    private fun runFileJob(name: String, paths: List<String>, failKey: String, job: (String) -> Boolean) {
        deleting = true
        renderSelection()
        thread(isDaemon = true, name = name) {
            PluginIdentity.claim()
            val failed = paths.filterNot { path ->
                try {
                    job(path)
                } catch (e: Exception) {
                    Log.w(tag, "$name failed: $path", e)
                    false
                }
            }.toSet()
            handler.post {
                deleting = false
                if (rootView == null) return@post
                paths.forEach { if (it !in failed) selection.remove(it) }
                if (failed.isNotEmpty()) Toast.makeText(reactContext, t(failKey), Toast.LENGTH_LONG).show()

                lists.clear()
                reload(keepScroll = true)
            }
        }
    }

    private fun closeAndRestore() {
        hide()
        toolbarModule.restoreToolbar()
    }
}
