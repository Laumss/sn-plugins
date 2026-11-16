package me.laumss.notipal.ui_common

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.text.TextUtils
import android.util.Base64
import android.util.LruCache
import android.util.TypedValue
import android.util.Xml
import android.view.Choreographer
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.facebook.react.bridge.Arguments
import me.laumss.notipal.BuildConfig
import me.laumss.notipal.common.App
import me.laumss.notipal.common.Dirs
import me.laumss.notipal.common.Log
import me.laumss.notipal.common.Main
import me.laumss.notipal.common.PluginIdentity
import me.laumss.notipal.common.Strings
import me.laumss.notipal.host.HostPlugins
import me.laumss.notipal.host.PageRender
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.FileOutputStream
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object BrowseDirs {

    const val ROOT = "/storage/emulated/0"

    val KEYS = listOf("Document", "Export", "MyStyle", "Note", "SCREENSHOT", "INBOX")

    private val PATH_MAP = mapOf(
        "Document" to "/sdcard/Document",
        "Export" to "/sdcard/EXPORT",
        "MyStyle" to "/sdcard/MyStyle",
        "Note" to "/sdcard/Note",
        "SCREENSHOT" to "/sdcard/SCREENSHOT",
        "INBOX" to "/sdcard/INBOX",
    )

    fun pathForKey(key: String?): String =
        if (key != null) PATH_MAP[key] ?: "/sdcard" else "/sdcard"

    val default: List<File> get() = KEYS.map { File(pathForKey(it)) }

    val documents: List<File> get() = default + listOf("LocalSend", "Books", "Download").map { File("/sdcard/$it") }
}

data class FilePickerConfig(
    val roots: List<File> = BrowseDirs.default,
    val multi: Boolean = false,
    val filter: (File) -> Boolean = { true },
    val titleKey: String,
    val extensions: Set<String>,
    val emptyKey: String = "picker_file_default",
    val actionKey: String = "picker_select",
)

class FilePicker(val config: FilePickerConfig) : Panel("file-picker") {
    override val tag: String get() = "InklingFilePicker"
    override val fullScreen: Boolean get() = true

    override val windowFlags: Int get() = 0

