package me.laumss.notipal.ui_common.snux

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.widget.EditText
import me.laumss.notipal.ui_common.filepicker.FilePickerScale as HcScale

internal class QueryInput(context: Context) : EditText(context) {
    private val density = resources.displayMetrics.density
    private val caretPaint = Paint()

    var onImeEdit: ((String) -> Unit)? = null

    init {
        isCursorVisible = false
        setTextColor(Color.BLACK)
        setHintTextColor(0xFF888888.toInt())
        highlightColor = 0xFFD0D0D0.toInt()
        textSize = HcScale.sp(context, 17f)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        isSingleLine = true

        background = GradientDrawable().apply {
            setColor(Color.WHITE)
            setStroke(HcScale.dp(context, 1), Color.BLACK)
        }
        minHeight = HcScale.dp(context, 44)
        setPadding(HcScale.dp(context, 12), 0, HcScale.dp(context, 12), 0)
    }

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

        val baseline = totalPaddingTop + layout.getLineBaseline(line).toFloat()
        val x = compoundPaddingLeft + layout.getPrimaryHorizontal(offset)
        caretPaint.color = currentTextColor
        canvas.drawRect(x, baseline + metrics.ascent, x + maxOf(2f, 1.5f * density), baseline + metrics.descent, caretPaint)
    }
}
