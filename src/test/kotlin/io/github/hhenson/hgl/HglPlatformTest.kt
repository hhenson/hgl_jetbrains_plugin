package io.github.hhenson.hgl

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.hhenson.hgl.psi.HglFile
import io.github.hhenson.hgl.psi.HglFunctionDecl
import io.github.hhenson.hgl.psi.HglImportItem
import io.github.hhenson.hgl.psi.HglLocalDecl
import io.github.hhenson.hgl.psi.HglNamedElement
import io.github.hhenson.hgl.psi.HglParameter
import io.github.hhenson.hgl.psi.HglStateDecl
import io.github.hhenson.hgl.psi.HglStructDecl
import io.github.hhenson.hgl.psi.HglUseAlias
import io.github.hhenson.hgl.structure.HglStructureViewModel

/**
 * End-to-end checks in a headless IDE: the plugin descriptor loads, the
 * parser definition, references, module index, completion, annotator and
 * structure view are wired, and they behave as docs/design.md says.
 */
class HglPlatformTest : BasePlatformTestCase() {

    override fun tearDown() {
        try {
            // A CLion component turns this on during the first test of the
            // process; the fixture's tear-down otherwise reports the code
            // insight settings as damaged.
            CodeInsightSettings.getInstance().AUTO_POPUP_JAVADOC_INFO = false
        } finally {
            super.tearDown()
        }
    }

    private val marketData = """
        module examples.market_data

        export abstract struct Instrument {
            symbol: str
        }

        export struct Quote {
            bid: f64
            ask: f64
        }

        export fn spread(quote: atomic<Quote>) -> f64 => quote.ask - quote.bid
    """.trimIndent()

    private fun resolveAtCaret(): List<PsiElement> {
        val reference = myFixture.file.findReferenceAt(myFixture.caretOffset)
            ?: error("no reference at caret")
        return (reference as PsiPolyVariantReference).multiResolve(false).mapNotNull { it.element }
    }

    fun testParameterResolves() {
        myFixture.configureByText("a.hgl", "module a\n\nfn f(value: f64) -> f64 => val<caret>ue * 2.0\n")
        val target = resolveAtCaret().single()
        assertTrue(target is HglParameter)
        assertEquals("value", (target as HglNamedElement).name)
    }

    fun testStateAndLocalResolveInsideBlocks() {
        myFixture.configureByText(
            "a.hgl",
            """
            module a

            fn total(a: f64) -> f64 {
                state total: f64 = 0.0
                inject out
                when modified(a) && valid(a) {
                    let scaled = a * 2.0
                    total += sca<caret>led
                    out = total
                }
            }
            """.trimIndent(),
        )
        assertTrue(resolveAtCaret().single() is HglLocalDecl)

        myFixture.configureByText(
            "b.hgl",
            """
            module b

            fn total(a: f64) -> f64 {
                state total: f64 = 0.0
                when modified(a) {
                    tot<caret>al += a
                }
            }
            """.trimIndent(),
        )
        assertTrue(resolveAtCaret().single() is HglStateDecl)
    }

    fun testLocalDeclaredLaterDoesNotResolve() {
        myFixture.configureByText("a.hgl", "module a\n\nfn f() -> f64 {\n    let x = lat<caret>er\n    let later = 1.0\n    x\n}\n")
        assertTrue(resolveAtCaret().isEmpty())
    }

    fun testOverloadsResolveToEveryCandidate() {
        myFixture.configureByText(
            "a.hgl",
            """
            module a

            operator len<T>(value: list<T>) -> i64
            impl fn len(value: list<f64>) -> i64 => 1
            impl fn len(value: list<str>) -> i64 => 1

            fn use_it(v: list<f64>) -> i64 => le<caret>n(v)
            """.trimIndent(),
        )
        assertEquals(3, resolveAtCaret().size)
    }

    fun testImportResolvesAcrossModules() {
        myFixture.addFileToProject("market_data.hgl", marketData)
        myFixture.configureByText(
            "book.hgl",
            """
            module examples.instrument_book

            use examples.market_data::{Quote}

            export fn mid(quote: atomic<Quo<caret>te>) -> f64 => (quote.bid + quote.ask) / 2.0
            """.trimIndent(),
        )
        val target = resolveAtCaret().single()
        assertTrue("expected the struct, got $target", target is HglStructDecl)
        assertEquals("market_data.hgl", target.containingFile.name)
    }

    fun testImportItemItselfResolvesToTheExport() {
        myFixture.addFileToProject("market_data.hgl", marketData)
        myFixture.configureByText("book.hgl", "module b\n\nuse examples.market_data::{spr<caret>ead}\n")
        val item = myFixture.file.findElementAt(myFixture.caretOffset)?.parent?.parent
        assertTrue("expected an import item, got $item", item is HglImportItem)
        val target = resolveAtCaret().single()
        assertTrue(target is HglFunctionDecl)
    }

