package io.github.hhenson.hgl.diagnostics

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.TextRange
import java.nio.file.Path

internal data class Position(val line: Int, val character: Int) {
    fun offset(document: Document): Int? {
        if (line == 0 && character == 0 && document.textLength == 0) return 0
        if (line !in 0 until document.lineCount) return null
        val start = document.getLineStartOffset(line)
        return if (character in 0..(document.getLineEndOffset(line) - start)) start + character else null
    }
}

internal data class CheckDiagnostic(
    val start: Position,
    val end: Position,
    val severity: HighlightSeverity,
    val message: String,
    val details: List<String>,
) {
    fun range(document: Document): TextRange? {
        val from = start.offset(document) ?: return null
        val to = end.offset(document) ?: return null
        return if (from <= to) TextRange(from, to) else null
    }
}

private fun JsonObject.position(key: String): Position {
    val point = getAsJsonObject(key)
    return Position(point["line"].asInt, point["character"].asInt)
}

internal fun parseCheckReport(text: String, directory: Path): Map<Path, List<CheckDiagnostic>> {
    val report = JsonParser.parseString(text).asJsonObject
    require(report["version"]?.asInt == 1) { "Unsupported hgl check JSON version" }
    val byFile = mutableMapOf<Path, MutableList<CheckDiagnostic>>()
    for (entry in report.getAsJsonArray("diagnostics")) {
        val d = entry.asJsonObject
        val primary = d.getAsJsonObject("primary")
        val severity = when (d["severity"].asString) {
            "error" -> HighlightSeverity.ERROR
            "warning" -> HighlightSeverity.WARNING
            else -> error("Unknown HGL diagnostic severity")
        }
        val details = buildList {
            d["rule"]?.takeUnless { it.isJsonNull }?.let { add("Rule ${it.asString}") }
            for (note in d.getAsJsonArray("notes")) add(note.asString)
            for (item in d.getAsJsonArray("related")) {
                val related = item.asJsonObject
                val location = related.getAsJsonObject("location")
                val at = location.position("start")
                add("${location["file"].asString}:${at.line + 1}:${at.character + 1} — ${related["message"].asString}")
            }
        }
        val diagnostic = CheckDiagnostic(primary.position("start"), primary.position("end"), severity,
            "[${d["category"].asString}/${d["code"].asString}] ${d["message"].asString}", details)
        // Bundled core files use synthetic names, which can be invalid OS paths.
        val name = primary["file"].asString
        if (name.startsWith("<core>/")) continue
        val path = directory.resolve(name).normalize()
        byFile.getOrPut(path) { mutableListOf() }.add(diagnostic)
    }
    return byFile
}
