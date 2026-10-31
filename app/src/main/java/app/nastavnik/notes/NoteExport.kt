package app.nastavnik.notes

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Чистовик в других форматах: Markdown (Obsidian, Notion), LaTeX (Overleaf) и Word (.docx).
 * Формулы в Markdown и LaTeX остаются формулами; в Word — текстом LaTeX, набранным как формула.
 */
object NoteExport {

    fun markdown(note: CleanNote): String = buildString {
        if (note.title.isNotBlank()) append("# ").append(note.title.trim()).append("\n\n")
        val meta = listOfNotNull(note.subject.takeIf { it.isNotBlank() }, note.date?.takeIf { it.isNotBlank() })
        if (meta.isNotEmpty()) append("*").append(meta.joinToString(" · ")).append("*\n\n")
        for (b in note.blocks) {
            when (b.type) {
                "h" -> append("## ").append(b.text.orEmpty().trim()).append("\n\n")
                "def", "thm" -> {
                    b.title?.takeIf { it.isNotBlank() }?.let { append("> **").append(it.trim()).append("**\n>\n") }
                    b.text?.takeIf { it.isNotBlank() }?.let { append(quote(it.trim())).append('\n') }
                    append('\n')
                }
                "formula" -> append("$$\n").append(b.tex.orEmpty().trim()).append("\n$$\n\n")
                "steps" -> {
                    b.title?.takeIf { it.isNotBlank() }?.let { append("**").append(it.trim()).append("**\n\n") }
                    b.items.orEmpty().forEachIndexed { i, s -> append(i + 1).append(". ").append(s.trim()).append('\n') }
                    append('\n')
                }
                "table" -> {
                    val head = b.head.orEmpty()
                    val rows = b.rows.orEmpty()
                    val cols = maxOf(head.size, rows.maxOfOrNull { it.size } ?: 0)
                    if (cols > 0) {
                        append("| ").append((0 until cols).joinToString(" | ") { cell(head.getOrNull(it)) }).append(" |\n")
                        append("|").append((0 until cols).joinToString("|") { " --- " }).append("|\n")
                        rows.forEach { r -> append("| ").append((0 until cols).joinToString(" | ") { cell(r.getOrNull(it)) }).append(" |\n") }
                        append('\n')
                    }
                }
                "plot" -> {
                    val fns = b.functions.orEmpty().joinToString(", ") { it.label ?: "y = ${it.expr}" }
                    append("*График").append(b.title?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "")
                    if (fns.isNotBlank()) append(" (").append(fns).append(")")
                    append("*\n\n")
                }
                "note" -> append(quote("⚠ " + b.text.orEmpty().trim())).append("\n\n")
                else -> b.text?.takeIf { it.isNotBlank() }?.let { append(it.trim()).append("\n\n") }
            }
        }
        if (note.summary.isNotEmpty()) {
            append("## Главное\n\n")
            note.summary.forEach { append("- ").append(it.trim()).append('\n') }
            append('\n')
        }
        if (note.questions.isNotEmpty()) {
            append("## Вопросы для самопроверки\n\n")
            note.questions.forEachIndexed { i, q -> append(i + 1).append(". ").append(q.trim()).append('\n') }
            append('\n')
        }
    }.trimEnd() + "\n"

