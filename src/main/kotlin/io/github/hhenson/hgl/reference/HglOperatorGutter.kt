package io.github.hhenson.hgl.reference

import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerProvider
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder
import com.intellij.codeInsight.navigation.impl.PsiTargetPresentationRenderer
import com.intellij.icons.AllIcons
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.indexing.FileBasedIndex
import io.github.hhenson.hgl.psi.*

class HglOperatorGutter : RelatedItemLineMarkerProvider() {
    override fun getName() = "HGL operator implementations"

    override fun collectNavigationMarkers(
        elements: List<PsiElement>, result: MutableCollection<in RelatedItemLineMarkerInfo<*>>, forNavigation: Boolean,
    ) {
        if (elements.isEmpty() || DumbService.isDumb(elements.first().project)) return
        val links = OperatorLinks()
        val seen = mutableSetOf<PsiElement>()
        for (element in elements) {
            val leaf = when {
                element.node.elementType == HglTypes.IDENTIFIER -> element
                forNavigation && element is HglNamedElement -> element.nameIdentifier?.firstChild
                else -> null
            } ?: continue
            val ident = leaf.parent as? HglIdent ?: continue
            val declaration = ident.parent as? HglNamedElement ?: continue
            if (declaration.parent !is HglFile || declaration.nameIdentifier != ident || !seen.add(leaf)) continue
            val targets = when {
                declaration is HglOperatorDecl -> links.implementationsOf(declaration)
                declaration.isOperatorImplementation -> links.operatorsOf(declaration)
                else -> continue
            }
            if (targets.isEmpty()) continue
            val isOperator = declaration is HglOperatorDecl
            val icon = if (isOperator) AllIcons.Gutter.ImplementedMethod else AllIcons.Gutter.ImplementingMethod
            val name = ident.text
            val tooltip = when {
                !isOperator -> "Implements $name"
                targets.size == 1 -> "Implemented by 1 candidate"
                else -> "Implemented by ${targets.size} candidates"
            }
            result.add(NavigationGutterIconBuilder.create(icon)
                .setTargets(targets)
                .setTooltipText(tooltip)
                .setPopupTitle(if (isOperator) "Implementations of $name" else "Operator $name")
                .setTargetRenderer { OperatorTargetRenderer }
                .createLineMarkerInfo(leaf))
        }
    }
}

/** Scope and candidate lookups are shared by overloads during one highlighting pass. */
private class OperatorLinks {
    private val operators = mutableMapOf<Pair<HglFile, String>, List<HglOperatorDecl>>()
    private val candidates = mutableMapOf<HglFile, Map<String?, List<HglNamedElement>>>()

    fun operatorsOf(candidate: HglNamedElement): List<HglOperatorDecl> {
        val file = candidate.parent as HglFile
        val name = candidate.name ?: return emptyList()
        return operators.getOrPut(file to name) {
            // Candidates add no binding of their own (GEN-4). Look outside the candidate's signature.
            HglScope.resolve(candidate, name, includeImplementations = false).filterIsInstance<HglOperatorDecl>()
        }
    }

    fun implementationsOf(operator: HglOperatorDecl): List<HglNamedElement> {
        val name = operator.name ?: return emptyList()
        val file = operator.parent as HglFile
        val manager = PsiManager.getInstance(file.project)
        val indexed = FileBasedIndex.getInstance().getContainingFiles(
            HglImplementationIndex.NAME, name, GlobalSearchScope.allScope(file.project),
        )
        val files = listOf(file) + indexed.filter { it != file.virtualFile }.mapNotNull { manager.findFile(it) as? HglFile }
        return files.flatMap { part ->
            ProgressManager.checkCanceled()
            candidates.getOrPut(part) {
                part.children.filterIsInstance<HglNamedElement>().filter { it.isOperatorImplementation }.groupBy { it.name }
            }[name].orEmpty().filter { operator in operatorsOf(it) }
        }
    }
}

internal object OperatorTargetRenderer : PsiTargetPresentationRenderer<PsiElement>() {
    private val lineBreak = Regex("[ \\t]*\\R[ \\t]*")

    override fun getElementText(element: PsiElement): String {
        val signature = PsiTreeUtil.getChildOfType(element, HglFunctionSignature::class.java) ?: return super.getElementText(element)
        val end = PsiTreeUtil.getChildOfType(element, HglRequiresClause::class.java) ?: signature
        val start = (element as? HglFunctionDecl)?.implKw?.textOffset ?: element.textRange.startOffset
        return element.containingFile.viewProvider.contents.subSequence(start, end.textRange.endOffset)
            .toString().replace(lineBreak, " ")
    }

    override fun getContainerText(element: PsiElement): String {
        val file = element.containingFile
        val path = file.virtualFile?.path ?: return file.name
        return element.project.basePath?.let { path.removePrefix("$it/") } ?: path
    }
}
