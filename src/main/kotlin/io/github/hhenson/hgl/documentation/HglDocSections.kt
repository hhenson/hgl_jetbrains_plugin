package io.github.hhenson.hgl.documentation

internal val docHeadings = setOf(
    "Args:", "Type Args:", "Returns:", "Raises:", "Ticks:", "Validity:",
    "Properties:", "Requires:", "Options:", "Rules:", "Status:", "Notes:", "Examples:",
)

/** SDOC-3. Only a known heading at column zero introduces four-space-indented content. */
internal data class HglDocSection(val heading: String?, val lines: List<String>, val recognized: Boolean = heading in docHeadings) {
    fun description(name: String): HglDocSection? {
        val fences = fencedLines(lines.joinToString("\n"))
        val index = lines.indices.firstOrNull { it !in fences && lines[it].startsWith("$name:") } ?: return null
        val first = lines[index].substringAfter(':').trimStart(' ', '\t')
        val rest = lines.drop(index + 1).takeWhile { it.isEmpty() || it.first() in " \t" }
        val description = (listOf(first) + rest).dropWhile { it.isBlank() }.dropLastWhile { it.isBlank() }
        return if (description.isEmpty()) null else HglDocSection(null, description)
    }
}

internal fun docSections(text: String): List<HglDocSection> {
    val sections = mutableListOf<HglDocSection>()
    val lines = text.lines()
    val fences = fencedLines(text)
    fun headingAt(index: Int): String? {
        if (index in fences) return null
        val line = lines[index].trimEnd(' ', '\t')
        if (line in docHeadings) return line
        return line.takeIf { it.endsWith(':') && !it.startsWith(' ') && !it.startsWith('\t') &&
            lines.getOrNull(index + 1)?.startsWith("    ") == true }
    }
    var i = 0
    while (i < lines.size) {
        val heading = headingAt(i)
        if (heading != null) i++
        val body = mutableListOf<String>()
        while (i < lines.size) {
            val line = lines[i]
            if (headingAt(i) != null) break
            if (heading != null && line.isNotBlank() && !line.startsWith("    ")) break
            body += if (heading == null) line else line.removePrefix("    ")
            i++
        }
        val content = body.dropLastWhile { it.isEmpty() }
        if (heading != null || content.isNotEmpty()) sections += HglDocSection(heading, content)
    }
    return sections
}
