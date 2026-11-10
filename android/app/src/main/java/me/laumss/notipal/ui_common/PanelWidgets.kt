package me.laumss.notipal.ui_common

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.facebook.react.bridge.ReactApplicationContext

object PanelHeader {

    fun create(ctx: ReactApplicationContext, title: String): LinearLayout {
        val scale = ScreenScale.factor(ctx)
        fun dp(v: Int) = ScreenScale.dp(ctx, v)

        val wrapper = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(22), dp(16), dp(22))
        }
        bar.addView(TextView(ctx).apply {
            text = title
            textSize = 23f * scale; setTextColor(Color.BLACK)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        wrapper.addView(bar)
        wrapper.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
            setBackgroundColor(Color.BLACK)
        })
        return wrapper
    }
}

object PanelWidgets {

    fun outlinedButton(host: PanelHost, label: String, onClick: () -> Unit): TextView =
        TextView(host.ctx).apply {
            text = label; textSize = host.sp(17f); setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
            minWidth = host.dp(106); minHeight = host.dp(44)
            setPadding(host.dp(16), 0, host.dp(16), 0)
            background = GradientDrawable().apply {
                setColor(Color.WHITE); setStroke(host.dp(1), Color.BLACK)
                cornerRadius = 0f
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = host.dp(32) }
            setOnClickListener { onClick() }
        }

    fun filledButton(host: PanelHost, label: String, onClick: () -> Unit): TextView =
        TextView(host.ctx).apply {
            text = label; textSize = host.sp(17f); setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            minWidth = host.dp(106); minHeight = host.dp(44)
            setPadding(host.dp(16), 0, host.dp(16), 0)
            background = GradientDrawable().apply {
                setColor(Color.BLACK); cornerRadius = 0f
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener { onClick() }
        }

    fun divider(host: PanelHost, dark: Boolean = true): View =
        View(host.ctx).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
            setBackgroundColor(if (dark) Color.BLACK else Color.parseColor("#D8D8D8"))
        }

    fun emptyView(host: PanelHost, text: String): LinearLayout =
        LinearLayout(host.ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(host.dp(30), host.dp(60), host.dp(30), 0)
            addView(TextView(host.ctx).apply {
                this.text = text; textSize = host.sp(14f)
                setTextColor(Color.parseColor("#999999")); gravity = Gravity.CENTER
            })
        }

    fun emptyView(host: PanelHost, title: String, hint: String): LinearLayout =
        LinearLayout(host.ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(host.dp(20), host.dp(60), host.dp(20), 0)
            addView(TextView(host.ctx).apply {
                text = title; textSize = host.sp(15f)
                setTextColor(Color.parseColor("#999999"))
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                gravity = Gravity.CENTER
            })
            addView(TextView(host.ctx).apply {
                text = hint; textSize = host.sp(12f)
                setTextColor(Color.parseColor("#BBBBBB"))
                gravity = Gravity.CENTER
                setPadding(host.dp(20), host.dp(8), host.dp(20), 0)
            })
        }

    fun bottomBar(
        host: PanelHost,
        leftButtons: List<View> = emptyList(),
        leftFlex: View? = null,
        rightButtons: List<View>
    ): LinearLayout {
        val wrapper = LinearLayout(host.ctx).apply { orientation = LinearLayout.VERTICAL }
        wrapper.addView(divider(host))
        val bar = LinearLayout(host.ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(host.dp(28), host.dp(28), host.dp(28), host.dp(28))
        }
        for (btn in leftButtons) bar.addView(btn)
        if (leftFlex != null) {
            bar.addView(leftFlex)
        } else {
            bar.addView(View(host.ctx).apply {
                layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
            })
        }
        for (btn in rightButtons) bar.addView(btn)
        wrapper.addView(bar)
        return wrapper
    }

    fun formatSize(size: Long): String = when {
        size < 1024 -> "$size B"
        size < 1024 * 1024 -> "${"%.1f".format(size / 1024.0)} KB"
        else -> "${"%.1f".format(size / (1024.0 * 1024.0))} MB"
    }
}

class MultiSelectState {
    var isActive = false
        private set

    private val paths = linkedSetOf<String>()

    val count: Int get() = paths.size
    val selectedPaths: List<String> get() = paths.toList()

    fun activate(seed: String? = null) {
        isActive = true
        seed?.let { paths.add(it) }
    }

    fun deactivate() {
        isActive = false
        paths.clear()
    }

    fun toggle(path: String): Boolean =
        if (path in paths) {
            paths.remove(path); false
        } else {
            paths.add(path); true
        }

    fun isSelected(path: String): Boolean = path in paths

    fun clear() {
        isActive = false
        paths.clear()
    }
}
