package io.github.hhenson.hgl.highlighting

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.psi.PsiElement
import com.intellij.psi.impl.source.tree.LeafPsiElement
import io.github.hhenson.hgl.psi.HglAnonymousParameter
import io.github.hhenson.hgl.psi.HglCacheDecl
import io.github.hhenson.hgl.psi.HglCallExpr
import io.github.hhenson.hgl.psi.HglFieldExpr
import io.github.hhenson.hgl.psi.HglForBinding
import io.github.hhenson.hgl.psi.HglFunctionDecl
import io.github.hhenson.hgl.psi.HglGenericParameter
import io.github.hhenson.hgl.psi.HglImportItem
import io.github.hhenson.hgl.psi.HglInjectItem
import io.github.hhenson.hgl.psi.HglLocalDecl
import io.github.hhenson.hgl.psi.HglModulePath
import io.github.hhenson.hgl.psi.HglIdent
import io.github.hhenson.hgl.psi.HglNamedArgument
import io.github.hhenson.hgl.psi.HglNamedType
import io.github.hhenson.hgl.psi.HglNativeFunctionDecl
import io.github.hhenson.hgl.psi.HglOperatorDecl
import io.github.hhenson.hgl.psi.HglParameter
import io.github.hhenson.hgl.psi.HglQualifiedName
import io.github.hhenson.hgl.psi.HglReferenceExpr
import io.github.hhenson.hgl.psi.HglStateDecl
import io.github.hhenson.hgl.psi.HglStructDecl
import io.github.hhenson.hgl.psi.HglStructMember
import io.github.hhenson.hgl.psi.HglTestDecl
import io.github.hhenson.hgl.psi.HglTokenSets
import io.github.hhenson.hgl.psi.HglUseAlias

/**
 * Semantic colouring on top of the lexer: declaration names by kind,
 * references by what they resolve to, contextual keywords, keywords used as
 * names, named-argument labels, field accesses and the metadata intrinsics.
 * It reports no errors: name resolution here is lexical only, and the
 * compiler owns the diagnostics.
 */
class HglAnnotator : Annotator {

    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        when (element) {
            is LeafPsiElement -> annotateLeaf(element, holder)
            is HglIdent -> annotateName(element, holder)
            is HglQualifiedName -> annotateReference(element, holder)
        }
    }

    private fun annotateLeaf(leaf: LeafPsiElement, holder: AnnotationHolder) {
        val type = leaf.elementType
        if (HglTokenSets.CONTEXTUAL_KEYWORDS.contains(type)) {
            colour(holder, leaf, HglColors.CONTEXTUAL_KEYWORD)
        } else if (HglTokenSets.KEYWORDS.contains(type) && leaf.parent is HglIdent) {
            // A reserved word in a name position (`use hgraph.time::{...}`) is a name, not a keyword.
            colour(holder, leaf, HglColors.IDENTIFIER)
        }
    }

    private fun annotateName(name: HglIdent, holder: AnnotationHolder) {
        val owner = name.parent
        val key = when (owner) {
            is HglFunctionDecl -> if (owner.nameIdentifier === name) HglColors.FUNCTION_DECLARATION else null
            is HglNativeFunctionDecl -> if (owner.nameIdentifier === name) HglColors.FUNCTION_DECLARATION else null
            is HglOperatorDecl -> if (owner.nameIdentifier === name) HglColors.OPERATOR_DECLARATION else null
            is HglStructDecl -> if (owner.nameIdentifier === name) HglColors.STRUCT_NAME else null
            is HglTestDecl -> HglColors.TEST_NAME
            is HglParameter, is HglAnonymousParameter -> HglColors.PARAMETER
            is HglGenericParameter -> HglColors.TYPE_PARAMETER
            is HglLocalDecl, is HglForBinding -> HglColors.LOCAL_VARIABLE
            is HglStateDecl, is HglCacheDecl -> HglColors.STATE_VARIABLE
            is HglStructMember -> HglColors.FIELD
            is HglInjectItem -> HglColors.INJECTABLE
            is HglImportItem -> keyForTargets(owner.reference?.let { (it as? com.intellij.psi.PsiPolyVariantReference)?.multiResolve(false)?.mapNotNull { r -> r.element } } ?: emptyList())
            is HglUseAlias -> HglColors.MODULE_PATH
            is HglModulePath -> HglColors.MODULE_PATH
            is HglNamedArgument -> HglColors.NAMED_ARGUMENT
            is HglFieldExpr -> HglColors.FIELD
            else -> null
        }
        if (key != null) colour(holder, name, key)
    }

    private fun annotateReference(reference: HglQualifiedName, holder: AnnotationHolder) {
        val last = reference.identList.lastOrNull() ?: return
        val targets = (reference.reference as? com.intellij.psi.PsiPolyVariantReference)
            ?.multiResolve(false)?.mapNotNull { it.element } ?: emptyList()
        val key = when {
            targets.isNotEmpty() -> keyForTargets(targets)
            reference.parent is HglNamedType -> HglColors.STRUCT_REFERENCE
            last.text in INTRINSICS -> HglColors.INTRINSIC
            isCallee(reference) -> HglColors.FUNCTION_CALL
            else -> null
        }
        if (key != null) colour(holder, last, key)
        if (reference.identList.size == 2) colour(holder, reference.identList[0], HglColors.MODULE_PATH)
    }

    private fun isCallee(reference: HglQualifiedName): Boolean {
        val expr = reference.parent as? HglReferenceExpr ?: return false
        val call = expr.parent as? HglCallExpr ?: return false
        return call.expr === expr
    }

    private fun keyForTargets(targets: List<PsiElement>): TextAttributesKey? {
        val target = targets.firstOrNull() ?: return null
        return when (target) {
            is HglStructDecl -> HglColors.STRUCT_REFERENCE
            is HglFunctionDecl, is HglNativeFunctionDecl -> HglColors.FUNCTION_CALL
            is HglOperatorDecl -> HglColors.FUNCTION_CALL
            is HglParameter, is HglAnonymousParameter -> HglColors.PARAMETER
            is HglGenericParameter -> HglColors.TYPE_PARAMETER
            is HglLocalDecl, is HglForBinding -> HglColors.LOCAL_VARIABLE
            is HglStateDecl, is HglCacheDecl -> HglColors.STATE_VARIABLE
            is HglInjectItem -> HglColors.INJECTABLE
            is HglUseAlias -> HglColors.MODULE_PATH
            is HglImportItem -> null
            else -> null
        }
    }

    private fun colour(holder: AnnotationHolder, element: PsiElement, key: TextAttributesKey) {
        holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
            .range(element)
            .textAttributes(key)
            .create()
    }

    companion object {
        /** Prelude intrinsics and metadata functions ("Temporal metadata syntax", ADR 0007, ADR 0010). */
        val INTRINSICS: Set<String> = setOf(
            "modified", "valid", "all_valid", "last_modified", "delta", "scheduled", "passivate", "activate",
            "key_set", "elements", "values", "keys", "len", "types", "type_at", "fields", "has_fields",
            "field_type", "schemas",
        )
    }
}
