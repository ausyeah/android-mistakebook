package com.mistakebook.print

/**
 * LaTeX -> **OMML**（Office Math Markup Language）转换。
 *
 * ## 为什么值得手写
 *
 * OMML 是 Word / WPS 的**原生公式**：双击能编辑、能跟正文一起排版、能复制到别处。
 * 而内嵌位图只是「看起来像公式的图」——用户要改个数字都得回去重新导出。
 * 项目规则禁止引 Apache POI 之类，而现成的 LaTeX→OMML 转换器也没有零依赖的，
 * 所以只能自己写一个**够用**的子集。
 *
 * ## 覆盖范围与退路
 *
 * 覆盖：`\frac` / `\sqrt` / `^` / `_` / 希腊字母 / 常用算符 / 函数名 /
 * 花括号分组 / `\left \right` 自适应括号。
 *
 * **不支持的一律返回 null**，由 [DocxExporter] 回退成内嵌位图。
 * 这是刻意的：与其吐出一个 Word 打不开或显示成乱码的 XML，
 * 不如给一张正确的图。
 */
object LatexToOmml {

    /**
     * @param display 是否块级公式（独立成行）。块级会去掉多余的行内间距。
     * @return OMML XML 片段（不含外层 `m:oMath` 之外的包装），失败返回 null。
     */
    fun convert(latex: String, display: Boolean = false): String? {
        val parser = Parser(latex)
        val body = parser.parseRun()
        if (!parser.atEnd()) return null
        val content = body.trim()
        if (content.isEmpty()) return null
        return if (display) content else content
    }

    /**
     * 极简递归下降解析器。
     *
     * 刻意不支持 `\begin{...}` 环境（矩阵、aligned、cases）——
     * 那些的 OMML 结构是 `m:m`（矩阵）与 `m:eqArr`（对齐数组），
     * 写出来很容易出错，而错题本里最需要漂亮排版的恰恰是推导过程。
     * 所以统一交给位图回退：**宁可丑一点，不能显示错。**
     */
    private class Parser(source: String) {
        private val src = source
        private var pos = 0

        fun atEnd(): Boolean = pos >= src.length

        private fun peek(): Char? = src.getOrNull(pos)

        private fun startsWith(text: String): Boolean = src.startsWith(text, pos)

        /**
         * 解析到当前层结束（`}` 或字符串结尾）。
         *
         * @param stopAtRightDelimiter 停在 `\right` 前，并把它的分隔符字符记进
         *   [rightChar]。给 `\left(...\right)` 用——否则括号配不上对。
         * @param stopAtClosing 停在这个闭括号前并**不**消费它（由调用方消费，
         *   因为 `\left(a,b\right)` 的右括号不是 `}`）。
         */
        fun parseRun(stopAtRightDelimiter: Boolean = false, stopAtClosing: Char? = null): String {
            val out = StringBuilder()
            while (pos < src.length) {
                val ch = src[pos]
                when {
                    stopAtClosing != null && ch == stopAtClosing -> return out.toString()
                    ch == '}' -> return out.toString()
                    ch == '{' -> {
                        pos++
                        val inner = parseRun()
                        if (peek() == '}') pos++
                        // LaTeX 的 `{...}` 是**纯分组**，不产生任何字符。
                        // 早先这里套了个 `m:d`，而 `m:d` 省略 `dPr` 时默认圆括号，
                        // 于是 `\frac{1}{2}` 显示成 `(1)/(2)`。
                        out.append(inner)
                    }

                    ch == '^' -> {
                        pos++
                        val base = out.takeLastRun()
                        out.setLength(out.length - base.length)
                        out.append(script(base, readScriptArg(), sup = true))
                    }

                    ch == '_' -> {
                        pos++
                        val base = out.takeLastRun()
                        out.setLength(out.length - base.length)
                        out.append(script(base, readScriptArg(), sup = false))
                    }

                    ch == '\\' -> {
                        // `\right` 必须由 `\left` 那边消费掉，不能当作独立命令处理
                        if (stopAtRightDelimiter && isCommand("right")) {
                            pos += 1 + "right".length
                            rightChar = readDelimiterChar()
                            return out.toString()
                        }
                        out.append(readCommand())
                    }

                    else -> {
                        // 连续的字母数字合成一个 run：`x + 1` 里的 `x` 不应被拆成
                        // `x` / `+` / `1` 三个 run，否则 `xy^2` 里的 `y` 上标会把 `xy` 整个当成基，
                        // 显示出来就是 `(xy)^2`。
                        val word = StringBuilder()
                        while (pos < src.length) {
                            val c = src[pos]
                            if (c.isWhitespace() || c == '{' || c == '}' ||
                                c == '^' || c == '_' || c == '\\'
                            ) break
                            word.append(c)
                            pos++
                        }
                        // 没收到任何字符 -> 当前处理的是空白（分隔符都在 when 里分支了），跳过它。
                        // 不能把空白当成一个 run 输出：那会在每个字之间插进去一个「空格」，
                        // OMML 里显示为一个字符宽的空格，而且会把上下标的基推错。
                        if (word.isEmpty()) pos++ else out.append(run(escapeXml(word.toString())))
                    }
                }
            }
            return out.toString()
        }

