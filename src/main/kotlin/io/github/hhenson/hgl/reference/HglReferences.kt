package io.github.hhenson.hgl.reference

import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.PsiPolyVariantReferenceBase
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.ResolveResult
import com.intellij.psi.util.parentOfType
import io.github.hhenson.hgl.HglLanguage
import io.github.hhenson.hgl.psi.HglAnonymousParameter
import io.github.hhenson.hgl.psi.HglCacheDecl
import io.github.hhenson.hgl.psi.HglConfigurationParameter
import io.github.hhenson.hgl.psi.HglConstantDecl
import io.github.hhenson.hgl.psi.HglElementFactory
import io.github.hhenson.hgl.psi.HglEnumDecl
import io.github.hhenson.hgl.psi.HglFieldExpr
import io.github.hhenson.hgl.psi.HglImportItem
import io.github.hhenson.hgl.psi.HglInjectItem
import io.github.hhenson.hgl.psi.HglLifecycleStmt
import io.github.hhenson.hgl.psi.HglLocalDecl
import io.github.hhenson.hgl.psi.HglMemberName
import io.github.hhenson.hgl.psi.HglModulePath
import io.github.hhenson.hgl.psi.HglNamedElement
import io.github.hhenson.hgl.psi.HglNativeTypeDecl
import io.github.hhenson.hgl.psi.HglParameter
import io.github.hhenson.hgl.psi.HglQualifiedName
import io.github.hhenson.hgl.psi.HglReferenceExpr
import io.github.hhenson.hgl.psi.HglStateDecl
import io.github.hhenson.hgl.psi.HglUseDecl
import io.github.hhenson.hgl.psi.HglWhenStmt
import io.github.hhenson.hgl.structure.HglPresentation

/**
 * A `name` or `alias::name` in expression or type position. `alias` must be
 * a `use module.path as alias` in the same file; the second part then names a
 * declaration of that module. Several declarations may share a name
 * (operator overloads, `impl fn` candidates), so the reference is poly-variant.
 */
class HglQualifiedNameReference(element: HglQualifiedName) :
    PsiPolyVariantReferenceBase<HglQualifiedName>(element, lastNameRange(element)) {

    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> =
        PsiElementResolveResult.createResults(declarations(includeImplementations = true))

    internal fun declarations(includeImplementations: Boolean): List<PsiElement> {
        if (element.isDescriptorType()) return emptyList()
        val names = element.identList
        return when (names.size) {
            1 -> HglScope.resolve(element, names[0].text, includeImplementations)
            2 -> {
                val name = names[1].text
                val exported = resolveQualified(names[0].text, name)
                if (includeImplementations && HglScope.isTestCode(element)) HglScope.withTestCandidates(element, name, exported) else exported
            }
            else -> emptyList()
        }
    }

    private fun resolveQualified(alias: String, name: String): List<PsiElement> {
        val useAlias = HglScope.findAlias(element, alias) ?: return emptyList()
        val useDecl = useAlias.parent as? HglUseDecl ?: return emptyList()
        return HglScope.targetFiles(useDecl).flatMap { HglScope.exportedDeclarations(it, name) }
    }

    override fun getVariants(): Array<Any> {
        if (element.identList.size > 1) return emptyArray()
        val variants = HglScope.visibleDeclarations(element)
            .filter { !it.name.isNullOrEmpty() && !(it.name == "Type" && element.expectsDescriptorType()) }
            .distinctBy { it.name }
            .map { lookupFor(it) }
            .toMutableList()
        if (element.parent is HglReferenceExpr &&
            (element.parentOfType<HglWhenStmt>() != null || element.parentOfType<HglLifecycleStmt>() != null)) {
            val visible = variants.mapTo(HashSet()) { it.lookupString }
            for (name in HglLanguage.endpointPredicates - visible) {
                variants.add(LookupElementBuilder.create(name).bold().withTypeText("endpoint predicate"))
            }
        }
        return variants.toTypedArray()
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

/** CAP-1. Injection binds the Core type even when a user declaration has the same name. */
internal val HglInjectItem.capabilityType: String? get() = when (name) {
    "clock" -> "Clock"
    "logger" -> "Logger"
    "scheduler" -> "Scheduler"
    "run_config" -> "RunConfig"
    else -> null
}

internal fun PsiElement.declaredValueType(): HglQualifiedName? = when (this) {
    is HglParameter -> type
    is HglConfigurationParameter -> type
    is HglAnonymousParameter -> type
    is HglLocalDecl -> type
    is HglConstantDecl -> type
    is HglStateDecl -> type
    is HglCacheDecl -> type
    else -> null
}?.namedType?.qualifiedName

internal fun fieldMemberReference(member: HglMemberName): PsiReferenceBase<HglMemberName>? {
    val receiver = (member.parent as? HglFieldExpr)?.expr as? HglReferenceExpr ?: return null
    val binding = receiver.qualifiedName.reference?.resolve() ?: return null
    if (binding is HglEnumDecl) return HglFieldMemberReference(member, binding.enumMemberList)
    val owner = if (binding is HglInjectItem) {
        val type = binding.capabilityType ?: return null
        HglModuleIndex.findFiles(member.project, "core").flatMap { HglScope.exportedDeclarations(it, type) }.singleOrNull()
    } else binding.declaredValueType()?.reference?.resolve()
    val body = (owner as? HglNativeTypeDecl)?.nativeTypeBody ?: return null
    return HglFieldMemberReference(member, body.nativePropertyList)
}

private class HglFieldMemberReference(member: HglMemberName, private val members: List<HglNamedElement>) :
    PsiReferenceBase<HglMemberName>(member, TextRange(0, member.textLength)) {

    override fun resolve(): PsiElement? = members.singleOrNull { it.name == element.text }

    override fun getVariants(): Array<LookupElement> =
        members.map(HglQualifiedNameReference::lookupFor).toTypedArray()

    override fun handleElementRename(newElementName: String): PsiElement =
        element.replace(HglElementFactory.createMemberName(element.project, newElementName))
}
