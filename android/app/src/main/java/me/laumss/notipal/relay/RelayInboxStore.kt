package me.laumss.notipal.relay

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import me.laumss.notipal.relay.model.RelayMessage
import org.json.JSONArray
import java.io.File

class RelayInboxStore(private val context: Context) {

    companion object {
        private const val TAG = "RelayInbox"
        private const val FILE_NAME = "relay_inbox.json"
        private const val MAX_MESSAGES = 200
        private const val SAVE_DEBOUNCE_MS = 800L
    }

    private val messages = mutableListOf<RelayMessage>()
    private val handler = Handler(Looper.getMainLooper())

    private val saveRunnable = Runnable {
        val copy = synchronized(messages) { messages.map { it.copy() } }
        saver.execute { write(copy) }
    }
    private val saver = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "RelayInboxSave").apply { isDaemon = true }
    }

    var onChanged: (() -> Unit)? = null

    private var loaded = false

    fun load() {
        val again = synchronized(messages) {
            if (loaded) messages.forEach { it.closed = true }
            loaded
        }
        if (again) return
        val f = File(context.filesDir, FILE_NAME)
        if (!f.exists()) {
            synchronized(messages) { loaded = true }
            return
        }
        try {
            val list = RelayMessage.listFromJson(JSONArray(f.readText()))
            synchronized(messages) {
                messages.clear()

                messages.addAll(list.onEach { it.closed = true })
                loaded = true
            }
            Log.i(TAG, "loaded ${list.size} messages")
        } catch (e: Exception) {
            Log.e(TAG, "load failed: ${e.message}")
        }
    }

    fun snapshot(): List<RelayMessage> =
        synchronized(messages) { messages.map { it.copy() } }

    fun get(id: String): RelayMessage? =
        synchronized(messages) { messages.firstOrNull { it.id == id }?.copy() }

    fun contains(id: String): Boolean =
        synchronized(messages) { messages.any { it.id == id } }

    fun upsert(
        id: String, conversationId: String, text: String, kind: String, closed: Boolean,
        lead: String = "", source: String = "llm", backend: String = "",
    ): Boolean {
        val now = System.currentTimeMillis()
        val created = synchronized(messages) {
            val existing = messages.firstOrNull { it.id == id }
            if (existing != null) {

                val kindChanged = kind.isNotEmpty() && existing.kind != kind
                if (existing.text == text && existing.closed == closed && existing.lead == lead && !kindChanged) return false

                if (!kindChanged && text.length < existing.text.length && existing.text.startsWith(text)) {

                    if (!closed || existing.closed) return false
                    existing.closed = true
                    existing.updatedAt = now
                    return@synchronized false
                }
                if (!kindChanged && existing.closed && !closed && text == existing.text) return false
                if (kindChanged) existing.kind = kind
                existing.text = text
                existing.closed = closed
                existing.lead = lead
                existing.updatedAt = now
                false
            } else {
                if (text.isBlank()) return false
                messages.add(0, RelayMessage(
                    id = id, text = text, source = source, createdAt = now, updatedAt = now,
                    closed = closed, kind = kind, conversationId = conversationId, lead = lead, seen = false,
                    backend = backend,
                ))

                while (messages.size > MAX_MESSAGES) {
                    val oldest = messages.indexOfLast { !it.starred }
                    if (oldest < 0) break
                    messages.removeAt(oldest)
                }
                true
            }
        }
        notifyChanged()
        return created
    }

    fun markInserted(id: String) {
        var changed = false
        synchronized(messages) {
            messages.firstOrNull { it.id == id }?.let {
                if (!it.inserted) { it.inserted = true; changed = true }
            }
        }
        if (changed) notifyChanged()
    }

    fun setStarred(ids: Collection<String>, starred: Boolean) {
        if (ids.isEmpty()) return
        val set = ids.toSet()
        var changed = false
        synchronized(messages) {
            messages.filter { it.id in set && it.starred != starred }.forEach { it.starred = starred; changed = true }
        }
        if (changed) notifyChanged()
    }

    fun markSeen(ids: Collection<String>) {
        if (ids.isEmpty()) return
        val set = ids.toSet()
        var changed = false
        synchronized(messages) {
            messages.filter { it.id in set && !it.seen }.forEach { it.seen = true; changed = true }
        }
        if (changed) notifyChanged()
    }

    fun deleteMany(ids: Collection<String>) {
        if (ids.isEmpty()) return
        val set = ids.toSet()
        val changed = synchronized(messages) { messages.removeAll { it.id in set } }
        if (changed) notifyChanged()
    }

    private fun notifyChanged() {
        handler.removeCallbacks(saveRunnable)
        handler.postDelayed(saveRunnable, SAVE_DEBOUNCE_MS)
        handler.post { onChanged?.invoke() }
    }

    private fun write(list: List<RelayMessage>) {
        try {
            File(context.filesDir, FILE_NAME).writeText(RelayMessage.listToJson(list).toString())
        } catch (e: Exception) {
            Log.e(TAG, "save failed: ${e.message}")
        }
    }

    fun flush() {
        handler.removeCallbacks(saveRunnable)

        saveRunnable.run()
    }
}
