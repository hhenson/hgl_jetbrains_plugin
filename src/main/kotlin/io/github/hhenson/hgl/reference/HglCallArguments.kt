package io.github.hhenson.hgl.reference

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.util.PsiTreeUtil
import io.github.hhenson.hgl.psi.HglAppliedConstructorExpr
import io.github.hhenson.hgl.psi.HglArguments
import io.github.hhenson.hgl.psi.HglCallExpr
import io.github.hhenson.hgl.psi.HglConfigurationParameters
import io.github.hhenson.hgl.psi.HglElementFactory
import io.github.hhenson.hgl.psi.HglFunctionDecl
import io.github.hhenson.hgl.psi.HglFunctionSignature
import io.github.hhenson.hgl.psi.HglGenericParameters
import io.github.hhenson.hgl.psi.HglHarnessTarget
import io.github.hhenson.hgl.psi.HglNamedArgument
import io.github.hhenson.hgl.psi.HglNamedElement
import io.github.hhenson.hgl.psi.HglNativeFunctionDecl
import io.github.hhenson.hgl.psi.HglOperatorConfiguration
import io.github.hhenson.hgl.psi.HglOperatorDecl
import io.github.hhenson.hgl.psi.HglPack
import io.github.hhenson.hgl.psi.HglParameter
import io.github.hhenson.hgl.psi.HglReferenceExpr

internal fun HglArguments.calleeReference(): HglQualifiedNameReference? {
    val callee = when (val call = parent) {
        is HglCallExpr -> (call.expr as? HglReferenceExpr)?.qualifiedName
        is HglAppliedConstructorExpr -> call.qualifiedName
        is HglHarnessTarget -> call.qualifiedName
        else -> null
    }
    return callee?.reference as? HglQualifiedNameReference
}

/** Labels belong to the declared interface, including the written type of a callback (FN-17). */
internal fun HglArguments.callParameters(): List<HglNamedElement>? =
    when (val target = calleeReference()?.declarations(includeImplementations = false)?.singleOrNull()) {
        is HglFunctionDecl -> target.functionSignature?.parameterList
        is HglNativeFunctionDecl -> target.functionSignature?.parameterList
        is HglOperatorDecl -> target.functionSignature?.parameterList
        is HglParameter -> target.functionType?.functionTypeParameterList
        else -> null
    }?.takeWhile { it.parameterPack == null }

/** A harness can also have its own prefix options before the target name. */
internal fun HglOperatorConfiguration.callArguments(): HglArguments? {
    val call = parent
    if (call is HglHarnessTarget && textOffset < call.qualifiedName.textOffset) return null
    if (call !is HglHarnessTarget && call !is HglCallExpr && call !is HglAppliedConstructorExpr) return null
    return PsiTreeUtil.getChildOfType(call, HglArguments::class.java)
}

internal fun HglOperatorConfiguration.declaredLabels(): List<HglNamedElement> {
    val target = callArguments()?.calleeReference()?.declarations(includeImplementations = false)?.singleOrNull()
        ?: return emptyList()
    if (target !is HglOperatorDecl && target !is HglFunctionDecl && target !is HglNativeFunctionDecl) return emptyList()
    val options = PsiTreeUtil.getChildOfType(target, HglConfigurationParameters::class.java)?.configurationParameterList.orEmpty()
    val generics = PsiTreeUtil.getChildOfType(target, HglGenericParameters::class.java)?.genericParameterList.orEmpty()
    val fixed = PsiTreeUtil.getChildOfType(target, HglFunctionSignature::class.java)?.parameterList.orEmpty().takeWhile { it.parameterPack == null }
    // EXPR-10 searches declared configuration first, then value generics and ordinary parameters.
    return (options + generics.filter { it.type != null } + fixed).distinctBy { it.name }
}

private val HglNamedElement.parameterPack: HglPack?
    get() = PsiTreeUtil.getChildOfType(this, HglPack::class.java)

internal class HglCallLabelReference(element: HglNamedArgument, private val parameters: List<HglNamedElement>) :
    PsiReferenceBase<HglNamedArgument>(element, element.memberName.textRangeInParent) {

    override fun resolve(): PsiElement? =
        parameters.singleOrNull { it.name == element.memberName.text }

    override fun handleElementRename(newElementName: String): PsiElement {
        element.memberName.ident?.replace(HglElementFactory.createIdent(element.project, newElementName))
        return element
    }
}
