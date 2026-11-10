package me.laumss.notipal.relay.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.SubscriptSpan
import android.text.style.SuperscriptSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import me.laumss.notipal.NativeLocale
import me.laumss.notipal.relay.core.MarkdownAlignment
import me.laumss.notipal.relay.core.MarkdownBlock
import me.laumss.notipal.relay.core.MarkdownDocument
import me.laumss.notipal.relay.core.MarkdownDocumentParser
import kotlin.math.ceil

internal const val LIST_INDENT_DP = 18
internal const val LIST_MARKER_DP = 26

private val TEX_SYMBOLS = mapOf(
    "alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ", "epsilon" to "ε",
    "zeta" to "ζ", "eta" to "η", "theta" to "θ", "iota" to "ι", "kappa" to "κ",
    "lambda" to "λ", "mu" to "μ", "nu" to "ν", "xi" to "ξ", "pi" to "π",
    "rho" to "ρ", "sigma" to "σ", "tau" to "τ", "phi" to "φ", "chi" to "χ",
    "psi" to "ψ", "omega" to "ω",
    "Gamma" to "Γ", "Delta" to "Δ", "Theta" to "Θ", "Lambda" to "Λ", "Xi" to "Ξ",
    "Pi" to "Π", "Sigma" to "Σ", "Phi" to "Φ", "Psi" to "Ψ", "Omega" to "Ω",
    "times" to "×", "cdot" to "·", "div" to "÷", "pm" to "±", "mp" to "∓",
    "leq" to "≤", "le" to "≤", "geq" to "≥", "ge" to "≥", "neq" to "≠", "ne" to "≠",
    "approx" to "≈", "equiv" to "≡", "sim" to "∼", "propto" to "∝",
    "infty" to "∞", "partial" to "∂", "nabla" to "∇",
    "sum" to "∑", "prod" to "∏", "int" to "∫", "oint" to "∮",
    "rightarrow" to "→", "to" to "→", "leftarrow" to "←", "Rightarrow" to "⇒",
    "Leftarrow" to "⇐", "leftrightarrow" to "↔", "Leftrightarrow" to "⇔",
    "in" to "∈", "notin" to "∉", "subset" to "⊂", "subseteq" to "⊆",
    "cup" to "∪", "cap" to "∩", "emptyset" to "∅", "forall" to "∀", "exists" to "∃",
    "land" to "∧", "lor" to "∨", "neg" to "¬", "oplus" to "⊕", "otimes" to "⊗",
    "angle" to "∠", "perp" to "⊥", "parallel" to "∥", "degree" to "°",
    "ldots" to "…", "cdots" to "⋯", "dots" to "…", "prime" to "′",
    "hbar" to "ℏ", "ell" to "ℓ", "Re" to "ℜ", "Im" to "ℑ",
    "quad" to "  ", "qquad" to "    ", ";" to " ", "," to " ", " " to " ",
    "left" to "", "right" to "", "displaystyle" to "", "limits" to "",
)

private fun readCommand(s: String, at: Int): Pair<String, Int> {
    var j = at + 1
    while (j < s.length && s[j].isLetter()) j++
    if (j == at + 1 && j < s.length) j++
    return s.substring(at + 1, j) to j
}

private fun readGroup(s: String, start: Int): Pair<String, Int> {
    if (start >= s.length) return "" to start
    if (s[start] == '{') {
        var depth = 0
        var j = start
        while (j < s.length) {
            when (s[j]) { '{' -> depth++; '}' -> { depth--; if (depth == 0) return s.substring(start + 1, j) to j + 1 } }
            j++
        }
        return s.substring(start + 1) to s.length
    }
    if (s[start] == '\\') {
        val next = readCommand(s, start).second
        return s.substring(start, next) to next
    }
    return s[start].toString() to start + 1
}

