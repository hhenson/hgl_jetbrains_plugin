package io.github.hhenson.hgl.documentation

import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.AbstractVisitor
import org.commonmark.node.Code
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.Image
import org.commonmark.node.Paragraph
import org.commonmark.node.Link
import org.commonmark.node.Text
import org.commonmark.parser.IncludeSourceSpans
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.DefaultUrlSanitizer
import org.commonmark.renderer.html.HtmlRenderer

private val extensions = listOf(TablesExtension.create())
private val parser = Parser.builder().extensions(extensions).includeSourceSpans(IncludeSourceSpans.BLOCKS).build()
private val renderer = HtmlRenderer.builder().extensions(extensions).escapeHtml(true).sanitizeUrls(true)
    .urlSanitizer(DefaultUrlSanitizer(listOf("http", "https", "mailto", "hgl-doc"))).build()

internal fun fencedLines(text: String): Set<Int> = buildSet {
    parser.parse(text).accept(object : AbstractVisitor() {
        override fun visit(fence: FencedCodeBlock) { fence.sourceSpans.forEach { add(it.lineIndex) } }
    })
}

internal const val SYMBOL_LINK = "hgl-doc:"
private const val IDENTIFIER = "[A-Za-z_][A-Za-z_0-9]*"
internal val docName = Regex("""(?:$IDENTIFIER::)?$IDENTIFIER(?:\.$IDENTIFIER)*""")

/** CommonMark with tables. Display fenced math and Mermaid as source (SDOC-7). */
private fun markdown(text: String, inExamples: Boolean, hasTarget: (String) -> Boolean): String {
    val document = parser.parse(text)
    document.accept(object : AbstractVisitor() {
        override fun visit(fence: FencedCodeBlock) {
            val label = when (fence.info?.trim()) {
                "hgl" -> if (inExamples) "Runnable example" else "Shown example"
                "hgl no-run" -> "Shown example"
                else -> return
            }
            fence.insertBefore(Paragraph().apply { appendChild(Text(label)) })
        }

        override fun visit(code: Code) {
            val name = code.literal
            if (code.parent is Link || !docName.matches(name)) return
            if ((code.previous as? Text)?.literal?.endsWith('$') == true &&
                (code.next as? Text)?.literal?.startsWith('$') == true) return
            if (hasTarget(name)) {
                val link = Link(SYMBOL_LINK + name, null)
                code.insertBefore(link)
                link.appendChild(code)
            }
        }

        override fun visit(image: Image) {
            val alt = StringBuilder()
            image.accept(object : AbstractVisitor() {
                override fun visit(text: Text) { alt.append(text.literal) }
                override fun visit(code: Code) { alt.append(code.literal) }
            })
            image.insertBefore(Text(alt.toString()))
            image.unlink()
        }
    })
    return renderer.render(document)
}

internal fun docHtml(signature: String, sections: List<HglDocSection>, brief: Boolean, hasTarget: (String) -> Boolean): String =
    buildString {
        append("<div class='definition'><pre>").append(escapeDoc(signature)).append("</pre></div>")
        if (brief) {
            val summary = sections.firstOrNull()?.takeIf { it.heading == null }?.lines
                ?.takeWhile { it.isNotBlank() }?.joinToString("\n")
            if (!summary.isNullOrEmpty()) append("<div class='content'>").append(markdown(summary, false, hasTarget)).append("</div>")
        } else if (sections.isNotEmpty()) {
            append("<div class='content'>")
            for (section in sections) {
                val heading = section.heading
                if (heading != null) {
                    if (section.recognized) append("<h4>").append(escapeDoc(heading)).append("</h4>")
                    else append(markdown(heading, false, hasTarget))
                }
                val inExamples = section.recognized && heading == "Examples:"
                for ((key, lines) in section.paragraphs()) {
                    if (key != null) append("<p><code>").append(escapeDoc(key)).append("</code></p>")
                    if (lines.isNotEmpty()) append(markdown(lines.joinToString("\n"), inExamples, hasTarget))
                }
            }
            append("</div>")
        }
    }

internal fun escapeDoc(text: String): String = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    .replace("\"", "&quot;").replace("'", "&#39;")