    fun latex(note: CleanNote): String = buildString {
        append(
            """\documentclass[12pt,a4paper]{article}
\usepackage[T2A]{fontenc}
\usepackage[utf8]{inputenc}
\usepackage[russian]{babel}
\usepackage{amsmath,amssymb,amsthm}
\usepackage[margin=2cm]{geometry}
\newtheorem*{definition}{Определение}
\newtheorem*{theorem}{Теорема}
""",
        )
        append("\\title{").append(texText(note.title)).append("}\n")
        append("\\author{").append(texText(note.subject)).append("}\n")
        append("\\date{").append(texText(note.date.orEmpty())).append("}\n")
        append("\\begin{document}\n\\maketitle\n\n")
        for (b in note.blocks) {
            when (b.type) {
                "h" -> append("\\section*{").append(texText(b.text.orEmpty())).append("}\n\n")
                "def", "thm" -> {
                    val env = if (b.type == "def") "definition" else "theorem"
                    val name = b.title.orEmpty().substringAfter(':', "").trim()
                    append("\\begin{").append(env).append("}")
                    if (name.isNotEmpty()) append("[").append(texText(name)).append("]")
                    append("\n").append(texText(b.text.orEmpty())).append("\n\\end{").append(env).append("}\n\n")
                }
                "formula" -> append("\\[\n").append(b.tex.orEmpty().trim()).append("\n\\]\n\n")
                "steps" -> {
                    b.title?.takeIf { it.isNotBlank() }?.let { append("\\paragraph{").append(texText(it)).append("}\n") }
                    append("\\begin{enumerate}\n")
                    b.items.orEmpty().forEach { append("  \\item ").append(texText(it)).append('\n') }
                    append("\\end{enumerate}\n\n")
                }
                "table" -> {
                    val head = b.head.orEmpty()
                    val rows = b.rows.orEmpty()
                    val cols = maxOf(head.size, rows.maxOfOrNull { it.size } ?: 0)
                    if (cols > 0) {
                        append("\\begin{center}\n\\begin{tabular}{|").append("c|".repeat(cols)).append("}\n\\hline\n")
                        if (head.isNotEmpty()) append((0 until cols).joinToString(" & ") { texText(head.getOrNull(it).orEmpty()) }).append(" \\\\ \\hline\n")
                        rows.forEach { r -> append((0 until cols).joinToString(" & ") { texText(r.getOrNull(it).orEmpty()) }).append(" \\\\ \\hline\n") }
                        append("\\end{tabular}\n\\end{center}\n\n")
                    }
                }
                "plot" -> append("% График: ").append(b.functions.orEmpty().joinToString(", ") { it.expr }).append("\n\n")
                "note" -> append("\\noindent\\fbox{\\parbox{\\linewidth}{").append(texText(b.text.orEmpty())).append("}}\n\n")
                else -> b.text?.takeIf { it.isNotBlank() }?.let { append(texText(it)).append("\n\n") }
            }
        }
        if (note.summary.isNotEmpty()) {
            append("\\section*{Главное}\n\\begin{itemize}\n")
            note.summary.forEach { append("  \\item ").append(texText(it)).append('\n') }
            append("\\end{itemize}\n\n")
        }
        if (note.questions.isNotEmpty()) {
            append("\\section*{Вопросы для самопроверки}\n\\begin{enumerate}\n")
            note.questions.forEach { append("  \\item ").append(texText(it)).append('\n') }
            append("\\end{enumerate}\n\n")
        }
        append("\\end{document}\n")
    }

