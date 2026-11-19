package io.github.hhenson.hgl

import com.intellij.lang.annotation.HighlightSeverity
import io.github.hhenson.hgl.diagnostics.Position
import io.github.hhenson.hgl.diagnostics.parseCheckReport
import java.nio.file.Path

class HglDiagnosticReportTest : HglEditorFixture() {
    fun testWarningRangeAcrossLinesAndAtEndOfFile() {
        val position = """{"line":1,"character":2}"""
        val eof = """{"line":2,"character":0}"""
        fun parse(start: String, end: String) = parseCheckReport("""
            {"version":1,"diagnostics":[{"severity":"warning","category":"phase","code":"sample","message":"warning",
              "primary":{"file":"lib/m.hgl","start":$start,"end":$end},"notes":["a note"],"related":[]}]}
        """, Path.of("sources")).getValue(Path.of("sources/lib/m.hgl")).single()
        myFixture.configureByText("m.hgl", "abc\n😀xyz\n")
        val document = myFixture.editor.document
        val item = parse(position, eof)
        assertEquals(HighlightSeverity.WARNING, item.severity)
        assertEquals(listOf("a note"), item.details)
        assertEquals("xyz\n", item.range(document)!!.substring(document.text))
        assertEquals(document.textLength, parse(eof, eof).range(document)!!.startOffset)
        assertNull(parse(eof, position).range(document))
        assertNull(parse(position, """{"line":2,"character":1}""").range(document))
    }

    fun testEmptyFileHasAnEofPosition() {
        myFixture.configureByText("empty.hgl", "")
        assertEquals(0, Position(0, 0).offset(myFixture.editor.document))
        assertNull(Position(0, 1).offset(myFixture.editor.document))
    }

    fun testUnsupportedVersionIsRejected() {
        val directory = Path.of(".")
        assertThrows(IllegalArgumentException::class.java) {
            parseCheckReport("""{"version":2,"diagnostics":[]}""", directory)
        }
    }
}
