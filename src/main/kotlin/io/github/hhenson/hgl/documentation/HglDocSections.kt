package io.github.hhenson.hgl.documentation

private val headings = setOf(
    "Args:", "Type Args:", "Returns:", "Raises:", "Ticks:", "Validity:",
    "Properties:", "Requires:", "Notes:", "Examples:",
)

/** SDOC-3. Only a known heading at column zero introduces four-space-indented content. */
internal data class HglDocSection(val heading: String?, val lines: List<String>) {
    fun description(name: String): HglDocSection? {
        val index = lines.indexOfFirst { it.startsWith("$name:") }
        if (index < 0) return null
        val first = lines[index].substringAfter(':').trimStart(' ', '\t')
        val rest = lines.drop(index + 1).takeWhile { it.isEmpty() || it.first() in " \t" }
        val description = (listOf(first) + rest).dropWhile { it.isBlank() }.dropLastWhile { it.isBlank() }
        return if (description.isEmpty()) null else HglDocSection(null, description)
    }
}

internal fun docSections(text: String): List<HglDocSection> {
    val sections = mutableListOf<HglDocSection>()
    val lines = text.lines()
    var i = 0
    while (i < lines.size) {
        val heading = lines[i].trimEnd(' ', '\t').takeIf { it in headings }
        if (heading != null) i++
        val body = mutableListOf<String>()
        while (i < lines.size) {
            val line = lines[i]
            if (line.trimEnd(' ', '\t') in headings) break
            if (heading != null && line.isNotBlank() && !line.startsWith("    ")) break
            body += if (heading == null) line else line.removePrefix("    ")
            i++
        }
        val content = body.dropLastWhile { it.isEmpty() }
        if (heading != null || content.isNotEmpty()) sections += HglDocSection(heading, content)
    }
    return sections
}