    companion object {
        private const val ROOT = BrowseDirs.ROOT
        private const val LOG_TAG = "InklingFilePicker"

        private val thumbnailExecutor = Executors.newFixedThreadPool(2) { r ->
            Thread(r, "InklingPickerThumb").apply { isDaemon = true }
        }

        private val noteCoverExecutor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "InklingPickerNote").apply { isDaemon = true }
        }

        private const val LATER_ROWS_PER_BATCH = 4

        private const val PERMISSION_TOAST_GAP_MS = 2500L
        private var lastPermissionToastAt = 0L

        private class CachedListing(val items: List<Item>, val signature: Int)

        private val listingCache = object : LinkedHashMap<String, CachedListing>(8, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedListing>?) = size > 8
        }
        private val inflight = HashMap<String, MutableList<(List<Item>, Boolean, Boolean) -> Unit>>()

        private fun cacheKey(path: String, filter: ListingFilter) =
            "$path|${filter.allowedTypes.sorted()}|${filter.rootFolders}"

        private fun signatureOf(items: List<Item>): Int =
            items.fold(17) { h, it -> 31 * h + (it.path.hashCode() xor it.modifiedAt.hashCode() xor it.size.hashCode() xor it.childCount) }

        private fun cachedListing(key: String): CachedListing? = synchronized(listingCache) { listingCache[key] }

        private fun scanNow(path: String, filter: ListingFilter, blocked: AtomicBoolean): List<Item> =
            if (path == ROOT) homeFolders(blocked) + collectHomeFiles(filter, blocked) else loadTraditional(path, filter, blocked)

        private fun scan(path: String, filter: ListingFilter, done: (List<Item>, Boolean, Boolean) -> Unit) {
            val key = cacheKey(path, filter)
            synchronized(inflight) {
                inflight[key]?.let { waiters ->
                    waiters += done
                    return
                }
                inflight[key] = arrayListOf(done)
            }
            Thread({
                PluginIdentity.claim()
                val started = SystemClock.elapsedRealtime()
                val blocked = AtomicBoolean(false)
                val result = try {
                    scanNow(path, filter, blocked)
                } catch (_: SecurityException) {
                    blocked.set(true)
                    emptyList()
                } catch (error: Throwable) {
                    Log.w(LOG_TAG, "scan failed $path: $error")
                    emptyList()
                }
                val sig = signatureOf(result)
                val changed = synchronized(listingCache) {
                    val old = listingCache[key]
                    if (!blocked.get()) listingCache[key] = CachedListing(result, sig)
                    old?.signature != sig
                }
                Log.i(LOG_TAG, "scan $path items=${result.size} " +
                    "ms=${SystemClock.elapsedRealtime() - started} changed=$changed blocked=${blocked.get()}")
                val waiters = synchronized(inflight) { inflight.remove(key) }.orEmpty()
                waiters.forEach { it(result, changed, blocked.get()) }
            }, "InklingPickerDirectory").apply { isDaemon = true }.start()
        }

        private fun homeFolders(blocked: AtomicBoolean?): List<Item> =
            BrowseDirs.KEYS.mapNotNull { key ->
                val folder = File(Dirs.hostForm(BrowseDirs.pathForKey(key)))
                if (!isDirectory(folder, blocked)) return@mapNotNull null
                Item(
                    name = key,
                    path = folder.absolutePath,
                    isDir = true,
                    size = 0L,
                    childCount = 0,
                )
            }

        private fun collectHomeFiles(filter: ListingFilter, blocked: AtomicBoolean): List<Item> {
            val roots = filter.rootFolders.map { File(it) }.filter { isDirectory(it, blocked) }
            val parts = arrayOfNulls<List<File>>(roots.size)
            val workers = roots.mapIndexed { index, root ->
                Thread({
                    PluginIdentity.claim()
                    val files = ArrayList<File>()
                    try {
                        val pending = ArrayDeque<File>()
                        val visited = HashSet<String>()
                        pending.add(root)
                        while (pending.isNotEmpty()) {
                            val folder = pending.removeFirst()
                            if (!visited.add(Dirs.canonical(folder.absolutePath))) continue
                            visibleChildren(folder, blocked).forEach { child ->
                                if (isDirectory(child, blocked)) {
                                    pending.addLast(child)
                                } else if (matchesExtension(child.name, filter)) {
                                    files.add(child)
                                }
                            }
                        }
                    } catch (error: Throwable) {
                        Log.w(LOG_TAG, "scan failed ${root.path}: $error")
                    }
                    parts[index] = files
                }, "InklingPickerScan").apply { isDaemon = true; start() }
            }
            workers.forEach { it.join() }
            val seen = HashSet<String>()

            return parts.asSequence().filterNotNull().flatten()
                .filter { seen.add(Dirs.canonical(it.absolutePath)) }
                .map { fileItem(it) }
                .sortedWith(
                    compareByDescending<Item> { it.modifiedAt }
                        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name },
                )
                .toList()
        }

        private fun loadTraditional(path: String, filter: ListingFilter, blocked: AtomicBoolean): List<Item> {
            val dir = File(path)
            val children = try {
                dir.listFiles() ?: emptyArray()
            } catch (_: SecurityException) {
                blocked.set(true)
                return emptyList()
            }
            return children.asSequence()
                .filter { !it.name.startsWith(".") }
                .mapNotNull { f ->
                    if (isDirectory(f, blocked)) {
                        val kids = visibleChildren(f, blocked)
                        val previews = kids.asSequence()
                            .filter { it.isFile && PickerCells.isPreviewImage(it.name) }
                            .take(4)
                            .map { it.absolutePath }
                            .toList()
                        Item(
                            f.name, f.absolutePath, isDir = true, 0L, kids.size,
                            previews, try { f.lastModified() } catch (_: SecurityException) { 0L },
                        )
                    } else {
                        if (!matchesExtension(f.name, filter)) return@mapNotNull null
                        fileItem(f)
                    }
                }
                .sortedWith(
                    compareByDescending<Item> { it.isDir }
                        .thenByDescending { it.modifiedAt }
                        .thenBy { it.name.lowercase() },
                )
                .toList()
        }

        private fun isDirectory(file: File, blocked: AtomicBoolean?): Boolean = try {
            file.isDirectory
        } catch (_: SecurityException) {
            blocked?.set(true)
            false
        }

        private fun visibleChildren(directory: File, blocked: AtomicBoolean): List<File> = try {
            directory.listFiles()?.filter { it.name.isNotBlank() && !it.name.startsWith('.') }.orEmpty()
        } catch (_: SecurityException) {
            blocked.set(true)
            emptyList()
        }

        private fun fileItem(file: File) = Item(
            name = file.name,
            path = file.absolutePath,
            isDir = false,
            size = try { file.length() } catch (_: SecurityException) { 0L },
            childCount = 0,
            modifiedAt = try { file.lastModified() } catch (_: SecurityException) { 0L },
        )

        private fun extensionOf(name: String): String = name.substringAfterLast('.', "").lowercase()

        private fun orderedFormatKeys(extensions: Set<String>): List<String> {
            val aliases = linkedMapOf(
                "epub" to setOf("epub"),
                "pdf" to setOf("pdf"),
                "txt" to setOf("txt"),
                "md" to setOf("md", "markdown"),
                "cbz" to setOf("cbz"),
                "zip" to setOf("zip"),
                "jpg" to setOf("jpg", "jpeg"),
                "png" to setOf("png"),
                "webp" to setOf("webp"),
            )
            val ordered = aliases.filterValues { it.any(extensions::contains) }.keys.toMutableList()
            extensions.filter { ext -> aliases.values.none { ext in it } }.sorted().forEach(ordered::add)
            return ordered
        }

        private fun matchesFilterKey(ext: String, key: String): Boolean = when (key) {
            "md" -> ext == "md" || ext == "markdown"
            "jpg" -> ext == "jpg" || ext == "jpeg"
            else -> ext == key
        }

        private fun matchesExtension(name: String, filter: ListingFilter): Boolean =
            extensionOf(name) in filter.allowedTypes

        private fun formatSize(bytes: Long): String = when {
            bytes >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", bytes / 1048576.0)
            bytes >= 1L shl 10 -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }

    private class CellMetrics(val colW: Int, val coverW: Int, val coverH: Int, val gap: Int)

    private data class Item(
        val name: String,
        val path: String,
        val isDir: Boolean,
        val size: Long,
        val childCount: Int,
        val previewPaths: List<String> = emptyList(),
        val modifiedAt: Long = 0L,
    )

    private data class ListingFilter(
        val allowedTypes: Set<String>,
        val rootFolders: List<String>,
    )

    private var host: FrameLayout? = null
    private var overlay: View? = null
    private var breadcrumbHost: LinearLayout? = null
    private var chips: FormatChips? = null
    private var gridContainer: LinearLayout? = null
    private var gridScroll: ScrollHost? = null
    private var bottomTray: BottomTray? = null

    private var filterExtensions: Set<String> = emptySet()
    private var filterChipKeys: List<String> = emptyList()
    private val documentSelection = MultiSelectState()
    private var rootFolders: List<String> = emptyList()
    private var onPicked: ((List<File>) -> Unit)? = null
    private var onCancel: (() -> Unit)? = null

    private var session = 0
    private var readBlockedGeneration = -1
    private var readPermissionAsked = false

    private var currentPath = ROOT
    private var items: List<Item> = emptyList()
    private var selectedDoc: String? = null
    private val fileCards = mutableMapOf<String, PickerCells.FileCardView>()
    private val selectedFilters = LinkedHashSet<String>()
    @Volatile private var generation = 0
    private var gridRevision = 0
    private var listingPending = false
    private var active = false
    private var traceSequence = 0

    private var homeFilesPending = false

    private class PendingThumb(val view: View, val task: Runnable)
    private val pendingThumbs = ArrayList<PendingThumb>()
    private var thumbFlushScheduled = false

    private val gridRows = ArrayList<View>()

    private class Listing(val path: String, val filter: ListingFilter, val items: List<Item>)
    private var listing: Listing? = null

    private val ctx: Context get() = App.context

    private fun dp(v: Int) = Style.hcDp(v)

    fun open(onPicked: (List<File>) -> Unit, onCancel: () -> Unit = {}, onShown: (Boolean) -> Unit = {}) {
        Main.post {
            val opening = ++session
            this.onPicked = onPicked
            this.onCancel = onCancel
            readPermissionAsked = false
            Panels.open(this) { shown ->
                if (shown && opening == session) start()
                onShown(shown)
            }
        }
    }

    fun refresh() {
        Main.post { if (active) navigateTo(currentPath) }
    }

    override fun build(root: LinearLayout) {

        root.setBackgroundColor(Style.clear)
        val frame = object : FrameLayout(root.context) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                    if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) handleBack()
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }.apply {
            setBackgroundColor(Style.clear)
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
        }
        root.addView(frame, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT))
        host = frame
        attach(frame)
    }

    private fun start() {
        active = true
        filterExtensions = config.extensions.map { it.lowercase() }.toSet()
        filterChipKeys = orderedFormatKeys(filterExtensions)
        rootFolders = config.roots.map { Dirs.hostForm(it.path).trimEnd('/') }.distinct()
        documentSelection.clear()
        trace("show title=${config.titleKey}")
        selectedDoc = null
        selectedFilters.clear()
        chips?.setKeys(filterChipKeys)
        chips?.clearSelection()
        currentPath = ROOT
        refreshSelectionChrome()
        navigateTo(currentPath)
        host?.requestFocus()
    }

    private fun handleBack() {
        if (currentPath != ROOT) navigateUp() else finish(null)
    }

    private fun finish(result: List<String>?) {
        trace("dismiss cancelled=${result == null}")
        val picked = onPicked
        val cancel = onCancel
        onPicked = null
        onCancel = null
        reset()
        if (result != null) picked?.invoke(result.map(::File)) else cancel?.invoke()
        hide()
    }

    private fun reset() {
        generation++
        active = false
        listingPending = false
        homeFilesPending = false
        pendingThumbs.clear()
        overlay?.visibility = View.INVISIBLE
        fileCards.clear()
        gridContainer?.removeAllViews()
    }

    override fun onHide() {
        session++
        val cancel = if (active) onCancel else null
        onPicked = null
        onCancel = null
        reset()
        host = null
        overlay = null
        breadcrumbHost = null
        chips = null
        gridContainer = null
        gridScroll = null
        bottomTray = null
        gridRows.clear()
        listing = null
        readPermissionAsked = false
        cancel?.invoke()
    }

    private fun relayout() {
        if (active && !listingPending) rebuildGrid()
    }

    private fun attach(parent: FrameLayout) {
        val page = FrameLayout(ctx).apply {
            setBackgroundColor(Style.paper)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT)
            visibility = View.INVISIBLE
            isClickable = true
            addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
                if (oldRight > oldLeft && oldBottom > oldTop &&
                    (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop)) {
                    Main.post { relayout() }
                }
            }
        }

        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }

        breadcrumbHost = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(4), dp(24), dp(4))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                Style.hcPx(86f),
            )
        }
        body.addView(breadcrumbHost)
        val formatChips = FormatChips(ctx) { formats ->
            selectedFilters.clear()
            selectedFilters.addAll(formats)
            applyFilterChange()
            refreshSelectionChrome()
        }
        chips = formatChips
        body.addView(formatChips.createView())

        val scroll = ScrollHost(ctx, ScrollHost.Mode.PAGED).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            pageItems = { gridRows }
            onScrolled = { scheduleThumbFlush() }
        }
        gridScroll = scroll
        gridContainer = scroll.content.apply {
            setPadding(dp(16), dp(10), maxOf(dp(16), scroll.scrollBarLaneWidthPx), dp(14))
        }
        body.addView(scroll)

        page.addView(body)
        val tray = BottomTray(ctx, { handleBack() }, { confirm() }, { toggleDocumentMulti() })
        bottomTray = tray
        val trayMargin = dp(24)
        page.addView(tray.view, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
        ).apply { bottomMargin = trayMargin })

        tray.view.addOnLayoutChangeListener { v, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top == oldBottom - oldTop) return@addOnLayoutChangeListener
            val content = scroll.content
            val want = v.height + trayMargin * 2
            if (content.paddingBottom != want) scroll.post {
                content.setPadding(content.paddingLeft, content.paddingTop, content.paddingRight, want)
            }
        }

        tray.setAction(Strings.get("picker_select"), visible = false)

        overlay = page
        parent.addView(page)
    }

    private fun navigateUp() {
        if (currentPath == ROOT) return
        val parentDir = File(currentPath).parent ?: ROOT
        navigateTo(if (parentDir.length < ROOT.length) ROOT else parentDir)
    }

    private fun navigateTo(path: String) {
        if (!active) return
        val gen = ++generation
        listingPending = true
        val fromPath = currentPath
        val wasShowing = overlay?.visibility == View.VISIBLE
        currentPath = path
        refreshSelectionChrome()

        val filter = ListingFilter(filterExtensions.toSet(), rootFolders.toList())
        val cached = cachedListing(cacheKey(path, filter))
        if (cached != null) {

            homeFilesPending = false
            showListing(path, filter, cached.items, resetScroll = true)
            trace("cache path=$path items=${cached.items.size}")
            scan(path, filter) { result, changed, blocked ->
                Main.post {
                    if (gen != generation) return@post
                    if (blocked) reportReadBlocked(gen)
                    if (changed && !blocked) {
                        trace("rescan changed path=$path items=${result.size}")
                        showListing(path, filter, result, resetScroll = false)
                    }
                }
            }
            return
        }

        val shellFirst = path == ROOT && (!wasShowing || fromPath != ROOT)
        homeFilesPending = shellFirst
        if (shellFirst) {
            items = homeFolders(null)
            rebuildGrid(resetScroll = true)
        }
        scan(path, filter) { result, _, blocked ->
            Main.post {
                if (gen != generation) return@post
                trace("scan path=$path items=${result.size} blocked=$blocked")
                if (blocked) reportReadBlocked(gen)
                homeFilesPending = false
                showListing(path, filter, result, resetScroll = !shellFirst)
            }
        }
    }

    private fun showListing(path: String, filter: ListingFilter, all: List<Item>, resetScroll: Boolean) {
        listing = Listing(path, filter, all)
        items = filteredItems(all)
        rebuildGrid(resetScroll)
    }

    private fun filteredItems(all: List<Item>): List<Item> {
        val keys = selectedFilters.toSet()
        return all.filter { item ->
            item.isDir || (config.filter(File(item.path)) &&
                (keys.isEmpty() || keys.any { matchesFilterKey(extensionOf(item.name), it) }))
        }
    }

    private fun applyFilterChange() {
        val cached = listing?.takeIf {
            it.path == currentPath && !listingPending && !homeFilesPending &&
                it.filter.allowedTypes == filterExtensions
        }
        if (cached == null) {
            navigateTo(currentPath)
            return
        }
        val gen = ++generation
        pendingThumbs.clear()
        Choreographer.getInstance().postFrameCallback {
            Main.post {
                if (gen != generation) return@post
                items = filteredItems(cached.items)
                trace("filter path=$currentPath formats=$selectedFilters items=${items.size}")
                rebuildGrid(resetScroll = true)
            }
        }
    }

    private fun refreshBreadcrumb() {
        val crumbs = breadcrumbHost ?: return
        crumbs.removeAllViews()
        crumbs.addView(
            PickerCells.breadcrumb(ctx, ROOT, currentPath) { navigateTo(it) },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                Style.hcPx(86f),
            ),
        )
    }

    private fun reportReadBlocked(expectedGeneration: Int) {
        if (generation != expectedGeneration || readBlockedGeneration == expectedGeneration) return
        readBlockedGeneration = expectedGeneration
        requestReadPermissionOnce(session)
    }

    private fun requestReadPermissionOnce(opening: Int) {
        if (session != opening || !showing || readPermissionAsked) return
        readPermissionAsked = true
        val now = System.currentTimeMillis()
        if (now - lastPermissionToastAt > PERMISSION_TOAST_GAP_MS) {
            lastPermissionToastAt = now
            Toast.makeText(ctx, Strings.get("file_read_permission_needed"), Toast.LENGTH_LONG).show()
        }
        HostPlugins.requestPermission(HostPlugins.FILE_READ) { granted ->
            Main.post {
                if (session != opening || !showing) return@post
                if (granted) refresh()
            }
        }
    }

    private fun pickerColumns(): Int {
        val parent = host
        val landscape = if (parent != null && parent.width > 0 && parent.height > 0) {
            parent.width > parent.height
        } else {
            ctx.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        }
        return if (landscape) 6 else Style.device().pickerGridColumns
    }

    private fun rebuildGrid(resetScroll: Boolean = false) {
        val container = gridContainer ?: return
        val gen = generation
        val revision = ++gridRevision
        if (container.width <= 0) {
            container.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
                override fun onLayoutChange(v: View, left: Int, top: Int, right: Int, bottom: Int,
                                            oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int) {
                    if (right <= left) return
                    v.removeOnLayoutChangeListener(this)
                    Main.post {
                        if (gen == generation && revision == gridRevision) rebuildGrid(resetScroll)
                    }
                }
            })
            return
        }

        pendingThumbs.clear()
        val columns = pickerColumns()
        val nextCards = mutableMapOf<String, PickerCells.FileCardView>()
        val nextGrid = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val atRoot = currentPath == ROOT
        val dirs = if (atRoot) items.filter { it.isDir } else emptyList()
        val gridItems = if (atRoot) items.filter { !it.isDir } else items
        val nextRows = ArrayList<View>()
        if (dirs.isNotEmpty()) nextGrid.addView(buildHomeFolderRow(dirs, columns, nextRows))
        var metrics: CellMetrics? = null
        var laterRows: List<List<Item>> = emptyList()
        var firstRows = 0
        when {
            gridItems.isNotEmpty() -> {
                val m = cellMetrics(container, columns)
                val rows = gridItems.chunked(columns)

                firstRows = firstScreenRows(m).coerceAtMost(rows.size)
                rows.take(firstRows).forEach {
                    val row = buildGridRow(it, m, nextCards)
                    nextRows += row
                    nextGrid.addView(row)
                }
                laterRows = rows.drop(firstRows)
                metrics = m
            }
            homeFilesPending -> nextGrid.addView(hintView(Strings.get("picker_loading_files")))
            items.isEmpty() -> nextGrid.addView(hintView(Strings.get(config.emptyKey).ifBlank { Strings.get("picker_empty_folder") }))
        }
        if (gen != generation || revision != gridRevision) return

        container.removeAllViews()
        container.addView(nextGrid, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        fileCards.clear()
        fileCards.putAll(nextCards)
        gridRows.clear()
        gridRows.addAll(nextRows)
        listingPending = false
        refreshBreadcrumb()
        refreshSelectionChrome()
        val page = overlay ?: return
        val parent = host
        if (parent != null && parent.width > 0 && parent.height > 0) {
            page.measure(
                View.MeasureSpec.makeMeasureSpec(parent.width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(parent.height, View.MeasureSpec.EXACTLY),
            )
            page.layout(0, 0, parent.width, parent.height)
        }
        if (resetScroll) gridScroll?.scrollToOffset(0)
        scheduleThumbFlush()
        page.visibility = View.VISIBLE
        page.bringToFront()
        trace("grid_commit path=$currentPath columns=$columns items=${items.size} " +
            "rows=$firstRows later=${laterRows.size} pendingHome=$homeFilesPending generation=$gen")
        val m = metrics
        if (m != null && laterRows.isNotEmpty()) appendRowsLater(nextGrid, laterRows, m, gen, revision)
    }

    private fun appendRowsLater(grid: LinearLayout, rows: List<List<Item>>, m: CellMetrics,
                                gen: Int, revision: Int) {
        val tail = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        var next = 0
        Main.handler.post(object : Runnable {
            override fun run() {
                if (gen != generation || revision != gridRevision) return
                val end = minOf(rows.size, next + LATER_ROWS_PER_BATCH)
                while (next < end) {
                    val row = buildGridRow(rows[next++], m, fileCards)
                    gridRows += row
                    tail.addView(row)
                }
                if (next < rows.size) {
                    Main.handler.post(this)
                    return
                }
                grid.addView(tail, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
                trace("grid_tail path=$currentPath rows=${rows.size} generation=$gen")
            }
        })
    }

    private fun hintView(message: String) = TextView(ctx).apply {
        text = message
        textSize = Style.hcSp(13.5f)
        setTextColor(Style.ink)
        gravity = Gravity.CENTER
        setPadding(0, dp(60), 0, dp(60))
    }

    private fun buildHomeFolderRow(dirs: List<Item>, columns: Int, rowsOut: MutableList<View>): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(8))
        }
        dirs.chunked(columns).forEach { chunk ->
            val line = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            chunk.forEach { item ->
                line.addView(
                    PickerCells.homeFolderCell(ctx, item.name) { navigateTo(item.path) },
                    LinearLayout.LayoutParams(0, Style.hcPx(126f), 1f),
                )
            }
            repeat(columns - chunk.size) {
                line.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
            }
            row.addView(line)
            rowsOut += line
        }
        return row
    }

    private fun cellMetrics(container: View, columns: Int): CellMetrics {
        val gap = dp(12)
        val colW = ((container.width - container.paddingLeft - container.paddingRight -
            gap * (columns - 1)) / columns).coerceAtLeast(dp(80))
        val coverW = (colW * 0.92f).toInt()
        val coverH = (coverW * PickerCells.COVER_ASPECT).toInt()
        return CellMetrics(colW, coverW, coverH, gap)
    }

    private fun firstScreenRows(m: CellMetrics): Int {
        val viewport = gridScroll?.viewportHeight?.takeIf { it > 0 } ?: (host?.height ?: 0)
        return (viewport / (m.coverH + m.gap) + 2).coerceAtLeast(3)
    }

    private fun buildGridRow(rowItems: List<Item>, m: CellMetrics,
                             cards: MutableMap<String, PickerCells.FileCardView>): View {
        val line = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, m.gap)
        }
        rowItems.forEachIndexed { index, item ->
            val cell = if (item.isDir) {
                PickerCells.folderCell(
                    ctx, item.name, item.childCount, m.coverW, m.coverH, item.previewPaths,
                    ::loadThumbnail,
                ) { navigateTo(item.path) }
            } else {
                val (view, card) = PickerCells.fileCell(
                    ctx, item.name, fileDetail(item), item.path, m.coverW, m.coverH,
                    isSelectedPath(item.path), ::loadThumbnail,
                ) { onCellTap(item) }
                cards[item.path] = card
                view
            }
            line.addView(cell, LinearLayout.LayoutParams(m.colW, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                if (index != 0) marginStart = m.gap
            })
        }
        return line
    }

    private fun fileDetail(item: Item): String {
        val ext = extensionOf(item.name).uppercase().ifBlank { "FILE" }
        return "$ext · ${formatSize(item.size)}"
    }

    private fun loadThumbnail(path: String, view: ImageView, reqW: Int, reqH: Int) {
        val gen = generation
        pendingThumbs.add(PendingThumb(view, Runnable { decodeThumbnail(path, view, reqW, reqH, gen) }))
        scheduleThumbFlush()
    }

    private fun scheduleThumbFlush() {
        if (thumbFlushScheduled || pendingThumbs.isEmpty()) return
        thumbFlushScheduled = true
        Choreographer.getInstance().postFrameCallback {

            Main.post {
                thumbFlushScheduled = false
                flushVisibleThumbs()
            }
        }
    }

    private fun flushVisibleThumbs() {
        val scroll = gridScroll ?: return
        val viewport = scroll.viewportHeight
        if (viewport <= 0) return
        val top = scroll.scrollOffset
        val bottom = top + 2 * viewport
        val it = pendingThumbs.iterator()
        while (it.hasNext()) {
            val p = it.next()
            val y = scroll.topInContent(p.view) ?: continue
            if (p.view.height == 0 && !p.view.isLaidOut) continue
            if (y + p.view.height >= top && y <= bottom) {
                thumbnailExecutor.execute(p.task)
                it.remove()
            }
        }
    }

    private fun decodeThumbnail(path: String, view: ImageView, reqW: Int, reqH: Int, gen: Int) {

        if (gen != generation) return
        if (path.endsWith(".note", ignoreCase = true)) {
            noteCoverExecutor.execute {
                if (gen == generation) decodeDocumentCover(path, view, reqW, reqH, gen)
            }
            return
        }
        if (PickerCells.hasDocumentCover(path)) {
            decodeDocumentCover(path, view, reqW, reqH, gen)
            return
        }
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= reqW &&
                bounds.outHeight / (sample * 2) >= reqH) sample *= 2
            if (gen != generation) return
            val bmp = BitmapFactory.decodeFile(
                path, BitmapFactory.Options().apply { inSampleSize = sample },
            ) ?: return
            Main.post {
                if (gen == generation) view.setImageBitmap(bmp) else bmp.recycle()
            }
        } catch (e: Throwable) {

            Log.w(LOG_TAG, "thumbnail failed $path: $e")
        }
    }

    private fun decodeDocumentCover(path: String, view: ImageView, reqW: Int, reqH: Int, gen: Int) {

        PluginIdentity.claim()
        val bmp = try {
            PickerCovers.load(path, reqW, reqH)
        } catch (e: Throwable) {
            Log.w(LOG_TAG, "cover failed $path: $e")
            null
        } ?: return
        Main.post {
            if (gen != generation) return@post
            view.setBackgroundColor(Style.paper)
            view.setImageBitmap(bmp)
            (view.parent as? PickerCells.FileCardView)?.setShowingCover(true)
        }
    }

    private fun isSelectedPath(path: String): Boolean =
        path == selectedDoc || documentSelection.isSelected(path)

    private fun onCellTap(item: Item) {
        if (!active || listingPending) return
        if (item.isDir) {
            navigateTo(item.path)
            return
        }

        if (documentSelection.isActive) {
            updateCell(item.path, documentSelection.toggle(item.path))
            refreshSelectionChrome()
        } else {
            trace("tap_auto path=${item.path}")
            selectedDoc = item.path
            confirm()
        }
    }

    private fun updateCell(path: String, selected: Boolean) {
        fileCards[path]?.setCardSelected(selected)
    }

    private fun toggleDocumentMulti() {
        if (!active || !config.multi || listingPending) return
        if (documentSelection.isActive) {
            documentSelection.deactivate()
        } else {
            documentSelection.activate()
        }
        selectedDoc = null
        fileCards.forEach { (path, card) -> card.setCardSelected(isSelectedPath(path)) }
        refreshSelectionChrome()
    }

    private fun refreshSelectionChrome() {
        val multiActive = documentSelection.isActive
        val count = if (multiActive) documentSelection.count else 0
        val actionLabel = Strings.get(config.actionKey)
        bottomTray?.setMultiSelection(
            visible = config.multi,
            enabled = !listingPending,
            active = multiActive,
        )

        bottomTray?.setAction(
            if (count > 0) "$actionLabel (${Strings.itemCount(count)})" else actionLabel,
            visible = multiActive,
            enabled = multiActive && count > 0 && !listingPending,
            count = count,
        )
    }

    private fun confirm() {
        if (!active || listingPending) return
        val result = when {
            documentSelection.isActive -> documentSelection.selectedPaths.ifEmpty { return }
            selectedDoc != null -> listOf(selectedDoc!!)
            else -> return
        }
        trace("confirm paths=${result.joinToString()}")
        finish(result)
    }

    private fun trace(event: String) {
        if (!BuildConfig.ENABLE_DEBUG) return
        traceSequence += 1
        Log.i(
            LOG_TAG,
            "picker-trace seq=$traceSequence wallMs=${System.currentTimeMillis()} " +
                "elapsedMs=${SystemClock.elapsedRealtime()} $event",
        )
    }
}

