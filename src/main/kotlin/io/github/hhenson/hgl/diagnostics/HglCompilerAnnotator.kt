package io.github.hhenson.hgl.diagnostics

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiElement
import io.github.hhenson.hgl.psi.HglFile

class HglCompilerAnnotator : Annotator {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        if (element !is HglFile) return
        val service = element.project.getServiceIfCreated(HglCheckService::class.java) ?: return
        val file = element.virtualFile ?: return
        val document = element.viewProvider.document ?: return
        for (item in service.diagnostics(file, document)) {
            val range = item.range(document) ?: continue
            val tooltip = (listOf(item.message) + item.details)
                .joinToString("<br>", "<html>", "</html>", transform = StringUtil::escapeXmlEntities)
            val annotation = holder.newAnnotation(item.severity, item.message).range(range).tooltip(tooltip)
            if (range.isEmpty && (document.textLength == 0 || range.endOffset == document.getLineEndOffset(item.end.line))) {
                annotation.afterEndOfLine()
            }
            annotation.create()
        }
    }
}
