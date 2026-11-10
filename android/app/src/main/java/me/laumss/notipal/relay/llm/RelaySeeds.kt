package me.laumss.notipal.relay.llm

import java.util.concurrent.CopyOnWriteArraySet

class RelaySeeds {

    class Seed(val text: String, val cardStart: Int, val lead: String) {
        fun cut(full: String): String = full.substring(cardStart.coerceIn(0, text.length))
    }

    private companion object {
        const val SEEDS_PER_CONVERSATION = 8
    }

    private val lock = Any()
    private val seeds = HashMap<String, ArrayDeque<Seed>>()
    private val holds = HashMap<String, Int>()
    private val listeners = CopyOnWriteArraySet<(String) -> Unit>()

    fun addListener(listener: (String) -> Unit) { listeners += listener }
    fun removeListener(listener: (String) -> Unit) { listeners -= listener }

    fun hold(conversationId: String) {
        synchronized(lock) { holds[conversationId] = (holds[conversationId] ?: 0) + 1 }
    }

    fun release(conversationId: String, seed: Seed?) {
        synchronized(lock) {
            val left = (holds[conversationId] ?: 1) - 1
            if (left > 0) holds[conversationId] = left else holds.remove(conversationId)
            if (seed != null && seed.text.isNotEmpty()) {
                val list = seeds.getOrPut(conversationId) { ArrayDeque() }
                list.removeAll { it.text == seed.text }
                list.addLast(seed)
                while (list.size > SEEDS_PER_CONVERSATION) list.removeFirst()
            }
        }
        listeners.forEach { it(conversationId) }
    }

    fun isHeld(conversationId: String): Boolean = synchronized(lock) { conversationId in holds }

    fun seedOf(conversationId: String, text: String): Seed? = synchronized(lock) {
        seeds[conversationId]?.filter { text.startsWith(it.text) }?.maxByOrNull { it.text.length }
    }
}
