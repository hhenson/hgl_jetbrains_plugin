package io.github.hhenson.hgl.reference

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.hhenson.hgl.psi.HglConfigurationParameters
import io.github.hhenson.hgl.psi.HglConstantDecl
import io.github.hhenson.hgl.psi.HglConstraintCall
import io.github.hhenson.hgl.psi.HglConstraintName
import io.github.hhenson.hgl.psi.HglConstraintOperand
import io.github.hhenson.hgl.psi.HglConstraintRelation
import io.github.hhenson.hgl.psi.HglConstraintTerm
import io.github.hhenson.hgl.psi.HglEnumDecl
import io.github.hhenson.hgl.psi.HglExpr
import io.github.hhenson.hgl.psi.HglFunctionSignature
import io.github.hhenson.hgl.psi.HglGenericArgument
import io.github.hhenson.hgl.psi.HglGenericArguments
import io.github.hhenson.hgl.psi.HglGenericParameter
import io.github.hhenson.hgl.psi.HglGenericParameters
import io.github.hhenson.hgl.psi.HglInstantiation
import io.github.hhenson.hgl.psi.HglNamedType
import io.github.hhenson.hgl.psi.HglNativeTypeDecl
import io.github.hhenson.hgl.psi.HglQualifiedName
import io.github.hhenson.hgl.psi.HglReferenceExpr
import io.github.hhenson.hgl.psi.HglRequiresClause
import io.github.hhenson.hgl.psi.HglStructBody
import io.github.hhenson.hgl.psi.HglStructDecl
import io.github.hhenson.hgl.psi.HglStructParents

/** TYPE-18 and GEN-14. Only a lone angle argument needs its declaration to distinguish the two readings. */
internal fun HglQualifiedName.isDescriptorType(): Boolean = textMatches("Type") && expectsDescriptorType()

/** Used on both the source name and the temporary identifier inserted by completion. */
internal fun HglQualifiedName.expectsDescriptorType(): Boolean {
    if (identList.size != 1) return false
    if (parent is HglNamedType) return true
    return when (angleParameterKind()) {
        HglGenericKind.TYPE -> true
        HglGenericKind.CALL -> HglScope.resolve(this, "Type", includeImplementations = false).isEmpty()
        else -> false
    }
}

internal fun HglQualifiedName.angleParameterKind(): HglGenericKind? =
    loneAngleArgument()?.parameterKind(emptySet())

private fun HglQualifiedName.loneAngleArgument(): HglGenericArgument? =
    (parent as? HglReferenceExpr)?.parent as? HglGenericArgument

internal enum class HglGenericKind { TYPE, VALUE, CALL }

private fun HglGenericArgument.parameterKind(visiting: Set<HglGenericParameter>): HglGenericKind? {
    val arguments = parent as HglGenericArguments
    val index = arguments.genericArgumentList.indexOf(this)
    val owner = arguments.parent
    val targets = if (owner is HglInstantiation) {
        HglScope.resolve(owner, owner.ident.text, includeImplementations = false)
    } else {
        val name = PsiTreeUtil.getChildOfType(owner, HglQualifiedName::class.java) ?: return null
        (name.reference as? HglQualifiedNameReference)?.declarations(includeImplementations = false) ?: return null
    }
    // An ambiguous or incomplete declaration cannot supply a reliable kind.
    return targets.map { target ->
        val parameters = PsiTreeUtil.getChildOfType(target, HglGenericParameters::class.java)?.genericParameterList ?: return null
        val parameter = parameters.getOrNull(index) ?: parameters.lastOrNull()?.takeIf { it.ellipsis != null } ?: return null
        parameter.kind(target, visiting)
    }.distinct().singleOrNull()
}

private fun HglGenericParameter.kind(declaration: PsiElement, visiting: Set<HglGenericParameter>): HglGenericKind? {
    if (type != null) return HglGenericKind.VALUE
    if (ellipsis != null) return HglGenericKind.TYPE
    if (this in visiting) return null
    val parameterName = ident.text
    val kinds = mutableSetOf<HglGenericKind?>()
    val signatureParts = declaration.children.filter {
        it is HglFunctionSignature || it is HglStructBody || it is HglStructParents ||
            it is HglConfigurationParameters || it is HglRequiresClause
    }
    for (part in signatureParts) {
        for (use in PsiTreeUtil.findChildrenOfType(part, HglQualifiedName::class.java)) {
            if (!use.textMatches(parameterName)) continue
            val kind = when (use.parent) {
                is HglNamedType -> HglGenericKind.TYPE
                is HglReferenceExpr -> {
                    val nested = use.loneAngleArgument()
                    if (nested == null) HglGenericKind.VALUE else nested.parameterKind(visiting + this)
                }
                is HglConstraintName -> constraintKind(use)
                else -> null
            }
            kinds.add(kind)
        }
    }
    if (kinds.isEmpty()) return HglGenericKind.CALL
    val determined = kinds.filter { it == HglGenericKind.TYPE || it == HglGenericKind.VALUE }
    return if (determined.isEmpty()) kinds.singleOrNull() else determined.singleOrNull()
}

private fun constraintKind(use: HglQualifiedName): HglGenericKind? {
    val operand = use.parent.parent as? HglConstraintOperand ?: return null
    return when (val context = operand.parent) {
        is HglConstraintCall -> if (context.qualifiedName.textMatches("type")) HglGenericKind.VALUE else null
        is HglConstraintTerm -> {
            val relation = context.constraintRelation ?: return null
            if (relation.structKw != null || relation.enumKw != null) HglGenericKind.TYPE
            else relation.constraintOperand?.operandKind()
        }
        is HglConstraintRelation -> (context.parent as? HglConstraintTerm)?.constraintOperand?.operandKind()
        else -> null
    }
}

private fun HglConstraintOperand.operandKind(): HglGenericKind? {
    constraintSet?.let { return it.constraintOperandList.map { entry -> entry.operandKind() }.distinct().singleOrNull() }
    constraintName?.qualifiedName?.let { name ->
        val target = (name.reference as? HglQualifiedNameReference)?.declarations(includeImplementations = false)?.singleOrNull()
        return when (target) {
            is HglStructDecl, is HglEnumDecl, is HglNativeTypeDecl -> HglGenericKind.TYPE
            is HglConstantDecl -> HglGenericKind.VALUE
            null -> if (name.textMatches("Type")) HglGenericKind.TYPE else null
            else -> null
        }
    }
    constraintCall?.let { return null }
    return if (firstChild is HglExpr) HglGenericKind.VALUE else HglGenericKind.TYPE
}
