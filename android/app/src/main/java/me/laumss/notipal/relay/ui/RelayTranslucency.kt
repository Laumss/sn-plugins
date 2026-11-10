package me.laumss.notipal.relay.ui

import android.content.Context
import kotlin.math.roundToInt

object RelayTranslucency {
    private const val PREFS = "air_relay_prefs"
    private const val KEY_LEVEL = "translucent_level_v2"
    const val MIN_LEVEL = 10
    const val MAX_LEVEL = 100
    const val STEP = 5
    const val DEFAULT_LEVEL = 10
    private const val KNEE_LEVEL = 60
    private const val MIN_SEE_THROUGH = 25f
    private const val KNEE_SEE_THROUGH = 40f
    private const val MAX_SEE_THROUGH = 100f

    var paperArgbValue: Int = argbOf(DEFAULT_LEVEL)
        private set

    var onChanged: (() -> Unit)? = null

    fun level(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_LEVEL, DEFAULT_LEVEL).coerceIn(MIN_LEVEL, MAX_LEVEL)

    fun setLevel(context: Context, level: Int) {
        val value = level.coerceIn(MIN_LEVEL, MAX_LEVEL)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_LEVEL, value).apply()
        paperArgbValue = argbOf(value)
        onChanged?.invoke()
    }

    fun load(context: Context) {
        paperArgbValue = argbOf(level(context))
    }

    private fun argbOf(level: Int): Int {
        val l = level.coerceIn(MIN_LEVEL, MAX_LEVEL)
        val seeThrough = if (l <= KNEE_LEVEL) {
            MIN_SEE_THROUGH + (KNEE_SEE_THROUGH - MIN_SEE_THROUGH) * (l - MIN_LEVEL) / (KNEE_LEVEL - MIN_LEVEL)
        } else {
            KNEE_SEE_THROUGH + (MAX_SEE_THROUGH - KNEE_SEE_THROUGH) * (l - KNEE_LEVEL) / (MAX_LEVEL - KNEE_LEVEL)
        }
        val alpha = (255 * (100f - seeThrough) / 100f).roundToInt().coerceIn(0, 255)
        return (alpha shl 24) or 0x00FFFFFF
    }
}
