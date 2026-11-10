package me.laumss.notipal.ui_common.filepicker

import me.laumss.notipal.NativeLocale
import me.laumss.notipal.BuildConfig
import me.laumss.notipal.net.PluginIdentity
import me.laumss.notipal.ui_common.MultiSelectState
import me.laumss.notipal.ui_common.snux.InkScrollView
import me.laumss.notipal.ui_common.snux.Snux
import android.content.Context
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.os.SystemClock
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean

class FilePicker(
    private val ctx: Context,
    private val parent: FrameLayout,
) {
    companion object {
        const val ROOT = "/storage/emulated/0"

        val HOME_FOLDERS = listOf("Document", "Export", "MyStyle", "Note", "SCREENSHOT", "INBOX")

        fun pathForChip(key: String): String = when (key) {
            "Export" -> "$ROOT/EXPORT"
            else -> "$ROOT/$key"
        }

        private val thumbnailExecutor = java.util.concurrent.Executors.newFixedThreadPool(2) { r ->
            Thread(r, "InklingPickerThumb").apply { isDaemon = true }
        }

        private val noteCoverExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "InklingPickerNote").apply { isDaemon = true }
        }

        private const val LATER_ROWS_PER_BATCH = 4

        private class CachedListing(val items: List<Item>, val signature: Int)

        private val listingCache = object : LinkedHashMap<String, CachedListing>(8, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedListing>?) = size > 8
        }
        private val inflight = HashMap<String, MutableList<(List<Item>, Boolean, Boolean) -> Unit>>()

        private fun cacheKey(path: String, filter: ListingFilter) =
            "$path|${filter.allowedTypes.sorted()}|${filter.imageTypes.sorted()}|${filter.rootFolders}"

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
                    android.util.Log.w("InklingFilePicker", "scan failed $path: $error")
                    emptyList()
                }
                val sig = signatureOf(result)
                val changed = synchronized(listingCache) {
                    val old = listingCache[key]
                    if (!blocked.get()) listingCache[key] = CachedListing(result, sig)
                    old?.signature != sig
                }
                android.util.Log.i("InklingFilePicker", "scan $path items=${result.size} " +
                    "ms=${SystemClock.elapsedRealtime() - started} changed=$changed blocked=${blocked.get()}")
                val waiters = synchronized(inflight) { inflight.remove(key) }.orEmpty()
                waiters.forEach { it(result, changed, blocked.get()) }
            }, "InklingPickerDirectory").apply { isDaemon = true }.start()
        }

        private fun homeFolders(blocked: AtomicBoolean?): List<Item> =
            HOME_FOLDERS.mapNotNull { key ->
                val folder = File(pathForChip(key))
                if (!isDirectory(folder, blocked)) return@mapNotNull null
                Item(
                    name = key,
                    path = folder.absolutePath,
                    isDir = true,
                    isImage = false,
                    size = 0L,
                    childCount = 0,
                )
            }

        private fun collectHomeFiles(filter: ListingFilter, blocked: AtomicBoolean): List<Item> {
            val roots = filter.rootFolders.map { File(pathForChip(it)) }.filter { isDirectory(it, blocked) }
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
                            if (!visited.add(folder.absolutePath)) continue
                            visibleChildren(folder, blocked).forEach { child ->
                                if (isDirectory(child, blocked)) {
                                    pending.addLast(child)
                                } else if (matchesExtension(child.name, filter)) {
                                    files.add(child)
                                }
                            }
                        }
                    } catch (error: Throwable) {
                        android.util.Log.w("InklingFilePicker", "scan failed ${root.path}: $error")
                    }
                    parts[index] = files
                }, "InklingPickerScan").apply { isDaemon = true; start() }
            }
            workers.forEach { it.join() }
            val seen = HashSet<String>()

            return parts.asSequence().filterNotNull().flatten()
                .filter { seen.add(it.absolutePath) }
                .map { fileItem(it, filter) }
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
                            .filter { it.isFile && FilePickerCells.isPreviewImage(it.name) }
                            .take(4)
                            .map { it.absolutePath }
                            .toList()
                        Item(
                            f.name, f.absolutePath, isDir = true, isImage = false, 0L, kids.size,
                            previews, try { f.lastModified() } catch (_: SecurityException) { 0L },
                        )
                    } else {
                        if (!matchesExtension(f.name, filter)) return@mapNotNull null
                        fileItem(f, filter)
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

        private fun fileItem(file: File, filter: ListingFilter) = Item(
            name = file.name,
            path = file.absolutePath,
            isDir = false,
            isImage = extensionOf(file.name) in filter.imageTypes,
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
    }

    private class CellMetrics(val colW: Int, val coverW: Int, val coverH: Int, val gap: Int)

    private data class Item(
        val name: String,
        val path: String,
        val isDir: Boolean,
        val isImage: Boolean,
        val size: Long,
        val childCount: Int,
        val previewPaths: List<String> = emptyList(),
        val modifiedAt: Long = 0L,
    )

    private data class ListingFilter(
        val imageTypes: Set<String>,
        val allowedTypes: Set<String>,
        val rootFolders: List<String>,
    )

    private var overlay: View? = null
    private var breadcrumbHost: LinearLayout? = null
    private var chips: FilePickerWidgets.FormatChips? = null
    private var topHost: FrameLayout? = null
    private var gridContainer: LinearLayout? = null
    private var gridScroll: InkScrollView? = null
    private var bottomTray: FilePickerChrome.BottomTrayHandle? = null

    private var imageExtensions: Set<String> = emptySet()
    private var docExtensions: Set<String> = emptySet()
    private var filterExtensions: Set<String> = emptySet()
    private var filterChipKeys: List<String> = emptyList()
    private var allowImageMulti = false
    private var confirmDocuments = false
    private var allowDocumentMulti = false
    private val documentSelection = MultiSelectState()
    private var excludedPaths: Set<String> = emptySet()
    private var rootFolders: List<String> = HOME_FOLDERS
    private var onReadBlocked: (() -> Unit)? = null
    private var readBlockedGeneration = -1
    private var keepOpenAfterPick = false
    private var pickOnTap = false
    private var maxImages = 30
    private var emptyHint = ""
    private var actionLabel = ""
    private var onPicked: ((List<String>) -> Unit)? = null
    private var onCancel: (() -> Unit)? = null

    private var currentPath = ROOT
    private var items: List<Item> = emptyList()
    private val selectedImages = LinkedHashSet<String>()
    private var selectedDoc: String? = null
    private val fileCards = mutableMapOf<String, FilePickerCells.FileCardView>()
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

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    private fun dp(v: Int) = FilePickerScale.dp(ctx, v)

    val isShowing: Boolean get() = active && overlay?.visibility == View.VISIBLE

    fun show(
        title: String,
        imageExtensions: Set<String> = emptySet(),
        docExtensions: Set<String> = emptySet(),
        allowImageMulti: Boolean = false,
        confirmDocuments: Boolean = false,
        allowDocumentMulti: Boolean = false,
        excludedPaths: Set<String> = emptySet(),
        additionalRootFolders: List<String> = emptyList(),
        onReadBlocked: (() -> Unit)? = null,
        keepOpenAfterPick: Boolean = false,
        pickOnTap: Boolean = false,
        maxImages: Int = 30,
        startDir: String = ROOT,
        emptyHint: String = NativeLocale.t("picker_file_default"),
        actionLabel: String = NativeLocale.t("picker_select"),
        topContent: View? = null,
        onCancel: (() -> Unit)? = null,
        onPicked: (List<String>) -> Unit,
    ) {

        if (overlay != null) destroy()
        this.keepOpenAfterPick = keepOpenAfterPick
        this.pickOnTap = pickOnTap
        attach()
        active = true
        this.imageExtensions = imageExtensions.map { it.lowercase() }.toSet()
        this.docExtensions = docExtensions.map { it.lowercase() }.toSet()
        this.filterExtensions = (this.imageExtensions + this.docExtensions).toSet()
        filterChipKeys = orderedFormatKeys(filterExtensions)
        this.allowImageMulti = allowImageMulti
        this.confirmDocuments = confirmDocuments
        this.allowDocumentMulti = confirmDocuments && allowDocumentMulti
        this.excludedPaths = excludedPaths.map(::normalizePath).toSet()
        this.rootFolders = (HOME_FOLDERS + additionalRootFolders.filter {
            it.isNotBlank() && it != "." && it != ".." && '/' !in it && '\\' !in it
        }).distinct()
        this.onReadBlocked = onReadBlocked
        documentSelection.clear()
        this.maxImages = maxImages.coerceAtLeast(1)
        this.emptyHint = emptyHint
        this.actionLabel = actionLabel
        setTopContent(topContent)
        this.onPicked = onPicked
        this.onCancel = onCancel
        trace("show title=$title pickOnTap=$pickOnTap keepOpen=$keepOpenAfterPick")
        selectedImages.clear()
        selectedDoc = null
        selectedFilters.clear()
        chips?.setKeys(filterChipKeys)
        chips?.clearSelection()
        val initialPath = normalizePath(startDir).trimEnd('/')
        currentPath = initialPath.takeIf { it == ROOT || it.startsWith("$ROOT/") } ?: ROOT
        refreshSelectionChrome()
        navigateTo(currentPath)
    }

    fun handleBack() {
        if (currentPath != ROOT) navigateUp() else dismiss()
    }

    fun dismiss(cancelled: Boolean = true) {
        trace("dismiss cancelled=$cancelled")
        generation++
        active = false
        listingPending = false
        homeFilesPending = false
        pendingThumbs.clear()
        overlay?.visibility = View.INVISIBLE
        fileCards.clear()
        gridContainer?.removeAllViews()
        val cancel = onCancel
        onPicked = null
        onCancel = null
        if (cancelled) cancel?.invoke()
    }

    fun refresh() {
        if (active) navigateTo(currentPath)
    }

    private fun relayout() {
        if (active && !listingPending) rebuildGrid()
    }

    private fun normalizePath(path: String): String = runCatching { File(path).canonicalPath }
        .getOrElse { path.replace("/sdcard/", "$ROOT/") }

    fun destroy() {
        dismiss(cancelled = false)
        overlay?.let { parent.removeView(it) }
        overlay = null
        breadcrumbHost = null
        chips = null
        topHost = null
        gridContainer = null
        gridScroll = null
        bottomTray = null
        onReadBlocked = null
    }

    private fun attach() {
        if (overlay != null) return
        val page = FrameLayout(ctx).apply {
            setBackgroundColor(Color.WHITE)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT)
            visibility = View.INVISIBLE
            isClickable = true
            addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
                if (oldRight > oldLeft && oldBottom > oldTop &&
                    (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop)) {
                    handler.post { relayout() }
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
                FilePickerScale.px(ctx, 86f),
            )
        }
        body.addView(breadcrumbHost)
        chips = FilePickerWidgets.FormatChips(ctx, emptyList()) { formats ->
            selectedFilters.clear()
            selectedFilters.addAll(formats)

            val hasImageFilter = formats.any { key ->
                imageExtensions.any { ext -> matchesFilterKey(ext, key) }
            }
            if (!confirmDocuments && formats.isNotEmpty() && !hasImageFilter) {
                selectedImages.clear()
                selectedDoc = null
            }
            applyFilterChange()
            refreshSelectionChrome()
        }
        body.addView(chips!!.createView())

        topHost = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        body.addView(topHost)

        val scroll = InkScrollView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            pageItems = { gridRows }
            onScrolled = { scheduleThumbFlush() }
        }
        gridScroll = scroll
        gridContainer = scroll.content.apply {
            setPadding(dp(16), dp(10), maxOf(dp(16), Snux.px(ctx, InkScrollView.LANE)), dp(14))
        }
        body.addView(scroll)

        page.addView(body)
        bottomTray = FilePickerChrome.buildBottomBackTray(ctx, { handleBack() }, { confirm() }, { toggleDocumentMulti() })
        val trayMargin = dp(24)
        page.addView(bottomTray!!.view, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
        ).apply { bottomMargin = trayMargin })

        bottomTray!!.view.addOnLayoutChangeListener { v, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top == oldBottom - oldTop) return@addOnLayoutChangeListener
            val content = scroll.content
            val want = v.height + trayMargin * 2
            if (content.paddingBottom != want) scroll.post {
                content.setPadding(content.paddingLeft, content.paddingTop, content.paddingRight, want)
            }
        }

        bottomTray?.setAction(NativeLocale.t("picker_select"), visible = false)

        overlay = page
        parent.addView(page)
    }

    fun setTopContent(content: View?) {
        val host = topHost ?: return
        host.removeAllViews()
        content ?: return
        host.addView(content, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        ))
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

        val filter = ListingFilter(imageExtensions.toSet(), filterExtensions.toSet(), rootFolders.toList())
        val cached = cachedListing(cacheKey(path, filter))
        if (cached != null) {

            homeFilesPending = false
            showListing(path, filter, cached.items, resetScroll = true)
            trace("cache path=$path items=${cached.items.size}")
            scan(path, filter) { result, changed, blocked ->
                handler.post {
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
            handler.post {
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
        items = filteredItems(path, all)
        rebuildGrid(resetScroll)
    }

    private fun filteredItems(path: String, all: List<Item>): List<Item> {
        val keys = selectedFilters.toSet()
        val imageChosen = keys.any { key -> imageExtensions.any { matchesFilterKey(it, key) } }
        val hideImages = path == ROOT && !imageChosen
        return all.filter { item ->
            item.isDir || (item.path !in excludedPaths && (!hideImages || !item.isImage) &&
                (keys.isEmpty() || keys.any { matchesFilterKey(extensionOf(item.name), it) }))
        }
    }

    private fun applyFilterChange() {
        val cached = listing?.takeIf {
            it.path == currentPath && !listingPending && !homeFilesPending &&
                it.filter.imageTypes == imageExtensions && it.filter.allowedTypes == filterExtensions
        }
        if (cached == null) {
            navigateTo(currentPath)
            return
        }
        val gen = ++generation
        pendingThumbs.clear()
        android.view.Choreographer.getInstance().postFrameCallback {
            handler.post {
                if (gen != generation) return@post
                items = filteredItems(cached.path, cached.items)
                trace("filter path=$currentPath formats=$selectedFilters items=${items.size}")
                rebuildGrid(resetScroll = true)
            }
        }
    }

    private fun refreshBreadcrumb() {
        val host = breadcrumbHost ?: return
        host.removeAllViews()
        host.addView(
            FilePickerCells.breadcrumb(ctx, ROOT, currentPath) { navigateTo(it) },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
            FilePickerScale.px(ctx, 86f),
            ),
        )
    }

    private fun reportReadBlocked(expectedGeneration: Int) {
        if (generation != expectedGeneration || readBlockedGeneration == expectedGeneration) return
        readBlockedGeneration = expectedGeneration
        onReadBlocked?.invoke()
    }

    private fun pickerColumns(): Int {
        val landscape = if (parent.width > 0 && parent.height > 0) {
            parent.width > parent.height
        } else {
            ctx.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        }
        return if (landscape) 6 else FilePickerDevice.profile(ctx).pickerGridColumns
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
                    handler.post {
                        if (gen == generation && revision == gridRevision) rebuildGrid(resetScroll)
                    }
                }
            })
            return
        }

        pendingThumbs.clear()
        val columns = pickerColumns()
        val nextCards = mutableMapOf<String, FilePickerCells.FileCardView>()
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
            homeFilesPending -> nextGrid.addView(hintView(NativeLocale.t("picker_loading_files")))
            items.isEmpty() -> nextGrid.addView(hintView(emptyHint.ifBlank { NativeLocale.t("picker_empty_folder") }))
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
        if (parent.width > 0 && parent.height > 0) {
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
        handler.post(object : Runnable {
            override fun run() {
                if (gen != generation || revision != gridRevision) return
                val end = minOf(rows.size, next + LATER_ROWS_PER_BATCH)
                while (next < end) {
                    val row = buildGridRow(rows[next++], m, fileCards)
                    gridRows += row
                    tail.addView(row)
                }
                if (next < rows.size) {
                    handler.post(this)
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
        textSize = FilePickerScale.sp(ctx, 13.5f)
        setTextColor(Color.BLACK)
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
                    FilePickerCells.homeFolderCell(ctx, item.name) { navigateTo(item.path) },
                    LinearLayout.LayoutParams(0, FilePickerScale.px(ctx, 126f), 1f),
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
        val coverH = (coverW * FilePickerCells.COVER_ASPECT).toInt()
        return CellMetrics(colW, coverW, coverH, gap)
    }

    private fun firstScreenRows(m: CellMetrics): Int {
        val viewport = gridScroll?.viewportHeight?.takeIf { it > 0 } ?: parent.height
        return (viewport / (m.coverH + m.gap) + 2).coerceAtLeast(3)
    }

    private fun buildGridRow(rowItems: List<Item>, m: CellMetrics,
                             cards: MutableMap<String, FilePickerCells.FileCardView>): View {
        val line = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, m.gap)
        }
        rowItems.forEachIndexed { index, item ->
            val cell = if (item.isDir) {
                FilePickerCells.folderCell(
                    ctx, item.name, item.childCount, m.coverW, m.coverH, item.previewPaths,
                    ::loadThumbnail,
                ) { navigateTo(item.path) }
            } else {
                val (view, card) = FilePickerCells.fileCell(
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
        android.view.Choreographer.getInstance().postFrameCallback {

            handler.post {
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
        if (FilePickerCells.hasDocumentCover(path)) {
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
            handler.post {
                if (gen == generation) view.setImageBitmap(bmp) else bmp.recycle()
            }
        } catch (_: Throwable) {}
    }

    private fun decodeDocumentCover(path: String, view: ImageView, reqW: Int, reqH: Int, gen: Int) {

        PluginIdentity.claim()
        val bmp = try {
            PickerCovers.load(ctx, path, reqW, reqH)
        } catch (_: Throwable) {
            null
        } ?: return
        handler.post {
            if (gen != generation) return@post
            view.setBackgroundColor(Color.WHITE)
            view.setImageBitmap(bmp)
            (view.parent as? FilePickerCells.FileCardView)?.setShowingCover(true)
        }
    }

    private fun isSelectedPath(path: String): Boolean =
        path == selectedDoc || path in selectedImages || documentSelection.isSelected(path)

    private fun onCellTap(item: Item) {
        if (!active || listingPending) return
        if (item.isDir) {
            navigateTo(item.path)
            return
        }
        if (confirmDocuments && !item.isImage) {

            if (documentSelection.isActive) {
                updateCell(item.path, documentSelection.toggle(item.path))
                refreshSelectionChrome()
            } else {
                trace("tap_auto path=${item.path}")
                selectedDoc = item.path
                confirm()
            }
            return
        }
        if (pickOnTap && (!item.isImage || !allowImageMulti)) {
            trace("tap_auto path=${item.path}")
            selectedImages.clear()
            selectedDoc = item.path
            confirm()
            return
        }
        trace("tap_select path=${item.path} image=${item.isImage}")
        if (!item.isImage) {
            selectedImages.clear()
            selectedDoc = item.path
            confirm()
            return
        }
        if (allowImageMulti) {
            selectedDoc?.let { doc ->
                selectedDoc = null
                updateCell(doc, false)
            }
            if (item.path in selectedImages) {
                selectedImages.remove(item.path)
                updateCell(item.path, false)
            } else {
                if (selectedImages.size >= maxImages) return
                selectedImages.add(item.path)
                updateCell(item.path, true)
            }
        } else {
            if (selectedImages.isNotEmpty()) {
                val cleared = selectedImages.toList()
                selectedImages.clear()
                cleared.forEach { updateCell(it, false) }
            }
            val previous = selectedDoc
            selectedDoc = if (previous == item.path) null else item.path
            previous?.let { updateCell(it, false) }
            selectedDoc?.let { updateCell(it, true) }
        }
        refreshSelectionChrome()
    }

    private fun updateCell(path: String, selected: Boolean) {
        fileCards[path]?.setCardSelected(selected)
    }

    private fun toggleDocumentMulti() {
        if (!active || !allowDocumentMulti || listingPending) return
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
        val imageCount = selectedImages.size
        val hasImageSelection = imageCount > 0 || selectedDoc?.let { extensionOf(it) in imageExtensions } == true
        val multiActive = documentSelection.isActive
        val count = if (multiActive) documentSelection.count else imageCount

        val actionVisible = hasImageSelection || (confirmDocuments && multiActive)
        val actionEnabled = (hasImageSelection || (multiActive && count > 0)) && !listingPending
        bottomTray?.setMultiSelection(
            visible = allowDocumentMulti,
            enabled = !listingPending,
            active = multiActive,
        )
        bottomTray?.setAction(
            if (count > 0) "$actionLabel (${NativeLocale.itemCount(count)})" else actionLabel,
            visible = actionVisible,
            enabled = actionEnabled,
            count = count,
        )
    }

    private fun confirm() {
        if (!active || listingPending) return

        val result = when {
            documentSelection.isActive -> documentSelection.selectedPaths.ifEmpty { return }
            selectedDoc != null -> listOf(selectedDoc!!)
            selectedImages.isNotEmpty() -> selectedImages.toList()
            else -> return
        }
        trace("confirm paths=${result.joinToString()}")
        val done = onPicked
        if (keepOpenAfterPick) {
            selectedImages.clear()
            selectedDoc = null
            documentSelection.clear()
            rebuildGrid()
            refreshSelectionChrome()
            done?.invoke(result)
        } else {
            dismiss(cancelled = false)
            done?.invoke(result)
        }
    }

    private fun trace(event: String) {
        if (!BuildConfig.ENABLE_DEBUG) return
        traceSequence += 1
        android.util.Log.i(
            "InklingFilePicker",
            "picker-trace seq=$traceSequence wallMs=${System.currentTimeMillis()} " +
                "elapsedMs=${SystemClock.elapsedRealtime()} $event",
        )
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1L shl 20 -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0)
        bytes >= 1L shl 10 -> String.format(java.util.Locale.US, "%.0f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}
