package me.laumss.notipal.panels

import me.laumss.notipal.NotipalDirs
import android.util.Log
import java.io.File

internal object ImageTrash {
    private const val TAG = "ImageTrash"
    val DIR get() = NotipalDirs.imageTrash
    private const val ORIGIN = ".origin"
    private const val KEEP_DAYS = 30

    class Item(val path: String, val name: String, val size: Long, val deletedAt: Long)

    fun moveIn(path: String): Boolean {
        val file = File(path)
        if (!file.exists()) return true
        val dir = File(DIR).apply { mkdirs() }
        val target = File(dir, "${System.currentTimeMillis()}_${file.name}")
        if (!NotipalDirs.move(file, target)) {
            Log.w(TAG, "move failed: $path")
            return false
        }
        try {
            File(target.path + ORIGIN).writeText(file.absolutePath)
        } catch (e: Exception) {
            Log.w(TAG, "origin not written for $path", e)
        }
        return true
    }

    fun list(): List<Item> =
        (File(DIR).listFiles() ?: emptyArray())
            .filter { it.isFile && !it.name.endsWith(ORIGIN) }
            .map { file ->
                val stamp = file.name.substringBefore('_').toLongOrNull()
                Item(file.absolutePath, if (stamp != null) file.name.substringAfter('_') else file.name,
                    file.length(), stamp ?: file.lastModified())
            }
            .sortedByDescending { it.deletedAt }

    fun restore(path: String, redirect: (String) -> String = { it }): Boolean {
        val file = File(path)
        if (!file.exists()) return true
        val originFile = File(path + ORIGIN)
        val origin = try { originFile.readText().trim() } catch (_: Exception) { "" }
        if (origin.isEmpty()) {
            Log.w(TAG, "no origin for $path")
            return false
        }
        val wanted = File(redirect(origin))
        wanted.parentFile?.mkdirs()
        val target = freeName(wanted)
        if (!NotipalDirs.move(file, target)) {
            Log.w(TAG, "restore failed: $path -> $target")
            return false
        }
        originFile.delete()
        return true
    }

    fun purge(path: String): Boolean {
        val file = File(path)
        val ok = !file.exists() || file.delete()
        if (ok) File(path + ORIGIN).delete()
        return ok
    }

    fun purgeExpired() {
        val cutoff = System.currentTimeMillis() - KEEP_DAYS * 24L * 60 * 60 * 1000
        list().filter { it.deletedAt < cutoff }.forEach {
            if (purge(it.path)) Log.i(TAG, "expired: ${it.name}") else Log.w(TAG, "expired but not deleted: ${it.path}")
        }
    }

    private fun freeName(file: File): File {
        if (!file.exists()) return file
        val dir = file.parentFile
        val base = file.nameWithoutExtension
        val ext = file.extension.let { if (it.isEmpty()) "" else ".$it" }
        var n = 1
        while (true) {
            val candidate = File(dir, "$base ($n)$ext")
            if (!candidate.exists()) return candidate
            n++
        }
    }
}
