package io.github.hhenson.hgl

import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.ParsingTestCase
import io.github.hhenson.hgl.parser.HglParserDefinition
import java.io.File

/** Verifies complete modules, declaration fragments and core sources at the pins in SOURCES.md. */
class HglExamplesParsingTest : ParsingTestCase("parser", "hgl", HglParserDefinition()) {

    override fun getTestDataPath(): String = "src/test/testData"

    fun testPinnedCorpusDiagnostics() {
        val failures = ArrayList<String>()
        var count = 0
        for (directory in listOf("spec", "core")) {
            val root = File(testDataPath, "parser/$directory")
            val files = root.walkTopDown().filter { it.isFile && it.extension == "hgl" }.sortedBy { it.path }.toList()
            assertTrue("no .hgl files under $root; run tools/refresh_test_data.sh", files.isNotEmpty())
            for (file in files) {
                count++
                val text = file.readText()
                val psi = createPsiFile(file.name, text)
                val errors = PsiTreeUtil.collectElementsOfType(psi, PsiErrorElement::class.java)
                for (error in errors) {
                    val line = StringUtil.offsetToLineNumber(text, error.textOffset) + 1
                    val lineText = text.lines().getOrNull(line - 1)?.trim() ?: ""
                    failures.add("$directory/${file.name}:$line: ${error.errorDescription}\n    $lineText")
                }
            }
        }
        assertTrue(
            "${failures.size} parse error(s) in $count HGL fixtures:\n" + failures.joinToString("\n"),
            failures.isEmpty(),
        )
    }
}
