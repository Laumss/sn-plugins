package me.laumss.notipal.relay.core

import android.content.Context
import android.content.SharedPreferences

object AppPrefs {
    private const val FILE = "air_relay_prefs"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun phoneIp(ctx: Context): String =
        prefs(ctx).getString("phone_ip", "") ?: ""

    fun setPhoneIp(ctx: Context, ip: String) =
        prefs(ctx).edit().putString("phone_ip", ip).apply()

    fun phoneFingerprint(ctx: Context): String =
        prefs(ctx).getString("phone_fingerprint", "") ?: ""

    fun setPhoneFingerprint(ctx: Context, fingerprint: String) =
        prefs(ctx).edit().putString("phone_fingerprint", fingerprint).apply()

    fun phonePort(ctx: Context): Int =
        prefs(ctx).getInt("phone_port", 8080)

    fun setPhonePort(ctx: Context, port: Int) =
        prefs(ctx).edit().putInt("phone_port", port).apply()

    fun clearPhonePairing(ctx: Context) =
        prefs(ctx).edit()
            .remove("phone_ip")
            .remove("phone_fingerprint")
            .remove("phone_port")
            .apply()

    fun relayBackend(ctx: Context): String =
        prefs(ctx).getString("relay_backend", "rikkahub") ?: "rikkahub"

    fun setRelayBackend(ctx: Context, value: String) =
        prefs(ctx).edit().putString("relay_backend", value).apply()

    @Synchronized
    fun webToken(ctx: Context): String {
        val existing = prefs(ctx).getString("web_token", "") ?: ""
        return existing.ifEmpty { newWebToken(ctx) }
    }

    @Synchronized
    fun newWebToken(ctx: Context): String {
        val bytes = ByteArray(5).also { java.security.SecureRandom().nextBytes(it) }
        val token = bytes.joinToString("") { "%02x".format(it) }
        prefs(ctx).edit().putString("web_token", token).apply()
        return token
    }

    fun autoOpenDetail(ctx: Context): Boolean =
        prefs(ctx).getBoolean("auto_open_detail", true)

    fun setAutoOpenDetail(ctx: Context, value: Boolean) =
        prefs(ctx).edit().putBoolean("auto_open_detail", value).apply()

    fun openMini(ctx: Context): Boolean =
        prefs(ctx).getBoolean("open_mini", true)

    fun setOpenMini(ctx: Context, value: Boolean) =
        prefs(ctx).edit().putBoolean("open_mini", value).apply()

    fun phoneFollows(ctx: Context): Boolean =
        prefs(ctx).getBoolean("phone_follows", true)

    fun setPhoneFollows(ctx: Context, value: Boolean) =
        prefs(ctx).edit().putBoolean("phone_follows", value).apply()

    @Synchronized
    fun conversationCreation(
        ctx: Context, peer: String, kind: String, ttlMs: Long = Long.MAX_VALUE,
    ): Pair<String, Long> {
        val key = "conversation_creation:$peer:$kind"
        val store = prefs(ctx)
        val now = System.currentTimeMillis()
        val id = store.getString(key, null)
        val createdAt = store.getLong("$key:at", 0)
        if (id != null && now - createdAt < ttlMs) return id to createdAt
        val nextId = java.util.UUID.randomUUID().toString()
        check(store.edit().putString(key, nextId).putLong("$key:at", now).commit()) {
            "Could not persist conversation creation ID"
        }
        return nextId to now
    }

    @Synchronized
    fun clearConversationCreation(ctx: Context, peer: String, kind: String) {
        val key = "conversation_creation:$peer:$kind"
        check(prefs(ctx).edit().remove(key).remove("$key:at").commit()) {
            "Could not reset conversation creation ID"
        }
    }
}