private object PickerCells {
    const val COVER_ASPECT = 450f / 350f
    private const val DOCUMENT_FOLD_RATIO = 0.18f
    private const val NOTE_SPINE_RATIO = 0.052f
    private const val FILE_CARD_STROKE = 2f
    private const val FILE_CARD_SELECTED_STROKE = 4f
    private val previewImageExts = setOf("jpg", "jpeg", "png", "webp")
    private val documentPreviewExts = setOf("note", "pdf", "cbz", "epub", "zip")

    private val documentCoverExts = setOf("pdf", "epub", "cbz", "zip", "note")

    fun hasDocumentCover(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase(Locale.ROOT) in documentCoverExts

    fun isPreviewImage(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase(Locale.ROOT) in previewImageExts

    fun breadcrumb(
        ctx: Context,
        rootPath: String,
        currentPath: String,
        onNavigate: (String) -> Unit,
    ): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        fun addLevel(label: String, destination: String, home: Boolean) {
            row.addView(TextView(ctx).apply {
                text = label
                setTextSize(TypedValue.COMPLEX_UNIT_PX, Style.hcPx(if (home) 57f else 42f).toFloat())
                typeface = if (home) Typeface.SERIF else Typeface.DEFAULT
                setTextColor(Style.pickerInk)
                gravity = Gravity.CENTER_VERTICAL
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                maxWidth = Style.hcPx(if (home) 340f else 420f)
                setOnClickListener { onNavigate(destination) }
            })
        }
        addLevel("Supernote", rootPath, home = true)
        val relative = currentPath.removePrefix(rootPath).trim('/')
        if (relative.isNotEmpty()) {
            var destination = rootPath
            relative.split('/').filter { it.isNotBlank() }.forEach { component ->
                destination = "$destination/$component"
                row.addView(TextView(ctx).apply {
                    text = "›"
                    setTextSize(TypedValue.COMPLEX_UNIT_PX, Style.hcPx(50f).toFloat())
                    setTextColor(Style.pickerSlotLine)
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(
                        Style.hcPx(58f),
                        LinearLayout.LayoutParams.MATCH_PARENT,
                    )
                })
                addLevel(component, destination, home = false)
            }
        }
        return HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }
    }

