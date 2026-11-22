package io.github.hhenson.hgl

import com.intellij.platform.backend.documentation.DocumentationTargetProvider
import com.intellij.platform.backend.documentation.PsiDocumentationTargetProvider
import com.intellij.psi.util.PsiTreeUtil
import io.github.hhenson.hgl.documentation.HglDocumentation
import io.github.hhenson.hgl.documentation.HglDocumentationTargetProvider
import io.github.hhenson.hgl.psi.HglFunctionDecl

class HglCodeDocumentationTest : HglEditorFixture() {
    private fun targets(text: String) = run {
        myFixture.configureByText("docs.hgl", text.trimIndent())
        val provider = DocumentationTargetProvider.EP_NAME.extensionList.filterIsInstance<HglDocumentationTargetProvider>().single()
        provider.documentationTargets(myFixture.file, myFixture.caretOffset)
    }

    private fun hint(text: String) = targets(text).single().computeDocumentationHint()!!

    fun testImportedCallHoverHasSignatureAndSummary() {
        myFixture.addFileToProject("library.hgl", """
            module library
            /** Preserve <x> & y.
            Notes:
                Supplier details.
            */
            export const fn keep<T>(value: T) -> T => value
        """.trimIndent())
        val target = targets("module consumer\nuse library as lib\nfn run_it() => lib::ke<caret>ep(1)").single()
        val hint = target.computeDocumentationHint()!!
        assertTrue(hint, hint.contains("export const fn keep&lt;T&gt;(value: T) -&gt; T"))
        assertTrue(hint.contains("Preserve &lt;x&gt; &amp; y."))
        assertFalse(hint.contains("Supplier details"))
        assertFalse(hint.contains("=&gt; value"))
        assertNotNull(target.computeDocumentation())
        assertEquals(hint, target.createPointer().dereference()?.computeDocumentationHint())
    }

    fun testQuickDocFormatsSectionsAndPreservesUnsupportedMarkup() {
        val source = """
            module contracts
            /** Preserve <data>.

            Args:
                value: The input.

            Examples:
                .. code-block:: hgl

                    keep("<unsafe>")

            Custom:
                Uninterpreted content.
            */
            const fn ke<caret>ep(value: str) -> str => value
        """
        val editorTarget = targets(source).single()
        val function = PsiTreeUtil.findChildOfType(myFixture.file, HglFunctionDecl::class.java)!!
        val provider = PsiDocumentationTargetProvider.EP_NAME.extensionList.filterIsInstance<HglDocumentationTargetProvider>().single()
        val psiTarget = provider.documentationTarget(function, null)!!
        assertEquals(editorTarget.computeDocumentationHint(), psiTarget.computeDocumentationHint())
        val html = HglDocumentation.html(function)!!
        assertTrue(html.contains("<h4>Args:</h4>"))
        assertTrue(html.contains("<h4>Examples:</h4>"))
        assertTrue(html.contains(".. code-block:: hgl\n\n    keep(&quot;&lt;unsafe&gt;&quot;)"))
        assertTrue(html.contains("Custom:\n    Uninterpreted content."))
        assertFalse(html.contains("<h4>Custom:"))
    }

    fun testParameterAndGenericHoverUseOnlyTheMatchingEntry() {
        val source = """
            module docs
            /** Enclosing summary.

            Args:
                other: Other input.
                value: Value to return.
                    Continued here.

            Type Args:
                T:
                    Element type.
            */
            const fn keep<T>(value: T, other: T) -> T => value
        """.trimIndent()
        val parameter = hint(source.replace("=> value", "=> val<caret>ue"))
        assertTrue(parameter.contains("value: T"))
        assertTrue(parameter.contains("Value to return.\n    Continued here."))
        assertFalse(parameter.contains("Other input"))
        assertFalse(parameter.contains("Enclosing summary"))
        val generic = hint(source.replace("value: T,", "value: <caret>T,"))
        assertTrue(generic.contains("Element type."))
        assertFalse(generic.contains("Value to return"))
    }