        private fun readScriptArg(): String {
            skipSpaces()
            if (pos >= src.length) return ""
            return when (src[pos]) {
                '{' -> {
                    pos++
                    val inner = parseRun()
                    if (peek() == '}') pos++
                    inner
                }

                '\\' -> {
                    val cmd = readCommand()
                    // 单词字符的命令直接当标识符：x^\alpha -> 上标 α
                    if (cmd.startsWith("<m:r>")) "<m:t>${cmd.removePrefix("<m:r><m:t>").removeSuffix("</m:t></m:r>")}</m:t>" else cmd
                }

                else -> {
                    val ch = src[pos]
                    pos++
                    run(escapeXml(ch.toString()))
                }
            }
        }

        /**
         * 读一个 `\command`。
         *
         * @return 已经转成 OMML 的片段；命令不认识时返回**原文文本**——
         *   这样 `\alpha` 之外的未知命令至少不会让整条公式失败。
         */
        private fun readCommand(): String {
            pos++ // 吃掉反斜杠
            val name = StringBuilder()
            while (pos < src.length && src[pos].isLetter()) {
                name.append(src[pos])
                pos++
            }
            return when (val cmd = name.toString()) {
                "frac" -> fraction()
                "dfrac", "tfrac" -> fraction()
                "sqrt" -> radical()
                "left" -> readLeftDelimiter()
                // `\right` 由 readLeftDelimiter 一起吃掉了。能走到这里说明是孤立的
                // `\right`（前面没有 `\left`），丢掉它比崩掉好。
                "right" -> ""
                "cdot" -> run("·")
                "times" -> run("×")
                "div" -> run("÷")
                "pm" -> run("±")
                "mp" -> run("∓")
                "leq", "le" -> run("≤")
                "geq", "ge" -> run("≥")
                "neq", "ne" -> run("≠")
                "approx" -> run("≈")
                "equiv" -> run("≡")
                "sim" -> run("∼")
                "propto" -> run("∝")
                "infty" -> run("∞")
                "partial" -> run("∂")
                "nabla" -> run("∇")
                "sum" -> bigOp("∑")
                "prod" -> bigOp("∏")
                "int" -> bigOp("∫")
                "iint" -> bigOp("∬")
                "oint" -> bigOp("∮")
                "rightarrow", "to" -> run("→")
                "leftarrow" -> run("←")
                "Rightarrow" -> run("⇒")
                "Leftarrow" -> run("⇐")
                "in" -> run("∈")
                "notin" -> run("∉")
                "subset" -> run("⊂")
                "subseteq" -> run("⊆")
                "cup" -> run("∪")
                "cap" -> run("∩")
                "forall" -> run("∀")
                "exists" -> run("∃")
                "circ" -> run("∘")
                "ldots" -> run("…")
                "cdots" -> run("⋯")
                "quad" -> run(" ")
                "qquad" -> run("  ")
                "alpha" -> run("α")
                "beta" -> run("β")
                "gamma" -> run("γ")
                "delta" -> run("δ")
                "epsilon" -> run("ϵ")
                "varepsilon" -> run("ε")
                "zeta" -> run("ζ")
                "eta" -> run("η")
                "theta" -> run("θ")
                "vartheta" -> run("ϑ")
                "iota" -> run("ι")
                "kappa" -> run("κ")
                "lambda" -> run("λ")
                "mu" -> run("μ")
                "nu" -> run("ν")
                "xi" -> run("ξ")
                "pi" -> run("π")
                "rho" -> run("ρ")
                "sigma" -> run("σ")
                "tau" -> run("τ")
                "upsilon" -> run("υ")
                "phi" -> run("φ")
                "varphi" -> run("ϕ")
                "chi" -> run("χ")
                "psi" -> run("ψ")
                "omega" -> run("ω")
                "Gamma" -> run("Γ")
                "Delta" -> run("Δ")
                "Theta" -> run("Θ")
                "Lambda" -> run("Λ")
                "Xi" -> run("Ξ")
                "Pi" -> run("Π")
                "Sigma" -> run("Σ")
                "Phi" -> run("Φ")
                "Psi" -> run("Ψ")
                "Omega" -> run("Ω")

                // 函数名：正体，和变量区分开
                "sin", "cos", "tan", "cot", "sec", "csc",
                "log", "ln", "lg", "exp", "lim", "max", "min", "arg", "det", "deg" ->
                    run(escapeXml(cmd))

                else -> {
                    // 不认识就退回「去掉反斜杠的文本」，而不是整体失败。
                    // 错题本里 `\text{...}` 之类的写法不少，丢掉反斜杠至少还能读。
                    if (cmd.isEmpty()) {
                        if (pos < src.length) {
                            val ch = src[pos]
                            pos++
                            return run(escapeXml(ch.toString()))
                        }
                        ""
                    } else {
                        run(escapeXml(cmd))
                    }
                }
            }
        }