private inline fun SpannableStringBuilder.styled(spans: List<Any>, block: SpannableStringBuilder.() -> Unit) {
    val start = length
    block()
    if (length > start) spans.forEach { setSpan(it, start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
}

private fun superscript() = listOf(SuperscriptSpan(), RelativeSizeSpan(0.72f), ForegroundColorSpan(INK))
private fun subscript() = listOf(SubscriptSpan(), RelativeSizeSpan(0.72f), ForegroundColorSpan(INK))

private fun SpannableStringBuilder.appendTex(tex: String) {
    var i = 0
    while (i < tex.length) {
        val c = tex[i]
        when {
            c == '\\' -> {
                val (cmd, j) = readCommand(tex, i)
                when (cmd) {
                    "frac" -> {
                        val (num, n1) = readGroup(tex, j)
                        val (den, n2) = readGroup(tex, n1)
                        val simple = num.length <= 2 && den.length <= 2 &&
                            num.none { it == '\\' } && den.none { it == '\\' }
                        if (simple) {
                            styled(superscript()) { appendTex(num) }
                            append('⁄')
                            styled(subscript()) { appendTex(den) }
                        } else {
                            append('(')
                            appendTex(num)
                            append(")/(")
                            appendTex(den)
                            append(')')
                        }
                        i = n2
                    }
                    "sqrt" -> {
                        val (arg, n1) = readGroup(tex, j)
                        append("√(")
                        appendTex(arg)
                        append(')')
                        i = n1
                    }
                    "vec", "hat", "widehat", "bar", "overline", "dot", "ddot",
                    "tilde", "widetilde", "check", "breve" -> {
                        val (arg, n1) = readGroup(tex, j)
                        val mark = when (cmd) {
                            "vec" -> '⃗'
                            "hat", "widehat" -> '̂'
                            "bar" -> '̄'
                            "overline" -> '̅'
                            "dot" -> '̇'
                            "ddot" -> '̈'
                            "check" -> '̌'
                            "breve" -> '̆'
                            else -> '̃'
                        }
                        if (arg.none { it == '\\' }) {
                            for (ch in arg) if (ch != '{' && ch != '}') { append(ch); append(mark) }
                        } else {
                            appendTex(arg)
                            append(mark)
                        }
                        i = n1
                    }
                    "text", "mathrm", "mathbf", "mathit", "operatorname" -> {
                        val (arg, n1) = readGroup(tex, j)
                        append(arg)
                        i = n1
                    }
                    else -> {
                        append(TEX_SYMBOLS[cmd] ?: cmd)
                        i = j
                    }
                }
            }
            c == '^' -> {
                val (arg, n1) = readGroup(tex, i + 1)
                styled(superscript()) { appendTex(arg) }
                i = n1
            }
            c == '_' -> {
                val (arg, n1) = readGroup(tex, i + 1)
                styled(subscript()) { appendTex(arg) }
                i = n1
            }
            c == '{' || c == '}' -> i++
            else -> { append(c); i++ }
        }
    }
}

private fun italicClose(text: String, open: Int): Int {
    if (text.getOrNull(open + 1)?.isWhitespace() != false) return -1
    return (open + 2 until text.length).firstOrNull { text[it] == '*' && !text[it - 1].isWhitespace() } ?: -1
}

private fun inlineStyled(text: String): SpannableStringBuilder {
    val out = SpannableStringBuilder()
    var i = 0

    fun span(delim: String, spans: List<Any>, close: Int = text.indexOf(delim, i + delim.length), raw: Boolean = false) {
        if (close < 0) { out.append(delim); i += delim.length; return }
        val inner = text.substring(i + delim.length, close)
        out.styled(spans) { if (raw) append(inner) else append(inlineStyled(inner)) }
        i = close + delim.length
    }

    while (i < text.length) {
        val c = text[i]
        when {
            text.startsWith("**", i) -> span("**", listOf(StyleSpan(Typeface.BOLD), ForegroundColorSpan(INK)))
            text.startsWith("~~", i) -> span("~~", listOf(StrikethroughSpan(), ForegroundColorSpan(INK)))
            c == '*' -> span("*", listOf(StyleSpan(Typeface.ITALIC), ForegroundColorSpan(INK)), close = italicClose(text, i))
            c == '`' -> span("`", listOf(TypefaceSpan("monospace"), BackgroundColorSpan(FAINT), ForegroundColorSpan(INK)), raw = true)
            c == '$' -> {

                val delim = if (text.startsWith("$$", i)) "$$" else "$"
                val end = text.indexOf(delim, i + delim.length)
                val tex = if (end < 0) "" else text.substring(i + delim.length, end)
                val isTex = if (delim == "$$") tex.isNotBlank() else MarkdownDocumentParser.isInlineTex(tex)
                if (isTex) {
                    out.appendTex(tex)
                    i = end + delim.length
                } else { out.append(c); i++ }
            }
            c == '[' || text.startsWith("![", i) -> {
                val image = c == '!'
                val mid = text.indexOf("](", i)
                val end = if (mid < 0) -1 else text.indexOf(')', mid)
                if (end < 0) { out.append(c); i++ } else {
                    val label = text.substring(i + if (image) 2 else 1, mid)
                    if (image) out.styled(listOf(ForegroundColorSpan(MUTED))) { append(NativeLocale.t("relay_image_alt", label)) }
                    else out.styled(listOf(UnderlineSpan(), ForegroundColorSpan(INK))) { append(label) }
                    i = end + 1
                }
            }
            else -> { out.append(c); i++ }
        }
    }
    return out
}

private fun mdText(
    ctx: Context,
    text: CharSequence,
    sizeSp: Number,
    face: Typeface?,
    color: Int = INK,
    lineSp: Number? = null,
): TextView = TextView(ctx).apply {
    setTextColor(color)
    setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp.toFloat())
    if (face != null) typeface = face

    breakStrategy = Layout.BREAK_STRATEGY_SIMPLE
    hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
    if (lineSp != null) lineHeightPx(spPx(ctx, lineSp))
    this.text = text
}

internal fun markdownBlockView(ctx: Context, block: MarkdownBlock, baseFont: Int, boldBody: Boolean = true, serifHeads: Boolean = false): View {
    val bodyFace = if (boldBody) Typeface.DEFAULT_BOLD else null
    val headFace = when {
        serifHeads -> Typeface.create(Typeface.SERIF, Typeface.BOLD)
        boldBody -> weightedTypeface(800)
        else -> Typeface.DEFAULT_BOLD
    }
    return when (block) {
        is MarkdownBlock.Heading -> {
            val size = when (block.level) {
                1 -> baseFont + 10
                2 -> baseFont + 6
                3 -> baseFont + 3
                else -> baseFont + 1
            }
            val role = MarkdownDocumentParser.roleHeading(block.text)
            if (role != null) {
                val text = SpannableStringBuilder()
                text.styled(listOf(AbsoluteSizeSpan(spPx(ctx, size).toInt()), StyleSpan(Typeface.BOLD), ForegroundColorSpan(INK))) {
                    append(role.first + ":")
                }
                if (role.second.isNotBlank()) {
                    text.append(" ")
                    text.append(inlineStyled(role.second))
                }
                mdText(ctx, text, baseFont, bodyFace, lineSp = size * 1.3f).apply {
                    setPadding(0, dpPx(ctx, baseFont * 0.8f), 0, 0)
                }
            } else {
                mdText(ctx, inlineStyled(block.text), size, headFace, lineSp = size * 1.3f)
            }
        }

        is MarkdownBlock.Paragraph -> mdText(ctx, inlineStyled(block.text), baseFont, bodyFace, lineSp = baseFont * 1.55f)

        is MarkdownBlock.Quote -> LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(View(ctx).apply { setBackgroundColor(MUTED) }, LinearLayout.LayoutParams(dpPx(ctx, 3), LinearLayout.LayoutParams.MATCH_PARENT))
            addView(
                mdText(ctx, inlineStyled(block.text), baseFont, bodyFace, MUTED, baseFont * 1.5f),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dpPx(ctx, 10) },
            )
        }

        is MarkdownBlock.CodeFence -> mdText(
            ctx, block.code, baseFont - 3,
            if (boldBody) Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) else Typeface.MONOSPACE,
            lineSp = baseFont * 1.35f,
        ).apply {
            background = GradientDrawable().apply {
                setColor(FAINT)
                setStroke(dpPx(ctx, 1).coerceAtLeast(1), MUTED)
            }
            val pad = dpPx(ctx, 11)
            setPadding(pad, pad, pad, pad)
        }

        is MarkdownBlock.ListItem -> LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dpPx(ctx, block.indent * LIST_INDENT_DP), 0, 0, 0)
            addView(
                mdText(ctx, block.marker, baseFont, bodyFace, lineSp = baseFont * 1.5f),
                LinearLayout.LayoutParams(dpPx(ctx, LIST_MARKER_DP), LinearLayout.LayoutParams.WRAP_CONTENT),
            )
            addView(
                mdText(ctx, inlineStyled(block.text), baseFont, bodyFace, lineSp = baseFont * 1.5f),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
        }

        is MarkdownBlock.Rule -> FrameLayout(ctx).apply {
            addView(View(ctx).apply { setBackgroundColor(MUTED) },
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dpPx(ctx, 1).coerceAtLeast(1)))
        }

        is MarkdownBlock.Math -> {
            val text = SpannableStringBuilder().apply { appendTex(block.tex.replace("\n", " ")) }
            mdText(ctx, text, baseFont + 2, bodyFace, lineSp = (baseFont + 2) * 1.6f).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                val pad = dpPx(ctx, 4)
                setPadding(0, pad, 0, pad)
            }
        }

        is MarkdownBlock.Table -> MarkdownTableView(ctx, block, baseFont, boldBody)
    }
}

