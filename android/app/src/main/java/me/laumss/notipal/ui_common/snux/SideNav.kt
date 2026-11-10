package me.laumss.notipal.ui_common.snux

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

@SuppressLint("ViewConstructor")
internal class SideNav(
    context: Context,
    private val onPick: (String) -> Unit,
) : LinearLayout(context) {

    sealed class Entry {

        class Item(
            val id: String,
            val glyph: LineIcon.Glyph,
            val label: String,
            val count: String? = null,
            val open: Boolean? = null,
        ) : Entry()

        class Sub(val id: String, val label: String, val count: String? = null) : Entry()

        object Dots : Entry()
        object Rule : Entry()
    }

    private fun u(v: Number) = Snux.u(context, v)

    private class Cell(val view: View, val label: TextView)

    private val cells = HashMap<String, Cell>()
    private val top = LinearLayout(context).apply { orientation = VERTICAL }
    private val bottom = LinearLayout(context).apply { orientation = VERTICAL }
    private var active: String? = null

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.WHITE)
        addView(top, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        addView(bottom, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    fun setEntries(topEntries: List<Entry>, bottomEntries: List<Entry> = emptyList()) {
        cells.clear()
        fill(top, topEntries)
        fill(bottom, bottomEntries)
        refresh()
    }

    fun setActive(id: String?) {
        active = id
        refresh()
    }

    private fun fill(block: LinearLayout, entries: List<Entry>) {
        block.removeAllViews()
        for (entry in entries) when (entry) {
            is Entry.Item -> block.addView(item(entry), LayoutParams(LayoutParams.MATCH_PARENT, u(Snux.SIDE_ITEM)))
            is Entry.Sub -> block.addView(sub(entry), LayoutParams(LayoutParams.MATCH_PARENT, u(Snux.SIDE_SUB)))
            Entry.Dots -> block.addView(Snux.dashes(context, insetPx = 48))
            Entry.Rule -> block.addView(Snux.rule(context))
        }
    }

    private fun item(entry: Entry.Item): View {
        val text = Snux.oneLine(Snux.text(context, entry.label, Snux.SIDE_TEXT))
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(u(Snux.SIDE_PAD), 0, u(Snux.SIDE_PAD), 0)
            addView(LineIcon(context, entry.glyph, Snux.SIDE_ICON), LayoutParams(u(Snux.SIDE_ICON), u(Snux.SIDE_ICON)))
            addView(text, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = u(Snux.SIDE_ICON_GAP) })
            entry.count?.let { addView(Snux.text(context, it, COUNT_TEXT, Snux.SOFT).apply { setPadding(u(10), 0, 0, 0) }) }

            entry.open?.let { open ->
                addView(LineIcon(context, LineIcon.Glyph.CHEVRON, EXPAND).apply { rotation = if (open) -90f else 90f },
                    LayoutParams(u(EXPAND), u(EXPAND)).apply { leftMargin = u(10) })
            }
        }
        return frame(entry.id, row, text)
    }

    private fun sub(entry: Entry.Sub): View {
        val text = Snux.oneLine(Snux.text(context, entry.label, Snux.SIDE_TEXT))
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL

            setPadding(u(Snux.SIDE_PAD + Snux.SIDE_SUB_INDENT), 0, u(Snux.SIDE_PAD), 0)
            addView(text, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            entry.count?.let { addView(Snux.text(context, it, COUNT_TEXT, Snux.SOFT).apply { setPadding(u(10), 0, 0, 0) }) }
        }
        return frame(entry.id, row, text)
    }

    private fun frame(id: String, row: View, label: TextView): View = FrameLayout(context).apply {
        addView(row, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        setOnClickListener { onPick(id) }
        cells[id] = Cell(this, label)
    }

    private fun box() = GradientDrawable().apply {
        setColor(Color.WHITE)
        cornerRadius = u(2.48f).toFloat()
        setStroke(u(2.48f).coerceAtLeast(1), Color.BLACK)
    }

    private fun refresh() {
        cells.forEach { (id, cell) ->
            val on = id == active
            cell.view.background = if (on) box() else null
            cell.label.typeface = if (on) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
    }
}

private const val COUNT_TEXT = 30 * Snux.INKHUB_U
private const val EXPAND = 40 * Snux.INKHUB_U