    fun homeFolderCell(
        ctx: Context,
        name: String,
        onClick: () -> Unit,
    ): View {
        val scale = Style.hcScale()
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Style.hcPx(14f), Style.hcPx(21f), Style.hcPx(14f), Style.hcPx(21f))
            setOnClickListener { onClick() }
            addView(
                CompactFolderIconView(ctx, scale),
                LinearLayout.LayoutParams(Style.hcPx(62f), Style.hcPx(54f)).apply {
                    marginEnd = Style.hcPx(18f)
                },
            )
            addView(TextView(ctx).apply {
                text = name
                setTextSize(TypedValue.COMPLEX_UNIT_PX, Style.hcPx(38f).toFloat())
                setTextColor(Style.pickerTitle)
                gravity = Gravity.CENTER_VERTICAL
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, Style.hcPx(54f), 1f))
        }
    }

    fun folderCell(
        ctx: Context,
        name: String,
        childCount: Int,
        coverW: Int,
        coverH: Int,
        previewPaths: List<String>,
        loadThumb: (String, ImageView, Int, Int) -> Unit,
        onClick: () -> Unit,
    ): View {
        val scale = Style.hcScale()
        val cell = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setOnClickListener { onClick() }
        }
        val preview = FolderPreviewView(ctx, scale, previewPaths, loadThumb)
        cell.addView(preview, LinearLayout.LayoutParams(coverW, coverH))
        cell.addView(metaBlock(ctx, name, Strings.itemCount(childCount), folder = true))
        return cell
    }

    fun fileCell(
        ctx: Context,
        name: String,
        detail: String,
        path: String,
        coverW: Int,
        coverH: Int,
        selected: Boolean,
        loadThumb: (String, ImageView, Int, Int) -> Unit,
        onClick: () -> Unit,
    ): Pair<View, FileCardView> {
        val scale = Style.hcScale()
        val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val cell = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val card = FileCardView(ctx, scale, extension, selected).apply {
            setOnClickListener { onClick() }
        }
        if (extension in previewImageExts) {
            val image = ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(Style.pickerImageBg)
            }
            card.addView(
                image,
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
            )
            loadThumb(path, image, coverW, coverH)
        } else {
            addExtensionLabel(ctx, card, extension)
            if (extension in documentCoverExts) {

                val image = ImageView(ctx).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }
                card.addView(
                    image,
                    FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
                )
                loadThumb(path, image, coverW, coverH)
            }
        }
        cell.addView(card, LinearLayout.LayoutParams(coverW, coverH))
        val title = File(name).nameWithoutExtension.takeIf { it.isNotBlank() } ?: name
        cell.addView(metaBlock(ctx, title, detail, folder = false))
        return cell to card
    }

    private fun addExtensionLabel(ctx: Context, card: FileCardView, extension: String) {
        card.addView(TextView(ctx).apply {
            text = extension.uppercase(Locale.ROOT).ifBlank { "FILE" }
            setTextSize(TypedValue.COMPLEX_UNIT_PX, Style.hcPx(36f).toFloat())
            typeface = Typeface.SERIF
            setTextColor(Style.pickerExtension)
            gravity = Gravity.CENTER
        }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    }

    private fun metaBlock(
        ctx: Context,
        title: String,
        detail: String,
        folder: Boolean,
    ): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.START
        setPadding(0, Style.hcPx(20f), 0, 0)
        addView(TextView(ctx).apply {
            text = title
            setTextSize(TypedValue.COMPLEX_UNIT_PX, Style.hcPx(if (folder) 30f else 36f).toFloat())
            typeface = Typeface.SERIF
            setTextColor(Style.pickerTitle)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        })
        addView(TextView(ctx).apply {
            text = detail
            setTextSize(TypedValue.COMPLEX_UNIT_PX, Style.hcPx(if (folder) 22f else 24f).toFloat())
            setTextColor(Style.subtle)
            maxLines = 1
        })
    }

    class FileCardView(
        context: Context,
        private val scale: Float,
        private val extension: String,
        selected: Boolean,
    ) : FrameLayout(context) {
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Style.paper
            style = Paint.Style.FILL
        }
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
        }
        private var cardSelected = selected

        private var showingCover = false

        init {
            setWillNotDraw(false)
            isClickable = true
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            val noteStart = if (extension == "note") {
                (w * NOTE_SPINE_RATIO + 3f * scale).roundToInt().coerceAtLeast(1)
            } else 0
            setPadding(noteStart, 0, 0, 0)
        }

        fun setShowingCover(showing: Boolean) {
            if (showingCover == showing) return
            showingCover = showing
            invalidate()
        }

        fun setCardSelected(selected: Boolean) {
            cardSelected = selected
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            drawCard(canvas, fillOnly = true)
            super.onDraw(canvas)
        }

        override fun dispatchDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            strokePaint.strokeWidth = (if (cardSelected) FILE_CARD_SELECTED_STROKE else FILE_CARD_STROKE) * scale
            strokePaint.color = if (cardSelected) Style.ink else Style.pickerCardStroke
            val inset = strokePaint.strokeWidth / 2f
            if (extension in documentPreviewExts && extension != "note" && !showingCover) {
                val fold = w * DOCUMENT_FOLD_RATIO
                val outline = documentOutline(w, h, inset, fold)
                val checkpoint = canvas.save()
                canvas.clipPath(outline)
                super.dispatchDraw(canvas)
                canvas.restoreToCount(checkpoint)
                canvas.drawPath(outline, strokePaint)
                val foldedCorner = Path().apply {
                    moveTo(w - fold, inset)
                    lineTo(w - fold, fold)
                    lineTo(w - inset, fold)
                    close()
                }
                canvas.drawPath(foldedCorner, fillPaint)
                canvas.drawPath(foldedCorner, strokePaint)
            } else {
                val rect = RectF(inset, inset, w - inset, h - inset)
                val checkpoint = canvas.save()
                canvas.clipRect(rect)
                super.dispatchDraw(canvas)
                canvas.restoreToCount(checkpoint)
                canvas.drawRect(rect, strokePaint)
                if (extension == "note") {
                    val spine = w * NOTE_SPINE_RATIO
                    canvas.drawLine(spine, inset, spine, h - inset, strokePaint)
                }
            }
            if (cardSelected) drawSelectionBadge(canvas)
        }

        private fun drawSelectionBadge(canvas: Canvas) {
            val left = 10f * scale
            val top = 10f * scale
            val size = 58f * scale
            SelectionArtwork.draw(canvas, RectF(left, top, left + size, top + size))
        }

        private fun drawCard(canvas: Canvas, fillOnly: Boolean) {
            val w = width.toFloat()
            val h = height.toFloat()
            strokePaint.strokeWidth = (if (cardSelected) FILE_CARD_SELECTED_STROKE else FILE_CARD_STROKE) * scale
            strokePaint.color = if (cardSelected) Style.ink else Style.pickerCardStroke
            val inset = strokePaint.strokeWidth / 2f
            if (extension in documentPreviewExts && extension != "note" && !showingCover) {
                val outline = documentOutline(w, h, inset, w * DOCUMENT_FOLD_RATIO)
                canvas.drawPath(outline, fillPaint)
                if (!fillOnly) canvas.drawPath(outline, strokePaint)
            } else {
                val rect = RectF(inset, inset, w - inset, h - inset)
                canvas.drawRect(rect, fillPaint)
                if (!fillOnly) canvas.drawRect(rect, strokePaint)
            }
        }

        private fun documentOutline(width: Float, height: Float, inset: Float, fold: Float) =
            Path().apply {
                moveTo(inset, inset)
                lineTo(width - fold, inset)
                lineTo(width - inset, fold)
                lineTo(width - inset, height - inset)
                lineTo(inset, height - inset)
                close()
            }
    }

    fun folderPath(
        path: Path,
        left: Float, top: Float, right: Float, bottom: Float,
        tabEnd: Float, tabSlope: Float, bodyTop: Float, radius: Float,
    ) {
        val tabRadius = minOf(radius, (bodyTop - top) * 0.9f)
        path.reset()
        path.moveTo(left, bottom - radius)
        path.lineTo(left, top + tabRadius)
        path.quadTo(left, top, left + tabRadius, top)
        path.lineTo(tabEnd, top)
        val midX = tabEnd + tabSlope / 2f
        path.cubicTo(midX, top, midX, bodyTop, tabEnd + tabSlope, bodyTop)
        path.lineTo(right - radius, bodyTop)
        path.quadTo(right, bodyTop, right, bodyTop + radius)
        path.lineTo(right, bottom - radius)
        path.quadTo(right, bottom, right - radius, bottom)
        path.lineTo(left + radius, bottom)
        path.quadTo(left, bottom, left, bottom - radius)
        path.close()
    }

    private class CompactFolderIconView(
        context: Context,
        private val scale: Float,
    ) : View(context) {
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Style.pickerInk
            style = Paint.Style.FILL
        }
        private val gapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Style.paper
            style = Paint.Style.FILL
        }
        private val back = Path()
        private val front = RectF()

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()

            val iconW = minOf(w, h * 1.2f)
            val iconH = iconW / 1.2f
            val left = (w - iconW) / 2f
            val top = (h - iconH) / 2f
            val right = left + iconW
            val bottom = top + iconH
            val radius = iconH * 0.12f
            folderPath(
                back, left, top, right, bottom,
                tabEnd = left + iconW * 0.34f,
                tabSlope = iconW * 0.1f,
                bodyTop = top + iconH * 0.16f,
                radius = radius,
            )
            canvas.drawPath(back, fillPaint)
            val gap = maxOf(3f * scale, 1.5f)
            front.set(left, top + iconH * 0.3f, right, bottom)
            canvas.drawRoundRect(
                front.left, front.top - gap, front.right, front.bottom,
                radius, radius, gapPaint,
            )
            canvas.drawRoundRect(front, radius, radius, fillPaint)
        }
    }

    private class FolderPreviewView(
        context: Context,
        scale: Float,
        previewPaths: List<String>,
        private val loadThumb: (String, ImageView, Int, Int) -> Unit,
    ) : FrameLayout(context) {
        private val previewImages = ArrayList<ImageView>()
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Style.paper
            style = Paint.Style.FILL
        }
        private val tabPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Style.pickerFolderTab
            style = Paint.Style.FILL
        }
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Style.pickerFolderLine
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            strokeWidth = 3f * scale
        }
        private val outline = Path()

        init {
            setWillNotDraw(false)
            val slotStroke = (2f * scale).roundToInt().coerceAtLeast(1)
            previewPaths.take(4).forEach { path ->
                val image = ImageView(context).apply {
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    background = Style.boxPx(Style.paper, Style.pickerSlotLine, slotStroke)

                    setPadding(slotStroke, slotStroke, slotStroke, slotStroke)
                    cropToPadding = true
                    tag = path
                }
                previewImages.add(image)
                addView(image, LayoutParams(1, 1))
            }
        }

        override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
            super.onSizeChanged(width, height, oldWidth, oldHeight)
            if (width <= 0 || height <= 0) return
            val slotWidth = (width * 0.34f).roundToInt()
            val slotHeight = (slotWidth * 1.31f).roundToInt()
            val horizontalGap = (width * 0.045f).roundToInt()
            val verticalGap = (height * 0.038f).roundToInt()
            val left = (width - slotWidth * 2 - horizontalGap) / 2
            val top = (height * 0.13f).roundToInt()
            previewImages.forEachIndexed { index, image ->
                image.layoutParams = LayoutParams(slotWidth, slotHeight).apply {
                    leftMargin = left + (index % 2) * (slotWidth + horizontalGap)
                    topMargin = top + (index / 2) * (slotHeight + verticalGap)
                }
                val path = image.tag as? String ?: return@forEachIndexed
                loadThumb(path, image, slotWidth, slotHeight)
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            val inset = strokePaint.strokeWidth / 2f
            val bodyTop = h * 0.075f
            val tabEnd = w * 0.36f
            val tabSlope = w * 0.08f
            folderPath(outline, inset, inset, w - inset, h - inset, tabEnd, tabSlope, bodyTop, radius = w * 0.045f)
            canvas.drawPath(outline, fillPaint)

            val checkpoint = canvas.save()
            canvas.clipPath(outline)
            canvas.drawRect(0f, 0f, tabEnd + tabSlope, bodyTop, tabPaint)
            canvas.restoreToCount(checkpoint)
            canvas.drawPath(outline, strokePaint)
            canvas.drawLine(inset, bodyTop, tabEnd + tabSlope, bodyTop, strokePaint)
        }
    }
}