    /**
     * Текст с формулами `$…$` для LaTeX: формулы оставляем как есть, в обычном тексте
     * экранируем спецсимволы (%, &, #, _, {, }), чтобы документ собрался.
     */
    fun texText(s: String): String {
        val out = StringBuilder()
        var math = false
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '$') {
                math = !math
                out.append(c)
            } else if (math) {
                out.append(c)
            } else {
                when (c) {
                    '%', '&', '#', '_', '{', '}' -> out.append('\\').append(c)
                    '\\' -> out.append("\\textbackslash{}")
                    '~' -> out.append("\\textasciitilde{}")
                    '^' -> out.append("\\textasciicircum{}")
                    else -> out.append(c)
                }
            }
            i++
        }
        return out.toString().trim()
    }

    /** Простой .docx: заголовки, абзацы, списки и таблицы; формулы — курсивом в Cambria Math. */
    fun docx(note: CleanNote): ByteArray {
        val body = StringBuilder()
        fun para(text: String, style: String? = null, bold: Boolean = false) {
            body.append("<w:p>")
            if (style != null) body.append("<w:pPr><w:pStyle w:val=\"").append(style).append("\"/></w:pPr>")
            runs(body, text, bold)
            body.append("</w:p>")
        }
        if (note.title.isNotBlank()) para(note.title.trim(), "Title")
        val meta = listOfNotNull(note.subject.takeIf { it.isNotBlank() }, note.date?.takeIf { it.isNotBlank() })
        if (meta.isNotEmpty()) para(meta.joinToString(" · "), "Subtitle")
        for (b in note.blocks) {
            when (b.type) {
                "h" -> para(b.text.orEmpty(), "Heading1")
                "def", "thm" -> {
                    b.title?.takeIf { it.isNotBlank() }?.let { para(it, bold = true) }
                    b.text?.takeIf { it.isNotBlank() }?.let { para(it, "Quote") }
                }
                "formula" -> para("$" + b.tex.orEmpty().trim() + "$")
                "steps" -> {
                    b.title?.takeIf { it.isNotBlank() }?.let { para(it, bold = true) }
                    b.items.orEmpty().forEachIndexed { i, s -> para("${i + 1}. $s") }
                }
                "table" -> {
                    val all = listOfNotNull(b.head) + b.rows.orEmpty()
                    if (all.isNotEmpty()) {
                        body.append("<w:tbl><w:tblPr><w:tblStyle w:val=\"TableGrid\"/><w:tblW w:w=\"0\" w:type=\"auto\"/></w:tblPr>")
                        all.forEachIndexed { ri, r ->
                            body.append("<w:tr>")
                            r.forEach { c ->
                                body.append("<w:tc><w:p>")
                                runs(body, c, bold = ri == 0 && b.head != null)
                                body.append("</w:p></w:tc>")
                            }
                            body.append("</w:tr>")
                        }
                        body.append("</w:tbl>")
                        para("")
                    }
                }
                "plot" -> para("График: " + b.functions.orEmpty().joinToString(", ") { it.label ?: "y = ${it.expr}" })
                "note" -> para("⚠ " + b.text.orEmpty(), "IntenseQuote")
                else -> b.text?.takeIf { it.isNotBlank() }?.let { para(it) }
            }
        }
        if (note.summary.isNotEmpty()) {
            para("Главное", "Heading1")
            note.summary.forEach { para("• $it") }
        }
        if (note.questions.isNotEmpty()) {
            para("Вопросы для самопроверки", "Heading1")
            note.questions.forEachIndexed { i, q -> para("${i + 1}. $q") }
        }
        val document = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>$body<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1134" w:right="1134" w:bottom="1134" w:left="1134" w:header="709" w:footer="709" w:gutter="0"/></w:sectPr></w:body></w:document>"""
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun put(name: String, text: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            put("[Content_Types].xml", CONTENT_TYPES)
            put("_rels/.rels", RELS)
            put("word/_rels/document.xml.rels", DOC_RELS)
            put("word/styles.xml", STYLES)
            put("word/document.xml", document)
        }
        return out.toByteArray()
    }

    /** Текст с `$…$`: обычные куски — обычным шрифтом, формулы — курсивом Cambria Math. */
    private fun runs(sb: StringBuilder, text: String, bold: Boolean) {
        text.split('$').forEachIndexed { i, part ->
            if (part.isEmpty()) return@forEachIndexed
            val math = i % 2 == 1
            sb.append("<w:r>")
            if (math || bold) {
                sb.append("<w:rPr>")
                if (math) sb.append("<w:rFonts w:ascii=\"Cambria Math\" w:hAnsi=\"Cambria Math\"/><w:i/>")
                if (bold) sb.append("<w:b/>")
                sb.append("</w:rPr>")
            }
            sb.append("<w:t xml:space=\"preserve\">").append(xml(if (math) prettyTex(part) else part)).append("</w:t></w:r>")
        }
    }

    /** Формула LaTeX покороче для Word: \cdot → ·, \le → ≤ и т. п. */
    fun prettyTex(tex: String): String {
        var s = tex
        for ((k, v) in TEX_SYMBOLS) s = s.replace(Regex("\\\\$k(?![A-Za-z])"), Regex.escapeReplacement(v))
        return s.replace(Regex("\\\\frac\\{([^{}]*)\\}\\{([^{}]*)\\}"), "($1)/($2)")
            .replace(Regex("\\\\sqrt\\{([^{}]*)\\}"), "√($1)")
            .replace("\\left", "").replace("\\right", "")
            .replace(Regex("\\\\(,|;|!|quad|qquad)"), " ")
    }

    fun xml(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun quote(s: String) = s.lines().joinToString("\n") { "> $it" }
    private fun cell(s: String?) = s.orEmpty().replace("|", "\\|").replace("\n", " ")

    private val TEX_SYMBOLS = listOf(
        "cdot" to "·", "times" to "×", "leq" to "≤", "le" to "≤", "geq" to "≥", "ge" to "≥", "neq" to "≠", "ne" to "≠",
        "pm" to "±", "infty" to "∞", "to" to "→", "Rightarrow" to "⇒", "Leftrightarrow" to "⇔", "approx" to "≈",
        "alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ", "Delta" to "Δ", "varepsilon" to "ε", "epsilon" to "ε",
        "lambda" to "λ", "mu" to "μ", "pi" to "π", "sigma" to "σ", "varphi" to "φ", "phi" to "φ", "omega" to "ω",
        "sum" to "∑", "int" to "∫", "lim" to "lim", "in" to "∈", "forall" to "∀", "exists" to "∃", "sin" to "sin", "cos" to "cos",
        "ln" to "ln", "log" to "log", "partial" to "∂", "nabla" to "∇",
    )

    private const val CONTENT_TYPES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/><Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/></Types>"""

    private const val RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>"""

    private const val DOC_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>"""

    private const val STYLES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii="Calibri" w:hAnsi="Calibri" w:cs="Calibri"/><w:sz w:val="24"/><w:lang w:val="ru-RU"/></w:rPr></w:rPrDefault><w:pPrDefault><w:pPr><w:spacing w:after="120" w:line="276" w:lineRule="auto"/></w:pPr></w:pPrDefault></w:docDefaults><w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/></w:style><w:style w:type="paragraph" w:styleId="Title"><w:name w:val="Title"/><w:basedOn w:val="Normal"/><w:rPr><w:b/><w:sz w:val="44"/></w:rPr></w:style><w:style w:type="paragraph" w:styleId="Subtitle"><w:name w:val="Subtitle"/><w:basedOn w:val="Normal"/><w:rPr><w:color w:val="5E5D66"/></w:rPr></w:style><w:style w:type="paragraph" w:styleId="Heading1"><w:name w:val="heading 1"/><w:basedOn w:val="Normal"/><w:pPr><w:spacing w:before="240"/><w:outlineLvl w:val="0"/></w:pPr><w:rPr><w:b/><w:sz w:val="32"/></w:rPr></w:style><w:style w:type="paragraph" w:styleId="Quote"><w:name w:val="Quote"/><w:basedOn w:val="Normal"/><w:pPr><w:ind w:left="567"/><w:pBdr><w:left w:val="single" w:sz="18" w:space="8" w:color="2C2C33"/></w:pBdr></w:pPr></w:style><w:style w:type="paragraph" w:styleId="IntenseQuote"><w:name w:val="Intense Quote"/><w:basedOn w:val="Normal"/><w:pPr><w:ind w:left="567"/><w:pBdr><w:left w:val="single" w:sz="18" w:space="8" w:color="D9822B"/></w:pBdr></w:pPr></w:style><w:style w:type="table" w:styleId="TableGrid"><w:name w:val="Table Grid"/><w:tblPr><w:tblBorders><w:top w:val="single" w:sz="4" w:color="BBBBBB"/><w:left w:val="single" w:sz="4" w:color="BBBBBB"/><w:bottom w:val="single" w:sz="4" w:color="BBBBBB"/><w:right w:val="single" w:sz="4" w:color="BBBBBB"/><w:insideH w:val="single" w:sz="4" w:color="BBBBBB"/><w:insideV w:val="single" w:sz="4" w:color="BBBBBB"/></w:tblBorders></w:tblPr></w:style></w:styles>"""
}