    fun testUndocumentedSymbolsShowTheirOwnSignatures() {
        for ((body, signature) in listOf(
            "fn outer() {}\nfn g(v: i64) -> i64 => v\nfn f() => <caret>g(42)" to "fn g(v: i64) -&gt; i64",
            "fn f(value: i64) -> i64 => val<caret>ue" to "value: i64",
            "fn f() {\nlet local: str = \"private value\"\nreturn lo<caret>cal\n}" to "let local: str",
            "fn f() {\nlet lo<caret>cal = 42\n}" to "let local",
            "struct Outer {\nfi<caret>eld: i64\n}" to "field: i64",
        )) {
            val html = hint("module docs\n/** Outer documentation. */\n" + body)
            assertTrue(html, html.contains("<pre>$signature</pre>"))
            assertFalse(html.contains("Outer documentation"))
            assertFalse(html.contains("private value"))
        }
    }

    fun testNamedArgumentsInCallsAndEvalShowTheTargetParameterDescription() {
        val source = """
            module docs
            /** Echo a tick.
            Args:
                value: The published value.
            */
            fn echo(value: i64) -> i64 => value
        """.trimIndent()
        for (use in listOf(
            "fn graph(x: i64) -> i64 => echo(val<caret>ue: x)",
            "test replay { assert eval echo(val<caret>ue: [1]) == [1] }",
        )) {
            val result = hint("$source\n$use")
            assertTrue(result.contains("value: i64"))
            assertTrue(result.contains("The published value."))
        }
        assertEmpty(targets("$source\nfn graph(x: i64) -> i64 => echo(oth<caret>er: x)"))
        assertEmpty(targets("$source\nfn graph(x: i64) -> i64 => echo{val<caret>ue: 2}(x)"))
    }

    fun testCallbackSlotDoesNotInheritAnOuterArgumentDescription() {
        val source = """
            module docs
            /** Supply the callback with a count.
            Args:
                count: Outer count.
            */
            fn relay(f: fn(count: i64) -> i64, count: i64) -> i64 => f(count: count)
        """.trimIndent()
        for (site in listOf("fn(count:", "f(count:")) {
            val result = hint(source.replace(site, site.replace("count", "co<caret>unt")))
            assertTrue(result.contains("count: i64"))
            assertFalse(result.contains("Outer count"))
        }
    }

    fun testGenericConstructorArgumentShowsFieldDocumentation() {
        val result = hint("""
            module docs
            struct Box<T> {
                /** Stored item. */
                value: T
            }
            fn boxed(x: i64) -> Box<i64> => Box<i64>(val<caret>ue: x)
        """)
        assertTrue(result.contains("value: T"))
        assertTrue(result.contains("Stored item."))
    }

    fun testOverloadHintsKeepBothDeclarationsDistinct() {
        val calls = targets("""
            module docs
            /** Value copy. */
            const fn copy(x: i64) -> i64 => x
            /** Graph copy. */
            fn copy(x: i64) -> i64 => x
            fn forward(x: i64) -> i64 => co<caret>py(x)
        """).map { it.computeDocumentationHint()!! }
        assertSize(2, calls)
        assertTrue(calls.single { it.contains("Value copy.") }.contains("const fn copy"))
        assertFalse(calls.single { it.contains("Graph copy.") }.contains("const fn copy"))
    }

    fun testOperatorCallUsesPublicDocumentationAndImplKeepsItsOwn() {
        val source = """
            module ops
            /** Public contract. */
            operator identity<T>(value: T) -> T
            /** Integer implementation. */
            impl const fn identity(value: i64) -> i64 => value
            const fn use_it() -> i64 => identity(1)
        """.trimIndent()
        assertTrue(hint(source.replace("=> identity(1)", "=> iden<caret>tity(1)")).contains("Public contract."))
        assertTrue(hint(source.replace("fn identity", "fn iden<caret>tity")).contains("Integer implementation."))
    }
}
