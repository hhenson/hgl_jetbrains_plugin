package io.github.hhenson.hgl

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.colors.TextAttributesKey
import io.github.hhenson.hgl.highlighting.HglColors
import io.github.hhenson.hgl.psi.HglNativeFunctionDecl
import java.io.File

class HglEndpointPredicateTest : HglEditorFixture() {
    private val predicates = listOf("modified", "valid", "all_valid")
    private val guard = "fn ready(xs: list<i64, 2>) -> bool { when modified(xs) && valid(xs) && all_valid(xs) { return all_valid(xs) } }"

    private fun addCore() {
        val source = File("src/test/testData/parser/core/hgl_native_endpoints.hgl")
        myFixture.addFileToProject("core/endpoints.hgl", source.readText())
    }

    private fun assertCallColours(body: String, vararg expected: TextAttributesKey) {
        val source = "module consumer\n$body"
        myFixture.configureByText("consumer.hgl", source)
        val calls = Regex("""\b(?:modified|valid|all_valid)(?=\()""").findAll(source).toList()
        assertEquals(expected.size, calls.size)
        val highlights = myFixture.doHighlighting(HighlightSeverity.INFORMATION)
        for ((match, key) in calls.zip(expected.asList())) {
            val keys = highlights.filter { it.startOffset == match.range.first && it.endOffset == match.range.last + 1 }
                .map { it.forcedTextAttributesKey }
            assertContainsElements("call ${match.value} at ${match.range.first} has $keys", keys, key)
        }
    }

    fun testPredicateCallsInGuardsAndBodiesWithAndWithoutCoreSources() {
        val colours = Array(4) { HglColors.CONTEXTUAL_KEYWORD }
        assertCallColours(guard, *colours)
        addCore()
        assertCallColours(guard, *colours)
        myFixture.configureByText("consumer.hgl", "module consumer\n" + guard.replace("return all_valid", "return all_<caret>valid"))
        assertEquals("all_valid", (myFixture.elementAtCaret as HglNativeFunctionDecl).name)
    }

    fun testLocalCallbackAndQualifiedLibraryFunctionKeepTheirColours() {
        myFixture.addFileToProject("other.hgl", "module other\nexport const fn all_valid(x: i64) -> bool => true")
        addCore()
        val code = """
            use other as dep
            fn ready(all_valid: const fn(i64) -> bool, x: i64) -> bool {
                when modified(x) { return all_valid(x) && dep::all_valid(x) }
            }
        """.trimIndent()
        assertCallColours(code, HglColors.CONTEXTUAL_KEYWORD, HglColors.PARAMETER, HglColors.FUNCTION_CALL)
    }

    fun testExplicitCoreImportsWithoutCoreSources() {
        val code = "use core::{valid}\nuse core as c\nfn ready(x: i64) -> bool { when valid(x) { return c::all_valid(x) } }"
        assertCallColours(code, HglColors.CONTEXTUAL_KEYWORD, HglColors.CONTEXTUAL_KEYWORD)
    }

    private fun completions(body: String): List<String> {
        myFixture.configureByText("consumer.hgl", "module consumer\n$body")
        myFixture.completeBasic()
        return myFixture.lookupElementStrings.orEmpty()
    }

    fun testHandlerCompletionOffersEachPredicateOnceWithAndWithoutCore() {
        val header = "fn ready(xs: list<i64, 2>) -> bool"
        val whenHeader = "$header { when <caret> { return true } }"
        for (body in listOf(whenHeader, "$header { when { return <caret> } }")) {
            assertSameElements(completions(body).filter { it in predicates }, predicates)
        }
        assertEmpty(completions("const fn f(x: i64) -> i64 => <caret>").filter { it in predicates })
        addCore()
        assertSameElements(completions(whenHeader).filter { it in predicates }, predicates)
    }
}
