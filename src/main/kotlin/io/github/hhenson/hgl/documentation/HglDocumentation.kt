package io.github.hhenson.hgl.documentation

import com.intellij.model.Pointer
import com.intellij.platform.backend.documentation.DocumentationResult
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.platform.backend.documentation.DocumentationTargetProvider
import com.intellij.platform.backend.documentation.PsiDocumentationTargetProvider
import com.intellij.platform.backend.presentation.TargetPresentation
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.util.parentOfType
import com.intellij.psi.util.PsiTreeUtil
import io.github.hhenson.hgl.psi.*
import io.github.hhenson.hgl.reference.HglQualifiedNameReference

class HglDocumentationTargetProvider : DocumentationTargetProvider, PsiDocumentationTargetProvider {
    override fun documentationTarget(element: PsiElement, originalElement: PsiElement?): DocumentationTarget? {
        val declaration = if (element is HglFile) element.moduleDecl ?: element.applicationDecl else element
        return declaration?.takeIf { HglDocumentation.signatureEnd(it) != null }?.let(::HglDocumentationTarget)
    }

    override fun documentationTargets(file: PsiFile, offset: Int): List<DocumentationTarget> {
        if (file !is HglFile) return emptyList()
        val reference = file.findReferenceAt(offset)
        val resolved = when (reference) {
            // SDOC-4: at a call the operator owns its public documentation.
            is HglQualifiedNameReference -> reference.declarations(includeImplementations = false)
            is PsiPolyVariantReference -> reference.multiResolve(false).mapNotNull { it.element }
            null -> emptyList()
            else -> listOfNotNull(reference.resolve())
        }
        val local = resolved.distinct().mapNotNull { documentationTarget(it, null) }
        if (local.isNotEmpty()) return local
        if (resolved.all { it is HglImportItem }) {
            val external = when (reference) {
                is HglQualifiedNameReference -> exportTargets(reference.element)
                is io.github.hhenson.hgl.reference.HglImportItemReference -> {
                    val item = reference.element
                    val module = item.parentOfType<HglUseDecl>()?.modulePath?.dottedName
                    if (module == null) emptyList() else exportTargets(file, module, item.text)
                }
                else -> emptyList()
            }
            if (external.isNotEmpty()) return external
        }

        val leaf = file.findElementAt(offset) ?: return emptyList()
        val label = leaf.parentOfType<HglMemberName>()
        if (label?.parent is HglNamedArgument) {
            return argumentTargets(label)
        }
        val declaration = generateSequence(leaf) { it.parent }.firstOrNull {
            it is HglNamedElement || it is HglFunctionTypeParameter || it is HglModuleDecl || it is HglApplicationDecl
        } ?: return emptyList()
        val name = when (declaration) {
            is HglNamedElement -> declaration.nameIdentifier
            is HglFunctionTypeParameter -> declaration.ident
            else -> declaration
        }
        if (name?.textRange?.containsOffset(offset) != true) return emptyList()
        return listOfNotNull(documentationTarget(declaration, leaf))
    }

    private fun argumentTargets(label: HglMemberName): List<DocumentationTarget> {
        val argument = label.parent.parent as? HglArgument ?: return emptyList()
        val arguments = argument.parent as? HglArguments ?: return emptyList()
        val callee = when (val call = arguments.parent) {
            is HglCallExpr -> (call.expr as? HglReferenceExpr)?.qualifiedName
            is HglAppliedConstructorExpr -> call.qualifiedName
            is HglHarnessTarget -> call.qualifiedName
            else -> null
        }
        val reference = callee?.reference as? HglQualifiedNameReference ?: return emptyList()
        val name = label.text
        val declarations = reference.declarations(includeImplementations = false)
        if (declarations.all { it is HglImportItem }) return exportTargets(reference.element, name)
        return declarations.flatMap { target ->
            when (target) {
                is HglParameter -> target.functionType?.functionTypeParameterList.orEmpty().filter { it.ident?.text == name }
                is HglStructDecl -> target.structBody?.structMemberList.orEmpty().filter { it.name == name }
                else -> PsiTreeUtil.getChildOfType(target, HglFunctionSignature::class.java)
                    ?.parameterList.orEmpty().filter { it.name == name }
            }
        }.distinct().mapNotNull { documentationTarget(it, label) }
    }
}

private class HglDocumentationTarget(private val element: PsiElement) : DocumentationTarget, HglLinkedDoc {
    override fun symbol(name: String) = sourceDocSymbol(element, name)