private object SelectionArtwork {
    private val checked: Bitmap by lazy {
        val bytes = Base64.decode(
            "iVBORw0KGgoAAAANSUhEUgAAADoAAAA6CAAAAADE0BX0AAABKElEQVRIx+3WIU8DMRiA4TaBiSVkYWQWM4Ei2R8AsV/ABBYHCo1jYvMIDkLCT9gEE7NDgMGQETwGOSRhySDZO3EFBlx3/T5H0k/fk9y1b5szJk6cOGGzmtxdlFSycAt0VPQSYKyRhwAMFbL+ATDZksvqCwAHcrnyCMC5XNoeANcFOW0D8FSRy90pwOumXNbeAKYNuaw8A9BU9HcDQNcq++Oh6H2gfNLfs/7+Ruv+jbsHEuvtb9v/VhuQaauj3P7W3jOt6+9s4WIcAcCpzehvsLx4IZsZthXY318b3t9v6/rbCdn8n9b1dxwWzrx1/XVC+5uzaX/DYnCwXza3P6+90tx/zgKwLzxk3zYRH9BPO1gySqu5/1Kruf9Sm9ef92NaxvTir0icfzAzJsklBbuZT48AAAAASUVORK5CYII=",
            Base64.DEFAULT,
        )
        requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
    }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    fun draw(canvas: Canvas, bounds: RectF) {
        canvas.drawBitmap(checked, null, bounds, paint)
    }
}

