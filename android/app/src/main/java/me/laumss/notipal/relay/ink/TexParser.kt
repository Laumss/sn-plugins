package me.laumss.notipal.relay.ink

internal enum class AtomClass { ORD, OP, BIN, REL, OPEN, CLOSE, PUNCT }

internal enum class AccentKind { HAT, WIDEHAT, BAR, OVERLINE, VEC, DOT, DDOT, TILDE, UNDERLINE }

internal enum class DotsKind { LOW, AXIS, VERTICAL, DIAGONAL }

internal enum class GridKind { MATRIX, CASES, ARRAY, ALIGNED, GATHERED }

internal sealed class TexNode {
    open val cls: AtomClass get() = AtomClass.ORD

    class Sym(val cp: Int, override val cls: AtomClass) : TexNode()

    class Word(val text: String, val limits: Boolean) : TexNode() { override val cls get() = AtomClass.OP }
    class Text(val text: String) : TexNode()
    class Row(val items: List<TexNode>) : TexNode()
    class Frac(val num: TexNode, val den: TexNode, val bar: Boolean = true) : TexNode()
    class Sqrt(val body: TexNode, val index: TexNode?) : TexNode()
    class Scripts(val base: TexNode, val sup: TexNode?, val sub: TexNode?) : TexNode() {
        override val cls get() = base.cls
    }

    class BigOp(val cp: Int, val count: Int = 1, val ring: Boolean = false, val defaultLimits: Boolean) : TexNode() {
        var limits: Boolean? = null
        override val cls get() = AtomClass.OP
    }

    class Delimited(val left: Int, val body: TexNode, val right: Int) : TexNode()

    class BigDelim(val cp: Int, val size: Float, override val cls: AtomClass) : TexNode()
    class Accent(val kind: AccentKind, val body: TexNode) : TexNode()
    class Space(val em: Float) : TexNode()

    class Stack(val base: TexNode, val over: TexNode?, val under: TexNode?) : TexNode() {
        override val cls get() = base.cls
    }
    class Not(val body: TexNode) : TexNode() { override val cls get() = body.cls }
    class Phantom(val body: TexNode) : TexNode()

    class RelRow(val items: List<TexNode>) : TexNode() { override val cls get() = AtomClass.REL }
    class Dots(val kind: DotsKind) : TexNode()

    class Grid(val rows: List<List<Row>>, val kind: GridKind, val aligns: String = "", val left: Int = 0, val right: Int = 0) : TexNode()
}

internal class TexParser(private val src: String) {
    private var pos = 0
    val unknown = linkedSetOf<String>()

    fun parse(): TexNode = cellsToNode(parseCells())

    private fun parseCells(): MutableList<MutableList<TexNode.Row>> {
        val rows = mutableListOf(mutableListOf<TexNode.Row>())
        var cell = mutableListOf<TexNode>()
        while (true) {
            skipSpaces()
            if (pos >= src.length || src[pos] == '}' || peekCommand("end") || peekCommand("right")) break
            when {
                src[pos] == '&' -> {
                    pos++
                    rows.last() += TexNode.Row(cell)
                    cell = mutableListOf()
                }
                src.startsWith("\\\\", pos) || peekCommand("cr") -> {
                    pos += if (src.startsWith("\\\\", pos)) 2 else 3
                    skipSpaces()
                    if (pos < src.length && src[pos] == '[') skipPast(']')
                    rows.last() += TexNode.Row(cell)
                    cell = mutableListOf()
                    rows += mutableListOf<TexNode.Row>()
                }
                else -> parseItem(cell)
            }
        }
        rows.last() += TexNode.Row(cell)
        if (rows.size > 1 && rows.last().all { it.items.isEmpty() }) rows.removeAt(rows.lastIndex)
        return rows
    }

    private fun cellsToNode(rows: List<List<TexNode.Row>>): TexNode = when {
        rows.size == 1 && rows[0].size == 1 -> rows[0][0]
        rows.any { it.size > 1 } -> TexNode.Grid(rows, GridKind.ALIGNED)
        else -> TexNode.Grid(rows, GridKind.GATHERED)
    }