/** Шпаргалка: все определения, теоремы и формулы из чистовиков — по предметам и темам, одним листом. */
object CheatSheet {
    private val KEEP = setOf("def", "thm", "formula")

    fun build(notes: List<CleanNote>, date: String? = null, subject: String? = null): CleanNote? {
        val chosen = notes.filter { subject == null || it.subject.equals(subject, ignoreCase = true) }
        val blocks = ArrayList<NoteBlock>()
        val bySubject = chosen.groupBy { it.subject.trim().ifBlank { "Без предмета" } }
        for ((subj, list) in bySubject) {
            val topics = list.mapNotNull { n ->
                val keep = n.blocks.filter { it.type in KEEP && hasContent(it) }
                if (keep.isEmpty()) null else n.title.trim() to keep
            }
            if (topics.isEmpty()) continue
            if (subject == null && bySubject.size > 1) blocks += NoteBlock(type = "h", text = subj)
            val seen = HashSet<String>()
            for ((title, keep) in topics) {
                val fresh = keep.filter { seen.add(key(it)) }
                if (fresh.isEmpty()) continue
                blocks += NoteBlock(type = if (subject == null && bySubject.size > 1) "p" else "h", text = if (subject == null && bySubject.size > 1) "**$title**" else title)
                blocks += fresh
            }
        }
        if (blocks.isEmpty()) return null
        return CleanNote(
            subject = subject ?: bySubject.keys.singleOrNull().orEmpty(),
            title = "Шпаргалка" + (subject?.let { ": $it" } ?: ""),
            blocks = blocks,
            date = date,
        )
    }

    private fun hasContent(b: NoteBlock) = !b.text.isNullOrBlank() || !b.tex.isNullOrBlank()

    /** Одна и та же формула из разных конспектов попадает в шпаргалку один раз. */
    private fun key(b: NoteBlock) = (b.type + "|" + (b.tex ?: b.text).orEmpty()).replace(Regex("\\s+"), "").lowercase()
}