private class FormatChips(
    private val ctx: Context,
    private val onChange: (Set<String>) -> Unit,
) {
    private var keys: List<String> = emptyList()
    private val selected = LinkedHashSet<String>()
    private var container: LinearLayout? = null

    fun createView(): View {
        val row = HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
        }
        container = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL

            setPadding(d(26), d(12), d(26), d(16))
        }
        rebuild()
        row.addView(container)
        return row
    }

    fun setKeys(next: List<String>) {
        val normalized = next.distinct()
        if (normalized == keys) return
        keys = normalized
        selected.retainAll(keys.toSet())
        rebuild()
    }

    fun clearSelection() {
        if (selected.isEmpty()) return
        selected.clear()
        rebuild()
    }

    private fun rebuild() {
        val c = container ?: return
        c.removeAllViews()
        (c.parent as? HorizontalScrollView)?.scrollTo(0, 0)
        keys.forEachIndexed { index, key ->
            val active = key in selected
            c.addView(TextView(ctx).apply {
                text = key.uppercase(Locale.ROOT)
                textSize = Style.hcSp(14f)
                typeface = Style.bold
                setTextColor(if (active) Style.paper else Style.ink)
                gravity = Gravity.CENTER
                setPadding(d(14), d(8), d(14), d(8))
                background = Style.boxPx(if (active) Style.ink else Style.paper, Style.ink, 2, d(53).toFloat())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { if (index > 0) marginStart = d(20) }
                setOnClickListener {
                    if (!selected.add(key)) selected.remove(key)
                    rebuild()
                    onChange(selected.toSet())
                }
            })
        }
    }

    private fun d(v: Int) = Style.hcDp(v)
}

private class BottomTray(
    ctx: Context,
    onBack: () -> Unit,
    onAction: () -> Unit,
    onMulti: () -> Unit,
) {
    val view: View
    private val actionButton: TrayIconButton
    private val divider: View
    private val multiButton: TrayIconButton

    init {

        val buttonW = Style.hcPx(120)
        val buttonH = Style.hcPx(92)
        val tray = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(Style.hcPx(12), Style.hcPx(4), Style.hcPx(12), Style.hcPx(4))
            background = Style.boxPx(Style.paper, Style.ink, Style.hcPx(3), Style.hcPx(60).toFloat())
        }
        tray.addView(TrayIconButton(ctx, TrayIconButton.Glyph.BACK).apply {
            contentDescription = Strings.get("picker_back")
            setOnClickListener { onBack() }
        }, LinearLayout.LayoutParams(buttonW, buttonH))
        multiButton = TrayIconButton(ctx, TrayIconButton.Glyph.MULTI).apply {
            contentDescription = Strings.get("multi_select")
            visibility = View.GONE
            setOnClickListener { onMulti() }
        }
        tray.addView(multiButton, LinearLayout.LayoutParams(buttonW, buttonH))
        actionButton = TrayIconButton(ctx, TrayIconButton.Glyph.CHECK).apply {
            contentDescription = Strings.get("picker_select")
            visibility = View.GONE
            setOnClickListener { onAction() }
        }
        divider = View(ctx).apply {
            setBackgroundColor(Style.ink)
            layoutParams = LinearLayout.LayoutParams(Style.hcPx(2), Style.hcPx(48)).apply {
                leftMargin = Style.hcPx(6)
                rightMargin = Style.hcPx(6)
            }
            visibility = View.GONE
        }
        tray.addView(divider)
        tray.addView(actionButton, LinearLayout.LayoutParams(buttonW, buttonH))
        view = tray
    }

    fun setAction(label: String, visible: Boolean, enabled: Boolean = true, count: Int = 0) {
        actionButton.contentDescription = label
        actionButton.count = count
        actionButton.visibility = if (visible) View.VISIBLE else View.GONE
        divider.visibility = if (visible) View.VISIBLE else View.GONE
        actionButton.isEnabled = enabled
    }

    fun setMultiSelection(visible: Boolean, enabled: Boolean, active: Boolean) {
        multiButton.visibility = if (visible) View.VISIBLE else View.GONE
        multiButton.isEnabled = enabled
        multiButton.active = active
    }
}

