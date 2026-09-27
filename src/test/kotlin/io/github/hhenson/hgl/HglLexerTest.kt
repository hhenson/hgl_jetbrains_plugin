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

    fun testCppForms() = doGoldenTest("cppForms")

    fun testComments() = doGoldenTest("comments")

    fun testPunctuation() = doGoldenTest("punctuation")

    fun testRestartOnEveryTokenOfEveryExample() {
        val roots = listOf("spec", "hgraph", "stdlib").map { File("src/test/testData/parser/$it") }
        val files = roots.flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "hgl" }.toList() }
        assertTrue("no example modules found", files.isNotEmpty())
        for (file in files) {
            checkCorrectRestart(file.readText())
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
