package io.github.hhenson.hgl

import com.intellij.lexer.Lexer
import com.intellij.testFramework.LexerTestCase
import io.github.hhenson.hgl.lexer.HglLexerAdapter
import java.io.File

/**
 * Token dumps for the lexically interesting corners (src/test/testData/lexer)
 * and a restart check over every example module, which is what the editor's
 * incremental highlighter relies on.
 */
class HglLexerTest : LexerTestCase() {

    override fun createLexer(): Lexer = HglLexerAdapter()

    override fun getDirPath(): String = "src/test/testData/lexer"

    fun testLiterals() = doGoldenTest("literals")

    fun testNativeForms() = doGoldenTest("nativeForms")

    fun testComments() = doGoldenTest("comments")

    fun testPunctuation() = doGoldenTest("punctuation")

    fun testRestartOnEveryTokenOfEveryExample() {
        val roots = listOf("spec", "core").map { File("src/test/testData/parser/$it") }
        val files = roots.flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "hgl" }.toList() }
        assertTrue("no example modules found", files.isNotEmpty())
        for (file in files) {
            checkCorrectRestart(file.readText())
        }
    }

    /** LEX-9/10: changes to the pinned reserved words must change the editor. */
    fun testReservedWordsMatchPinnedSpec() {
        val text = File("spec/hgl_spec/spec/language/source-text.md").readText()
        val words = text.substringAfter("Hard reserved words:").substringAfter("```text")
            .substringBefore("```").trim().split(Regex("\\s+"))
        for (word in words) {
            val lexer = createLexer()
            lexer.start(word)
            assertTrue(word, io.github.hhenson.hgl.psi.HglTokenSets.KEYWORDS.contains(lexer.tokenType))
            assertEquals(word.length, lexer.tokenEnd)
        }
        for (word in listOf("const", "native", "type", "switch", "case", "default", "raises", "application", "run", "integration_test", "timeout", "rolling")) {
            val lexer = createLexer()
            lexer.start(word)
            assertEquals(word, io.github.hhenson.hgl.psi.HglTypes.IDENTIFIER, lexer.tokenType)
        }
    }

    fun testTenorUnitsAndDurationUnits() {
        for (text in listOf("1Y6M", "1W3D", "2B", "3M", "3m", "10us")) {
            val lexer = createLexer()
            lexer.start(text)
            assertEquals(text, io.github.hhenson.hgl.psi.HglTypes.TEMPORAL_LITERAL, lexer.tokenType)
            assertEquals(text.length, lexer.tokenEnd)
        }
        for (text in listOf("2.5Y", "1B1D", "1D1W", "1M2M", "1h2D")) {
            val lexer = createLexer()
            lexer.start(text)
            assertEquals(text, com.intellij.psi.TokenType.BAD_CHARACTER, lexer.tokenType)
            assertEquals(text.length, lexer.tokenEnd)
        }
    }

    private fun doGoldenTest(name: String) {
        val source = File(dirPath, "$name.hgl")
        val expected = File(dirPath, "$name.txt")
        val actual = printTokens(source.readText(), 0, createLexer())
        if (!expected.exists()) {
            expected.writeText(actual)
            fail("golden file ${expected.path} did not exist; it was written, re-run the test")
        }
        assertSameLinesWithFile(expected.path, actual)
    }
}
