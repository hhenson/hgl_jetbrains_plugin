package io.github.hhenson.hgl

import com.intellij.codeInsight.folding.CodeFoldingManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
import io.github.hhenson.hgl.documentation.HglDocumentationTargetProvider

class HglDocumentationFoldingTest : HglEditorFixture() {
    fun testDocumentationStartsFoldedAndCanBeExpandedToEdit() {
        myFixture.testFoldingWithCollapseStatus("src/test/testData/folding/documentation.hgl")
        val editor = myFixture.editor
        val folding = editor.foldingModel
        val document = editor.document
        fun hover(): String {
            val name = document.text.indexOf("echo(")
            return HglDocumentationTargetProvider().documentationTargets(myFixture.file, name).single().computeDocumentationHint()!!
        }
        fun docRegion() = folding.allFoldRegions.single { document.charsSequence.subSequence(it.startOffset, it.endOffset).contains("Return the input") }

        assertTrue(hover().contains("Return the input unchanged."))
        val comment = docRegion()
        folding.runBatchFoldingOperation { comment.isExpanded = true }
        WriteCommandAction.runWriteCommandAction(project) {
            val start = document.text.indexOf("unchanged")
            document.replaceString(start, start + "unchanged".length, "as supplied")
        }
        PsiDocumentManager.getInstance(project).commitDocument(document)
        CodeFoldingManager.getInstance(project).updateFoldRegions(editor)
        assertTrue(docRegion().isExpanded)
        assertTrue(hover().contains("Return the input as supplied."))
    }
}
