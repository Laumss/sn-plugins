package me.laumss.notipal.relay.ink

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

internal class InkBox(val w: Float, val h: Float, val d: Float, val strokes: List<FloatArray>)

internal enum class InkStyle(val scale: Float) {
    D(1f), T(1f), S(0.7f), SS(0.5f);

    val script: InkStyle get() = if (this == D || this == T) S else SS
    val fracPart: InkStyle get() = when (this) { D -> T; T -> S; else -> SS }
    val tight: Boolean get() = this == S || this == SS
}

internal class InkLayout(private val glyphs: HersheyGlyphs) {
    val missing = linkedSetOf<Int>()

    private class InkBuilder {
        val strokes = mutableListOf<FloatArray>()
        var h = 0f
        var d = 0f

        fun place(box: InkBox, x: Float, y: Float) {
            for (s in box.strokes) strokes += FloatArray(s.size) { i -> s[i] + if (i % 2 == 0) x else y }
            h = max(h, box.h - y)
            d = max(d, box.d + y)
        }

        fun line(vararg pts: Float) {
            strokes += pts
            for (i in 1 until pts.size step 2) {
                h = max(h, -pts[i])
                d = max(d, pts[i])
            }
        }

        fun build(w: Float) = InkBox(w, h, d, strokes)
    }

    fun layout(node: TexNode, style: InkStyle): InkBox = when (node) {
        is TexNode.Sym -> glyph(node.cp, style)
        is TexNode.Word -> word(node.text, style)
        is TexNode.Text -> word(node.text, style)
        is TexNode.Row -> row(node.items, style)
        is TexNode.RelRow -> row(node.items, style)
        is TexNode.Frac -> frac(node, style)
        is TexNode.Sqrt -> sqrt(node, style)
        is TexNode.Scripts -> scripts(node, style)
        is TexNode.BigOp -> bigOp(node, style)
        is TexNode.Delimited -> delimited(node, style)
        is TexNode.BigDelim -> delimiter(node.cp, style, node.size * DELIM_UNITS * HU * style.scale)
        is TexNode.Accent -> accent(node, style)
        is TexNode.Space -> InkBox(node.em * style.scale, 0f, 0f, emptyList())
        is TexNode.Stack -> stack(node, style)
        is TexNode.Not -> negate(node, style)
        is TexNode.Phantom -> layout(node.body, style).let { InkBox(it.w, it.h, it.d, emptyList()) }
        is TexNode.Dots -> dots(node.kind, style)
        is TexNode.Grid -> grid(node, style)
    }

    private fun glyph(cp: Int, style: InkStyle, sx: Float = 1f, sy: Float = 1f): InkBox {
        val g = glyphs[cp] ?: glyphs['?'.code].also { missing += cp }
            ?: return InkBox(0.5f * style.scale, 0f, 0f, emptyList())
        val kx = style.scale * HU * sx
        val ky = style.scale * HU * sy
        val strokes = g.strokes.map { s ->
            FloatArray(s.size) { i -> if (i % 2 == 0) (s[i] - g.left) * kx else (s[i] - HersheyGlyph.BASELINE) * ky }
        }
        return InkBox((g.right - g.left) * kx, max(0f, (HersheyGlyph.BASELINE - g.top) * ky),
            max(0f, (g.bottom - HersheyGlyph.BASELINE) * ky), strokes)
    }

    private fun shifted(box: InkBox, dy: Float) =
        InkBox(box.w, box.h - dy, box.d + dy, box.strokes.map { s -> FloatArray(s.size) { i -> s[i] + if (i % 2 == 1) dy else 0f } })

    private fun centredOnAxis(box: InkBox, style: InkStyle): InkBox = shifted(box, -AXIS * style.scale - (box.d - box.h) / 2)

