package me.laumss.notipal.relay.web

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

internal class WebConversationStore(private val dir: File) {
    companion object {
        private const val TAG = "WebConversations"
        private const val MAX_CONVERSATIONS = 200
        private const val SAVE_DEBOUNCE_MS = 800L

        private const val HISTORY_MAX_NODES = 40
        private const val HISTORY_MAX_CHARS = 60_000
        private const val FILE_URL_PREFIX = "/web/files/"

        const val DEFAULT_TITLE = "新对话"

        private val ISO = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

        fun isoTime(ms: Long): String = synchronized(ISO) { ISO.format(Date(ms)) }

        fun partsText(parts: JSONArray): String = buildString {
            for (i in 0 until parts.length()) {
                val part = parts.optJSONObject(i) ?: continue
                if (part.optString("type") == "text") append(part.optString("text"))
            }
        }

        fun textParts(text: String): JSONArray =
            JSONArray().put(JSONObject().put("type", "text").put("text", text))
    }

    class Message(
        val id: String,

        val role: String,
        var parts: JSONArray,
        val createdAt: Long,
        var finishedAt: Long?,
    ) {
        val text: String get() = partsText(parts)

        fun toJson(): JSONObject = JSONObject()
            .put("id", id)
            .put("role", role)
            .put("parts", parts)
            .put("createdAt", isoTime(createdAt))
            .put("finishedAt", finishedAt?.let { isoTime(it) } ?: JSONObject.NULL)
    }

    class Node(val id: String, val messages: MutableList<Message>, var selectIndex: Int) {
        val current: Message get() = messages[selectIndex.coerceIn(0, messages.size - 1)]

        fun toJson(): JSONObject = JSONObject()
            .put("id", id)
            .put("messages", JSONArray().apply { messages.forEach { put(it.toJson()) } })
            .put("selectIndex", selectIndex)
    }

    class Conversation(
        val id: String,
        var title: String,
        var customSystemPrompt: String,
        val createAt: Long,
        var updateAt: Long,
        val nodes: MutableList<Node>,
        var generating: Boolean,
    ) {

        val hidden: Boolean get() = customSystemPrompt.isNotEmpty()

        fun toJson(): JSONObject = JSONObject()
            .put("id", id)
            .put("assistantId", "web")
            .put("title", title)
            .put("messages", JSONArray().apply { nodes.forEach { put(it.toJson()) } })
            .put("chatSuggestions", JSONArray())
            .put("isPinned", false)
            .apply { if (customSystemPrompt.isNotEmpty()) put("customSystemPrompt", customSystemPrompt) }
            .put("createAt", createAt)
            .put("updateAt", updateAt)
            .put("isGenerating", generating)

        fun toListJson(): JSONObject = JSONObject()
            .put("id", id)
            .put("title", title)
            .put("createAt", createAt)
            .put("updateAt", updateAt)
            .put("isGenerating", generating)
            .put("messageCount", nodes.size)
            .put("hidden", hidden)

        fun findNode(messageId: String): Int = nodes.indexOfFirst { node -> node.messages.any { it.id == messageId } }

        fun isUnused(): Boolean = nodes.isEmpty() && !generating && !hidden
    }

    private val lock = Any()
    private val conversations = HashMap<String, Conversation>()

    @Volatile private var activeId: String? = null

