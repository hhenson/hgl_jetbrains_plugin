package io.github.hhenson.hgl

import com.intellij.psi.PsiErrorElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.ParsingTestCase
import io.github.hhenson.hgl.parser.HglParserDefinition
import io.github.hhenson.hgl.psi.HglEnumDecl
import io.github.hhenson.hgl.psi.HglFunctionDecl

/** Regressions for current HGL forms absent from the original plugin. */
class HglCurrentSyntaxTest : ParsingTestCase("parser", "hgl", HglParserDefinition()) {
    override fun getTestDataPath(): String = "src/test/testData"

    private fun accepted(text: String) = createPsiFile("current.hgl", text.trimIndent()).also {
        val errors = PsiTreeUtil.collectElementsOfType(it, PsiErrorElement::class.java)
        assertTrue(errors.joinToString { error -> error.errorDescription }, errors.isEmpty())
    }

    fun testEnumSwitchAndNullableFields() {
        val file = accepted("""
            module current
            enum Side { buy, sell }
            struct Quote { side: Side? }
            const fn price(side: Side) -> i64 {
                switch side {
                    case .buy: return 1
                    case .sell: return 2
                }
            }
        """)
        assertEquals("Side", PsiTreeUtil.findChildOfType(file, HglEnumDecl::class.java)?.name)
    }

    fun testConstIsAnOrdinaryCallableName() {
        val file = accepted("""
            module current
            const fn const(value: i64) -> i64 => value
            fn copy(value: i64) -> i64 => const(value)
        """)
        assertEquals("const", PsiTreeUtil.findChildOfType(file, HglFunctionDecl::class.java)?.name)
    }

    fun testOptionsAndPropagationInEval() {
        accepted("""
            module current
            fn ratio(value: f64) -> f64 {
                when { return (6.0 /{zero: .error} value)? }
            }
            test failure {
                assert raises("numeric.division_by_zero") { eval(ratio, value: [0.0]) }
            }
        """)
    }

    fun testNativeTypesAndGenericDefaults() {
        accepted("""
            module current
            export native type Handle
            native impl const fn divide<const zero: Policy = .ieee>(a: f64, b: f64) -> f64
            requires zero in {.ieee, .nan}
        """)
    }

    fun testConstAsParameterAndTypeParameterNames() {
        accepted("""
            module current
            const fn identity<const>(value: const) -> const => value
            const fn named(const: i64) -> i64 => const
            const fn modified(const const: i64) -> i64 => const
            fn configured(value: f64) -> f64 => divide<.ieee>(value, 2.0)
        """)
    }

    fun testUnaryPrecedenceAndOptions() {
        val file = accepted("""
            module current
            const fn compute(a: i64, b: i64) -> i64 => -a + b
            const fn configured(a: i64) -> i64 => -{overflow: .error} a
        """)
        val unary = PsiTreeUtil.findChildOfType(file, io.github.hhenson.hgl.psi.HglUnaryExpr::class.java)
        assertEquals("-a", unary?.text)
    }

    fun testSeparatedCallDelimiterRejects() {
        val file = createPsiFile("gap.hgl", "module gap\nfn copy(value: i64) => copy (value)\n")
        assertFalse(PsiTreeUtil.collectElementsOfType(file, PsiErrorElement::class.java).isEmpty())
    }

}
