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

    fun testOptionsInEval() {
        accepted("""
            module current
            fn ratio(value: f64) -> f64 {
                when { return 6.0 /{zero: .error} value }
            }
            test failure {
                assert raises("numeric.division_by_zero") { eval ratio(value: [0.0]) }
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

    fun testRunUsesItsTestMeaningOnlyInsideTestBodies() {
        val file = accepted("""
            module demo
            fn step(value: i64) -> i64 => value
            fn ordinary() -> i64 => step(1)
            @timeout(5s)
            integration_test live { let observation = run{length: 1s} ordinary() }
            fn also_ordinary() -> i64 => step(2)
        """)
        val runs = PsiTreeUtil.findChildrenOfType(file, io.github.hhenson.hgl.psi.HglRunExpr::class.java)
        assertEquals(listOf("run{length: 1s} ordinary()"), runs.map { it.text })
    }

    fun testApplicationStoreAndRunHaveDistinctNodes() {
        val file = accepted("""
            application demo.live
            inject global_state
            put(global_state, "limit", 2)
            run heartbeat()
        """)
        assertNotNull(PsiTreeUtil.findChildOfType(file, io.github.hhenson.hgl.psi.HglApplicationDecl::class.java))
        assertEquals("put(global_state, \"limit\", 2)", PsiTreeUtil.findChildOfType(file, io.github.hhenson.hgl.psi.HglStoreCall::class.java)?.text)
        assertEquals("run heartbeat()", PsiTreeUtil.findChildOfType(file, io.github.hhenson.hgl.psi.HglRunStatement::class.java)?.text)
    }

    fun testEvalRejectsReplayArgumentsOutsideTheTargetCall() {
        val file = createPsiFile("old.hgl", "module demo\ntest old { assert eval(target, value: [1]) == [1] }\n")
        assertFalse(PsiTreeUtil.findChildrenOfType(file, PsiErrorElement::class.java).isEmpty())
        accepted("module demo\ntest updated { assert eval target(value: [1]) == [1] }")
    }

    fun testConfiguredCallsReferencesAndRanges() {
        val file = accepted("""
            module demo
            fn adapt(f: fn(key: str) -> ref i64, ref price: i64) -> ref i64 => price
            fn ranges(n: i64) -> list<i64> => [for i in 0..<n: i + 1]
            fn choose(a: i64, b: i64, c: i64) -> bool => a < b > (c)
            fn probe(x: i64) => apply(op::scale<i64>, op::scale<i64>{places: 2}(x))
            integration_test live {
                inject run_config{length: 2s}
                let result_ = run{length: 1s} op::sample<i64>{places: 2}(x: 1)
            }
        """)
        assertEquals("op::scale<i64>", PsiTreeUtil.findChildOfType(file, io.github.hhenson.hgl.psi.HglFunctionReference::class.java)?.text)
        assertEquals("0..<n", PsiTreeUtil.findChildOfType(file, io.github.hhenson.hgl.psi.HglIterable::class.java)?.text)
    }

    fun testHeaderBracesAndParenthesizedConfiguredCalls() {
        accepted("""
            module demo
            fn pick(x: i64) -> bool {
                if (pred{limit: 2}(x)) { return true }
                if x >{limit: 2} 0 { return true }
                return false
            }
        """)
        rejected("fn pick(x: i64) { if pred{limit: 2}(x) { return x } }")
    }

    private fun rejected(declaration: String) {
        val file = createPsiFile("rejected.hgl", "module demo\n$declaration\n")
        assertFalse(declaration, PsiTreeUtil.collectElementsOfType(file, PsiErrorElement::class.java).isEmpty())
    }

    fun testRejectsRetiredAndMisplacedForms() {
        for (declaration in listOf(
            "fn run() {}",
            "operator part<T>(x: T) -> T",
            "test old { assert eval(target(x: [1])) == [1] }",
            "integration_test old { run(target()) }",
            "test ordinary { run target() }",
            "fn old(x: ref<i64>) => x",
            "fn old(x: i64?) => x?",
            "fn range(n: i64) => 0..<n",
            "fn mixed() => [1, 2: 3]",
            "fn mixed() => [1: 2, 3]",
            "operator identity(x: i64) -> i64 => x",
            "fn wrong() => combine(a in arg_keys(a, b))",
        )) rejected(declaration)
    }

    fun testSeparatedCallDelimiterRejects() {
        val file = createPsiFile("gap.hgl", "module gap\nfn copy(value: i64) => copy (value)\n")
        assertFalse(PsiTreeUtil.collectElementsOfType(file, PsiErrorElement::class.java).isEmpty())
    }

}