    private val saver = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "WebConv-save").apply { isDaemon = true } }
    private val pendingSaves = HashMap<String, ScheduledFuture<*>>()

    @Volatile private var closed = false

    class Change(
        val id: String,
        val nodeIndex: Int?,
        val nodeId: String?,
        val node: JSONObject?,
        val conversation: JSONObject?,
        val updateAt: Long,
        val generating: Boolean,
    )

    @Volatile var onChanged: ((Change) -> Unit)? = null

    @Volatile var onActiveChanged: ((String) -> Unit)? = null

    fun load() {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return
        var loaded = 0
        synchronized(lock) {
            for (file in files) {
                try {
                    val conversation = fromJson(JSONObject(file.readText(Charsets.UTF_8)))

                    conversation.generating = false
                    conversations[conversation.id] = conversation
                    loaded++
                } catch (e: Exception) {
                    Log.w(TAG, "skip ${file.name}: ${e.message}")
                }
            }

            conversations.values.filter { it.isUnused() && it.id != activeId }.forEach { removeLocked(it.id) }
        }
        Log.i(TAG, "loaded $loaded conversations from ${dir.path}")
    }

    fun get(id: String): Conversation? = synchronized(lock) { conversations[id] }

    fun exists(id: String): Boolean = synchronized(lock) { conversations.containsKey(id) }

    fun toJson(id: String): JSONObject? = synchronized(lock) { conversations[id]?.toJson() }

    fun listJson(): JSONArray = synchronized(lock) {
        JSONArray().apply {
            conversations.values.sortedByDescending { it.updateAt }.forEach { put(it.toListJson()) }
        }
    }

    fun active(): Pair<String?, Boolean> = synchronized(lock) {
        val current = activeId?.takeIf { conversations.containsKey(it) }
        if (current != null) return current to true
        conversations.values.filter { !it.hidden }.maxByOrNull { it.updateAt }?.id to false
    }

    fun activeIsUnused(): Boolean = synchronized(lock) { activeId?.let { conversations[it] }?.isUnused() == true }

    fun setActive(id: String): String? {
        var removed: String? = null
        synchronized(lock) {
            val next = conversations[id]?.takeIf { !it.hidden } ?: return null
            val previous = activeId
            if (previous == next.id) return null
            activeId = next.id
            previous?.let { conversations[it] }?.takeIf { it.isUnused() }?.let {
                removeLocked(it.id)
                removed = it.id
            }
        }
        removed?.let { Log.i(TAG, "removed unused $it") }
        onActiveChanged?.invoke(id)
        return removed
    }

    fun create(id: String?, title: String?, systemPrompt: String?): Pair<Conversation, Boolean> {
        val result = synchronized(lock) {
            id?.let { conversations[it] }?.let { return@synchronized it to false }
            val now = System.currentTimeMillis()
            val conversation = Conversation(
                id = id?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString(),
                title = title?.trim().orEmpty().ifEmpty { DEFAULT_TITLE },
                customSystemPrompt = systemPrompt?.trim().orEmpty(),
                createAt = now,
                updateAt = now,
                nodes = mutableListOf(),
                generating = false,
            )
            conversations[conversation.id] = conversation
            pruneLocked()
            scheduleSave(conversation)
            conversation to true
        }
        if (result.second) onChanged?.invoke(synchronized(lock) { changeOf(result.first, null) })
        return result
    }

    fun delete(id: String): Boolean {
        synchronized(lock) {
            if (!conversations.containsKey(id)) return false
            removeLocked(id)
        }
        Log.i(TAG, "deleted $id")
        return true
    }

    fun appendTurn(id: String, userParts: JSONArray?): String? {
        val (change, replyId) = synchronized(lock) {
            val conversation = conversations[id] ?: return null
            val now = System.currentTimeMillis()
            if (userParts != null && userParts.length() > 0) {
                conversation.nodes += Node(
                    UUID.randomUUID().toString(),
                    mutableListOf(Message(UUID.randomUUID().toString(), "USER", userParts, now, now)),
                    0,
                )

                if (conversation.nodes.size == 1 && !conversation.hidden) {
                    partsText(userParts).lineSequence().firstOrNull { it.isNotBlank() }?.trim()
                        ?.let { conversation.title = it.take(40) }
                }
            }
            val reply = Message(UUID.randomUUID().toString(), "ASSISTANT", JSONArray(), now, null)
            conversation.nodes += Node(UUID.randomUUID().toString(), mutableListOf(reply), 0)
            conversation.generating = true
            conversation.updateAt = now
            scheduleSave(conversation)
            changeOf(conversation, null) to reply.id
        }
        onChanged?.invoke(change)
        return replyId
    }

    fun updateReply(id: String, messageId: String, text: String, finished: Boolean, error: String? = null): Boolean {
        val change = synchronized(lock) {
            val conversation = conversations[id] ?: return false
            val index = conversation.findNode(messageId)
            if (index < 0) return false
            val message = conversation.nodes[index].messages.first { it.id == messageId }

            if (!finished && message.finishedAt != null) return false
            val now = System.currentTimeMillis()
            message.parts = if (error == null) textParts(text) else JSONArray().apply {
                if (text.isNotBlank()) put(JSONObject().put("type", "text").put("text", text))
                put(JSONObject().put("type", "error").put("text", error))
            }
            if (finished) {
                message.finishedAt = now
                conversation.generating = false
            }
            conversation.updateAt = now
            if (finished) scheduleSave(conversation)
            changeOf(conversation, index)
        }
        onChanged?.invoke(change)
        return true
    }

    fun stop(id: String): String? {
        val (change, messageId) = synchronized(lock) {
            val conversation = conversations[id] ?: return null
            if (!conversation.generating) return null
            val now = System.currentTimeMillis()
            conversation.generating = false
            conversation.updateAt = now
            val index = conversation.nodes.indexOfLast { it.current.role == "ASSISTANT" && it.current.finishedAt == null }
            val message = conversation.nodes.getOrNull(index)?.current
            message?.finishedAt = now
            scheduleSave(conversation)
            changeOf(conversation, index.takeIf { it >= 0 }) to message?.id
        }
        onChanged?.invoke(change)
        return messageId
    }

    fun openReply(id: String): String? = synchronized(lock) {
        val conversation = conversations[id] ?: return null
        if (!conversation.generating) return null
        conversation.nodes.lastOrNull()?.current?.takeIf { it.role == "ASSISTANT" && it.finishedAt == null }?.id
    }

    fun historyJson(id: String, replyId: String, fileExists: (String) -> Boolean): JSONArray = synchronized(lock) {
        val conversation = conversations[id] ?: return JSONArray()
        val kept = ArrayList<JSONObject>()
        var chars = 0
        val upTo = conversation.nodes.indexOfFirst { it.current.id == replyId }.let { if (it < 0) conversation.nodes.size else it }
        for (i in upTo - 1 downTo 0) {
            val message = conversation.nodes[i].current
            if (message.role == "ASSISTANT" && message.parts.length() == 0) continue
            val parts = JSONArray()
            for (j in 0 until message.parts.length()) {
                val part = message.parts.optJSONObject(j) ?: continue

                if (part.optString("type") == "error") continue
                if (part.optString("type") == "image") {
                    val name = part.optString("url").removePrefix(FILE_URL_PREFIX)
                    if (!part.optString("url").startsWith(FILE_URL_PREFIX) || !fileExists(name)) continue
                }
                parts.put(part)
            }
            if (parts.length() == 0) continue
            chars += partsText(parts).length
            if (kept.isNotEmpty() && (kept.size >= HISTORY_MAX_NODES || chars > HISTORY_MAX_CHARS)) break
            kept += JSONObject().put("role", message.role.lowercase()).put("parts", parts)
        }

        while (kept.isNotEmpty() && kept.last().optString("role") == "assistant") kept.removeAt(kept.lastIndex)

        val merged = ArrayList<JSONObject>()
        for (entry in kept.asReversed()) {
            val last = merged.lastOrNull()
            if (last == null || last.optString("role") != entry.optString("role")) {
                merged += entry
                continue
            }
            val parts = last.getJSONArray("parts")
            parts.put(JSONObject().put("type", "text").put("text", "\n\n"))
            val more = entry.getJSONArray("parts")
            for (j in 0 until more.length()) parts.put(more.get(j))
        }
        JSONArray().apply { merged.forEach { put(it) } }
    }

    fun referencedFiles(): Set<String> = synchronized(lock) {
        val names = HashSet<String>()
        for (conversation in conversations.values) for (node in conversation.nodes) for (message in node.messages) {
            for (j in 0 until message.parts.length()) {
                val part = message.parts.optJSONObject(j) ?: continue
                val url = part.optString("url")
                if (part.optString("type") == "image" && url.startsWith(FILE_URL_PREFIX)) names += url.removePrefix(FILE_URL_PREFIX)
            }
        }
        names
    }

    fun setTitle(id: String, title: String): Boolean {
        val change = synchronized(lock) {
            val conversation = conversations[id] ?: return false
            conversation.title = title
            conversation.updateAt = System.currentTimeMillis()
            scheduleSave(conversation)
            changeOf(conversation, null)
        }
        onChanged?.invoke(change)
        return true
    }

    fun systemPrompt(id: String): String = synchronized(lock) { conversations[id]?.customSystemPrompt.orEmpty() }

    fun close() {
        onChanged = null
        onActiveChanged = null
        val due = synchronized(lock) {
            closed = true
            val ids = pendingSaves.keys.toList()
            pendingSaves.values.forEach { it.cancel(false) }
            pendingSaves.clear()
            ids.mapNotNull { id -> conversations[id]?.let { id to it.toJson().toString() } }
        }
        saver.shutdown()
        try {
            saver.awaitTermination(1, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
        }
        due.forEach { (id, json) -> writeFile(id, json) }
    }

    private fun changeOf(conversation: Conversation, nodeIndex: Int?): Change {
        val node = nodeIndex?.let { conversation.nodes.getOrNull(it) }
        return Change(
            id = conversation.id,
            nodeIndex = node?.let { nodeIndex },
            nodeId = node?.id,
            node = node?.toJson(),
            conversation = if (node == null) conversation.toJson() else null,
            updateAt = conversation.updateAt,
            generating = conversation.generating,
        )
    }

    private fun pruneLocked() {
        while (conversations.size > MAX_CONVERSATIONS) {
            val oldest = conversations.values.filter { !it.generating }.minByOrNull { it.updateAt } ?: break
            removeLocked(oldest.id)
        }
    }

    private fun removeLocked(id: String) {
        conversations.remove(id)
        pendingSaves.remove(id)?.cancel(false)
        if (activeId == id) activeId = null
        background { File(dir, "$id.json").delete() }
    }

    private fun background(task: () -> Unit) {
        if (closed) return
        try {
            saver.execute(Runnable { task() })
        } catch (_: RejectedExecutionException) {
        }
    }

    private fun scheduleSave(conversation: Conversation) {
        if (closed) return
        pendingSaves.remove(conversation.id)?.cancel(false)
        pendingSaves[conversation.id] = try {
            saver.schedule(Runnable {
                val json = synchronized(lock) {
                    pendingSaves.remove(conversation.id)
                    conversations[conversation.id]?.toJson()?.toString()
                } ?: return@Runnable
                writeFile(conversation.id, json)
            }, SAVE_DEBOUNCE_MS, TimeUnit.MILLISECONDS)
        } catch (_: RejectedExecutionException) {
            return
        }
    }

    private fun writeFile(id: String, json: String) {
        try {
            dir.mkdirs()
            val tmp = File(dir, "$id.json.tmp")
            tmp.writeText(json, Charsets.UTF_8)
            val out = File(dir, "$id.json")
            if (!tmp.renameTo(out)) {
                out.delete()
                tmp.renameTo(out)
            }
        } catch (e: Exception) {
            Log.e(TAG, "save $id failed: ${e.message}")
        }
    }

    private fun fromJson(json: JSONObject): Conversation {
        val nodes = mutableListOf<Node>()
        val nodeArr = json.optJSONArray("messages") ?: JSONArray()
        for (i in 0 until nodeArr.length()) {
            val nodeJson = nodeArr.getJSONObject(i)
            val messages = mutableListOf<Message>()
            val messageArr = nodeJson.optJSONArray("messages") ?: JSONArray()
            for (j in 0 until messageArr.length()) {
                val m = messageArr.getJSONObject(j)
                messages += Message(
                    id = m.optString("id", UUID.randomUUID().toString()),
                    role = m.optString("role", "ASSISTANT"),
                    parts = m.optJSONArray("parts") ?: JSONArray(),
                    createdAt = parseTime(m.optString("createdAt")),
                    finishedAt = m.optString("finishedAt").takeIf { it.isNotEmpty() && it != "null" }?.let(::parseTime),
                )
            }
            if (messages.isEmpty()) continue
            nodes += Node(nodeJson.optString("id", UUID.randomUUID().toString()), messages,
                nodeJson.optInt("selectIndex", 0).coerceIn(0, messages.size - 1))
        }
        return Conversation(
            id = json.getString("id"),
            title = json.optString("title"),
            customSystemPrompt = json.optString("customSystemPrompt"),
            createAt = json.optLong("createAt", System.currentTimeMillis()),
            updateAt = json.optLong("updateAt", System.currentTimeMillis()),
            nodes = nodes,
            generating = json.optBoolean("isGenerating", false),
        )
    }

    private fun parseTime(text: String): Long =
        runCatching { synchronized(ISO) { ISO.parse(text)?.time } }.getOrNull() ?: System.currentTimeMillis()
}
