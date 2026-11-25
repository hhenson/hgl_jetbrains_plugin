package io.github.hhenson.hgl.psi.impl

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.lang.ASTNode
import com.intellij.psi.PsiReference
import io.github.hhenson.hgl.psi.HglArgument
import io.github.hhenson.hgl.psi.HglArguments
import io.github.hhenson.hgl.psi.HglImportItem
import io.github.hhenson.hgl.psi.HglModulePath
import io.github.hhenson.hgl.psi.HglNamedArgument
import io.github.hhenson.hgl.psi.HglOperatorConfiguration
import io.github.hhenson.hgl.psi.HglQualifiedName
import io.github.hhenson.hgl.reference.HglCallLabelReference
import io.github.hhenson.hgl.reference.HglImportItemReference
import io.github.hhenson.hgl.reference.HglModulePathReference
import io.github.hhenson.hgl.reference.HglQualifiedNameReference
import io.github.hhenson.hgl.reference.callParameters
import io.github.hhenson.hgl.reference.declaredLabels

abstract class HglQualifiedNameMixin(node: ASTNode) : ASTWrapperPsiElement(node) {
    override fun getReference(): PsiReference = HglQualifiedNameReference(this as HglQualifiedName)
}

abstract class HglModulePathMixin(node: ASTNode) : ASTWrapperPsiElement(node) {
    override fun getReference(): PsiReference? =
        if (parent is io.github.hhenson.hgl.psi.HglUseDecl) HglModulePathReference(this as HglModulePath) else null
}

abstract class HglImportItemMixin(node: ASTNode) : HglNamedElementImpl(node) {
    override fun getReference(): PsiReference = HglImportItemReference(this as HglImportItem)
}

abstract class HglNamedArgumentMixin(node: ASTNode) : ASTWrapperPsiElement(node) {
    override fun getReference(): PsiReference? {
        val parameters = when (val owner = parent) {
            is HglOperatorConfiguration -> owner.declaredLabels()
            is HglArgument -> (owner.parent as? HglArguments)?.callParameters() ?: return null
            else -> return null
        }
        return HglCallLabelReference(this as HglNamedArgument, parameters)
    }
}
