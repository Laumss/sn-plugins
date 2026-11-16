package me.laumss.notipal.links

import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactMethod
import me.laumss.notipal.common.Dirs
import me.laumss.notipal.common.FeatureModule
import me.laumss.notipal.common.Log
import me.laumss.notipal.common.Main
import me.laumss.notipal.host.HostNotice
import me.laumss.notipal.host.HostPenSignals
import me.laumss.notipal.host.HostSession
import me.laumss.notipal.host.LassoHolds
import me.laumss.notipal.host.MonitorPhase
import me.laumss.notipal.image.ImageInsert
import me.laumss.notipal.toolbar.InsertTxns
import me.laumss.notipal.toolbar.SelectionHold
import me.laumss.notipal.toolbar.Toolbar
import me.laumss.notipal.ui_common.BrowseDirs
import me.laumss.notipal.ui_common.FilePicker
import me.laumss.notipal.ui_common.FilePickerConfig
import java.io.File

object DocLinks {
    private const val TAG = "DocLinks"

    const val TXN = "doc_link"

    val DOC_EXTS = setOf("epub", "pdf", "cbz", "doc", "docx", "note", "mobi", "fb2")

    private const val START_DELAY_MS = 300L

    private const val AWAIT_MAX_MS = 15_000L

    @Volatile private var module: LinksModule? = null
    private var installed = false

    private var picker: FilePicker? = null

    private var chainActive = false

    private var chainGen = 0L

    private val queue = ArrayDeque<String>()
    @Volatile private var queued = 0

    private var activeRequest: String? = null

    private var targetFile = ""
    private var targetPage = -1

    @Volatile private var awaitingSelection = false

    private val awaitTimeout = Runnable {
        if (!awaitingSelection) return@Runnable
        Log.w(TAG, "doc link chain: selection never shown; abandon remaining=${queue.size}")
        clearChain("selection timeout")
        HostPenSignals.endInsertImageGuard()
        scheduleRestore("doc link chain timeout")
    }

    fun install() {

        val image = LassoHolds.awaitingNextSelection
        LassoHolds.awaitingNextSelection = { image() || awaitingSelection }
        SelectionHold.addHolder("doc-links") { queued > 0 }
        if (installed) return
        installed = true
        HostSession.addNoticeListener { notice ->
            when (notice) {
                HostNotice.LassoShown -> onSelectionShown()
                is HostNotice.LassoCancelled -> onLassoCancelled(notice.reason)
                is HostNotice.Monitor -> if (notice.phase == MonitorPhase.STOPPING) clearChain("foreground monitor stopped")
                else -> Unit
            }
        }

        InsertTxns.addEndListener { name, ok, reason ->
            if (name != TXN || ok) return@addEndListener
            val request = activeRequest ?: return@addEndListener
            Log.w(TAG, "doc link txn ended ($reason) while $request was active")
            clearChain("doc link txn ended: $reason")
            HostPenSignals.endInsertImageGuard()
            scheduleRestore("doc link txn ended")
        }
    }

    internal fun attach(owner: LinksModule) {
        module = owner
    }

    internal fun detach(owner: LinksModule) {
        if (module === owner) module = null
    }

    fun openPicker(currentFilePath: String?) {
        Main.post {
            val current = currentFilePath?.takeIf { it.isNotBlank() }
            val next = FilePicker(
                FilePickerConfig(
                    roots = BrowseDirs.documents,
                    multi = true,
                    filter = { file -> current == null || !Dirs.same(file.path, current) },
                    titleKey = "doc_panel_title",
                    extensions = DOC_EXTS,
                    emptyKey = "doc_no_files",
                    actionKey = "doc_insert_link",
                )
            )
            picker = next
            next.open(
                onPicked = { files ->
                    if (picker === next) picker = null
                    insertLinks(files.map { it.path }, current)
                },
                onCancel = { if (picker === next) picker = null },
                onShown = { shown ->
                    if (!shown) {
                        if (picker === next) picker = null
                        Log.w(TAG, "document picker not shown")
                        Toolbar.restore("doc link picker not shown")
                    }
                },
            )
        }
    }

    fun onFileReceived() {
        Main.post { picker?.refresh() }
    }