    private fun parseGroupRest(): TexNode {
        val node = cellsToNode(parseCells())
        if (pos < src.length && src[pos] == '}') pos++
        return node
    }

    private fun parseArg(): TexNode {
        skipSpaces()
        if (pos >= src.length) return TexNode.Row(emptyList())
        if (src[pos] == '{') {
            pos++
            return parseGroupRest()
        }
        val out = mutableListOf<TexNode>()
        parseItem(out)
        return out.singleOrNull() ?: TexNode.Row(out)
    }

    private fun parseItem(out: MutableList<TexNode>) {
        val c = src[pos]
        when (c) {
            '{' -> { pos++; out += parseGroupRest() }
            '}', '&' -> pos++
            '^', '_' -> { pos++; attachScript(out, c == '^', parseArg()) }
            '\'' -> {
                var n = 0
                while (pos < src.length && src[pos] == '\'') { n++; pos++ }
                attachScript(out, true, TexNode.Row(List(n) { TexNode.Sym(PRIME, AtomClass.ORD) }))
            }
            '\\' -> parseCommand(out)
            '~' -> { pos++; out += TexNode.Space(0.33f) }
            else -> {
                val cp = src.codePointAt(pos)
                pos += Character.charCount(cp)
                out += symFor(cp)
            }
        }
    }

    private fun attachScript(out: MutableList<TexNode>, sup: Boolean, arg: TexNode) {
        val base = out.removeLastOrNull() ?: TexNode.Row(emptyList())
        out += if (base is TexNode.Scripts) {
            if (sup) TexNode.Scripts(base.base, join(base.sup, arg), base.sub)
            else if (base.sub == null) TexNode.Scripts(base.base, base.sup, arg)
            else TexNode.Scripts(base, null, arg)
        } else {
            if (sup) TexNode.Scripts(base, arg, null) else TexNode.Scripts(base, null, arg)
        }
    }

    private fun join(a: TexNode?, b: TexNode): TexNode = if (a == null) b else TexNode.Row(listOf(a, b))

