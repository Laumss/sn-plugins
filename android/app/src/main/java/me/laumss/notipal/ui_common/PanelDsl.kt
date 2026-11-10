package me.laumss.notipal.ui_common

import android.graphics.Color
import android.graphics.Typeface
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.WritableMap

interface PanelHost {
    val ctx: ReactApplicationContext
    fun dp(v: Int): Int
    fun dp(v: Float): Int
    fun sp(v: Float): Float
    fun close()
    fun onDispose(block: () -> Unit)
    val panelW: Int
    val screenW: Int
    val screenH: Int
    fun emitEvent(name: String, data: WritableMap)
}

fun interface PanelComponent {
    fun build(host: PanelHost): View
}

class ButtonHandle internal constructor() {
    internal var view: TextView? = null
    var enabled: Boolean = true
        set(value) {
            field = value
            view?.let { it.isEnabled = value; it.alpha = if (value) 1f else 0.4f }
        }
}

class ListHandle internal constructor() {
    internal var rebuild: (() -> Unit)? = null
    internal var scrollTopFn: (() -> Unit)? = null
    fun refresh() { rebuild?.invoke() }
    fun scrollTop() { scrollTopFn?.invoke() }
}

class TabBarHandle internal constructor() {
    internal var tabBar: PanelTabBar? = null
    fun setSelection(index: Int) { tabBar?.setSelection(index) }
    fun getSelection(): Int = tabBar?.getSelection() ?: 0
}

class BottomBarScope internal constructor() {
    internal val rightItems = mutableListOf<PanelComponent>()

    fun outlined(label: String, onClick: () -> Unit): ButtonHandle {
        val handle = ButtonHandle()
        rightItems += PanelComponent { h ->
            PanelWidgets.outlinedButton(h, label, onClick).also {
                handle.view = it
                it.isEnabled = handle.enabled
                it.alpha = if (handle.enabled) 1f else 0.4f
            }
        }
        return handle
    }

    fun filled(label: String, onClick: () -> Unit): ButtonHandle {
        val handle = ButtonHandle()
        rightItems += PanelComponent { h ->
            PanelWidgets.filledButton(h, label, onClick).also {
                handle.view = it
                it.isEnabled = handle.enabled
                it.alpha = if (handle.enabled) 1f else 0.4f
            }
        }
        return handle
    }
}

class PanelScope internal constructor() {
    internal val components = mutableListOf<PanelComponent>()

    fun header(title: String) {
        components += PanelComponent { h -> PanelHeader.create(h.ctx, title) }
    }

    fun tabBar(tabs: List<PanelTabBar.Tab>, initial: Int = 0, onSelect: (Int) -> Unit): TabBarHandle {
        val handle = TabBarHandle()
        components += PanelComponent { h ->
            val t = PanelTabBar(h.ctx, tabs, onSelect = onSelect)
            if (initial != 0) t.setSelection(initial)
            handle.tabBar = t
            t.createView()
        }
        return handle
    }

    fun <T> list(
        itemsProvider: () -> List<T>,
        emptyText: String,
        row: (host: PanelHost, item: T) -> View
    ): ListHandle {
        val handle = ListHandle()
        components += PanelComponent { h ->
            val scroll = PanelScrollHost(h.ctx, overlayScrollbar = true)
            handle.rebuild = {
                scroll.content.removeAllViews()
                val items = itemsProvider()
                if (items.isEmpty()) {
                    scroll.content.addView(PanelWidgets.emptyView(h, emptyText))
                } else {
                    items.forEach { scroll.content.addView(row(h, it)) }
                }
                scroll.refreshThumb()
            }
            handle.scrollTopFn = { scroll.scrollToTop() }
            handle.rebuild?.invoke()
            scroll.view
        }
        return handle
    }

    fun section(title: String) {
        components += PanelComponent { h ->
            LinearLayout(h.ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(PanelWidgets.divider(h, dark = false))
                addView(TextView(h.ctx).apply {
                    text = title
                    textSize = h.sp(14f)
                    setTextColor(Color.parseColor("#666666"))
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    setPadding(h.dp(28), h.dp(16), h.dp(28), h.dp(8))
                })
            }
        }
    }

    fun custom(component: PanelComponent) {
        components += component
    }

    fun custom(builder: (PanelHost) -> View) {
        components += PanelComponent { h -> builder(h) }
    }

    fun bottomBar(build: BottomBarScope.() -> Unit) {
        val scope = BottomBarScope().apply(build)
        components += PanelComponent { h ->
            PanelWidgets.bottomBar(h, rightButtons = scope.rightItems.map { it.build(h) })
        }
    }
}