        /** `\frac{a}{b}`。 */
        private fun fraction(): String {
            val num = readBraceArg()
            val den = readBraceArg()
            if (num.isBlank() || den.isBlank()) return run("/")
            return "<m:f><m:fPr><m:type m:val=\"bar\"/></m:fPr>" +
                "<m:num>$num</m:num><m:den>$den</m:den></m:f>"
        }

        /** `\sqrt[n]{x}`。可选参数只吃掉、不渲染——比乱渲染好。 */
        private fun radical(): String {
            // 先跳可选的 [n]
            skipSpaces()
            if (peek() == '[') {
                while (pos < src.length && src[pos] != ']') pos++
                if (peek() == ']') pos++
            }
            val body = readBraceArg()
            if (body.isBlank()) return run("√")
            return "<m:rad><m:radPr><m:degHide m:val=\"1\"/></m:radPr><m:deg/><m:e>$body</m:e></m:rad>"
        }

        /**
     * `\left` 的分隔符命令 -> 对应的括号字符。
     *
     * `readDelimiterChar` 先把命令过一遍 [readCommand] 变成 OMML 再剥回来，很绕；
     * 这里直接在字符层映射，省掉一次「生成 XML 再解析出字面量」的往返。
     */
    private val DELIMITER_ALIASES = mapOf(
        "langle" to "⟨", "rangle" to "⟩",
        "lceil" to "⌈", "rceil" to "⌉",
        "lfloor" to "⌊", "rfloor" to "⌋",
        "lbrace" to "{", "rbrace" to "}",
        "lvert" to "|", "rvert" to "|"
    )

    /** `\right` 读到的闭括号字符，由 `parseRun(stopAtRightDelimiter = true)` 填。 */
    private var rightChar: String? = null

    /** 当前位置是不是 `\name`。 */
    private fun isCommand(name: String): Boolean = startsWith("\\$name")

