package io.github.hhenson.hgl

import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.ParsingTestCase
import io.github.hhenson.hgl.parser.HglParserDefinition
import java.io.File

/**
 * Every example module shipped with the language specification and the C++
 * compiler must parse without a single error element. The files are copied
 * by tools/refresh_test_data.sh; src/test/testData/parser/SOURCES.md records
 * the revisions.
 */
class HglExamplesParsingTest : ParsingTestCase("parser", "hgl", HglParserDefinition()) {

    override fun getTestDataPath(): String = "src/test/testData"

    fun testAllExampleModulesParseWithoutErrors() {
        val failures = ArrayList<String>()
        var count = 0
        for (directory in listOf("spec", "hgraph", "stdlib")) {
            val root = File(testDataPath, "parser/$directory")
            val files = root.walkTopDown().filter { it.isFile && it.extension == "hgl" }.sortedBy { it.path }.toList()
            assertTrue("no .hgl files under $root; run tools/refresh_test_data.sh", files.isNotEmpty())
            for (file in files) {
                count++
                val text = file.readText()
                val psi = createPsiFile(file.name, text)
                val errors = PsiTreeUtil.collectElementsOfType(psi, PsiErrorElement::class.java)
                for (error in errors.take(3)) {
                    val line = StringUtil.offsetToLineNumber(text, error.textOffset) + 1
                    val lineText = text.lines().getOrNull(line - 1)?.trim() ?: ""
                    failures.add("$directory/${file.name}:$line: ${error.errorDescription}\n    $lineText")
                }
            }
        }
        assertTrue(
            "${failures.size} parse error(s) in $count example modules:\n" + failures.joinToString("\n"),
            failures.isEmpty(),
        )
    }
}
