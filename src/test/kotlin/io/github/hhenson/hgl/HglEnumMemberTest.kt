package io.github.hhenson.hgl

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.util.PsiTreeUtil
import io.github.hhenson.hgl.documentation.HglDocumentationTargetProvider
import io.github.hhenson.hgl.psi.HglEnumDecl
import io.github.hhenson.hgl.psi.HglEnumMember

class HglEnumMemberTest : HglEditorFixture() {
    fun testEnumConversionNavigatesToDocumentedMemberThroughAliasAndImport() {
        myFixture.addFileToProject("quotes.hgl", """
            module quotes
            export enum Side {
                /** A buy order. */
                buy = -2, sell = 7
            }
        """.trimIndent())
        for ((use, type) in listOf("use quotes as q" to "q::Side", "use quotes::{Side}" to "Side")) {
            myFixture.configureByText("sample.hgl", "module sample\n$use\nconst fn value() -> i64 => convert<i64>($type.b<caret>uy)")
            val member = myFixture.elementAtCaret as HglEnumMember
            assertEquals("buy = -2", member.text)
            assertEquals("quotes.hgl", member.containingFile.name)
            val doc = HglDocumentationTargetProvider().documentationTargets(myFixture.file, myFixture.caretOffset).single()
            assertTrue(doc.computeDocumentationHint()!!.contains("A buy order."))
        }
    }

    fun testCompletionUsesOnlyTheNamedEnumIncludingKeywordMembers() {
        val declarations = "module choices\nenum Side { buy, sell, default, Type }\nenum Result { missing, buy }\n"
        myFixture.configureByText("choices.hgl", declarations + "const fn side() -> Side => Side.<caret>")
        myFixture.completeBasic()
        val members = myFixture.lookupElementStrings.orEmpty()
        assertContainsElements(members, "buy", "sell", "default", "Type")
        assertDoesntContain(members, "missing", "Side", "return")
    }

    fun testLocalValueShadowsEnumAndChainedFieldsStayFields() {
        val source = """
            module prices
            enum Side { buy }
            struct Row { buy: i64, side: Side }
            const fn local(Side: Row) -> i64 => Side.b<caret>uy
        """.trimIndent()
        for (text in listOf(source, source.replace("Side.b<caret>uy", "Side.side.b<caret>uy"))) {
            myFixture.configureByText("prices.hgl", text)
            assertNull(myFixture.file.findReferenceAt(myFixture.caretOffset)?.resolve())
            assertEmpty(HglDocumentationTargetProvider().documentationTargets(myFixture.file, myFixture.caretOffset))
        }
    }

    fun testKeywordMemberRenamePreservesTheDeclarationAndReferenceShape() {
        myFixture.configureByText("keyword.hgl", "module keyword\nenum Side { buy }\nconst fn side() -> Side => Side.b<caret>uy")
        val reference = myFixture.file.findReferenceAt(myFixture.caretOffset)!!
        val member = reference.resolve() as HglEnumMember
        WriteCommandAction.runWriteCommandAction(project) {
            reference.handleElementRename("default")
            member.setName("default")
        }
        val enum = PsiTreeUtil.findChildOfType(myFixture.file, HglEnumDecl::class.java)!!
        assertEquals("default", enum.enumMemberList.single().memberName.text)
        val use = myFixture.file.text.indexOf("Side.default") + 5
        assertEquals(enum.enumMemberList.single(), myFixture.file.findReferenceAt(use)?.resolve())
    }
}
