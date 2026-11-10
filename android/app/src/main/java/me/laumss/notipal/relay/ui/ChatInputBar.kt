package me.laumss.notipal.relay.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import me.laumss.notipal.NativeLocale
import me.laumss.notipal.ui_common.snux.LineIcon
import me.laumss.notipal.ui_common.snux.Snux

class ChatInputBar(
    context: Context,
    private val onSend: (String) -> Unit,
    private val onStop: () -> Unit,
) : LinearLayout(context) {
    private val density = resources.displayMetrics.density
    private fun u(v: Number) = Snux.u(context, v)
    private val imm get() = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
    private val caretPaint = Paint()

    var onImeEdit: ((String) -> Unit)? = null

    var canSendEmpty: () -> Boolean = { false }

    val input: EditText = object : EditText(context) {
        override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
            val connection = super.onCreateInputConnection(outAttrs) ?: return null
            return object : InputConnectionWrapper(connection, false) {
                private fun edited(what: String, done: Boolean) = done.also { onImeEdit?.invoke(what) }
                override fun commitText(text: CharSequence?, newCursorPosition: Int) =
                    edited("commit", super.commitText(text, newCursorPosition))
                override fun setComposingText(text: CharSequence?, newCursorPosition: Int) =
                    edited("compose", super.setComposingText(text, newCursorPosition))
                override fun finishComposingText() = edited("finish", super.finishComposingText())
                override fun deleteSurroundingText(beforeLength: Int, afterLength: Int) =
                    edited("delete", super.deleteSurroundingText(beforeLength, afterLength))
                override fun sendKeyEvent(event: KeyEvent?): Boolean {
                    val done = super.sendKeyEvent(event)
                    return if (event?.action == KeyEvent.ACTION_UP) edited("key", done) else done
                }
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val layout = layout ?: return
            val offset = selectionStart
            if (!isFocused || offset < 0 || offset != selectionEnd) return
            val line = layout.getLineForOffset(offset)
            val metrics = paint.fontMetrics

            val box = height - extendedPaddingTop - extendedPaddingBottom
            val centred = gravity and Gravity.VERTICAL_GRAVITY_MASK == Gravity.CENTER_VERTICAL
            val textTop = extendedPaddingTop + if (centred) ((box - layout.height) / 2).coerceAtLeast(0) else 0
            val baseline = textTop + layout.getLineBaseline(line).toFloat()
            val x = compoundPaddingLeft + layout.getPrimaryHorizontal(offset)
            caretPaint.color = currentTextColor
            canvas.drawRect(x, baseline + metrics.ascent, x + maxOf(2f, 1.5f * density), baseline + metrics.descent, caretPaint)
        }
    }.apply {
        isCursorVisible = false
        hint = NativeLocale.t("relay_chat_hint")
        setTextColor(Color.BLACK)
        setHintTextColor(0xFF888888.toInt())
        highlightColor = 0xFFD0D0D0.toInt()
        setTextSize(TypedValue.COMPLEX_UNIT_PX, u(TEXT_SIZE).toFloat())
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
        minLines = 1
        maxLines = 4
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        background = GradientDrawable().apply {
            setColor(Color.WHITE)
            setStroke(u(OUTLINE).coerceAtLeast(1), Color.BLACK)
        }
        setPadding(u(TEXT_START), u(12), u(12), u(12))
    }

    private val action = TextView(context).apply {
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, u(TEXT_SIZE).toFloat())
        gravity = Gravity.CENTER
        isSingleLine = true
    }

    private val sendIcon = LineIcon(context, LineIcon.Glyph.SEND, SEND_ICON)

    private val actionFill = GradientDrawable()

    private val actionCell = FrameLayout(context).apply {
        background = actionFill
        addView(action, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        addView(sendIcon, FrameLayout.LayoutParams(u(SEND_ICON), u(SEND_ICON), Gravity.CENTER))

        isFocusable = true
        isFocusableInTouchMode = false
        setOnClickListener {
            val text = input.text.toString().trim()
            if (generating) onStop() else if (!sending && (text.isNotEmpty() || canSendEmpty())) onSend(text)
        }
    }

    private var drawn: Int? = null

    var generating = false
        set(value) { field = value; refresh() }

    var sending = false
        set(value) { field = value; refresh() }

    init {
        orientation = HORIZONTAL

        gravity = Gravity.BOTTOM
        setBackgroundColor(Color.WHITE)
        addView(input, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(actionCell)
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = refresh()
        })
        measures(small = false)
        refresh()
    }

    fun refreshSend() = refresh()

    private fun refresh() {
        val ready = input.text.isNotBlank() || canSendEmpty()
        val state = when {
            generating -> 0
            sending -> 1
            ready -> 2
            else -> 3
        }
        if (state == drawn) return
        drawn = state
        action.text = if (state == 0) NativeLocale.t("relay_chat_stop") else ""
        sendIcon.visibility = if (state == 0) GONE else VISIBLE
        sendIcon.color = if (state == 3) 0xFFA0A0A0.toInt() else Color.WHITE
        actionFill.setColor(if (state == 3) Color.WHITE else Color.BLACK)
        actionFill.setStroke(u(OUTLINE).coerceAtLeast(1), Color.BLACK)
        actionCell.alpha = if (state == 1) 0.4f else 1f
    }

    private fun measures(small: Boolean) {
        val height = u(if (small) 60 else 72)
        input.minHeight = height
        val outline = u(OUTLINE).coerceAtLeast(1)
        actionCell.layoutParams = LayoutParams(u(if (small) 76 else 96), height).apply { leftMargin = -outline }
        val side = u(if (small) 18 else 40)
        val edge = u(if (small) 10 else 16)
        setPadding(side, edge, side, edge)
    }

    fun clearText() = input.setText("")

    fun hideKeyboard(): Boolean {
        if (!input.isFocused) return false
        imm?.hideSoftInputFromWindow(input.windowToken, 0)
        input.clearFocus()
        return true
    }

    fun setSmall(small: Boolean) {

        val px = u(TEXT_SIZE).toFloat()
        input.setTextSize(TypedValue.COMPLEX_UNIT_PX, px)
        action.setTextSize(TypedValue.COMPLEX_UNIT_PX, px)
        measures(small)
    }

    companion object {

        private const val OUTLINE = 2f
        private const val SEND_ICON = 32
        private const val TEXT_START = 18.63f
        private const val TEXT_SIZE = 21.11f

        class Keyboard(val keys: Int, val strip: Int, val shown: Boolean, val how: String)

        private val visibleHeightMethod by lazy {
            runCatching { InputMethodManager::class.java.getMethod("getInputMethodWindowVisibleHeight") }.getOrNull()
        }

        fun imeVisibleHeight(context: Context): Int {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return -1
            return runCatching { visibleHeightMethod?.invoke(imm) as? Int }.getOrNull() ?: -1
        }

        fun keyboard(root: View, screenHeight: Int): Keyboard {
            val density = root.resources.displayMetrics.density
            val resizedBy = (screenHeight - root.height).coerceAtLeast(0)
            val ime = if (Build.VERSION.SDK_INT >= 30) {
                root.rootWindowInsets?.getInsets(WindowInsets.Type.ime())?.bottom ?: 0
            } else 0
            val visible = Rect().also(root::getWindowVisibleDisplayFrame)
            val location = IntArray(2).also(root::getLocationOnScreen)
            val rootBottom = location[1] + root.height
            val frame = (rootBottom - visible.bottom).coerceIn(0, root.height)
            val visibleHeight = imeVisibleHeight(root.context)
            val window = (visibleHeight - (screenHeight - rootBottom)).coerceIn(0, root.height)

            val keys = maxOf(ime, frame).takeIf { it > 24 * density } ?: window
            val strip = (window - keys).coerceAtLeast(0)
            val shown = resizedBy + keys + strip > 120 * density
            return Keyboard(keys, strip, shown, "ime=$ime frame=$frame visible=$visibleHeight resizedBy=$resizedBy")
        }
    }
}
