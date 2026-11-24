package io.github.hhenson.hgl.documentation

internal val docHeadings = setOf(
    "Args:", "Type Args:", "Returns:", "Raises:", "Ticks:", "Validity:",
    "Properties:", "Requires:", "Options:", "Rules:", "Status:", "Notes:", "Examples:",
)
private val entryKey = Regex("""^([A-Za-z_][A-Za-z_0-9]*):""")
internal fun String.docIndent() = length - trimStart(' ', '\t').length
internal fun String.docBlank() = all { it == ' ' || it == '\t' }

internal data class DocParagraphs(val key: String?, val lines: List<String>)

internal data class HglDocSection(
    val heading: String?, val lines: List<String>, val recognized: Boolean = heading in docHeadings,
    val entries: Map<String, String>? = null,
) {
    /** SDOC-7. Entry descriptions and the surrounding prose are separate Markdown blocks. */
    fun paragraphs(): List<DocParagraphs> {
        if (!recognized || (heading != "Args:" && heading != "Type Args:") || entries?.isEmpty() == true) {
            return listOf(DocParagraphs(null, lines))
        }
        val fences = fencedLines(lines.joinToString("\n"))
        val parts = mutableListOf<DocParagraphs>()
        var start = 0
        var i = 0
        while (i < lines.size) {
            val match = if (i in fences) null else entryKey.find(lines[i])
            val key = match?.groupValues?.get(1)
            if (key == null || (entries != null && key !in entries)) {
                i++
                continue
            }
            if (i > start) parts += DocParagraphs(null, lines.subList(start, i))
            val first = lines[i].substring(match.range.last + 1).removePrefix(" ")
            i++
            val following = i
            while (i < lines.size && (lines[i].docBlank() || lines[i].docIndent() > 0)) i++
            val description = entries?.get(key)?.lines() ?: run {
                val rest = lines.subList(following, i).dropLastWhile { it.docBlank() }
                val margin = rest.filterNot { it.docBlank() }.minOfOrNull { it.docIndent() } ?: 0
                (listOf(first) + rest.map { it.drop(minOf(it.docIndent(), margin)) }).dropWhile { it.isEmpty() }
            }
            parts += DocParagraphs(key, description)
            start = i
        }
        if (start < lines.size) parts += DocParagraphs(null, lines.subList(start, lines.size))
        return parts
    }

    fun description(name: String): HglDocSection? =
        paragraphs().firstOrNull { it.key == name && it.lines.any { line -> !line.docBlank() } }?.let { HglDocSection(null, it.lines) }
}

internal fun docSections(text: String, openingLineIsSummary: Boolean = false): List<HglDocSection> {
    val sections = mutableListOf<HglDocSection>()
    val lines = text.lines()
    val fences = fencedLines(text)
    fun headingAt(index: Int): String? {
        if (index in fences) return null
        val line = lines[index].trimEnd(' ', '\t')
        if (line in docHeadings && !(index == 0 && openingLineIsSummary)) return line
        if (!line.endsWith(':') || line.first() in " \t") return null
        var next = index + 1
        while (next < lines.size && lines[next].docBlank()) next++
        return line.takeIf { lines.getOrNull(next)?.startsWith("    ") == true }
    }
    var i = 0
    while (i < lines.size) {
        val heading = headingAt(i)
        val recognized = heading in docHeadings && !(i == 0 && openingLineIsSummary)
        if (heading != null) i++
        val body = mutableListOf<String>()
        while (i < lines.size) {
            val line = lines[i]
            if (headingAt(i) != null) break
            if (heading != null && line.isNotBlank() && !line.startsWith("    ")) break
            body += if (heading == null) line else line.removePrefix("    ")
            i++
        }
        val content = body.dropWhile { it.docBlank() }.dropLastWhile { it.docBlank() }
        if (heading != null || content.isNotEmpty()) sections += HglDocSection(heading, content, recognized)
    }
    return sections
}