    private fun parseCommand(out: MutableList<TexNode>) {
        pos++
        if (pos >= src.length) return
        val c = src[pos]
        if (!c.isLetter()) {
            pos++
            out += when (c) {
                ',' -> TexNode.Space(3f / 18)
                ':', '>' -> TexNode.Space(4f / 18)
                ';' -> TexNode.Space(5f / 18)
                '!' -> TexNode.Space(-3f / 18)
                ' ' -> TexNode.Space(0.33f)
                '{' -> TexNode.Sym('{'.code, AtomClass.OPEN)
                '}' -> TexNode.Sym('}'.code, AtomClass.CLOSE)
                '|' -> TexNode.Sym(DOUBLE_BAR, AtomClass.ORD)
                else -> TexNode.Sym(c.code, AtomClass.ORD)
            }
            return
        }
        val name = readName()
        SYMBOLS[name]?.let { (cp, cls) -> out += TexNode.Sym(cp, cls); return }
        when (name) {
            in FUNCTIONS -> out += TexNode.Word(name, false)
            in LIMIT_FUNCTIONS -> out += TexNode.Word(name, true)
            "operatorname" -> {
                val star = consume('*')
                out += TexNode.Word(readRawGroup().replace("\\", "").trim(), star)
            }
            "sum" -> out += TexNode.BigOp(0x2211, defaultLimits = true)
            "prod" -> out += TexNode.BigOp(0x220F, defaultLimits = true)
            "bigcup" -> out += TexNode.BigOp(0x222A, defaultLimits = true)
            "bigcap" -> out += TexNode.BigOp(0x2229, defaultLimits = true)
            "int" -> out += TexNode.BigOp(INTEGRAL, defaultLimits = false)
            "iint" -> out += TexNode.BigOp(INTEGRAL, count = 2, defaultLimits = false)
            "iiint" -> out += TexNode.BigOp(INTEGRAL, count = 3, defaultLimits = false)
            "oint" -> out += TexNode.BigOp(INTEGRAL, ring = true, defaultLimits = false)
            "limits", "nolimits" -> (out.lastOrNull() as? TexNode.BigOp)?.limits = name == "limits"
            "ll", "gg" -> {
                val cp = if (name == "ll") '<'.code else '>'.code
                out += TexNode.RelRow(List(2) { TexNode.Sym(cp, AtomClass.ORD) })
            }
            "frac", "dfrac", "tfrac", "cfrac" -> {
                val num = parseArg()
                out += TexNode.Frac(num, parseArg())
            }
            "binom", "dbinom", "tbinom" -> {
                val top = parseArg()
                out += TexNode.Delimited('('.code, TexNode.Frac(top, parseArg(), bar = false), ')'.code)
            }
            "sqrt" -> {
                skipSpaces()
                val index = if (pos < src.length && src[pos] == '[') subParse(readBracket()) else null
                out += TexNode.Sqrt(parseArg(), index)
            }
            "left" -> {
                val left = readDelim()
                val body = cellsToNode(parseCells())
                val right = if (peekCommand("right")) { pos += 6; readDelim() } else 0
                out += TexNode.Delimited(left, body, right)
            }
            "right" -> readDelim()
            "middle" -> out += TexNode.BigDelim(readDelim(), 1f, AtomClass.ORD)
            in BIG_SIZES -> {
                val cp = readDelim()
                val cls = when {
                    name.endsWith("l") -> AtomClass.OPEN
                    name.endsWith("r") -> AtomClass.CLOSE
                    else -> symFor(cp).cls
                }
                out += TexNode.BigDelim(cp, BIG_SIZES.getValue(name), cls)
            }
            in TEXT_COMMANDS -> out += TexNode.Text(readRawGroup())
            in FONT_COMMANDS -> out += parseArg()
            in ACCENTS -> out += TexNode.Accent(ACCENTS.getValue(name), parseArg())
            "overset", "stackrel" -> {
                val over = parseArg()
                out += TexNode.Stack(parseArg(), over, null)
            }
            "underset" -> {
                val under = parseArg()
                out += TexNode.Stack(parseArg(), null, under)
            }
            "overbrace", "underbrace", "boxed" -> out += parseArg()
            "not" -> {
                val next = mutableListOf<TexNode>()
                skipSpaces()
                if (pos < src.length) parseItem(next)
                next.forEach { out += TexNode.Not(it) }
            }
            "phantom", "hphantom" -> out += TexNode.Phantom(parseArg())
            "vphantom" -> parseArg()
            "quad" -> out += TexNode.Space(1f)
            "qquad" -> out += TexNode.Space(2f)
            "enspace" -> out += TexNode.Space(0.5f)
            "thinspace" -> out += TexNode.Space(3f / 18)
            "medspace" -> out += TexNode.Space(4f / 18)
            "thickspace" -> out += TexNode.Space(5f / 18)
            "hspace" -> { consume('*'); readRawGroup(); out += TexNode.Space(0.5f) }
            "ldots", "dots", "dotsc", "dotso" -> out += TexNode.Dots(DotsKind.LOW)
            "cdots", "dotsb", "dotsm", "dotsi" -> out += TexNode.Dots(DotsKind.AXIS)
            "vdots" -> out += TexNode.Dots(DotsKind.VERTICAL)
            "ddots" -> out += TexNode.Dots(DotsKind.DIAGONAL)
            "color" -> readRawGroup()
            "textcolor" -> { readRawGroup(); out += parseArg() }
            "tag", "label" -> { consume('*'); readRawGroup() }
            "begin" -> out += parseEnvironment(readRawGroup().trim())
            "end" -> readRawGroup()
            in IGNORED -> Unit
            else -> {
                unknown += name
                out += TexNode.Text(name)
            }
        }
    }

