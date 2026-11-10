package me.laumss.notipal.panels.pagepicker

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import me.laumss.notipal.NativeLocale

class PagePickerPager(ctx: Context, private val onPage: (Int) -> Unit) : LinearLayout(ctx) {
    private var current = 0
    private var count = 1
    private val icons = mutableMapOf<String, Pair<View, ImageView>>()
    private val number = TextView(ctx)

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.TRANSPARENT)
        val nav = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER }
        addView(nav, LayoutParams(-1, PagePickerScale.px(ctx, 72)))
        fun pageButton(key: String, label: String, action: () -> Unit): View = LinearLayout(ctx).apply {
            gravity = Gravity.CENTER
            val icon = ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                contentDescription = label
            }
            addView(icon, LayoutParams(PagePickerScale.px(ctx, 32), PagePickerScale.px(ctx, 32)))
            icons[key] = this to icon
            setOnClickListener { if (isEnabled) action() }
        }
        val itemWidth = PagePickerScale.px(ctx, 88)
        nav.addView(pageButton("first", NativeLocale.t("picker_first_page")) { onPage(0) }, LayoutParams(itemWidth, -1))
        nav.addView(pageButton("pre", NativeLocale.t("picker_previous_page")) { onPage(current - 1) }, LayoutParams(itemWidth, -1))
        nav.addView(View(ctx), LayoutParams(PagePickerScale.px(ctx, 68), -1))
        number.apply {
            textSize = PagePickerScale.sp(ctx, 16f)
            setTextColor(Color.rgb(45, 45, 45))
            setLineSpacing(0f, 1.12f)
            gravity = Gravity.CENTER
            setSingleLine(true)
        }
        nav.addView(number, LayoutParams(PagePickerScale.px(ctx, 100), -1))
        nav.addView(View(ctx), LayoutParams(PagePickerScale.px(ctx, 68), -1))
        nav.addView(pageButton("next", NativeLocale.t("picker_next_page")) { onPage(current + 1) }, LayoutParams(itemWidth, -1))
        nav.addView(pageButton("last", NativeLocale.t("picker_last_page")) { onPage(count - 1) }, LayoutParams(itemWidth, -1))
        update(0, 1)
    }

    fun update(page: Int, pageCount: Int) {
        count = pageCount.coerceAtLeast(1)
        current = page.coerceIn(0, count - 1)
        number.text = "${current + 1} / $count"
        icons.forEach { (key, pair) ->
            val enabled = if (key == "first" || key == "pre") current > 0 else current + 1 < count
            pair.first.isEnabled = enabled
            pair.second.isEnabled = enabled
            pair.second.setImageBitmap(bitmaps.getValue(key + if (enabled) "" else "_disabled"))
        }
    }

    companion object {

        private val encoded = mapOf(
        "first" to "iVBORw0KGgoAAAANSUhEUgAAACAAAAAgCAYAAABzenr0AAAApklEQVRYhe3XMQ6DMBBE0dGksA+WJmdCKOJUHG8pQkEBUnZ3wAFSbOv/JCNsw8weLQd/wK6LAyPJtxtAcgBgAIzkEI0v1ugPBSzj84yHATxxOcAblwIicRkgGpcAMvE0IBtPARTxMEAVDwGUcTeglPJSxt2AWuuzKaD5FvzER6hGnPdHpEKc/zDKIq5zIYkirncpXUOQ7FwA1eDzMNmM7w74Cnl7wAQfkyUX3JNknAAAAABJRU5ErkJggg==",
        "first_disabled" to "iVBORw0KGgoAAAANSUhEUgAAACAAAAAgCAQAAADZc7J/AAABWklEQVRIx9VVu07DQBCcu7N5WDxiHmUQCISQEBWioEQ0oeUTkCj4BQpL7hB/QElBh1CEDClCEGnyFUh0dJFoIMgEDYUdxTH2OXYauOlm7/Z2Z/f2BBVGWhL4Xw6kZ7oxiqoH6YKgdPtMHHNlEJROlMsVQfu1tAJwNyUCPcQdfCoqeynGD1tG8YExfHFSI6JyRFd0lfP7sF0GaMHHU6EqyOu3l8VVgBYPEszZ2eMKBEvLKVb94aCouEA11b2uD4QHopZxhS57+wRARZ1qJUoNrRH2XiNDoWR6vAKCuMmWOKUPhB9QhV9jpwmPBg+nN0Q1w4MuvKlNEDSPC2gQGh9A43xoEZPmgXkUOkptJEOfoH8JyBr2gbX553ZuDfr9uL5QSIMetmeDrbjPrUF0I4j3nG9hYNXR4Qxg7eTWIIqJPRD1wlPZ3vp8BGRzYFbm+xvlrWh9n43g4C9+rj9Qpd+0mIMvEAAAAABJRU5ErkJggg==",
        "pre" to "iVBORw0KGgoAAAANSUhEUgAAACAAAAAgCAYAAABzenr0AAAAmElEQVRYhe3WPQqAMAyG4Y84tAdz8UwiHqvHi4uCY9okDUGHrH0fKP0BMy+Rgx9gviDQiOgMAQBoABgAE9ExFfCO39OmAUbjJgBNXA3QxlUAi/gwwCo+BLCMdwOs410Aj7gY4BUXAUopm1dcBKi1rqGA8C3wRuQ5hl6IfFexNSLvc2yFyP8l0yI8v+X7dMCDkMZdAN3gzwMu7Vz792SwCAYAAAAASUVORK5CYII=",
        "pre_disabled" to "iVBORw0KGgoAAAANSUhEUgAAACAAAAAgCAQAAADZc7J/AAABKUlEQVRIx9WVvUoDQRRGz+y4/oQQXVG7FURIE6zEwtIuzyH4GArpxDfwJTRoVDBGtPEdBGsr066yUT6LTefuOMw2ulNscYfDmZk73xhZan0R/BdANIh7pQVZn7GcIhQd/ax4GoxflzZAu0EG5opcVjZZL63+fowmY5aJFoI2MUlBDXIeKqc45c/RStu9QKeBMnh7TlKnZCW5JyvLKX23QQXADBA3Ph2Cs3EOgwCMpohRkMFcFyHO/Fq85BTMBAD/lCiRupSVbXaKf+AmNjsIxQfBAO7QzEkNA9l4fwrqBwJkZc0tQptrwXeBHNqrL+Ogu1CM7cVCk+tKS49A+QQytcJTeci7WtDYqZHKsvN7iGFwKidbH/cQPZYs0PdtjC7M09dxDcDffVy/AUhW9dbUq4sMAAAAAElFTkSuQmCC",
        "next" to "iVBORw0KGgoAAAANSUhEUgAAACAAAAAgCAYAAABzenr0AAAAoklEQVRYhe3Xuw2AMAwE0JOwlExFx2aIlRAsZ5oUocvnwhWkcIfEE4nPBu6+KAsTUPqgmR0ATgkgvdxTURFFAAB3BqAiio9gFKLqEo5AVHcBG9HUhkxEcw6wEF1BxEB0J2EvghLFPQjaLGhFUIcRgCtHhBC2rwGvrxBjXP9xBNJLKG1DaRBJo1g6jKTjWLqQSFcy+VJqZrt0Lc8Qmh+TkTUBDwIw/GiRWw8uAAAAAElFTkSuQmCC",
        "next_disabled" to "iVBORw0KGgoAAAANSUhEUgAAACAAAAAgCAQAAADZc7J/AAABM0lEQVRIx9WVvUpDQRCFv71zL2ogmPhXmYAImkYQJLWVDyL4HIqInQ/gU/hHUDAGtPIBbCzsrYKISCRRjkWQgN4bN3sb3Sl35nBmz5lZJyPXieAvAiR7ruGNIPse8T5CU/M/b9IihcF7HUoL7cdgBrJyVSaj6xq/M3BZMroOCV0VwlW46ZeXKwEtDGJmGbmjYRnxMPBS9fke9JqDAWccymTRbmaGj9ZcoCxFPACibYSmKnkYtPrltAIBZDJOEBrb8LByhrEieuB6I6rwpUWxJpNxHNRCsoXQdC3HI8YHiKsgAE5pymTJZpCRFucQcpcjLpTBeWgvzQLdoFngHMlka5OhC+WFCVActFAKdVCRDs2gnUgLja/7WjyFQXwLb9flFT+Lp/TY20n4WH2685yR//+5fgIf5PZPZD/pfgAAAABJRU5ErkJggg==",
        "last" to "iVBORw0KGgoAAAANSUhEUgAAACAAAAAgCAYAAABzenr0AAAAqUlEQVRYhe3XQQrDIBAF0GH6Qa/VTe+VUnqlHM9uEugiBJ0/8gvpYlaC/4E6qrXWbsqyP+BsEMDbzFYJYAtvW4UQ7r7sc7j7MgQws/ULEEJQgAwEDWARKQAGkQaIIlIBEUQ6YBQxBTCCmAboRUwF9CCmA44QpZSHFFBrvV9jCaSbUHoMpY1I2oqll5H0OpY+SJhwGsCG0wAATyY8ZQkAvKLhvfXbX7NLAD5GLSVJrFNSwAAAAABJRU5ErkJggg==",
        "last_disabled" to "iVBORw0KGgoAAAANSUhEUgAAACAAAAAgCAQAAADZc7J/AAABXUlEQVRIx9VVsUrDUBQ9LzdBDWhNq5sVClVcBEE6uyh0EvwEwcVvECpF3PwAZx0drFLsUAtKBz/AD3B0Ep2spNHjELUJbWze66LvDiHv8g73nXNyoigYaVnA3wJwDlRdE4HSK/sQBLNz0b14WVUQtKqRnShYUAKmC0+PxhNQvHkKBb6qJ08RL9Uvo+rAgU/XXIXb8LiX729JRQUqkMqvACzTBWaLLw/WmTYH35UpgCBOh3OAxMYljkPhDAG+2g0wqsgQH/RRtocy4O0acPAzQSubD5+GV6BQUAPBsY0Ecw/XSVnoAqqb2gexw+dTCx9bnECt0zbgwNkBwdzSCDLaRyCuzYx0gSaF4mxr5EFvFXPYxLrVAPwTo2+BsjgT96DGFXAFUiirmTSBMgjgFQwhtDMRcEsAJ9FB0ygT0QLH19Km4YAJ7Dvg7cZb1gllO/rS3XfwvvJ8rwOg/v/P9RMIPt+x6jNf1AAAAABJRU5ErkJggg==",
        )
        private val bitmaps by lazy {
            encoded.mapValues { (_, value) ->
                val bytes = Base64.decode(value, Base64.DEFAULT)
                requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
            }
        }
    }
}
