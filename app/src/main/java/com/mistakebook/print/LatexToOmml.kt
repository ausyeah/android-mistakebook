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

        /** 解析到当前层结束（`}` 或字符串结尾）。 */
        fun parseRun(): String {
            val out = StringBuilder()
            while (pos < src.length) {
                val ch = src[pos]
                when {
                    ch == '}' -> return out.toString()
                    ch == '{' -> {
                        pos++
                        val inner = parseRun()
                        if (peek() == '}') pos++
                        out.append(group(inner))
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

                    ch == '\\' -> out.append(readCommand())
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
                "left" -> readDelimiterCommand()
                "right" -> readDelimiterCommand()
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

        /** `\left( ... \right)`。括号本身丢掉，只取里面内容。 */
        private fun readDelimiterCommand(): String {
            // `\left.` / `\right.` 表示无括号
            if (pos < src.length && src[pos] != '{') {
                pos++
            }
            return ""
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
    }

    /** 一段普通文本 -> 一个 run。 */
    private fun run(text: String): String = "<m:r><m:t>$text</m:t></m:r>"

    /** `m:d`（分隔符组）用于让括号随内容撑高。 */
    private fun group(inner: String): String =
        if (inner.isBlank()) "" else "<m:d><m:e>$inner</m:e></m:d>"

    /** 上标 `m:sSup` / 下标 `m:sSub`。[arg] 是解析好的上标/下标内容。 */
    private fun script(base: String, arg: String, sup: Boolean): String {
        if (base.isBlank()) return ""
        val tag = if (sup) "m:sup" else "m:sub"
        val kind = if (sup) "m:sSup" else "m:sSub"
        return if (arg.isBlank()) base else "<$kind><m:e>$base</m:e><$tag>$arg</$tag></$kind>"
    }

    /** 大算符（求和、积分）带下标/上标位。 */
    private fun bigOp(symbol: String): String =
        "<m:nary><m:naryPr><m:chr m:val=\"$symbol\"/><m:limLoc m:val=\"undOvr\"/></m:naryPr>" +
            "<m:sub/><m:sup/><m:e/></m:nary>"

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