    private fun parseEnvironment(env: String): TexNode {
        val name = env.removeSuffix("*")
        val aligns = if (name == "array" || name == "tabular" || name == "alignat") readRawGroup().filter { it in "lcr" } else ""
        val rows = parseCells()
        if (peekCommand("end")) { pos += 4; readRawGroup() }
        val (kind, delims) = when (name) {
            "matrix", "smallmatrix" -> GridKind.MATRIX to (0 to 0)
            "pmatrix" -> GridKind.MATRIX to ('('.code to ')'.code)
            "bmatrix" -> GridKind.MATRIX to ('['.code to ']'.code)
            "Bmatrix" -> GridKind.MATRIX to ('{'.code to '}'.code)
            "vmatrix" -> GridKind.MATRIX to ('|'.code to '|'.code)
            "Vmatrix" -> GridKind.MATRIX to (DOUBLE_BAR to DOUBLE_BAR)
            "cases", "dcases" -> GridKind.CASES to ('{'.code to 0)
            "rcases" -> GridKind.CASES to (0 to '}'.code)
            "array", "tabular" -> GridKind.ARRAY to (0 to 0)
            "aligned", "align", "alignat", "eqnarray", "split", "flalign", "alignedat" -> GridKind.ALIGNED to (0 to 0)
            "gathered", "gather", "equation", "multline", "displaymath" -> GridKind.GATHERED to (0 to 0)
            else -> {
                unknown += "env:$env"
                GridKind.GATHERED to (0 to 0)
            }
        }
        if ((kind == GridKind.GATHERED) && rows.size == 1 && rows[0].size == 1) return rows[0][0]
        return TexNode.Grid(rows, kind, aligns, delims.first, delims.second)
    }

    private fun subParse(text: String): TexNode {
        val sub = TexParser(text)
        return sub.parse().also { unknown += sub.unknown }
    }

    private fun readName(): String {
        val start = pos
        while (pos < src.length && src[pos].isLetter()) pos++
        return src.substring(start, pos)
    }

    private fun peekCommand(name: String): Boolean =
        src.startsWith("\\$name", pos) && (pos + 1 + name.length >= src.length || !src[pos + 1 + name.length].isLetter())

    private fun consume(c: Char): Boolean {
        if (pos < src.length && src[pos] == c) { pos++; return true }
        return false
    }

    private fun skipSpaces() {
        while (pos < src.length && src[pos].isWhitespace()) pos++
    }

    private fun skipPast(c: Char) {
        val end = src.indexOf(c, pos)
        pos = if (end < 0) src.length else end + 1
    }

    private fun readRawGroup(): String {
        skipSpaces()
        if (pos >= src.length) return ""
        if (src[pos] != '{') {
            val cp = src.codePointAt(pos)
            pos += Character.charCount(cp)
            return String(Character.toChars(cp))
        }
        var depth = 0
        val start = pos + 1
        while (pos < src.length) {
            when (src[pos]) {
                '\\' -> pos++
                '{' -> depth++
                '}' -> if (--depth == 0) { pos++; return src.substring(start, pos - 1) }
            }
            pos++
        }
        return src.substring(start.coerceAtMost(src.length))
    }

    private fun readBracket(): String {
        pos++
        var depth = 0
        val start = pos
        while (pos < src.length) {
            when (src[pos]) {
                '{' -> depth++
                '}' -> depth--
                ']' -> if (depth == 0) { pos++; return src.substring(start, pos - 1) }
            }
            pos++
        }
        return src.substring(start)
    }

    private fun readDelim(): Int {
        skipSpaces()
        if (pos >= src.length) return 0
        if (src[pos] == '\\') {
            pos++
            if (pos < src.length && !src[pos].isLetter()) {
                return when (src[pos++]) {
                    '{' -> '{'.code
                    '}' -> '}'.code
                    '|' -> DOUBLE_BAR
                    else -> 0
                }
            }
            val name = readName()
            return SYMBOLS[name]?.first ?: 0.also { unknown += name }
        }
        val cp = src.codePointAt(pos)
        pos += Character.charCount(cp)
        return if (cp == '.'.code) 0 else cp
    }

