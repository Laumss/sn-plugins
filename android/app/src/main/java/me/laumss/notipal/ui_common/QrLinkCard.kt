package me.laumss.notipal.ui_common

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

object QrLinkCard {

    fun build(h: PanelHost, payload: String, title: String, desc: String, link: String, wrapLink: Boolean = false): View {
        val card = LinearLayout(h.ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(h.dp(16), h.dp(16), h.dp(20), h.dp(16))
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                setStroke(h.dp(1), Color.parseColor("#BBBBBB"))
                cornerRadius = h.dp(6).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = h.dp(24); rightMargin = h.dp(24); topMargin = h.dp(16) }
        }
        val qr = QrCode.encode(payload)
        if (qr != null) {
            val target = h.dp(150)
            val scale = maxOf(2, target / (qr.size + 4))
            val bmp = qr.toBitmap(scale, quiet = 2)
            card.addView(ImageView(h.ctx).apply {
                setImageBitmap(bmp)
                scaleType = ImageView.ScaleType.CENTER
                layoutParams = LinearLayout.LayoutParams(bmp.width, bmp.height).apply { rightMargin = h.dp(20) }
            })
        }
        val col = LinearLayout(h.ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        col.addView(TextView(h.ctx).apply {
            text = title
            textSize = h.sp(18f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(Color.BLACK)
        })
        col.addView(TextView(h.ctx).apply {
            text = desc
            textSize = h.sp(14f)
            setTextColor(Color.parseColor("#666666"))
            setPadding(0, h.dp(6), 0, 0)
            setLineSpacing(h.dp(2).toFloat(), 1f)
        })
        col.addView(TextView(h.ctx).apply {
            text = link
            textSize = h.sp(14f)
            setTextColor(Color.BLACK)
            setPadding(0, h.dp(10), 0, 0)
            if (wrapLink) {
                typeface = Typeface.MONOSPACE
            } else {
                isSingleLine = true
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            }
        })
        card.addView(col)
        return card
    }
}
