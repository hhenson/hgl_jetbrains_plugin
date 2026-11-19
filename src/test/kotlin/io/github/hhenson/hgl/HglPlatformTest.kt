package io.github.hhenson.hgl

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.ide.structureView.StructureViewTreeElement
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
class HglPlatformTest : HglEditorFixture() {

    fun testDocumentationAtDeclarationAndReference() {
        myFixture.configureByText("docs.hgl", """
            module docs
            /**
            Preserve α and <tag>.

            Args:
                value: Input.

            Notes:
                .. math::

                    y = x
            */
            fn keep(value: i64) -> i64 => value
            fn consume(value: i64) -> i64 => ke<caret>ep(value)
        """.trimIndent())
        val target = resolveAtCaret().single()
        val text = io.github.hhenson.hgl.documentation.HglDocumentation.attached(target)
        assertNotNull(text)
        assertTrue(text!!.contains("        y = x"))
        val html = io.github.hhenson.hgl.documentation.HglDocumentation.html(target)!!
        assertTrue(html.contains("&lt;tag&gt;"))
        assertFalse(html.contains("<tag>"))
        assertTrue(com.intellij.platform.backend.documentation.DocumentationTargetProvider.EP_NAME.extensionList
            .any { it is io.github.hhenson.hgl.documentation.HglDocumentationTargetProvider })
        val provider = io.github.hhenson.hgl.documentation.HglDocumentationTargetProvider()
        val targets = provider.documentationTargets(myFixture.file, myFixture.caretOffset)
        assertEquals(1, targets.size)
        assertNotNull(targets.single().computeDocumentation())
        assertNotNull(targets.single().createPointer().dereference())
    }

    fun testDocumentationPreservesMultibyteLeadingText() {
        myFixture.configureByText("docs.hgl", "module docs\n/**\n a\n　b\n*/\nfn f() {}")
        val function = com.intellij.psi.util.PsiTreeUtil.findChildOfType(myFixture.file, HglFunctionDecl::class.java)!!
        assertEquals(" a\n　b", io.github.hhenson.hgl.documentation.HglDocumentation.attached(function))
    }

    fun testDocumentationOnDeclarationNames() {
        for (declaration in listOf(
            "fn na<caret>me(value: i64) -> i64 => value",
            "native fn na<caret>me(value: i64) -> i64",
            "operator na<caret>me(value: i64) -> i64",
            "struct Na<caret>me { value: i64 }",
            "test na<caret>me {}",
            "struct Outer {\n/** Field. */\nna<caret>me: i64\n}",
        )) {
            myFixture.configureByText("docs.hgl", "module docs\n/** Declaration. */\n$declaration\n")
            val targets = io.github.hhenson.hgl.documentation.HglDocumentationTargetProvider()
                .documentationTargets(myFixture.file, myFixture.caretOffset)
            assertEquals(declaration, 1, targets.size)
            assertNotNull(targets.single().computeDocumentation())
        }
    }

    fun testDocumentationDoesNotFallBackFromDeclarationBodies() {
        for (declaration in listOf(
            "fn f() => mis<caret>sing()",
            "fn f() => <caret>42",
            "fn f() {\nlet lo<caret>cal = 42\n}",
            "fn f(value: i64) -> i64 => val<caret>ue",
            "fn f() {\nlet local = 42\nreturn lo<caret>cal\n}",
            "test example {\nmis<caret>sing()\n}",
            "struct Outer {\nfi<caret>eld: i64\n}",
        )) {
            myFixture.configureByText("docs.hgl", "module docs\n/** Enclosing declaration. */\n$declaration\n")
            val targets = io.github.hhenson.hgl.documentation.HglDocumentationTargetProvider()
                .documentationTargets(myFixture.file, myFixture.caretOffset)
            assertTrue(declaration, targets.isEmpty())
        }
    }

    fun testDocumentationOnModuleDeclaration() {
        myFixture.configureByText("docs.hgl", "/** Module. */\nmodule do<caret>cs\n")
        val targets = io.github.hhenson.hgl.documentation.HglDocumentationTargetProvider()
            .documentationTargets(myFixture.file, myFixture.caretOffset)
        assertEquals(1, targets.size)
        assertNotNull(targets.single().computeDocumentation())
    }

    fun testModuleDocumentationFromAnImport() {
        myFixture.addFileToProject("foo/bar.hgl", "/** Module overview. */\nmodule foo.bar\n")
        myFixture.configureByText("consumer.hgl", "module consumer\nuse foo.b<caret>ar\n")
        assertTrue(resolveAtCaret().single() is HglFile)
        val targets = io.github.hhenson.hgl.documentation.HglDocumentationTargetProvider()
            .documentationTargets(myFixture.file, myFixture.caretOffset)
        assertEquals(1, targets.size)
        assertNotNull(targets.single().computeDocumentation())
        assertNotNull(targets.single().createPointer().dereference())
    }