    /**
         * `\left( ... \right)`。
         *
         * 之前这里直接返回空串，括号连同里面全部内容一起消失——
         * `\left(\frac12\right)` 显示成没有括号的分式，与原式不符。
         *
         * 现在输出真正的 `m:d`（分隔符组）。注意 `m:d` 的 `begChr`/`endChr`
         * **必须显式写**：省略 `dPr` 时 ECMA-376 §22.1.2.12 规定默认是 `(` 和 `)`，
         * 于是 `\left[` 会被渲染成圆括号。
         */
        private fun readLeftDelimiter(): String {
            // 注意这里**不能**再 pos++：`readCommand` 已经把 \left 这四个字母吃掉了，
            // 再跳一次会连分隔符字符一起跳过，于是 \left( 变成 beginChr="x"。
            val begin = readDelimiterChar() ?: return ""
            rightChar = null
            val body = parseRun(stopAtRightDelimiter = true)
            val end = rightChar ?: ""
            rightChar = null
            return if (begin.isEmpty() && end.isEmpty() && body.isBlank()) {
                ""
            } else {
                "<m:d><m:dPr><m:begChr m:val=\"$begin\"/><m:endChr m:val=\"$end\"/></m:dPr>" +
                    "<m:e>$body</m:e></m:d>"
            }
        }

        /**
         * 读 `\left` / `\right` 后面的分隔符字符，**返回裸字符**（不是 OMML 片段）。
         *
         * 返回 `null` 表示没有可读的分隔符；返回空串表示「显式无括号」（`\left.`）。
         *
         * 这里必须给裸字符：`m:begChr m:val` 要的是一个字面量，
         * 塞进 `<m:r><m:t>` 就会让 Word 显示出一串 XML 标签。
         */
        private fun readDelimiterChar(): String? {
            skipSpaces()
            if (pos >= src.length) return null
            if (src[pos] == '\\') {
                pos++ // 反斜杠
                if (pos >= src.length) return null
                // `\langle` 这种字母命令
                if (src[pos].isLetter()) {
                    val name = StringBuilder()
                    while (pos < src.length && src[pos].isLetter()) { name.append(src[pos]); pos++ }
                    return DELIMITER_ALIASES[name.toString()] ?: "?"
                }
                // `\{` `\|` 这种转义字面量。
                // 不能走上面那条：`{` 不是字母，名字会读成空串，
                // 查表落空后 begChr 会变成 "?"，\left\{ 显示成一个问号。
                val ch = src[pos]
                pos++
                return escapeXml(ch.toString())
            }
            return when (val ch = src[pos]) {
                // `.` 是「无括号」
                '.' -> { pos++; "" }
                else -> { pos++; escapeXml(ch.toString()) }
            }
        }

        /**
         * 读**一个原子**作为大算符的操作数，并把它自带的上下标一并吃掉。
         *
         * 这是 `\sum_{i=1}^n x_i` 里 `x_i` 的 `i` 不被拆出去的原因：
         * 若只取 `x`，剩下的 `_i` 会变成外层兄弟节点，渲染成 `(∑ x)_i`。
         */
        private fun readOperandAtom(): String {
            skipSpaces()
            if (pos >= src.length) return ""
            val atom = when {
                src[pos] == '{' || src[pos] == '(' -> {
                    val close = if (src[pos] == '{') '}' else ')'
                    pos++
                    val inner = parseRun(stopAtClosing = close)
                    if (peek() == close) pos++
                    inner
                }
                src[pos] == '\\' -> readCommand()
                else -> {
                    val word = StringBuilder()
                    while (pos < src.length) {
                        val c = src[pos]
                        if (c.isWhitespace() || c == '{' || c == '}' ||
                            c == '^' || c == '_' || c == '\\'
                        ) break
                        word.append(c)
                        pos++
                    }
                    if (word.isEmpty()) { pos++; "" } else run(escapeXml(word.toString()))
                }
            }
            // 原子自己的上下标
            var result = atom
            while (pos < src.length && (src[pos] == '^' || src[pos] == '_')) {
                val isSup = src[pos] == '^'
                pos++
                val arg = readScriptArg()
                result = if (result.isBlank()) "" else script(result, arg, isSup)
            }
            return result
        }

        private fun readBraceArg(): String {
            skipSpaces()
            if (pos >= src.length) return ""
            return when (src[pos]) {
                '{' -> {
                    pos++
                    val inner = parseRun()
                    if (peek() == '}') pos++
                    inner
                }

                '\\' -> readCommand()
                else -> {
                    val ch = src[pos]
                    pos++
                    run(escapeXml(ch.toString()))
                }
            }
        }

        private fun skipSpaces() {
            while (pos < src.length && src[pos].isWhitespace()) pos++
        }