private class TrayIconButton(ctx: Context, private val glyph: Glyph) : View(ctx) {
    enum class Glyph { BACK, CHECK, MULTI }

    var count = 0
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    var active = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Style.ink
        style = Paint.Style.FILL
    }
    private val activeRect = RectF()
    private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Style.paper
        textAlign = Paint.Align.CENTER
        typeface = Style.bold
    }
    private val path = Path()
    private val badgeRect = RectF()

    init {
        isClickable = true
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val color = when {
            active -> Style.paper
            isEnabled -> Style.ink
            else -> Style.pickerTrayDisabled
        }
        if (active) {
            val inset = height * 0.08f
            activeRect.set(inset, inset, width - inset, height - inset)
            val radius = activeRect.height() / 2f
            canvas.drawRoundRect(activeRect, radius, radius, activePaint)
        }

        val size = minOf(width, height) * 0.56f
        val s = size / 24f
        val ox = (width - size) / 2f
        val oy = (height - size) / 2f
        strokePaint.color = color
        strokePaint.strokeWidth = 2.6f * s
        path.reset()
        when (glyph) {
            Glyph.BACK -> {
                path.moveTo(ox + 19.5f * s, oy + 12f * s)
                path.lineTo(ox + 4.5f * s, oy + 12f * s)
                path.moveTo(ox + 11f * s, oy + 5.5f * s)
                path.lineTo(ox + 4.5f * s, oy + 12f * s)
                path.lineTo(ox + 11f * s, oy + 18.5f * s)
            }
            Glyph.CHECK -> {
                path.moveTo(ox + 4f * s, oy + 12.5f * s)
                path.lineTo(ox + 9.5f * s, oy + 18f * s)
                path.lineTo(ox + 20f * s, oy + 6.5f * s)
            }
            Glyph.MULTI -> {

                path.moveTo(ox + 8f * s, oy + 4f * s)
                path.lineTo(ox + 17.5f * s, oy + 4f * s)
                path.quadTo(ox + 20f * s, oy + 4f * s, ox + 20f * s, oy + 6.5f * s)
                path.lineTo(ox + 20f * s, oy + 16f * s)
                path.addRoundRect(
                    ox + 4f * s, oy + 8f * s, ox + 16f * s, oy + 20f * s,
                    2.5f * s, 2.5f * s, Path.Direction.CW,
                )
                path.moveTo(ox + 7f * s, oy + 14f * s)
                path.lineTo(ox + 9.3f * s, oy + 16.3f * s)
                path.lineTo(ox + 13.2f * s, oy + 11.8f * s)
            }
        }
        canvas.drawPath(path, strokePaint)
        if (count > 0) drawBadge(canvas, color, ox + size + 1.5f * s, oy + 2.5f * s, s)
    }

    private fun drawBadge(canvas: Canvas, color: Int, cx: Float, cy: Float, s: Float) {
        val label = if (count > 99) "99+" else count.toString()
        badgeTextPaint.textSize = 9.5f * s
        val radius = 7.5f * s
        val halfWidth = maxOf(radius, badgeTextPaint.measureText(label) / 2f + 4f * s)
        badgeRect.set(cx - halfWidth, cy - radius, cx + halfWidth, cy + radius)
        badgePaint.color = Style.paper
        val ring = 1.5f * s
        canvas.drawRoundRect(
            badgeRect.left - ring, badgeRect.top - ring, badgeRect.right + ring, badgeRect.bottom + ring,
            radius + ring, radius + ring, badgePaint,
        )
        badgePaint.color = color
        canvas.drawRoundRect(badgeRect, radius, radius, badgePaint)
        val baseline = cy - (badgeTextPaint.descent() + badgeTextPaint.ascent()) / 2f
        canvas.drawText(label, cx, baseline, badgeTextPaint)
    }
}

private object PickerCovers {
    private const val TAG = "InklingPickerCover"
    private const val VERSION = "v1"
    private const val DISK_LIMIT = 32L * 1024 * 1024
    private const val MAX_ENTRY_BYTES = 40L * 1024 * 1024
    private const val JPEG_QUALITY = 85
    private const val NOTE_TIMEOUT_MS = 20_000L
    private val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")

    private val memory = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    @Volatile private var trimmed = false

    private val pdfLock = Any()