    private fun insertLinks(paths: List<String>, current: String?) {
        val selected = paths.filter { current == null || !Dirs.same(it, current) }.distinct()
        if (selected.isEmpty()) {

            Toolbar.restore("doc link: nothing to insert")
            return
        }
        start(selected)
    }

    fun start(paths: List<String>) {
        Main.assertMain()
        if (paths.isEmpty()) return

        ImageInsert.clearChain("new document link insert")
        chainActive = true
        val gen = chainGen
        queue.addAll(paths.drop(1))
        queued = queue.size
        HostPenSignals.beginInsertImageGuard()
        Main.handler.postDelayed({
            if (gen == chainGen) {
                insertNext(paths.first())
            } else if (!chainActive && ImageInsert.idle) {

                HostPenSignals.endInsertImageGuard()
            }
        }, START_DELAY_MS)
    }

    private fun insertNext(path: String) {
        if (!chainActive) return
        val owner = module
        if (owner == null) {
            Log.w(TAG, "doc link chain: no links module")
            clearChain("no links module")
            HostPenSignals.endInsertImageGuard()
            scheduleRestore("doc link chain: no module")
            return
        }
        val requestId = System.nanoTime().toString()
        activeRequest = requestId
        awaitingSelection = true
        HostPenSignals.beginInsertImageGuard()
        Main.handler.removeCallbacks(awaitTimeout)
        Main.handler.postDelayed(awaitTimeout, AWAIT_MAX_MS)
        owner.insertRequested(requestId, path, File(path).nameWithoutExtension, targetFile, targetPage)
    }

    internal fun onJsFinished(requestId: String, selectionReady: Boolean, file: String, page: Int) {
        Main.post {
            if (activeRequest != requestId) return@post
            activeRequest = null
            if (selectionReady) {
                targetFile = file
                targetPage = page
                onSelectionShown()
                HostPenSignals.endInsertImageGuard()
                Log.i(TAG, "document link ready; awaiting selection cancellation remaining=${queue.size}")
            } else {
                clearChain("document link insertion or selection failed")
                HostPenSignals.endInsertImageGuard()
                scheduleRestore("document link insertion ended")
            }
        }
    }

    fun clearChain(reason: String) {
        chainGen++
        chainActive = false
        activeRequest = null
        targetFile = ""
        targetPage = -1
        if (queue.isEmpty() && !awaitingSelection) return
        Log.i(TAG, "doc link chain cleared: $reason remaining=${queue.size}")
        queue.clear()
        queued = 0
        awaitingSelection = false
        Main.handler.removeCallbacks(awaitTimeout)
    }

    private fun scheduleRestore(reason: String) {
        Main.handler.postDelayed({ Toolbar.restore(reason) }, HostSession.RESTORE_DELAY_NORMAL)
    }

    private fun onSelectionShown() {
        if (activeRequest != null) return
        if (!awaitingSelection) return
        awaitingSelection = false
        Main.handler.removeCallbacks(awaitTimeout)
    }

    private fun onLassoCancelled(reason: String) {
        if (!chainActive) return
        if (queue.isEmpty()) {
            clearChain("chain done")
            return
        }
        if (module == null) {
            clearChain("no links module")
            return
        }
        val next = queue.removeFirst()
        queued = queue.size
        Log.i(TAG, "doc link chain: $reason → next=$next remaining=${queue.size}")
        insertNext(next)
    }
}

class LinksModule(ctx: ReactApplicationContext) : FeatureModule(ctx, "NotipalLinks") {

    init {
        DocLinks.attach(this)
    }

    override fun invalidate() {
        DocLinks.detach(this)
        super.invalidate()
    }

    internal fun insertRequested(requestId: String, path: String, linkName: String, targetFile: String, targetPage: Int) {
        emit("links:insert", Arguments.createMap().apply {
            putString("requestId", requestId)
            putString("path", path)
            putString("linkName", linkName)
            putString("targetFile", targetFile)
            putInt("targetPage", targetPage)
        })
    }

    @ReactMethod
    fun openPicker(currentFilePath: String) = DocLinks.openPicker(currentFilePath)

    @ReactMethod
    fun insertFinished(requestId: String, selectionReady: Boolean, targetFile: String, targetPage: Double) =
        DocLinks.onJsFinished(requestId, selectionReady, targetFile, targetPage.toInt())
}
