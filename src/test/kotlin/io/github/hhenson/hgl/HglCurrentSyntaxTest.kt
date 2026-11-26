package io.github.hhenson.hgl

import com.intellij.psi.PsiErrorElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.ParsingTestCase
import io.github.hhenson.hgl.parser.HglParserDefinition
import io.github.hhenson.hgl.psi.HglConstraintRelation
import io.github.hhenson.hgl.psi.HglEnumDecl
import io.github.hhenson.hgl.psi.HglFunctionDecl
import io.github.hhenson.hgl.psi.HglOperatorConfiguration
import io.github.hhenson.hgl.psi.HglRaisesAssertion
import io.github.hhenson.hgl.psi.HglSwitchStmt

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

    fun testTypeOfAcceptsOneExpressionAndPreservesOrdinaryNames() {
        val file = accepted("""
            module current
            const fn reflected(values: list<i64, 2>) -> Type => type(values[0] + 1)
            const fn ordinary(type: i64) -> i64 => type
        """)
        val node = PsiTreeUtil.findChildOfType(file, io.github.hhenson.hgl.psi.HglTypeOfExpr::class.java)
        assertEquals("type(values[0] + 1)", node?.text)
        rejected("const fn empty() -> Type => type()")
        rejected("const fn two(x: i64) -> Type => type(x, x)")
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

    fun testInequalityIsAConstraintRelation() {
        val file = accepted("""
            module demo.constraints
            const fn distinct<T, U>(a: T, b: U) -> bool requires T != U => true
            const fn nonzero<const n: i64>(x: i64) -> i64 requires n != 0 && n != 1 => x
        """)
        val relations = PsiTreeUtil.findChildrenOfType(file, HglConstraintRelation::class.java)
        assertEquals(listOf("!= U", "!= 0", "!= 1"), relations.map { it.text })
    }

    fun testCategoryKeywordsOnlyFollowMembership() {
        for (category in listOf("struct", "enum")) {
            accepted("""
                module demo.categories
                const fn one<T>(value: T) -> T requires T in $category => value
                const fn all<...Ts>(values: tuple<Ts>) -> bool
                    requires each T in types(Ts) { T in $category } => true
            """)
            for (constraint in listOf("T is $category", "T == $category", "T != $category",
                "T in {$category}", "$category in {T}")) {
                rejected("const fn f<T>(value: T) -> T requires $constraint => value")
            }
        }
        accepted("module demo\nconst fn f<T>(value: T) -> T requires T is i64 => value")
    }

    fun testLoopMadeListInAConstantArgument() {
        accepted("""
            module demo.offsets
            fn first(const values: list<i64>) -> i64 { yield 0s: values[0] }
            fn wired() -> i64 => first([for i in 2..<4: i])
        """)
    }

    fun testToleranceConfigurationInBothOperatorSpellings() {
        val file = accepted("""
            module demo.tolerance
            const fn symbol(lhs: f64, rhs: f64) -> bool => lhs =={abs: 0.01} rhs
            const fn named(lhs: f64, rhs: f64) -> bool => eq_{abs: 0.01}(lhs, rhs)
        """)
        val options = PsiTreeUtil.findChildrenOfType(file, HglOperatorConfiguration::class.java)
        assertEquals(listOf("{abs: 0.01}", "{abs: 0.01}"), options.map { it.text })
    }

    fun testForwardedLawAndCapabilityMembership() {
        accepted("""
            module demo.laws
            operator schedule<C, W>(capability: C, at: W) requires C in {Scheduler, Alarm}
            const fn reduce<T, const associative: bool = associative(f)>(f: const fn(T, T) -> T, a: T, b: T) -> T
                requires associative in {true, false} => fold{associative: associative}(f, a, b)
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

    fun testConfiguredCallsInEachControlHeader() {
        val call = "scaled{by: 1}(1)"
        for (header in listOf("if %s == 1", "while %s == 1", "when %s == 1", "for item in %s", "switch %s")) {
            fun statement(expr: String) = "fn pick() { " + header.format(expr) + " {} }"
            accepted("module demo\n" + statement("($call)"))
            rejected(statement(call))
        }
        accepted("module demo\nfn pick(x: i64) { if x /{zero: .ieee} 2 == 1 {} }")
    }

    fun testHarnessOptionsInControlHeadersRequireGrouping() {
        val eval = "eval{length: 1us} ticks()"
        accepted("module demo\ntest observed { if ($eval) == [1] {} }")
        rejected("test observed { if $eval == [1] {} }")
        val run = "run{length: 1s} ticks()"
        accepted("module demo\nintegration_test observed { if ($run) == [1] {} }")
        rejected("integration_test observed { if $run == [1] {} }")
    }

    fun testOneLineBreakBeforeBodies() {
        for (source in listOf(
            "struct S|{ value: i64 }", "enum E|{ a, b }",
            "fn f<T>(x: T) -> T requires T in {i64, f64}|{ return x }",
            "native fn f()|{ when; }", "native type S|{ value: i64 }",
            "test t|{ assert true }", "integration_test t|{ assert true }",
            "test|{ test nested|{ assert true } }",
            "fn f() { start|{}\nwhen|{}\nwhen ready|{}\nstop|{} }",
            "fn f() { for item in items|{}\nwhile ready|{}\nwhile|{} }",
            "fn f() { if ready|{} else if again|{} else|{} }",
            "fn f() { let g = fn(a: i64) -> i64|{ return a } }",
        )) {
            accepted("module demo\n" + source.replace("|", "\n"))
            rejected(source.replace("|", "\n\n"))
        }
        accepted("module demo\nfn f() /* one\nlogical\nbreak */ {}")
        accepted("module demo\nfn f() => apply(fn(x: i64)\n\n{ return x })")
        rejected("fn f() => apply(fn() { if ready\n\n{ step() } })")
        rejected("fn f() /* one\nbreak */\n{}")
        rejected("test sample\n# blank line\n{}")
    }

    fun testSwitchAndRaisesOwnTheirBodiesAcrossOneLineBreak() {
        for (selector in listOf("(x)", " -x", " .buy", "(x).field", "(x, y)")) {
            for (gap in listOf(" ", "\n", " /* one\nbreak */ ")) {
                val body = "{ default: step() }"
                val file = accepted("module demo\nfn f() { switch$selector$gap$body }")
                val statement = PsiTreeUtil.findChildOfType(file, HglSwitchStmt::class.java)
                assertEquals("switch$selector$gap$body", statement?.text)
            }
        }
        for (gap in listOf(" ", "\n", " /* one\nbreak */ ")) {
            val text = """raises("value.missing_key")""" + gap + "{ work() }"
            val file = accepted("module demo\ntest t { assert $text }")
            val assertion = PsiTreeUtil.findChildOfType(file, HglRaisesAssertion::class.java)
            assertEquals(text, assertion?.text)
        }
        accepted("module demo\nfn f() { switch\n(x) { default: step() } }")
        accepted("module demo\ntest t { assert\nraises(\"error\") { work() } }")
        rejected("fn f() { switch\n\n(x) { default: step() } }")
        rejected("test t { assert\n\nraises(\"error\") { work() } }")
        rejected("fn f() { switch(x)\n\n{ default: step() } }")
        rejected("test t { assert raises(\"error\")\n\n{ work() } }")
        rejected("fn f() { switch(x) }")
        rejected("test t { assert raises(x) == 1 }")
        for (call in listOf("raises (x)", "raises()", "raises(x, y)", "raises(code: x)")) {
            rejected("test t { assert $call { work() } }")
        }
    }

    fun testReservedSwitchAndRaisesAsMemberNamesAndLabels() {
        for (word in listOf("switch", "raises")) {
            accepted("""
                module demo
                struct Packet { $word: i64 }
                enum Mode { $word }
                fn f(p: Packet) => Packet($word: p.$word)
                fn selected() => choose<.$word>()
            """)
            for (declaration in listOf(
                "fn $word() {}", "struct $word {}", "fn f($word: i64) {}",
                "fn f() { let $word = 1 }", "fn f() => $word()",
            )) rejected(declaration)
        }
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
