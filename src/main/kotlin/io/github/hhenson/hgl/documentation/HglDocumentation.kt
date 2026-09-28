package io.github.hhenson.hgl.documentation

import com.intellij.model.Pointer
import com.intellij.platform.backend.documentation.DocumentationResult
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.platform.backend.documentation.DocumentationTargetProvider
import com.intellij.platform.backend.presentation.TargetPresentation
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.util.PsiTreeUtil
import io.github.hhenson.hgl.psi.*

class HglDocumentationTargetProvider : DocumentationTargetProvider {
    override fun documentationTargets(file: PsiFile, offset: Int): List<DocumentationTarget> {
        if (file !is HglFile) return emptyList()
        val reference = file.findReferenceAt(offset)
        val resolved = when (reference) {
            is PsiPolyVariantReference -> reference.multiResolve(false).mapNotNull { it.element }
            null -> emptyList()
            else -> listOfNotNull(reference.resolve())
        }
        val candidates = if (resolved.isNotEmpty()) resolved else {
            val leaf = file.findElementAt(offset) ?: return emptyList()
            generateSequence(leaf) { it.parent }.takeWhile { it !is PsiFile }.toList()
        }
        return candidates.filter { HglDocumentation.attached(it) != null }
            .distinct().map { HglDocumentationTarget(it) }
    }
}

private class HglDocumentationTarget(private val element: PsiElement) : DocumentationTarget {
    override fun createPointer(): Pointer<out DocumentationTarget> {
        val pointer = SmartPointerManager.createPointer(element)
        return Pointer { pointer.element?.let { HglDocumentationTarget(it) } }
    }
    override fun computePresentation(): TargetPresentation =
        TargetPresentation.builder((element as? HglNamedElement)?.name ?: "HGL module").presentation()
    override fun computeDocumentation(): DocumentationResult? =
        HglDocumentation.html(element)?.let { DocumentationResult.documentation(it) }
}

/** reST stays literal in the editor; the compiler's reST export is the publication input. */
object HglDocumentation {
    fun attached(element: PsiElement): String? {
        if (element !is HglFunctionDecl && element !is HglNativeFunctionDecl &&
            element !is HglOperatorDecl && element !is HglStructDecl &&
            element !is HglStructMember && element !is HglTestDecl && element !is HglModuleDecl) return null
        var leaf = PsiTreeUtil.prevLeaf(element)
        while (leaf != null && leaf.text.isBlank()) leaf = PsiTreeUtil.prevLeaf(leaf)
        if (leaf?.node?.elementType != HglTypes.DOC_COMMENT) return null
        val raw = leaf.text
        val lines = raw.substring(3, raw.length - 2).replace("\r\n", "\n").split('\n').toMutableList()
        if (lines.isNotEmpty()) lines[0] = lines[0].trim()
        val indent = lines.drop(1).filter { it.isNotBlank() }.minOfOrNull { it.length - it.trimStart().length } ?: 0
        for (i in 1 until lines.size) lines[i] = lines[i].drop(indent)
        return lines.dropWhile { it.isBlank() }.dropLastWhile { it.isBlank() }.joinToString("\n")
    }

    fun html(element: PsiElement): String? {
        val text = attached(element) ?: return null
        val name = (element as? HglNamedElement)?.name ?: "HGL module"
        return "<div class='definition'><pre>${escape(name)}</pre></div>" +
            "<div class='content'><pre>${escape(text)}</pre></div>"
    }

    private fun escape(text: String): String = text.replace("&", "&amp;")
        .replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")
}