    fun testOrdinaryCommentsDoNotAttachDocumentation() {
        myFixture.configureByText("docs.hgl", "module docs\n/** Hidden. */\n# barrier\nfn f() {}\n")
        val function = com.intellij.psi.util.PsiTreeUtil.findChildOfType(myFixture.file, HglFunctionDecl::class.java)!!
        assertNull(io.github.hhenson.hgl.documentation.HglDocumentation.attached(function))
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

    fun testPrivateConstantResolvesAcrossPartsAndImportsStayFileLocal() {
        myFixture.addFileToProject("config.hgl", "module demo part config\nuse remote::{foreign}\nconst limit: i64 = 4\n")
        myFixture.configureByText("main.hgl", "module demo\nfn read() -> i64 => li<caret>mit\n")
        assertEquals("config.hgl", resolveAtCaret().single().containingFile.name)
        myFixture.configureByText("main.hgl", "module demo\nfn read() -> i64 => fo<caret>reign\n")
        assertEmpty(resolveAtCaret())
    }

    fun testCorePreludeExposesExportsAndOperators() {
        myFixture.addFileToProject("core.hgl", "module core\nconst hidden: i64 = 2\n")
        myFixture.addFileToProject("core_native.hgl", """
            module core part native_ops
            export native fn snapshot(input: endpoint<i64>) -> i64
            operator plus(a: i64, b: i64) -> i64
        """.trimIndent())
        for (name in listOf("sn<caret>apshot", "pl<caret>us", "hi<caret>dden")) {
            myFixture.configureByText("user.hgl", "module user\nfn read() => $name\n")
            val targets = resolveAtCaret()
            if (name.startsWith("hi")) assertEmpty(targets)
            else assertEquals("core_native.hgl", targets.single().containingFile.name)
        }
    }

    fun testHelpersAreSharedOnlyWithinTheModuleTestScope() {
        myFixture.addFileToProject("helpers.hgl", "module suite part helpers\ntest { fn helper() -> bool => true }\n")
        myFixture.configureByText("checks.hgl", "module suite\ntest check { assert hel<caret>per() }\n")
        assertEquals("helpers.hgl", resolveAtCaret().single().containingFile.name)
        myFixture.configureByText("checks.hgl", "module suite\nfn live() -> bool => hel<caret>per()\n")
        assertEmpty(resolveAtCaret())
    }

    fun testAnApplicationIsNotAnImportableModule() {
        myFixture.addFileToProject("app.hgl", "/*\nmodule phantom\n*/\napplication demo.app\nrun(work())\n")
        myFixture.configureByText("consumer.hgl", "module client\nuse demo.a<caret>pp as app\n")
        assertEmpty(resolveAtCaret())
        myFixture.configureByText("consumer.hgl", "module client\nuse phan<caret>tom as ghost\n")
        assertEmpty(resolveAtCaret())
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
                assert eval(midpoint(a: [1.0], b: [2.0])) == [1.5]
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

    private fun completionsInModule(body: String): List<String> {
        myFixture.configureByText("arrow.hgl", "module arrow\n$body")
        myFixture.completeBasic()
        return myFixture.lookupElementStrings.orEmpty()
    }

    fun testArrowExpressionStartersAndNames() {
        val names = completionsInModule("""
            fn helper(x: str) -> str => x
            fn render(value: str) -> str => <caret>
        """.trimIndent())
        assertContainsElements(names, "if", "fn", "true", "false", "null", "delta", "str", "bytes", "any", "error",
            "value", "helper")
        assertDoesntContain(names, "module", "integration_test", "operator", "native", "export", "struct", "requires",
            "const", "eval", "run", "list", "i64", "let", "return")
    }

    fun testArrowCompletionWithTypedPrefix() {
        val names = completionsInModule("fn f(truth: bool) -> bool => tr<caret>")
        assertContainsElements(names, "true", "truth")
    }

    fun testArrowCompletionInsideOperatorAndAnonymousFunction() {
        val operand = completionsInModule("operator echo(input: i64) -> i64 => # on the next line\n input + <caret>")
        assertContainsElements(operand, "if", "null", "input")
        assertDoesntContain(operand, "module", "return", "i64")

        val argument = completionsInModule("""
            fn run(x: i64) -> i64 => x
            fn outer(value: i64) -> i64 {
                let apply = fn(x: i64) => run(<caret>)
                return apply(value)
            }
        """.trimIndent())
        assertContainsElements(argument, "if", "false", "x", "value", "run")
        assertDoesntContain(argument, "let", "return", "state", "eval", "list")
    }

    fun testArrowCompletionRespectsNestedAndFollowingContexts() {
        assertContainsElements(completionsInModule("fn f(x: i64) -> i64 => if true { <caret> }"), "let", "return")
        assertContainsElements(completionsInModule("fn f(x: i64) -> i64 => delta<<caret>>(x)"), "i64", "list")
        assertContainsElements(completionsInModule("fn f() -> i64 => 1\n<caret>"), "module", "operator")
        val field = completionsInModule("fn f(x: i64) -> i64 => x.<caret>")
        assertDoesntContain(field, "if", "null", "module")
    }

    fun testArrowCompletionInTestBodies() {
        val unit = completionsInModule("test replay { let probe = fn() => <caret> }")
        assertContainsElements(unit, "eval", "if")
        assertDoesntContain(unit, "run", "return")
        val integration = completionsInModule("integration_test live { let probe = fn() => <caret> }")
        assertContainsElements(integration, "run", "eval", "if")
        assertDoesntContain(integration, "return")
    }

    fun testIntegrationTestCompletion() {
        fun suggestions(source: String): List<String> {
            myFixture.configureByText("live.hgl", source)
            myFixture.completeBasic()
            return myFixture.lookupElementStrings.orEmpty()
        }
        assertContainsElements(suggestions("module live\n<caret>"), "test", "integration_test")
        assertContainsElements(suggestions("module live\n@<caret>"), "timeout", "suppress_warning")
        assertContainsElements(suggestions("module live\nintegration_test pulses { <caret> }"), "run", "assert")
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

            operator summarize<T>(window: rolling_window<T, 20>) -> T

            fn midpoint(quote: atomic<Quote>) -> f64 => (quote.bid + quote.ask) / 2.0

            test {
                fn fixture(v: f64) -> f64 => v
                test fixture_ticks {
                    assert eval(fixture(v: [1.0])) == [1.0]
                }
            }

            test midpoint_ticks {
                assert eval(midpoint(quote: [Quote(bid: 1.0, ask: 2.0)])) == [1.5]
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
