package io.github.hhenson.hgl.reference

import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.PsiPolyVariantReferenceBase
import com.intellij.psi.ResolveResult
import io.github.hhenson.hgl.psi.HglImportItem
import io.github.hhenson.hgl.psi.HglModulePath
import io.github.hhenson.hgl.psi.HglNamedElement
import io.github.hhenson.hgl.psi.HglQualifiedName
import io.github.hhenson.hgl.psi.HglUseDecl
import io.github.hhenson.hgl.structure.HglPresentation

/**
 * A `name` or `alias::name` in expression or type position. `alias` must be
 * a `use module.path as alias` in the same file; the second part then names a
 * declaration of that module. Several declarations may share a name
 * (operator overloads, `impl fn` candidates), so the reference is poly-variant.
 */
class HglQualifiedNameReference(element: HglQualifiedName) :
    PsiPolyVariantReferenceBase<HglQualifiedName>(element, lastNameRange(element)) {

    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> {
        val names = element.identList
        val targets: List<PsiElement> = when (names.size) {
            1 -> HglScope.resolve(element, names[0].text)
            2 -> resolveQualified(names[0].text, names[1].text)
            else -> emptyList()
        }
        return PsiElementResolveResult.createResults(targets)
    }

    private fun resolveQualified(alias: String, name: String): List<PsiElement> {
        val useAlias = HglScope.findAlias(element, alias) ?: return emptyList()
        val useDecl = useAlias.parent as? HglUseDecl ?: return emptyList()
        return HglScope.targetFiles(useDecl).flatMap { HglScope.exportedDeclarations(it, name) }
    }

    override fun getVariants(): Array<Any> {
        if (element.identList.size > 1) return emptyArray()
        return HglScope.visibleDeclarations(element)
            .filter { !it.name.isNullOrEmpty() }
            .distinctBy { it.name }
            .map { lookupFor(it) }
            .toList()
            .toTypedArray()
    }

    override fun handleElementRename(newElementName: String): PsiElement {
        val last = element.identList.lastOrNull() ?: return element
        last.replace(io.github.hhenson.hgl.psi.HglElementFactory.createIdent(element.project, newElementName))
        return element
    }

    companion object {
        fun lastNameRange(element: HglQualifiedName): TextRange {
            val last = element.identList.lastOrNull() ?: return TextRange.EMPTY_RANGE
            return last.textRangeInParent
        }

        fun lookupFor(declaration: HglNamedElement): LookupElement =
            LookupElementBuilder.create(declaration, declaration.name ?: "")
                .withIcon(HglPresentation.iconOf(declaration))
                .withTypeText(HglPresentation.kindOf(declaration), true)
                .withTailText(HglPresentation.tailOf(declaration), true)
    }
}

/** The declaration an imported name refers to in its module. */
class HglImportItemReference(element: HglImportItem) :
    PsiPolyVariantReferenceBase<HglImportItem>(element, element.nameIdentifier?.textRangeInParent ?: TextRange.EMPTY_RANGE) {

    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> =
        PsiElementResolveResult.createResults(element.importedDeclarations())

    override fun handleElementRename(newElementName: String): PsiElement = element.setName(newElementName)
}

/** A module path in `use`: resolves to the file (or part files) declaring that module. */
class HglModulePathReference(element: HglModulePath) :
    PsiPolyVariantReferenceBase<HglModulePath>(element, TextRange(0, element.textLength)) {

    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> {
        val useDecl = element.parent as? HglUseDecl ?: return ResolveResult.EMPTY_ARRAY
        return PsiElementResolveResult.createResults(HglScope.targetFiles(useDecl))
    }
}
