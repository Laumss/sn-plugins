package me.laumss.notipal.panels

import android.graphics.Color
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.Toast
import com.facebook.react.bridge.ReactApplicationContext
import me.laumss.notipal.FloatingToolbarModule
import me.laumss.notipal.NativeLocale
import me.laumss.notipal.ui_common.PanelBase
import me.laumss.notipal.ui_common.filepicker.FilePicker

class MosaicBoardFilePanel(
    ctx: ReactApplicationContext,
    private val toolbar: FloatingToolbarModule,
) : PanelBase(ctx, toolbar) {
    override val tag = "MosaicBoardFile"
    override val panelName = "mosaic-board-file"
    override val fullScreen = true
    override val windowFlags = 0

    private var picker: FilePicker? = null
    private var onResult: ((String?) -> Unit)? = null
    private var fileReadPermissionRequested = false
    private var lastFilePermissionToastAt = 0L

    fun show(extensions: Set<String>, title: String, done: (String?) -> Unit, onShown: (Boolean) -> Unit) {
        onResult = done
        showPanel { shown ->
            if (!shown) {
                onShown(false)
                return@showPanel
            }
            onShown(true)
            picker?.show(
                title = title,
                docExtensions = extensions,
                confirmDocuments = true,
                additionalRootFolders = listOf("LocalSend", "Books", "Download"),
                emptyHint = NativeLocale.t("doc_no_files"),
                onReadBlocked = ::requestFileReadPermissionOnce,
                onCancel = { finish(null) },
                onPicked = { paths -> finish(paths.firstOrNull()) },
            )
            rootView?.requestFocus()
        }
    }

    override fun buildFullScreenContent(): View {

        val host = object : FrameLayout(reactContext) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                    if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) picker?.handleBack()
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }.apply {
            setBackgroundColor(Color.TRANSPARENT)
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
        }
        picker = FilePicker(reactContext, host)
        return host
    }

    override fun onHide() {
        picker?.destroy()
        picker = null
        onResult?.let { onResult = null; it(null) }
    }

    private fun finish(path: String?) {
        val done = onResult ?: return
        onResult = null

        done(path)
        hide()
    }

    private fun requestFileReadPermissionOnce() {
        if (!isShowing || fileReadPermissionRequested) return

        fileReadPermissionRequested = true
        val now = System.currentTimeMillis()
        if (now - lastFilePermissionToastAt > 2500L) {
            lastFilePermissionToastAt = now
            Toast.makeText(reactContext, NativeLocale.t("file_read_permission_needed"), Toast.LENGTH_LONG).show()
        }
        toolbar.requestPluginFileReadPermission { granted ->
            handler.post { if (granted && isShowing) picker?.refresh() }
        }
    }
}
