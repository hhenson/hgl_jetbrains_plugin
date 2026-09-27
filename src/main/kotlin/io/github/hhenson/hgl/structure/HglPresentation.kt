package io.github.hhenson.hgl.structure

import com.intellij.icons.AllIcons
import com.intellij.psi.PsiElement
import io.github.hhenson.hgl.HglIcons
import io.github.hhenson.hgl.psi.HglAnonymousParameter
import io.github.hhenson.hgl.psi.HglCacheDecl
import io.github.hhenson.hgl.psi.HglForBinding
import io.github.hhenson.hgl.psi.HglFunctionDecl
import io.github.hhenson.hgl.psi.HglFunctionSignature
import io.github.hhenson.hgl.psi.HglGenericParameter
import io.github.hhenson.hgl.psi.HglImportItem
import io.github.hhenson.hgl.psi.HglInjectItem
import io.github.hhenson.hgl.psi.HglLocalDecl
import io.github.hhenson.hgl.psi.HglNativeFunctionDecl
import io.github.hhenson.hgl.psi.HglOperatorDecl
import io.github.hhenson.hgl.psi.HglParameter
import io.github.hhenson.hgl.psi.HglStateDecl
import io.github.hhenson.hgl.psi.HglStructDecl
import io.github.hhenson.hgl.psi.HglStructMember
import io.github.hhenson.hgl.psi.HglTestContext
import io.github.hhenson.hgl.psi.HglTestDecl
import io.github.hhenson.hgl.psi.HglUseAlias
import io.github.hhenson.hgl.psi.HglUseDecl
import javax.swing.Icon

/** Icons and short descriptions shared by the structure view, completion and navigation. */
object HglPresentation {
    fun iconOf(element: PsiElement): Icon = when (element) {
        is HglStructDecl -> AllIcons.Nodes.Class
        is HglFunctionDecl -> if (element.exportKw != null) AllIcons.Nodes.Function else AllIcons.Nodes.Lambda
        is HglNativeFunctionDecl -> AllIcons.Nodes.Function
        is HglOperatorDecl -> AllIcons.Nodes.AbstractMethod
        is HglTestDecl -> AllIcons.Nodes.Test
        is HglTestContext -> AllIcons.Nodes.TestGroup
        is HglParameter, is HglAnonymousParameter -> AllIcons.Nodes.Parameter
        is HglGenericParameter -> AllIcons.Nodes.Type
        is HglLocalDecl, is HglForBinding -> AllIcons.Nodes.Variable
        is HglStateDecl, is HglCacheDecl, is HglStructMember -> AllIcons.Nodes.Field
        is HglInjectItem -> AllIcons.Nodes.Plugin
        is HglImportItem, is HglUseAlias, is HglUseDecl -> AllIcons.Nodes.Include
        else -> HglIcons.FILE
    }

    fun kindOf(element: PsiElement): String = when (element) {
        is HglStructDecl -> if (element.abstractKw != null) "abstract struct" else "struct"
        is HglFunctionDecl -> when {
            element.constKw != null -> "const fn"
            element.implKw != null -> "impl fn"
            element.exportKw != null -> "export fn"
            else -> "fn"
        }
        is HglNativeFunctionDecl -> if (element.constKw != null) "native const fn" else "native fn"
        is HglOperatorDecl -> "operator"
        is HglTestDecl -> "test"
        is HglParameter -> if (element.constKw != null) "const parameter" else "parameter"
        is HglAnonymousParameter -> "parameter"
        is HglGenericParameter -> if (element.constKw != null) "const generic" else "type parameter"
        is HglLocalDecl -> if (element.varKw != null) "var" else "let"
        is HglForBinding -> "loop binding"
        is HglStateDecl -> "state"
        is HglCacheDecl -> "cache"
        is HglStructMember -> "field"
        is HglInjectItem -> "injectable"
        is HglImportItem -> "import"
        is HglUseAlias -> "module alias"
        else -> ""
    }

    /** A compact signature shown after a name, e.g. `(a, b) -> f64`. */
    fun tailOf(element: PsiElement): String = when (element) {
        is HglFunctionDecl -> signatureText(element.functionSignature)
        is HglNativeFunctionDecl -> signatureText(element.functionSignature)
        is HglOperatorDecl -> signatureText(element.functionSignature)
        is HglParameter -> element.type?.let { ": " + collapse(it.text) } ?: ""
        is HglStructMember -> element.type?.let { ": " + collapse(it.text) } ?: ""
        is HglStateDecl -> element.type?.let { ": " + collapse(it.text) } ?: ""
        is HglCacheDecl -> element.type?.let { ": " + collapse(it.text) } ?: ""
        else -> ""
    }

    fun signatureText(signature: HglFunctionSignature?): String {
        if (signature == null) return ""
        val parameters = signature.parameterList.joinToString(", ") { it.name ?: "" }
        val result = signature.type?.let { " -> " + collapse(it.text) } ?: ""
        return "($parameters)$result"
    }

    private fun collapse(text: String): String = text.replace(Regex("\\s+"), " ")
}
