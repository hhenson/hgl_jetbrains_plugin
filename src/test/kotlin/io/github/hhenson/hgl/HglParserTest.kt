package io.github.hhenson.hgl

import com.intellij.testFramework.ParsingTestCase
import io.github.hhenson.hgl.parser.HglParserDefinition

/**
 * Golden PSI dumps for representative modules (src/test/testData/parser/golden).
 * A dump is written on the first run and compared afterwards, so a change in
 * tree shape shows up as a diff to review.
 */
class HglParserTest : ParsingTestCase("parser/golden", "hgl", HglParserDefinition()) {

    override fun getTestDataPath(): String = "src/test/testData"

    override fun skipSpaces(): Boolean = false

    override fun includeRanges(): Boolean = true

    fun testMidpoint() = doTest(true, true)

    fun testStructuralTypes() = doTest(true, true)

    fun testParameterPacks() = doTest(true, true)

    fun testNativeFunctions() = doTest(true, true)

    fun testLifecycleCapabilities() = doTest(true, true)

    fun testTemporalLiterals() = doTest(true, true)

    fun testContinuations() = doTest(true, true)

    fun testExpressions() = doTest(true, true)

    /** Broken input: the errors must stay confined to their lines. */
    fun testRecovery() = doTest(true, false)
}