    fun load(path: String, reqW: Int, reqH: Int): Bitmap? {
        if (reqW <= 0 || reqH <= 0) return null
        val file = File(path)
        if (!file.isFile) return null
        val key = cacheKey(file)
        memory.get(key)?.let { if (!it.isRecycled) return it }
        val dir = File(App.context.applicationContext.cacheDir, "me.laumss.notipal/picker-covers")
        val jpg = File(dir, "$key.jpg")
        val none = File(dir, "$key.none")
        if (none.exists()) return null
        if (jpg.length() > 0) {
            decodeFile(jpg, reqW, reqH)?.let {
                jpg.setLastModified(System.currentTimeMillis())
                memory.put(key, it)
                return it
            }
        }
        val started = SystemClock.elapsedRealtime()
        val ext = file.extension.lowercase(Locale.ROOT)
        var transient = false
        val bmp = try {
            when (ext) {
                "pdf" -> renderPdf(file, reqW, reqH)
                "epub" -> epubCover(file, reqW, reqH)
                "cbz", "zip" -> archiveCover(file, reqW, reqH)
                "note" -> {

                    transient = true
                    noteCover(file, key, reqW, reqH)?.also { transient = false }
                }
                else -> null
            }
        } catch (t: Throwable) {
            Log.w(TAG, "cover failed: $path", t)
            transient = transient || t is OutOfMemoryError
            null
        }
        dir.mkdirs()
        if (bmp == null) {

            if (!transient) runCatching { none.createNewFile() }

            if (ext == "note") {
                val latest = latestNoteFile(dir, file)
                if (latest.length() > 0) decodeFile(latest, reqW, reqH)?.let { return it }
            }
            Log.i(TAG, "no cover: ${file.name}")
            return null
        }
        runCatching {
            val tmp = File(dir, "$key.tmp")
            FileOutputStream(tmp).use { bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
            if (!tmp.renameTo(jpg)) tmp.delete()
            if (ext == "note") jpg.copyTo(latestNoteFile(dir, file), overwrite = true)
        }.onFailure { Log.w(TAG, "cover cache write ${file.name}: ${it.message}") }
        Log.i(TAG, "cover ${file.name} ${bmp.width}x${bmp.height} ${SystemClock.elapsedRealtime() - started}ms")
        memory.put(key, bmp)
        if (!trimmed) {
            trimmed = true
            trimDisk(dir)
        }
        return bmp
    }

    private fun latestNoteFile(dir: File, file: File): File {
        val digest = MessageDigest.getInstance("SHA-1").digest(file.absolutePath.toByteArray())
        return File(dir, "latest-" + digest.joinToString("") { "%02x".format(it) } + ".jpg")
    }

    private fun cacheKey(file: File): String {
        val id = "$VERSION|${file.absolutePath}|${file.lastModified()}|${file.length()}"
        val digest = MessageDigest.getInstance("SHA-1").digest(id.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun trimDisk(dir: File) {
        val files = dir.listFiles()?.filter { it.isFile } ?: return
        var total = files.sumOf { it.length() }
        if (total <= DISK_LIMIT) return
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= DISK_LIMIT * 3 / 4) break
            total -= f.length()
            f.delete()
        }
    }

    private fun noteCover(file: File, key: String, reqW: Int, reqH: Int): Bitmap? {
        val react = App.react ?: return null
        val png = File(Dirs.hostRender, "$key.host.png")
        png.parentFile?.mkdirs()
        val latch = CountDownLatch(1)
        var rendered: File? = null
        val params = Arguments.createMap().apply {
            putString("notePath", file.absolutePath); putInt("page", 0); putInt("times", 1)
            putString("pngPath", png.absolutePath); putInt("type", 1)
        }
        PageRender.callHost(react, "generateNotePng", listOf(params)) { value, error ->
            val ok = value == true && png.isFile
            rendered = if (ok) png else null
            if (!ok) Log.w(TAG, "note cover ${file.name}: ${error ?: "generateNotePng failed"}")
            latch.countDown()
        }
        if (!latch.await(NOTE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            Log.w(TAG, "note cover ${file.name}: host timeout")
            return null
        }
        val out = rendered ?: return null
        return try {
            if (out.length() <= 0) null
            else decodeFile(out, reqW, reqH)?.let { fitInto(it, reqW, reqH) }
        } finally {
            out.delete()
        }
    }

    private fun renderPdf(file: File, reqW: Int, reqH: Int): Bitmap? = synchronized(pdfLock) {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { renderer ->
                if (renderer.pageCount <= 0) return null
                renderer.openPage(0).use { page ->
                    val pw = page.width.coerceAtLeast(1)
                    val ph = page.height.coerceAtLeast(1)
                    val s = min(reqW.toFloat() / pw, reqH.toFloat() / ph)
                    val w = (pw * s).roundToInt().coerceAtLeast(1)
                    val h = (ph * s).roundToInt().coerceAtLeast(1)
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Style.paper)
                    val m = Matrix().apply { setScale(w.toFloat() / pw, h.toFloat() / ph) }
                    page.render(bmp, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bmp
                }
            }
        }
    }

    private fun epubCover(file: File, reqW: Int, reqH: Int): Bitmap? = ZipFile(file).use { zip ->
        val href = runCatching { epubCoverPath(zip) }.getOrNull()
        href?.let { zip.getEntry(it) }?.let { entry ->
            decodeEntry(zip, entry, reqW, reqH)?.let { return it }
        }
        val images = imageEntries(zip)
        val fallback = images.firstOrNull {
            it.name.substringAfterLast('/').contains("cover", ignoreCase = true)
        } ?: images.firstOrNull()
        fallback?.let { decodeEntry(zip, it, reqW, reqH) }
    }

    private fun epubCoverPath(zip: ZipFile): String? {
        val opfPath = zip.getEntry("META-INF/container.xml")?.let { entry ->
            zip.getInputStream(entry).use { input ->
                val p = Xml.newPullParser()
                p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
                p.setInput(input, null)
                var found: String? = null
                while (p.next() != XmlPullParser.END_DOCUMENT && found == null) {
                    if (p.eventType == XmlPullParser.START_TAG && localName(p.name) == "rootfile") {
                        found = p.getAttributeValue(null, "full-path")
                    }
                }
                found
            }
        } ?: return null
        val opfEntry = zip.getEntry(opfPath) ?: return null
        val base = opfPath.substringBeforeLast('/', "")

        class Item(val id: String?, val href: String, val mediaType: String?, val properties: String?)
        val items = ArrayList<Item>()
        var metaCoverId: String? = null
        var guideCover: String? = null
        zip.getInputStream(opfEntry).use { input ->
            val p = Xml.newPullParser()
            p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            p.setInput(input, null)
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                if (p.eventType != XmlPullParser.START_TAG) continue
                when (localName(p.name)) {
                    "item" -> p.getAttributeValue(null, "href")?.let { href ->
                        items += Item(
                            p.getAttributeValue(null, "id"), href,
                            p.getAttributeValue(null, "media-type"),
                            p.getAttributeValue(null, "properties"),
                        )
                    }
                    "meta" -> if (p.getAttributeValue(null, "name") == "cover") {
                        metaCoverId = p.getAttributeValue(null, "content")
                    }
                    "reference" -> if (p.getAttributeValue(null, "type")
                            ?.equals("cover", ignoreCase = true) == true) {
                        guideCover = p.getAttributeValue(null, "href")
                    }
                }
            }
        }
        fun isImage(item: Item) = item.mediaType?.startsWith("image/") == true ||
            extOf(item.href) in IMAGE_EXTS

        items.firstOrNull { it.properties?.split(' ')?.contains("cover-image") == true && isImage(it) }
            ?.let { return resolve(base, it.href) }
        metaCoverId?.let { id ->
            items.firstOrNull { it.id == id }?.let { item ->
                if (isImage(item)) return resolve(base, item.href)

                if (guideCover == null) guideCover = item.href
            }
        }
        guideCover?.let { pageHref ->
            val pagePath = resolve(base, pageHref.substringBefore('#'))
            if (extOf(pagePath) in IMAGE_EXTS) return pagePath
            return firstImageInPage(zip, pagePath)
        }
        return null
    }

    private val imgSrc = Regex("""<img\b[^>]*?\bsrc\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val svgHref = Regex("""<image\b[^>]*?\b(?:xlink:)?href\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)

    private fun firstImageInPage(zip: ZipFile, pagePath: String): String? {
        val entry = zip.getEntry(pagePath) ?: return null
        if (entry.size > 2L * 1024 * 1024) return null
        val html = zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
        val candidates = listOfNotNull(imgSrc.find(html), svgHref.find(html))
        val src = candidates.minByOrNull { it.range.first }?.groupValues?.get(1) ?: return null
        return resolve(pagePath.substringBeforeLast('/', ""), src)
    }

    private fun localName(name: String) = name.substringAfter(':')

    private fun extOf(path: String) = path.substringAfterLast('.', "").lowercase(Locale.ROOT)

    private fun resolve(base: String, href: String): String {
        val decoded = runCatching { URLDecoder.decode(href.replace("+", "%2B"), "UTF-8") }.getOrDefault(href)
        val parts = ArrayList<String>()
        if (!decoded.startsWith("/")) base.split('/').filter { it.isNotEmpty() }.forEach { parts += it }
        for (seg in decoded.split('/')) {
            when (seg) {
                "", "." -> {}
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                else -> parts += seg
            }
        }
        return parts.joinToString("/")
    }

    private fun archiveCover(file: File, reqW: Int, reqH: Int): Bitmap? = ZipFile(file).use { zip ->
        val first = imageEntries(zip).firstOrNull() ?: return null
        decodeEntry(zip, first, reqW, reqH)
    }

    private fun imageEntries(zip: ZipFile) = zip.entries().asSequence()
        .filter { !it.isDirectory && extOf(it.name) in IMAGE_EXTS }
        .filter { e ->
            !e.name.startsWith("__MACOSX/") && e.name.split('/').none { it.startsWith(".") }
        }
        .sortedWith(compareBy(naturalOrder) { it.name.lowercase(Locale.ROOT) })
        .toList()

    private val chunk = Regex("""\d+|\D+""")
    private val naturalOrder = Comparator<String> { a, b ->
        val ra = chunk.findAll(a).map { it.value }.toList()
        val rb = chunk.findAll(b).map { it.value }.toList()
        for (i in 0 until min(ra.size, rb.size)) {
            val x = ra[i]
            val y = rb[i]
            val c = if (x[0].isDigit() && y[0].isDigit()) {
                val nx = x.trimStart('0')
                val ny = y.trimStart('0')
                if (nx.length != ny.length) nx.length - ny.length else nx.compareTo(ny)
            } else x.compareTo(y)
            if (c != 0) return@Comparator c
        }
        ra.size - rb.size
    }

    private fun decodeEntry(zip: ZipFile, entry: ZipEntry, reqW: Int, reqH: Int): Bitmap? {
        if (entry.size > MAX_ENTRY_BYTES) return null
        val bytes = zip.getInputStream(entry).use { it.readBytes() }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, reqW, reqH)
        }
        val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
        return fitInto(raw, reqW, reqH)
    }

    private fun decodeFile(file: File, reqW: Int, reqH: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, reqW, reqH)
        }
        return BitmapFactory.decodeFile(file.path, opts)
    }

    private fun sampleSize(w: Int, h: Int, reqW: Int, reqH: Int): Int {
        var sample = 1
        while (w / (sample * 2) >= reqW && h / (sample * 2) >= reqH) sample *= 2
        return sample
    }

    private fun fitInto(src: Bitmap, reqW: Int, reqH: Int): Bitmap {
        val s = min(1f, min(reqW.toFloat() / src.width, reqH.toFloat() / src.height))
        val w = max(1, (src.width * s).roundToInt())
        val h = max(1, (src.height * s).roundToInt())
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            drawColor(Style.paper)
            drawBitmap(src, null, android.graphics.Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
        }
        if (out !== src) src.recycle()
        return out
    }
}
