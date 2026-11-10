package me.laumss.notipal.ui_common.snux

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout

@SuppressLint("ViewConstructor")
internal class SearchBox(
    context: Context,
    private val onClear: () -> Unit,
    private val onGo: () -> Unit,
    goLabel: String,
    clearLabel: String,
) : LinearLayout(context) {
    private companion object {
        const val BORDER = 1.24f
        const val FIELD_INSET = 2.48f
        const val TEXT_START = 18.63f
        const val TEXT_SIZE = 21.11f
    }

    private fun u(v: Number) = Snux.u(context, v)

    val input = QueryInput(context).apply {
        background = null
        minHeight = 0
        setTextSize(TypedValue.COMPLEX_UNIT_PX, u(TEXT_SIZE).toFloat())
        setPadding(u(TEXT_START), 0, 0, 0)
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
    }

    private val clear = Snux.iconButton(context, LineIcon.Glyph.CLEAR, Snux.ICON, 60, clearLabel) {
        onClear()
    }.apply { visibility = GONE }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val inset = u(FIELD_INSET)
        val field = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(inset, inset, 0, inset)
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                setStroke(u(BORDER).coerceAtLeast(1), Color.BLACK)
            }
            addView(input, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))

            addView(clear, LayoutParams(u(Snux.SEARCH_HEIGHT - 2 * FIELD_INSET), LayoutParams.MATCH_PARENT))
        }
        val go = Snux.iconButton(context, LineIcon.Glyph.SEARCH, Snux.ICON, 60, goLabel) { onGo() }.apply {
            setBackgroundColor(Color.BLACK)
            (getChildAt(0) as LineIcon).inverse = true
        }
        addView(field, LayoutParams(0, u(Snux.SEARCH_HEIGHT), 1f))
        addView(go, LayoutParams(u(Snux.SEARCH_BUTTON), u(Snux.SEARCH_HEIGHT)))
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                clear.visibility = if (s.isNullOrEmpty()) GONE else VISIBLE
            }
        })
    }
}
