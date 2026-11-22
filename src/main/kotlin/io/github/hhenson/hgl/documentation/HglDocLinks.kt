package io.github.hhenson.hgl.documentation

import com.intellij.platform.backend.documentation.DocumentationLinkHandler
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.platform.backend.documentation.LinkResolveResult
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.hhenson.hgl.psi.*
import io.github.hhenson.hgl.reference.HglModuleIndex
import io.github.hhenson.hgl.reference.HglScope
import io.github.hhenson.hgl.reference.isOperatorImplementation

internal interface HglLinkedDoc {
    fun symbol(name: String): DocumentationTarget?
}

class HglDocLinkHandler : DocumentationLinkHandler {
    override fun resolveLink(target: DocumentationTarget, url: String): LinkResolveResult? {
        if (target !is HglLinkedDoc || !url.startsWith(SYMBOL_LINK)) return null
        val name = url.removePrefix(SYMBOL_LINK)
        if (!docName.matches(name)) return null
        return target.symbol(name)?.let { LinkResolveResult.resolvedTarget(it) }
    }
}

// A dotted reference is either module.path.declaration or module.path.Owner.member.
internal fun qualifiedDocNames(name: String): List<String> =
    name.indices.filter { name[it] == '.' }.takeLast(2).map { i -> name.take(i) + "::" + name.drop(i + 1) }

private fun member(owner: PsiElement, name: String): PsiElement? = when (owner) {
    is HglEnumDecl -> owner.enumMemberList.singleOrNull { it.name == name }
    is HglStructDecl -> owner.structBody?.structMemberList?.singleOrNull { it.name == name }
    is HglNativeTypeDecl -> owner.nativeTypeBody?.nativePropertyList?.singleOrNull { it.name == name }
    else -> null
}

internal fun sourceDocSymbol(element: PsiElement, name: String): DocumentationTarget? {
    val owner = when (element) {
        is HglParameter, is HglGenericParameter, is HglConfigurationParameter -> element.parent?.parent ?: return null
        else -> element
    }
    val file = owner.containingFile as? HglFile ?: return null
    val first = name.substringBefore('.')
    val parameters = PsiTreeUtil.getChildOfType(owner, HglFunctionSignature::class.java)?.parameterList.orEmpty() +
        PsiTreeUtil.getChildOfType(owner, HglGenericParameters::class.java)?.genericParameterList.orEmpty() +
        PsiTreeUtil.getChildOfType(owner, HglConfigurationParameters::class.java)?.configurationParameterList.orEmpty()
    if (parameters.any { it.name == first }) return null
    val local = HglScope.resolve(owner, first, includeImplementations = false)
    val tail = name.substringAfter('.', "")
    val visible = if (tail.isEmpty()) local else local.mapNotNull { member(it, tail) }
    val provider = HglDocumentationTargetProvider()
    if (visible.isNotEmpty() && visible.any { it !is HglImportItem }) {
        return visible.mapNotNull { provider.documentationTarget(it, owner) }.singleOrNull()
    }
    if (tail.isEmpty() || local.any { it is HglImportItem }) {
        val modules = file.useDecls.filter { it.importSet?.importItemList.orEmpty().any { item -> item.name == first } }
            .mapNotNull { it.modulePath?.dottedName }.ifEmpty { listOf("core") }
        return modules.flatMap { exportTargets(file, it, name) }.singleOrNull()
    }
    val targets = qualifiedDocNames(name).flatMap { canonical ->
        val module = canonical.substringBefore("::")
        val path = canonical.substringAfter("::")
        val files = HglModuleIndex.findFiles(file.project, module)
        if (files.isEmpty()) exportTargets(file, module, path)
        else files.flatMap { part ->
            val declared = if (module == file.modulePath)
                part.children.filterIsInstance<HglNamedElement>().filter { it.name == path.substringBefore('.') }
            else HglScope.exportedDeclarations(part, path.substringBefore('.'))
            declared.filterNot { it.isOperatorImplementation }.mapNotNull { if ('.' in path) member(it, path.substringAfter('.')) else it }
                .mapNotNull { provider.documentationTarget(it, owner) }
        }
    }
    return targets.singleOrNull()
}
