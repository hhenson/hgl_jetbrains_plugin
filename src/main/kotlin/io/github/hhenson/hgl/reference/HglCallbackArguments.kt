package io.github.hhenson.hgl.reference

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReferenceBase
import io.github.hhenson.hgl.psi.HglArguments
import io.github.hhenson.hgl.psi.HglCallExpr
import io.github.hhenson.hgl.psi.HglElementFactory
import io.github.hhenson.hgl.psi.HglFunctionType
import io.github.hhenson.hgl.psi.HglNamedArgument
import io.github.hhenson.hgl.psi.HglParameter
import io.github.hhenson.hgl.psi.HglReferenceExpr

/** FN-17 exposes the names written in the callback parameter's function type. */
internal fun HglArguments.callbackType(): HglFunctionType? {
    val call = parent as? HglCallExpr ?: return null
    val name = (call.expr as? HglReferenceExpr)?.qualifiedName ?: return null
    if (name.identList.size != 1) return null
    return (name.reference?.resolve() as? HglParameter)?.functionType
}

internal class HglCallbackLabelReference(element: HglNamedArgument, private val signature: HglFunctionType) :
    PsiReferenceBase<HglNamedArgument>(element, element.memberName.textRangeInParent) {

    override fun resolve(): PsiElement? =
        signature.functionTypeParameterList.singleOrNull { it.name == element.memberName.text }

    override fun handleElementRename(newElementName: String): PsiElement {
        element.memberName.ident?.replace(HglElementFactory.createIdent(element.project, newElementName))
        return element
    }
}