internal class MarkdownDocumentLayout(
    context: Context,
    private val baseFont: Int,
    private val boldBody: Boolean = true,
    private val serifHeads: Boolean = false,
) : LinearLayout(context) {
    private var blocks: List<MarkdownBlock> = emptyList()
    init {
        orientation = VERTICAL
    }

    fun setDocument(document: MarkdownDocument) {
        val next = document.blocks
        var same = 0
        while (same < blocks.size && same < next.size && blocks[same] == next[same]) same++
        if (same == blocks.size && same == next.size) return
        removeViews(same, childCount - same)
        for (index in same until next.size) {
            addView(markdownBlockView(context, next[index], baseFont, boldBody, serifHeads), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                if (index > 0) topMargin = dpPx(context, 10)
            })
        }
        blocks = next
    }
}

@SuppressLint("ViewConstructor")
private class MarkdownTableView(
    context: Context,
    private val table: MarkdownBlock.Table,
    private val baseFont: Int,
    private val boldBody: Boolean,
) : LinearLayout(context) {
    private var builtFor = -1

    init {
        orientation = VERTICAL
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        if (width > 0 && width != builtFor) {
            builtFor = width
            build(width)
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun build(width: Int) {
        removeAllViews()
        val maxDp = width / resources.displayMetrics.density
        val cols = table.headers.indices.filter { c -> table.headers[c].isNotBlank() || table.rows.any { it[c].isNotBlank() } }

        val sized = cols.map { c ->
            c to (maxOf(table.headers[c].length, table.rows.maxOfOrNull { it[c].length } ?: 0) * 8).coerceIn(80, 480)
        }
        val count = if (cols.isEmpty()) 0
            else ceil(sized.sumOf { it.second } / maxDp.coerceAtLeast(1f)).toInt().coerceIn(1, cols.size)
        var start = 0
        for (g in 0 until count) {
            val end = start + cols.size / count + if (g < cols.size % count) 1 else 0
            val group = sized.subList(start, end)
            start = end
            val box = LinearLayout(context).apply {
                orientation = VERTICAL
                foreground = GradientDrawable().apply { setStroke(dpPx(context, 1).coerceAtLeast(1), INK) }
                addView(tableRow(group, group.map { table.headers[it.first] }, weightedTypeface(800), FAINT))
                val face = if (boldBody) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                table.rows.forEach { row -> addView(tableRow(group, group.map { row[it.first] }, face, null)) }
            }
            addView(box, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                if (g > 0) topMargin = dpPx(context, 10)
            })
        }
    }

    private fun tableRow(group: List<Pair<Int, Int>>, cells: List<String>, face: Typeface, fill: Int?): LinearLayout =
        LinearLayout(context).apply {
            orientation = HORIZONTAL
            cells.forEachIndexed { index, cell ->
                val (column, widthDp) = group[index]
                addView(mdText(context, inlineStyled(cell), baseFont, face, lineSp = baseFont * 1.4f).apply {
                    gravity = when (table.alignments[column]) {
                        MarkdownAlignment.LEFT -> Gravity.START
                        MarkdownAlignment.CENTER -> Gravity.CENTER_HORIZONTAL
                        MarkdownAlignment.RIGHT -> Gravity.END
                    }
                    background = GradientDrawable().apply {
                        if (fill != null) setColor(fill)
                        setStroke(dpPx(context, 0.5f).coerceAtLeast(1), MUTED)
                    }
                    setPadding(dpPx(context, 10), dpPx(context, 6), dpPx(context, 10), dpPx(context, 6))
                }, LayoutParams(dpPx(context, widthDp), LayoutParams.MATCH_PARENT))
            }
        }
}
