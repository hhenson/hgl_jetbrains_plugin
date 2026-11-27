package io.github.hhenson.hgl

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.util.PsiTreeUtil
import io.github.hhenson.hgl.highlighting.HglColors
import io.github.hhenson.hgl.psi.HglLocalDecl
import io.github.hhenson.hgl.psi.HglParameter
import io.github.hhenson.hgl.psi.HglQualifiedName

class HglDescriptorTypeTest : HglEditorFixture() {
    fun testDescriptorAnnotationsAndOrdinaryValueAndMemberNames() {
        val source = """
            module descriptors
            enum Kind { Type }
            struct Row { Type: Kind }
            const fn inspect() -> Type {
                let Type = Row(Type: Kind.Type)
                let field = Type.Type
                let t: Type = type(42)
                let ts: list<Type?> = [t]
                return type(type(t))
            }
        """.trimIndent()
        myFixture.configureByText("descriptors.hgl", source)
        val colours = myFixture.doHighlighting(HighlightSeverity.INFORMATION)
        fun keyAt(start: Int) = colours.filter { it.startOffset == start && it.endOffset == start + 4 }
            .map { it.forcedTextAttributesKey }
        for ((context, offset) in listOf("-> Type" to 3, "t: Type" to 3, "list<Type" to 5)) {
            val start = source.indexOf(context) + offset
            assertContainsElements(keyAt(start), HglColors.CONTEXTUAL_KEYWORD)
            assertNull(myFixture.file.findReferenceAt(start)?.resolve())
        }
        val field = source.indexOf("Type.Type")
        assertTrue(myFixture.file.findReferenceAt(field)?.resolve() is HglLocalDecl)
        assertContainsElements(keyAt(field), HglColors.LOCAL_VARIABLE)
        assertContainsElements(keyAt(field + 5), HglColors.FIELD)
        assertContainsElements(keyAt(source.indexOf("Row(Type:") + 4), HglColors.NAMED_ARGUMENT)
    }

    fun testGenericTypeValueAndCallSuppliedSlots() {
        myFixture.addFileToProject("lib.hgl", """
            module lib
            export struct Box<T> { item: T }
            export struct Wrapped<T> { box: Box<T> }
            export const fn limit<N>() -> bool requires N != 0 => true
            export const fn sized<N>(items: list<i64, N>) -> i64 => 1
            export const fn flags<const N: i64>() -> i64 => N
            export const fn choose<X>() -> i64 => 1
            export const fn typed<X>() -> bool requires X in {i64, f64} => true
        """.trimIndent())
        val source = """
            module client
            use lib as l
            const fn check(Type: i64) -> i64 {
                let box: l::Box<Type> = l::Box<Type>(type(42))
                let wrapped: l::Wrapped<Type> = l::Wrapped(box)
                let a = l::flags<Type>()
                let b = l::sized<Type>([1])
                let c = l::choose<Type>()
                let d = l::typed<Type>()
                return c
            }
            const fn other() -> i64 => l::choose<Type>()
            const fn missing() -> i64 => l::flags<Type>()
            const fn missingLimit() -> bool => l::limit<Type>()
        """.trimIndent()
        myFixture.configureByText("client.hgl", source)
        val colours = myFixture.doHighlighting(HighlightSeverity.INFORMATION)
        val names = PsiTreeUtil.findChildrenOfType(myFixture.file, HglQualifiedName::class.java)
            .filter { it.text == "Type" }.sortedBy { it.textOffset }
        val expected = listOf(
            "descriptor", "descriptor", "descriptor", "parameter", "parameter", "parameter",
            "descriptor", "descriptor", "unresolved", "unresolved"
        )
        assertEquals(expected.size, names.size)
        for ((name, role) in names.zip(expected)) {
            val reference = name.reference as PsiPolyVariantReference
            val target = reference.multiResolve(false).singleOrNull()?.element
            val keyword = colours.any { it.startOffset == name.textOffset && it.endOffset == name.textRange.endOffset &&
                it.forcedTextAttributesKey == HglColors.CONTEXTUAL_KEYWORD }
            assertEquals("at offset ${name.textOffset}", role == "descriptor", keyword)
            if (role == "parameter") assertTrue(target is HglParameter) else assertNull(target)
        }
    }

    fun testGenericDescriptorCompletionInExpressionBody() {
        fun suggestions(call: String): List<String> {
            myFixture.configureByText("completion.hgl", """
                module completion
                const fn pick<T>(x: T) -> T => x
                const fn count<const N: i64>() -> i64 => N
                const fn sample() -> Type => $call
            """.trimIndent())
            myFixture.completeBasic()
            return myFixture.lookupElementStrings.orEmpty()
        }
        assertContainsElements(suggestions("pick<<caret>>(type(1))"), "Type")
        assertDoesntContain(suggestions("count<<caret>>()"), "Type")
    }
}