    private fun word(text: String, style: InkStyle): InkBox {
        val b = InkBuilder()
        var x = 0f
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            if (Character.isWhitespace(cp)) {
                x += SPACE_EM * style.scale
                continue
            }
            val g = glyph(cp, style)
            b.place(g, x, 0f)
            x += g.w
        }
        return b.build(x)
    }

    private fun dot(b: InkBuilder, x: Float, y: Float, r: Float) = b.line(x, y - r, x - r, y, x, y + r, x + r, y, x, y - r)

    private fun row(items: List<TexNode>, style: InkStyle, leadOrd: Boolean = false): InkBox {
        val b = InkBuilder()
        var x = 0f
        var prev: AtomClass? = if (leadOrd) AtomClass.ORD else null
        for ((i, node) in items.withIndex()) {
            if (node is TexNode.Space) {
                x += node.em * style.scale
                continue
            }
            var cls = node.cls
            if (cls == AtomClass.BIN) {
                val next = items.drop(i + 1).firstOrNull { it !is TexNode.Space }?.cls
                if (prev == null || prev in NO_BIN_AFTER || next == null || next in NO_BIN_BEFORE) cls = AtomClass.ORD
            }
            if (prev != null) x += gap(prev, cls, style)
            val box = layout(node, style)
            b.place(box, x, 0f)
            x += box.w
            prev = cls
        }
        return b.build(x)
    }

    private fun gap(prev: AtomClass, cur: AtomClass, style: InkStyle): Float {
        val em = when {
            prev == AtomClass.REL && cur == AtomClass.REL -> 0f
            prev == AtomClass.REL || cur == AtomClass.REL -> if (style.tight) 0f else THICK
            prev == AtomClass.BIN || cur == AtomClass.BIN -> if (style.tight) 0f else MEDIUM
            prev == AtomClass.OP && (cur == AtomClass.ORD || cur == AtomClass.OP) -> THIN
            cur == AtomClass.OP && (prev == AtomClass.ORD || prev == AtomClass.CLOSE) -> THIN
            prev == AtomClass.PUNCT -> if (style.tight) 0f else THIN
            else -> 0f
        }
        return em * style.scale
    }

    private fun frac(node: TexNode.Frac, style: InkStyle): InkBox {
        val e = style.scale
        val num = layout(node.num, style.fracPart)
        val den = layout(node.den, style.fracPart)
        val a = AXIS * e
        val gap = (if (style == InkStyle.D) 0.16f else 0.1f) * e * (if (node.bar) 1f else 1.6f)
        val bar = max(num.w, den.w) + 0.16f * e
        val side = 0.06f * e
        val b = InkBuilder()
        b.place(num, side + (bar - num.w) / 2, -(a + gap + num.d))
        if (node.bar) b.line(side, -a, side + bar, -a)
        b.place(den, side + (bar - den.w) / 2, -a + gap + den.h)
        return b.build(bar + 2 * side)
    }

    private fun sqrt(node: TexNode.Sqrt, style: InkStyle): InkBox {
        val e = style.scale
        val body = layout(node.body, style)
        val top = max(body.h, 0.5f * e) + 0.12f * e
        val bottom = max(body.d, 0.05f * e)
        val total = top + bottom
        val index = node.index?.let { layout(it, InkStyle.SS) }
        val tickY = bottom - 0.46f * total
        val shift = index?.let { max(0f, it.w - 0.18f * e) } ?: 0f
        val x1 = shift + 0.1f * e
        val x2 = shift + 0.26f * e
        val x3 = x2 + 0.16f * e + 0.12f * total
        val x4 = x3 + body.w + 0.1f * e
        val b = InkBuilder()
        index?.let { b.place(it, x1 + 0.08f * e - it.w, tickY - 0.08f * e - it.d) }
        b.line(shift, bottom - 0.4f * total, x1, tickY, x2, bottom, x3, -top, x4, -top)
        b.place(body, x3 + 0.06f * e, 0f)
        return b.build(x4 + 0.04f * e)
    }

    private fun scripts(node: TexNode.Scripts, style: InkStyle): InkBox {
        val e = style.scale
        val base = node.base
        val limits = when (base) {
            is TexNode.BigOp -> base.limits ?: (base.defaultLimits && style == InkStyle.D)
            is TexNode.Word -> base.limits && style == InkStyle.D
            else -> false
        }
        val baseBox = layout(base, style)
        val sup = node.sup?.let { layout(it, style.script) }
        val sub = node.sub?.let { layout(it, style.script) }
        val b = InkBuilder()
        if (limits) {
            val w = maxOf(baseBox.w, sup?.w ?: 0f, sub?.w ?: 0f)
            val gap = 0.1f * e
            b.place(baseBox, (w - baseBox.w) / 2, 0f)
            sup?.let { b.place(it, (w - it.w) / 2, -(baseBox.h + gap + it.d)) }
            sub?.let { b.place(it, (w - it.w) / 2, baseBox.d + gap + it.h) }
            return b.build(w)
        }
        b.place(baseBox, 0f, 0f)
        var u = 0f
        var v = 0f
        if (sup != null) u = maxOf(0.38f * e, baseBox.h - 0.3f * e, sup.d + 0.15f * e)
        if (sub != null) v = maxOf(0.22f * e, baseBox.d * 0.8f, sub.h - 0.4f * e)
        if (sup != null && sub != null) {
            val clear = (u - sup.d) - (sub.h - v)
            if (clear < 0.12f * e) v += 0.12f * e - clear
        }

        val kern = if (base is TexNode.BigOp && base.cp == TexParser.INTEGRAL) 0.3f * baseBox.w / base.count else 0f
        val supX = baseBox.w - 0.03f * e
        val subX = supX - kern
        sup?.let { b.place(it, supX, -u) }
        sub?.let { b.place(it, subX, v) }
        return b.build(maxOf(baseBox.w, sup?.let { supX + it.w } ?: 0f, sub?.let { subX + it.w } ?: 0f) + 0.02f * e)
    }

    private fun bigOp(op: TexNode.BigOp, style: InkStyle): InkBox {
        val display = style == InkStyle.D
        val integral = op.cp == TexParser.INTEGRAL
        val scale = when {
            integral && display -> 1.8f
            integral -> 1.15f
            display -> 1.5f
            else -> 1.1f
        }
        val one = glyph(op.cp, style, scale, scale)
        val step = if (integral) one.w * 0.55f else one.w
        val b = InkBuilder()
        for (k in 0 until op.count) b.place(one, k * step, 0f)
        val w = (op.count - 1) * step + one.w
        if (op.ring) {
            val r = one.w * 0.28f
            val cy = (one.d - one.h) / 2
            b.line(*FloatArray(26) { i -> val t = 2 * PI * (i / 2) / 12; if (i % 2 == 0) w / 2 + r * cos(t).toFloat() else cy + r * sin(t).toFloat() })
        }
        return centredOnAxis(b.build(w), style)
    }

    private fun delimited(node: TexNode.Delimited, style: InkStyle): InkBox {
        val e = style.scale
        val a = AXIS * e
        val body = layout(node.body, style)
        val target = 2 * max(body.h - a, body.d + a) + 0.15f * e
        val left = delimiter(node.left, style, target)
        val right = delimiter(node.right, style, target)
        val b = InkBuilder()
        b.place(left, 0f, 0f)
        b.place(body, left.w, 0f)
        b.place(right, left.w + body.w, 0f)
        return b.build(left.w + body.w + right.w)
    }

    private fun delimiter(cp: Int, style: InkStyle, target: Float): InkBox {
        if (cp == 0) return InkBox(0.05f * style.scale, 0f, 0f, emptyList())
        val g = glyphs[cp] ?: return glyph(cp, style)
        val natural = (g.bottom - g.top) * HU * style.scale
        val sy = if (natural > 0f) max(1f, target / natural) else 1f
        val sx = min(1f + (sy - 1f) * 0.15f, 1.6f)
        return centredOnAxis(glyph(cp, style, sx, sy), style)
    }

    private fun accent(node: TexNode.Accent, style: InkStyle): InkBox {
        val e = style.scale
        val body = layout(node.body, style)
        val b = InkBuilder()
        b.place(body, 0f, 0f)
        val cx = body.w / 2
        val top = -(max(body.h, 0.45f * e) + 0.1f * e)
        when (node.kind) {
            AccentKind.HAT, AccentKind.WIDEHAT -> {
                val hw = if (node.kind == AccentKind.WIDEHAT) max(body.w * 0.45f, 0.15f * e) else 0.15f * e
                b.line(cx - hw, top, cx, top - 0.15f * e, cx + hw, top)
            }
            AccentKind.BAR, AccentKind.OVERLINE -> {
                val inset = if (node.kind == AccentKind.BAR) body.w * 0.12f else 0.02f * e
                b.line(inset, top - 0.03f * e, body.w - inset, top - 0.03f * e)
            }
            AccentKind.VEC -> {
                val y = top - 0.08f * e
                val half = max(body.w * 0.4f, 0.2f * e)
                val x1 = cx + half
                b.line(cx - half, y, x1, y)
                b.line(x1 - 0.1f * e, y - 0.07f * e, x1, y, x1 - 0.1f * e, y + 0.07f * e)
            }
            AccentKind.DOT -> dot(b, cx, top - 0.04f * e, 0.035f * e)
            AccentKind.DDOT -> {
                dot(b, cx - 0.09f * e, top - 0.04f * e, 0.035f * e)
                dot(b, cx + 0.09f * e, top - 0.04f * e, 0.035f * e)
            }
            AccentKind.TILDE -> {
                val hw = max(0.16f * e, body.w * 0.4f)
                b.line(*FloatArray(18) { i ->
                    val t = (i / 2) / 8f
                    if (i % 2 == 0) cx - hw + 2 * hw * t else top - 0.06f * e - 0.05f * e * sin(2 * PI * t).toFloat()
                })
            }
            AccentKind.UNDERLINE -> {
                val y = body.d + 0.1f * e
                b.line(0f, y, body.w, y)
            }
        }
        return b.build(body.w)
    }

    private fun stack(node: TexNode.Stack, style: InkStyle): InkBox {
        val e = style.scale
        val base = layout(node.base, style)
        val over = node.over?.let { layout(it, style.script) }
        val under = node.under?.let { layout(it, style.script) }
        val w = maxOf(base.w, over?.w ?: 0f, under?.w ?: 0f)
        val gap = 0.08f * e
        val b = InkBuilder()
        b.place(base, (w - base.w) / 2, 0f)
        over?.let { b.place(it, (w - it.w) / 2, -(base.h + gap + it.d)) }
        under?.let { b.place(it, (w - it.w) / 2, base.d + gap + it.h) }
        return b.build(w)
    }

    private fun negate(node: TexNode.Not, style: InkStyle): InkBox {
        val e = style.scale
        val a = AXIS * e
        val body = layout(node.body, style)
        val b = InkBuilder()
        b.place(body, 0f, 0f)
        b.line(body.w * 0.7f, -a - 0.32f * e, body.w * 0.3f, -a + 0.32f * e)
        return b.build(body.w)
    }

    private fun dots(kind: DotsKind, style: InkStyle): InkBox {
        val e = style.scale
        val r = 0.035f * e
        val b = InkBuilder()
        val w = when (kind) {
            DotsKind.LOW -> { repeat(3) { dot(b, (0.12f + it * 0.24f) * e, -r, r) }; 0.72f * e }
            DotsKind.AXIS -> { repeat(3) { dot(b, (0.12f + it * 0.24f) * e, -AXIS * e, r) }; 0.72f * e }
            DotsKind.VERTICAL -> { repeat(3) { dot(b, 0.15f * e, -(0.05f + it * 0.28f) * e, r) }; 0.3f * e }
            DotsKind.DIAGONAL -> { repeat(3) { dot(b, (0.12f + it * 0.28f) * e, -(0.65f - it * 0.28f) * e, r) }; 0.9f * e }
        }
        return b.build(w)
    }

    private fun grid(node: TexNode.Grid, style: InkStyle): InkBox {
        val e = style.scale
        val a = AXIS * e
        val kind = node.kind
        val cellStyle = if (style == InkStyle.D && kind != GridKind.ALIGNED && kind != GridKind.GATHERED) InkStyle.T else style
        val cells = node.rows.map { line ->
            line.mapIndexed { c, cell -> row(cell.items, cellStyle, leadOrd = kind == GridKind.ALIGNED && c % 2 == 1) }
        }
        val cols = cells.maxOf { it.size }
        val colW = FloatArray(cols)
        cells.forEach { line -> line.forEachIndexed { c, box -> colW[c] = max(colW[c], box.w) } }
        val rowH = cells.map { line -> max(line.maxOfOrNull { it.h } ?: 0f, 0.7f * e) }
        val rowD = cells.map { line -> max(line.maxOfOrNull { it.d } ?: 0f, 0.25f * e) }
        val rowGap = (if (kind == GridKind.ALIGNED || kind == GridKind.GATHERED) 0.4f else 0.3f) * e
        val colX = FloatArray(cols)
        var x = 0f
        for (c in 0 until cols) {
            colX[c] = x
            x += colW[c]
            if (c < cols - 1) x += when (kind) {
                GridKind.ALIGNED -> if (c % 2 == 1) 1.2f * e else 0f
                GridKind.CASES -> 0.9f * e
                else -> 0.8f * e
            }
        }
        val height = rowH.sum() + rowD.sum() + rowGap * (cells.size - 1)
        val b = InkBuilder()
        b.h = height / 2 + a
        b.d = height / 2 - a
        var top = -(height / 2 + a)
        cells.forEachIndexed { r, line ->
            val baseline = top + rowH[r]
            line.forEachIndexed { c, box ->
                val align = when (kind) {
                    GridKind.ALIGNED -> if (c % 2 == 0) 'r' else 'l'
                    GridKind.CASES -> 'l'
                    GridKind.ARRAY -> node.aligns.getOrElse(c) { 'c' }
                    else -> 'c'
                }
                val cx = when (align) {
                    'r' -> colX[c] + colW[c] - box.w
                    'l' -> colX[c]
                    else -> colX[c] + (colW[c] - box.w) / 2
                }
                b.place(box, cx, baseline)
            }
            top = baseline + rowD[r] + rowGap
        }
        val body = b.build(x)
        if (node.left == 0 && node.right == 0) return body
        val target = height + 0.15f * e
        val out = InkBuilder()
        var ox = 0f
        if (node.left != 0) {
            val left = delimiter(node.left, style, target)
            out.place(left, 0f, 0f)
            ox = left.w + 0.1f * e
        }
        out.place(body, ox, 0f)
        ox += body.w
        if (node.right != 0) {
            ox += 0.1f * e
            val right = delimiter(node.right, style, target)
            out.place(right, ox, 0f)
            ox += right.w
        }
        return out.build(ox)
    }

    companion object {

        const val HU = 1f / 30f

        const val AXIS = 9f * HU

        private const val DELIM_UNITS = 32f
        private const val SPACE_EM = 0.3f

        private const val THIN = 0.08f
        private const val MEDIUM = 0.1f
        private const val THICK = 0.14f
        private val NO_BIN_AFTER = setOf(AtomClass.BIN, AtomClass.OP, AtomClass.REL, AtomClass.OPEN, AtomClass.PUNCT)
        private val NO_BIN_BEFORE = setOf(AtomClass.REL, AtomClass.CLOSE, AtomClass.PUNCT)
    }
}
