package me.laumss.notipal

import android.util.Log
import com.facebook.react.bridge.ReactApplicationContext
import me.laumss.notipal.render.PageImageSource
import java.io.File

object NotipalDirs {
    private const val TAG = "NotipalDirs"

    @Volatile private var context: ReactApplicationContext? = null
    @Volatile private var resolved: File? = null

    fun attach(ctx: ReactApplicationContext) {
        context = ctx
    }

    private fun pluginRoot(): File? {
        resolved?.let { return it }
        val ctx = context ?: return null
        return try {
            val app = PageImageSource.getPluginApp(ctx)
            val path = app.javaClass.methods.first { it.name == "getPluginPath" && it.parameterCount == 0 }.invoke(app)
            (path as? String)?.takeIf { it.isNotEmpty() }?.let(::File)?.also { resolved = it }
        } catch (error: Throwable) {
            Log.w(TAG, "plugin dir unavailable: $error")
            null
        }
    }

    private val root: File
        get() = pluginRoot() ?: File((context ?: error("NotipalDirs used before attach")).filesDir, "notipal")

    const val CLIPBOARD = "/sdcard/EXPORT/Notipal/clipboard"

    val hostRender: String get() = File(root, "host-render").path

    val aiCapture: String get() = File(root, "ai-capture").path

    val docScreenshots: String get() = File(root, "doc-screenshots").path

    val imageTrash: String get() = File(root, "image-trash").path

    private const val LEGACY_HOST_RENDER = "/sdcard/EXPORT/inkling_capture"
    private const val LEGACY_AI_CAPTURE = "/sdcard/EXPORT/lasso_ai"
    private const val LEGACY_STAGING = "/sdcard/SCREENSHOT/.plugin_staging"
    private const val LEGACY_HISTORY = "/sdcard/SCREENSHOT/.plugin_history"
    private const val LEGACY_TRASH = "/sdcard/SCREENSHOT/.plugin_trash"

    fun retireLegacy() {
        Thread({
            try {

                var plugin = pluginRoot()
                var tries = 0
                while (plugin == null && tries++ < 20) {
                    Thread.sleep(500)
                    plugin = pluginRoot()
                }
                if (plugin == null) {
                    Log.w(TAG, "legacy dirs kept: plugin dir unavailable")
                    return@Thread
                }
                File(LEGACY_HOST_RENDER).deleteRecursively()

                File("/sdcard/INBOX").listFiles()?.filter { it.name.startsWith("clipboard_sync_") && it.name.endsWith(".zip") }
                    ?.forEach { it.delete() }
                File(LEGACY_AI_CAPTURE).deleteRecursively()
                val queue = File(docScreenshots, "queue")
                val moved = moveAll(File(LEGACY_STAGING, "queue"), queue) +
                    moveAll(File(LEGACY_HISTORY), File(docScreenshots, "history")) +
                    moveAll(File(LEGACY_TRASH), File(imageTrash))

                File(LEGACY_STAGING).listFiles()?.filter { it.name != "queue" }?.forEach { it.deleteRecursively() }
                for (dir in listOf(File(LEGACY_STAGING, "queue"), File(LEGACY_STAGING), File(LEGACY_HISTORY), File(LEGACY_TRASH))) {
                    if (dir.isDirectory && dir.list().isNullOrEmpty()) dir.delete()
                }
                if (moved > 0) Log.i(TAG, "legacy dirs retired moved=$moved")
            } catch (error: Throwable) {
                Log.w(TAG, "legacy dirs retire failed: $error")
            }
        }, "NotipalLegacyDirs").start()
    }

    private fun moveAll(from: File, to: File): Int {
        val files = from.listFiles()?.filter { it.isFile } ?: return 0
        if (files.isEmpty()) return 0
        to.mkdirs()
        var moved = 0
        for (file in files) {
            val dest = File(to, file.name)
            if (move(file, dest)) moved++
        }
        return moved
    }

    fun move(from: File, to: File): Boolean {
        if (from.renameTo(to)) return true
        return try {
            from.copyTo(to, overwrite = true)
            if (!from.delete()) {
                to.delete()
                false
            } else {
                true
            }
        } catch (error: Throwable) {
            Log.w(TAG, "move failed $from -> $to: $error")
            to.delete()
            false
        }
    }
}