    companion object {
        const val PRIME = 0x2032
        const val INTEGRAL = 0x222B
        const val DOUBLE_BAR = 0x2016
        const val MINUS = 0x2212

        private val BIN_CPS = setOf('+'.code, MINUS, '*'.code, 0xB1, 0x2213, 0xD7, 0xF7, 0x22C5, 0x2218, 0x222A, 0x2229, 0x2227, 0x2228)
        private val REL_CPS = setOf('='.code, '<'.code, '>'.code, ':'.code, 0x2264, 0x2265, 0x2260, 0x2261, 0x2248, 0x223C,
            0x2243, 0x221D, 0x2208, 0x2209, 0x2282, 0x2286, 0x2283, 0x2287, 0x2192, 0x2190, 0x21D2, 0x21D0, 0x21D4, 0x2194,
            0x21A6, 0x2225, 0x22A5, 0x2223, 0x2191, 0x2193)

        fun symFor(cp: Int): TexNode.Sym = when {
            cp == '-'.code || cp == 0x2013 -> TexNode.Sym(MINUS, AtomClass.BIN)
            cp in BIN_CPS -> TexNode.Sym(cp, AtomClass.BIN)
            cp in REL_CPS -> TexNode.Sym(cp, AtomClass.REL)
            cp == ','.code || cp == ';'.code -> TexNode.Sym(cp, AtomClass.PUNCT)
            cp == '('.code || cp == '['.code -> TexNode.Sym(cp, AtomClass.OPEN)
            cp == ')'.code || cp == ']'.code -> TexNode.Sym(cp, AtomClass.CLOSE)
            else -> TexNode.Sym(cp, AtomClass.ORD)
        }

        private fun ord(cp: Int) = cp to AtomClass.ORD

        private val SYMBOLS: Map<String, Pair<Int, AtomClass>> = buildMap {
            listOf("alpha" to 0x3B1, "beta" to 0x3B2, "gamma" to 0x3B3, "delta" to 0x3B4, "epsilon" to 0x3B5,
                "varepsilon" to 0x3B5, "zeta" to 0x3B6, "eta" to 0x3B7, "theta" to 0x3B8, "vartheta" to 0x3B8,
                "iota" to 0x3B9, "kappa" to 0x3BA, "lambda" to 0x3BB, "mu" to 0x3BC, "nu" to 0x3BD, "xi" to 0x3BE,
                "omicron" to 0x3BF, "pi" to 0x3C0, "varpi" to 0x3C0, "rho" to 0x3C1, "varrho" to 0x3C1,
                "sigma" to 0x3C3, "varsigma" to 0x3C3, "tau" to 0x3C4, "upsilon" to 0x3C5, "phi" to 0x3C6,
                "varphi" to 0x3C6, "chi" to 0x3C7, "psi" to 0x3C8, "omega" to 0x3C9,
                "Gamma" to 0x393, "Delta" to 0x394, "Theta" to 0x398, "Lambda" to 0x39B, "Xi" to 0x39E,
                "Pi" to 0x3A0, "Sigma" to 0x3A3, "Upsilon" to 0x3A5, "Phi" to 0x3A6, "Psi" to 0x3A8, "Omega" to 0x3A9,
                "infty" to 0x221E, "partial" to 0x2202, "nabla" to 0x2207, "forall" to 0x2200, "exists" to 0x2203,
                "emptyset" to 0x2205, "varnothing" to 0x2205, "angle" to 0x2220, "therefore" to 0x2234,
                "because" to 0x2234, "aleph" to 0x2135, "prime" to PRIME, "neg" to 0xAC, "lnot" to 0xAC,
                "degree" to 0xB0, "ell" to 'l'.code, "hbar" to 'h'.code, "imath" to 'i'.code, "jmath" to 'j'.code,
                "Re" to 'R'.code, "Im" to 'I'.code, "bot" to 0x22A5, "triangle" to 0x394, "vert" to '|'.code,
                "Vert" to DOUBLE_BAR, "backslash" to '\\'.code,
            ).forEach { (n, cp) -> put(n, ord(cp)) }
            listOf("pm" to 0xB1, "mp" to 0x2213, "times" to 0xD7, "div" to 0xF7, "cdot" to 0x22C5, "ast" to '*'.code,
                "star" to '*'.code, "circ" to 0x2218, "bullet" to 0x22C5, "cup" to 0x222A, "cap" to 0x2229,
                "setminus" to '\\'.code, "wedge" to 0x2227, "land" to 0x2227, "vee" to 0x2228, "lor" to 0x2228,
            ).forEach { (n, cp) -> put(n, cp to AtomClass.BIN) }
            listOf("leq" to 0x2264, "le" to 0x2264, "leqslant" to 0x2264, "geq" to 0x2265, "ge" to 0x2265,
                "geqslant" to 0x2265, "neq" to 0x2260, "ne" to 0x2260, "equiv" to 0x2261, "approx" to 0x2248,
                "sim" to 0x223C, "simeq" to 0x2243, "cong" to 0x2243, "propto" to 0x221D, "in" to 0x2208,
                "notin" to 0x2209, "subset" to 0x2282, "subseteq" to 0x2286, "supset" to 0x2283, "supseteq" to 0x2287,
                "to" to 0x2192, "rightarrow" to 0x2192, "longrightarrow" to 0x2192, "leftarrow" to 0x2190,
                "gets" to 0x2190, "longleftarrow" to 0x2190, "Rightarrow" to 0x21D2, "Longrightarrow" to 0x21D2,
                "implies" to 0x21D2, "Leftarrow" to 0x21D0, "Longleftarrow" to 0x21D0, "Leftrightarrow" to 0x21D4,
                "Longleftrightarrow" to 0x21D4, "iff" to 0x21D4, "leftrightarrow" to 0x2194,
                "longleftrightarrow" to 0x2194, "mapsto" to 0x21A6, "parallel" to 0x2225, "perp" to 0x22A5,
                "mid" to 0x2223, "uparrow" to 0x2191, "downarrow" to 0x2193,
            ).forEach { (n, cp) -> put(n, cp to AtomClass.REL) }
            listOf("langle" to 0x27E8, "lfloor" to 0x230A, "lceil" to 0x2308, "lbrace" to '{'.code, "lbrack" to '['.code,
                "lvert" to '|'.code, "lVert" to DOUBLE_BAR).forEach { (n, cp) -> put(n, cp to AtomClass.OPEN) }
            listOf("rangle" to 0x27E9, "rfloor" to 0x230B, "rceil" to 0x2309, "rbrace" to '}'.code, "rbrack" to ']'.code,
                "rvert" to '|'.code, "rVert" to DOUBLE_BAR).forEach { (n, cp) -> put(n, cp to AtomClass.CLOSE) }
            put("colon", ':'.code to AtomClass.PUNCT)
        }

        private val FUNCTIONS = setOf("sin", "cos", "tan", "cot", "sec", "csc", "arcsin", "arccos", "arctan", "sinh",
            "cosh", "tanh", "coth", "log", "ln", "lg", "exp", "det", "dim", "ker", "deg", "arg", "gcd", "hom", "Pr")
        private val LIMIT_FUNCTIONS = setOf("lim", "limsup", "liminf", "max", "min", "sup", "inf")
        private val BIG_SIZES = mapOf("big" to 1.2f, "Big" to 1.8f, "bigg" to 2.4f, "Bigg" to 3f).let { base ->
            base + base.flatMap { (k, v) -> listOf("${k}l" to v, "${k}r" to v, "${k}m" to v) }
        }
        private val TEXT_COMMANDS = setOf("text", "textrm", "textit", "textbf", "textsf", "texttt", "textnormal", "mbox", "hbox")
        private val FONT_COMMANDS = setOf("mathrm", "mathbf", "mathit", "mathsf", "mathtt", "mathcal", "mathbb", "mathfrak",
            "mathscr", "boldsymbol", "bm", "mathnormal", "pmb")
        private val ACCENTS = mapOf("hat" to AccentKind.HAT, "widehat" to AccentKind.WIDEHAT, "check" to AccentKind.HAT,
            "bar" to AccentKind.BAR, "overline" to AccentKind.OVERLINE, "vec" to AccentKind.VEC,
            "overrightarrow" to AccentKind.VEC, "dot" to AccentKind.DOT, "ddot" to AccentKind.DDOT,
            "tilde" to AccentKind.TILDE, "widetilde" to AccentKind.TILDE, "underline" to AccentKind.UNDERLINE)
        private val IGNORED = setOf("displaystyle", "textstyle", "scriptstyle", "scriptscriptstyle", "nonumber", "notag",
            "allowbreak", "nobreak", "relax", "mathstrut")
    }
}