    fun testAliasResolvesAcrossModules() {
        myFixture.addFileToProject("market_data.hgl", marketData)
        myFixture.configureByText(
            "book.hgl",
            """
            module examples.instrument_book

            use examples.market_data as market

            export struct Future: market::Instr<caret>ument {
                expiry: i64
            }
            """.trimIndent(),
        )
        val target = resolveAtCaret().single()
        assertTrue(target is HglStructDecl)
        assertEquals("Instrument", (target as HglNamedElement).name)

        myFixture.configureByText("c.hgl", "module c\n\nuse examples.market_data as mar<caret>ket\n\nfn f() -> f64 => market::spread\n")
        val alias = myFixture.file.findElementAt(myFixture.caretOffset)?.parent?.parent
        assertTrue("expected the alias, got $alias", alias is HglUseAlias)
    }

    fun testModulePathResolvesToTheFile() {
        myFixture.addFileToProject("market_data.hgl", marketData)
        myFixture.configureByText("book.hgl", "module b\n\nuse examples.mark<caret>et_data::{Quote}\n")
        val target = resolveAtCaret().single()
        assertEquals("market_data.hgl", target.containingFile.name)
    }

    fun testRenameParameter() {
        myFixture.configureByText("a.hgl", "module a\n\nfn f(value: f64) -> f64 => val<caret>ue * 2.0\n")
        myFixture.renameElementAtCaret("price")
        myFixture.checkResult("module a\n\nfn f(price: f64) -> f64 => price * 2.0\n")
    }

    fun testFindUsages() {
        myFixture.configureByText(
            "a.hgl",
            """
            module a

            fn mid<caret>point(a: f64, b: f64) -> f64 => (a + b) / 2.0

            export fn twice(a: f64) -> f64 => midpoint(a, a) + midpoint(a, a)

            test t {
                assert eval(midpoint, a: [1.0], b: [2.0]) == [1.5]
            }
            """.trimIndent(),
        )
        val declaration = myFixture.elementAtCaret
        assertTrue(declaration is HglFunctionDecl)
        assertEquals(3, myFixture.findUsages(declaration).size)
    }

    fun testCompletionOffersKeywordsAndNamesInScope() {
        myFixture.configureByText(
            "a.hgl",
            """
            module a

            fn helper(x: f64) -> f64 => x

            fn f(price: f64) -> f64 {
                let scaled = price * 2.0
                <caret>
            }
            """.trimIndent(),
        )
        myFixture.completeBasic()
        val strings = myFixture.lookupElementStrings ?: emptyList()
        assertTrue("got $strings", strings.containsAll(listOf("price", "helper", "scaled", "return", "let", "when")))
    }

    fun testValidModuleHasNoErrorHighlights() {
        myFixture.configureByText("a.hgl", marketData)
        val errors = myFixture.doHighlighting().filter { it.severity == HighlightSeverity.ERROR }
        assertTrue("unexpected errors: $errors", errors.isEmpty())
    }

    fun testBrokenModuleHasErrorHighlightOnItsLine() {
        myFixture.configureByText("a.hgl", "module a\n\nfn f(value: f64) -> f64 {\n    let x = )\n    value\n}\n\nfn g() -> i64 => 1\n")
        val errors = myFixture.doHighlighting().filter { it.severity == HighlightSeverity.ERROR }
        assertEquals("expected exactly one error, got $errors", 1, errors.size)
        val line = myFixture.editor.document.getLineNumber(errors.single().startOffset)
        assertEquals(3, line)
    }

    fun testStructureView() {
        myFixture.configureByText(
            "a.hgl",
            """
            module examples.structure

            export struct Quote {
                bid: f64
                ask: f64
            }

            operator summarize<T>(window: rolling<T, 20>) -> T

            fn midpoint(quote: atomic<Quote>) -> f64 => (quote.bid + quote.ask) / 2.0

            test {
                fn fixture(v: f64) -> f64 => v
                test fixture_ticks {
                    assert eval(fixture, v: [1.0]) == [1.0]
                }
            }

            test midpoint_ticks {
                assert eval(midpoint, quote: [Quote(bid: 1.0, ask: 2.0)]) == [1.5]
            }
            """.trimIndent(),
        )
        val model = HglStructureViewModel(myFixture.file as HglFile, null)
        fun render(element: StructureViewTreeElement, depth: Int): String {
            val children = element.children.map { it as StructureViewTreeElement }
            val prefix = " ".repeat(depth) + (if (children.isEmpty()) "" else "-")
            return prefix + element.presentation.presentableText + "\n" +
                children.joinToString("") { render(it, depth + 1) }
        }
        assertEquals(
            """
            -a.hgl
             -Quote
              bid
              ask
             summarize
             midpoint
             -test context
              fixture
              fixture_ticks
             midpoint_ticks

            """.trimIndent(),
            render(model.root, 0),
        )
    }
}
