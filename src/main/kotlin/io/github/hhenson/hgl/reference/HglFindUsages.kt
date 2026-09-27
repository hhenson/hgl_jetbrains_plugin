package io.github.hhenson.hgl.reference

import com.intellij.lang.cacheBuilder.DefaultWordsScanner
import com.intellij.lang.cacheBuilder.WordsScanner
import com.intellij.lang.findUsages.FindUsagesProvider
import com.intellij.lang.refactoring.RefactoringSupportProvider
import com.intellij.psi.PsiElement
import io.github.hhenson.hgl.lexer.HglLexerAdapter
import io.github.hhenson.hgl.psi.HglNamedElement
import io.github.hhenson.hgl.psi.HglTokenSets
import io.github.hhenson.hgl.structure.HglPresentation

class HglFindUsagesProvider : FindUsagesProvider {
    override fun getWordsScanner(): WordsScanner =
        DefaultWordsScanner(HglLexerAdapter(), HglTokenSets.NAME_TOKENS, HglTokenSets.COMMENTS, HglTokenSets.STRINGS)

    override fun canFindUsagesFor(psiElement: PsiElement): Boolean = psiElement is HglNamedElement

    override fun getHelpId(psiElement: PsiElement): String? = null

    override fun getType(element: PsiElement): String = HglPresentation.kindOf(element).ifEmpty { "declaration" }

    override fun getDescriptiveName(element: PsiElement): String =
        (element as? HglNamedElement)?.name ?: element.text

    override fun getNodeText(element: PsiElement, useFullName: Boolean): String {
        val name = (element as? HglNamedElement)?.name ?: return element.text
        return if (useFullName) name + HglPresentation.tailOf(element) else name
    }
}

class HglRefactoringSupportProvider : RefactoringSupportProvider() {
    override fun isMemberInplaceRenameAvailable(element: PsiElement, context: PsiElement?): Boolean =
        element is HglNamedElement

    override fun isSafeDeleteAvailable(element: PsiElement): Boolean = false
}
