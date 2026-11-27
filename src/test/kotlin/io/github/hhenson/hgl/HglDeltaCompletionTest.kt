package io.github.hhenson.hgl

import com.intellij.codeInsight.lookup.Lookup

class HglDeltaCompletionTest : HglEditorFixture() {
    private val header = "module delta_labels\nconst fn sample(other: i64) -> str => convert<str>("

    private fun complete(expression: String): Set<String> {
        myFixture.configureByText("sample.hgl", header + expression + ")")
        myFixture.completeBasic()
        return myFixture.lookupElementStrings.orEmpty().toSet()
    }

    fun testDeltaIsOfferedInATypeAnnotation() {
        myFixture.configureByText("annotation.hgl", "module labels\nstruct Change { payload: <caret> }")
        myFixture.completeBasic()
        assertContainsElements(myFixture.lookupElementStrings.orEmpty(), "delta")
    }

    fun testCollectionTypeDeterminesTheConstructorProperties() {
        for (type in listOf("list<i64>", "list<i64, 2>")) {
            assertEquals(setOf("updated", "removed", "size_modified"), complete("delta<$type>(<caret>)"))
        }
        assertEquals(setOf("added", "removed"), complete("delta<set<i64>>(<caret>)"))
        assertEquals(setOf("updated", "removed"), complete("delta<dict<str, i64>>(<caret>)"))
    }

    fun testSkipPropertiesAlreadyWrittenAndUseTheInnermostConstructor() {
        assertEquals(setOf("removed", "size_modified"), complete("delta<list<i64>>(updated: [0: 4], <caret>)"))
        assertEquals(setOf("updated", "removed"), complete("delta<list<i64>>(<caret>, size_modified: 3)"))
        assertEquals(setOf("added", "removed"), complete("delta<dict<str, set<i64>>>(updated: [\"x\": delta<set<i64>>(<caret>)])"))
        for (expression in listOf("delta<list<i64>>(size_modified: <caret>)", "delta<list<i64>?>(<caret>)",
                "delta<i64>(<caret>)", "delta<tuple<i64, i64>>(<caret>)")) {
            assertDoesntContain(complete(expression), "updated", "removed", "added", "size_modified")
        }
    }

    fun testInsertingAPropertyAddsOrReusesItsColon() {
        for ((input, expected) in listOf(
            "delta<list<i64>>(<caret>)" to "delta<list<i64>>(updated: <caret>)",
            "delta<list<i64>>(up<caret>dated: [0: 7])" to "delta<list<i64>>(updated: <caret>[0: 7])",
        )) {
            complete(input)
            myFixture.lookup?.let { lookup ->
                lookup.currentItem = lookup.items.single { it.lookupString == "updated" }
                myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR)
            }
            myFixture.checkResult(header + expected + ")")
        }
    }
}
