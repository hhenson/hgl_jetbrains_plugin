package io.github.hhenson.hgl.psi.impl

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.lang.ASTNode
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.hhenson.hgl.psi.HglElementFactory
import io.github.hhenson.hgl.psi.HglIdent
import io.github.hhenson.hgl.psi.HglNamedElement

/**
 * Mixin for every rule listed under `implements(...)=HglNamedElement` in
 * Hgl.bnf. The declared name is the element's first direct `name` child; the
 * generic parameters, parameters and body that follow are nested one level
 * deeper, so they are never mistaken for it.
 */
abstract class HglNamedElementImpl(node: ASTNode) : ASTWrapperPsiElement(node), HglNamedElement {

    override fun getNameIdentifier(): PsiElement? = PsiTreeUtil.getChildOfType(this, HglIdent::class.java)

    override fun getName(): String? = nameIdentifier?.text

    override fun setName(name: String): PsiElement {
        val identifier = nameIdentifier ?: return this
        identifier.replace(HglElementFactory.createIdent(project, name))
        return this
    }

    override fun getTextOffset(): Int = nameIdentifier?.textOffset ?: super.getTextOffset()
}