    override fun createPointer(): Pointer<out DocumentationTarget> {
        val pointer = SmartPointerManager.createPointer(element)
        return Pointer { pointer.element?.let { HglDocumentationTarget(it) } }
    }
    override fun computePresentation(): TargetPresentation =
        TargetPresentation.builder(HglDocumentation.signature(element).orEmpty())
            .locationText(element.containingFile.name).presentation()
    override fun computeDocumentationHint(): String? = HglDocumentation.html(element, brief = true)
    override fun computeDocumentation(): DocumentationResult? =
        HglDocumentation.html(element)?.let { DocumentationResult.documentation(it) }
}

/** Documentation of the current source buffer. */
object HglDocumentation {
    fun attached(element: PsiElement): String? {
        if (element !is HglFunctionDecl && element !is HglNativeFunctionDecl &&
            element !is HglOperatorDecl && element !is HglStructDecl &&
            element !is HglStructMember && element !is HglTestDecl && element !is HglModuleDecl &&
            element !is HglApplicationDecl && element !is HglEnumDecl && element !is HglEnumMember &&
            element !is HglConstantDecl && element !is HglNativeTypeDecl && element !is HglNativeProperty) return null
        var leaf = PsiTreeUtil.prevLeaf(element)
        while (leaf != null && leaf.text.isBlank()) leaf = PsiTreeUtil.prevLeaf(leaf)
        if (leaf?.node?.elementType != HglTypes.DOC_COMMENT) return null
        val raw = leaf.text
        val lines = raw.substring(3, raw.length - 2).lines().toMutableList()
        if (lines.isNotEmpty()) lines[0] = lines[0].trim(' ', '\t')
        val indent = lines.drop(1).filter { it.trim(' ', '\t').isNotEmpty() }.minOfOrNull { it.length - it.trimStart(' ', '\t').length } ?: 0
        for (i in 1 until lines.size) lines[i] = lines[i].drop(indent)
        return lines.dropWhile { it.trim(' ', '\t').isEmpty() }.dropLastWhile { it.trim(' ', '\t').isEmpty() }.joinToString("\n")
    }

    /** End of the written declaration header. Initializers and implementation bodies can be arbitrarily large. */
    internal fun signatureEnd(element: PsiElement): PsiElement? = when (element) {
        is HglFunctionDecl -> element.requiresClause ?: element.functionSignature
        is HglNativeFunctionDecl -> element.requiresClause ?: element.functionSignature
        is HglOperatorDecl -> element.requiresClause ?: element.functionSignature
        is HglStructDecl -> element.requiresClause ?: element.structParents ?: element.genericParameters ?: element.ident
        is HglEnumDecl -> element.ident
        is HglNativeTypeDecl -> element.ident
        is HglTestDecl -> element.ident
        is HglConstantDecl -> element.type
        is HglStructMember -> element.type ?: element.memberName
        is HglLocalDecl -> element.type ?: element.ident
        is HglStateDecl -> element.type ?: element.ident
        is HglCacheDecl -> element.type ?: element.ident
        is HglModuleDecl, is HglApplicationDecl, is HglEnumMember, is HglNativeProperty,
        is HglParameter, is HglConfigurationParameter, is HglGenericParameter,
        is HglAnonymousParameter, is HglForBinding, is HglFunctionTypeParameter -> element
        else -> null
    }

    internal fun signature(element: PsiElement): String? {
        val end = signatureEnd(element) ?: return null
        return element.containingFile.viewProvider.contents
            .subSequence(element.textRange.startOffset, end.textRange.endOffset).toString()
    }

    private fun sections(element: PsiElement): List<HglDocSection> {
        val heading = when (element) {
            is HglParameter, is HglConfigurationParameter -> "Args:"
            is HglGenericParameter -> "Type Args:"
            else -> return attached(element)?.let(::docSections).orEmpty()
        }
        val owner = element.parent?.parent ?: return emptyList()
        val name = element.name ?: return emptyList()
        val entry = attached(owner)?.let(::docSections)?.asSequence()
            ?.filter { it.heading == heading }?.firstNotNullOfOrNull { it.description(name) }
        return listOfNotNull(entry)
    }

    fun html(element: PsiElement, brief: Boolean = false): String? {
        val signature = signature(element) ?: return null
        return docHtml(signature, sections(element), brief) { sourceDocSymbol(element, it) != null }
    }
}