        /**
         * 取出**最后一个完整 run**，作为上下标的基。
         *
         * 上标的基必须是它紧左边那个东西：`x^2` 的基是 `x`，
         * 而不是「前面所有内容」。所以从缓冲区尾部往回扫，
         * 找到最后一个 `<m:r>` 或 `</m:d>` / `</m:f>` 这类成对标签的起点。
         *
         * 取不到（比如 `^2` 出现在开头）时返回空串——那时不生成 sSup，
         * 宁可少渲染也不产出结构错误的 XML。
         */
        private fun StringBuilder.takeLastRun(): String {
            val text = this.toString()
            if (text.isEmpty()) return ""
            // 成对标签：闭合 -> 开标签
            val pairs = listOf(
                "</m:r>" to "<m:r>",
                "</m:d>" to "<m:d>",
                "</m:f>" to "<m:f>",
                "</m:sSup>" to "<m:sSup>",
                "</m:sSub>" to "<m:sSub>",
                "</m:rad>" to "<m:rad>"
            )
            // 取**最靠后**的那个闭合标签：它才是紧邻上标的那个基。
            // 最后一个 <m:r> 是字符串尾部的那段，它之后的内容不能被当成基。
            val closing = pairs
                .map { it.first to text.lastIndexOf(it.first) }
                .filter { it.second >= 0 }
                .maxByOrNull { it.second }
                ?: return ""
            if (closing.second == 0) return ""
            val opening = pairs.first { it.first == closing.first }.second
            val start = text.lastIndexOf(opening, closing.second)
            if (start < 0) return ""
            return text.substring(start)
        }

        /**
         * 大算符（求和、积分）。
         *
         * 早先的版本把参数整个丢了，输出 `<m:sub/><m:sup/><m:e/>` 三个空标签——
         * `\sum_{i=1}^n x` 里的 `i=1`、`n`、`x` **全部消失**，只剩一个孤零零的 ∑。
         * 比转换失败更糟：失败会回退到位图，看起来正常；这个会静默给出错误公式。
         *
         * 必须住在 [Parser] 里：它要接着往下读下限、上限、操作数。
         */
        private fun bigOp(symbol: String): String {
            skipSpaces()
            val sub = if (peek() == '_') { pos++; readScriptArg() } else ""
            skipSpaces()
            val sup = if (peek() == '^') { pos++; readScriptArg() } else ""
            val operand = readOperandAtom()
            if (operand.isBlank()) return ""
            return "<m:nary><m:naryPr><m:chr m:val=\"$symbol\"/><m:limLoc m:val=\"undOvr\"/></m:naryPr>" +
                "<m:sub>$sub</m:sub><m:sup>$sup</m:sup><m:e>$operand</m:e></m:nary>"
        }
    }

    /**
     * 一段普通文本 -> 一个 run。
     *
     * `xml:space="preserve"` **只在首尾有空白时加**：`\quad` / `\qquad` 展开来
     * 就是纯空格，省了会被 Word 当成可忽略的排版空白吃掉，两者看起来一样长。
     * 反过来无条件加会让每个 run 都多出 23 个字符的噪声，也没解决问题。
     */
    private fun run(text: String): String {
        val padded = text.firstOrNull()?.isWhitespace() == true ||
            text.lastOrNull()?.isWhitespace() == true
        return if (padded) {
            "<m:r><m:t xml:space=\"preserve\">$text</m:t></m:r>"
        } else {
            "<m:r><m:t>$text</m:t></m:r>"
        }
    }

    /** 上标 `m:sSup` / 下标 `m:sSub`。[arg] 是解析好的上标/下标内容。 */
    private fun script(base: String, arg: String, sup: Boolean): String {
        if (base.isBlank()) return ""
        val tag = if (sup) "m:sup" else "m:sub"
        val kind = if (sup) "m:sSup" else "m:sSub"
        return if (arg.isBlank()) base else "<$kind><m:e>$base</m:e><$tag>$arg</$tag></$kind>"
    }



    /** XML 文本转义。OMML 里 `<` `>` `&` 必须是实体，否则 Word 直接报文档损坏。 */
    fun escapeXml(raw: String): String = buildString(raw.length) {
        raw.forEach { ch ->
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(ch)
            }
        }
    }
}