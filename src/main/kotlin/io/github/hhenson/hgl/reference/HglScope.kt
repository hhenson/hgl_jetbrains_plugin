package io.github.hhenson.hgl.reference

import com.intellij.psi.PsiElement
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.parentOfType
import io.github.hhenson.hgl.psi.HglBlock
import io.github.hhenson.hgl.psi.HglConfigurationParameters
import io.github.hhenson.hgl.psi.HglCacheDecl
import io.github.hhenson.hgl.psi.HglFile
import io.github.hhenson.hgl.psi.HglFnExpr
import io.github.hhenson.hgl.psi.HglLoopEntries
import io.github.hhenson.hgl.psi.HglConstraintEach
import io.github.hhenson.hgl.psi.HglForStmt
import io.github.hhenson.hgl.psi.HglFunctionDecl
import io.github.hhenson.hgl.psi.HglGenericParameters
import io.github.hhenson.hgl.psi.HglImportItem
import io.github.hhenson.hgl.psi.HglInjectDecl
import io.github.hhenson.hgl.psi.HglLocalDecl
import io.github.hhenson.hgl.psi.HglNamedElement
import io.github.hhenson.hgl.psi.HglNativeFunctionDecl
import io.github.hhenson.hgl.psi.HglOperatorDecl
import io.github.hhenson.hgl.psi.HglStateDecl
import io.github.hhenson.hgl.psi.HglStructDecl
import io.github.hhenson.hgl.psi.HglTestContext
import io.github.hhenson.hgl.psi.HglTestDecl
import io.github.hhenson.hgl.psi.HglTypes
import io.github.hhenson.hgl.psi.HglUseAlias
import io.github.hhenson.hgl.psi.HglUseDecl
import io.github.hhenson.hgl.psi.dottedName

/**
 * Lexical scoping for name resolution ("Scopes and name lookup" in the
 * developer guide, as far as a syntax-only plugin can follow it): block
 * locals declared before the use, function-level state, cache and injected
 * names, parameters and generic parameters of the enclosing function,
 * operator, struct or anonymous function, test-context helpers, then the
 * module's declarations, imports and aliases.
 */
object HglScope {

    /** The declarations of each enclosing scope of `from`, innermost scope first. */
    fun scopes(from: PsiElement): Sequence<List<HglNamedElement>> = sequence {
        val useOffset = from.textOffset
        var inTests = false
        var scope: PsiElement? = from.parent
        while (scope != null) {
            val declared: List<HglNamedElement> = when (scope) {
                is HglBlock -> blockDeclarations(scope, useOffset)
                is HglForStmt -> if (PsiTreeUtil.isAncestor(scope.block, from, false)) scope.forBindingList else emptyList()
                is HglLoopEntries -> if (PsiTreeUtil.isAncestor(scope.expr, from, false)) listOfNotNull(scope.forBinding) else emptyList()
                is HglConstraintEach -> if (PsiTreeUtil.isAncestor(scope.constraint, from, false)) listOfNotNull(scope.forBinding) else emptyList()
                is HglFnExpr -> scope.anonymousParameterList
                is HglFunctionDecl -> signatureDeclarations(scope.functionSignature, scope.genericParameters, scope.configurationParameters)
                is HglNativeFunctionDecl -> signatureDeclarations(scope.functionSignature, scope.genericParameters, scope.configurationParameters)
                is HglOperatorDecl -> signatureDeclarations(scope.functionSignature, scope.genericParameters, scope.configurationParameters)
                is HglStructDecl -> genericParameters(scope.genericParameters)
                is HglTestContext, is HglTestDecl -> {
                    inTests = true
                    emptyList()
                }
                is HglFile -> {
                    yieldAll(fileScopes(scope, useOffset, inTests))
                    break
                }
                else -> emptyList()
            }
            if (declared.isNotEmpty()) yield(declared)
            scope = scope.parent
        }
    }

    /** Names visible from `from`, innermost scope first. */
    fun visibleDeclarations(from: PsiElement): Sequence<HglNamedElement> = scopes(from).flatten()

    /**
     * The declarations `name` refers to from `from`: those of the innermost
     * scope that declares the name, so an inner binding shadows an outer one,
     * while overloads declared side by side in one scope are all returned.
     * An import expands to what it imports.
     */
    fun resolve(from: PsiElement, name: String): List<PsiElement> = resolve(from, name, includeImplementations = true)

    internal fun resolve(from: PsiElement, name: String, includeImplementations: Boolean): List<PsiElement> {
        val direct = scopes(from)
            .map { scope -> scope.filter { it.name == name && (includeImplementations || !it.isOperatorImplementation) } }
            .firstOrNull { it.isNotEmpty() }
            ?: return emptyList()
        return direct.flatMap { declaration ->
            if (declaration is HglImportItem) declaration.importedDeclarations().ifEmpty { listOf(declaration) }
            else listOf(declaration)
        }.distinct()
    }

    /** Parts share declarations and test helpers. Imports and application bindings belong to their file. */
    private fun fileScopes(file: HglFile, useOffset: Int, inTests: Boolean): Sequence<List<HglNamedElement>> = sequence {
        val module = file.modulePath
        val siblings = module?.let { HglModuleIndex.findFiles(file.project, it) }.orEmpty()
        val parts = (listOf(file) + siblings).distinct()
        if (inTests) {
            yield(parts.flatMap { part ->
                PsiTreeUtil.getChildrenOfTypeAsList(part, HglTestContext::class.java).flatMap { context ->
                    context.functionDeclList + context.structDeclList + context.enumDeclList
                }
            })
        }
        yield(parts.flatMap(::declarations))
        if (file.applicationDecl != null) {
            yield(PsiTreeUtil.getChildrenOfTypeAsList(file, HglInjectDecl::class.java)
                .filter { it.textOffset < useOffset }.flatMap { it.injectItemList })
        }
        yield(file.useDecls.flatMap { it.importSet?.importItemList.orEmpty() + listOfNotNull(it.useAlias) })
        if (module != "core") yield(HglModuleIndex.findFiles(file.project, "core").flatMap(::publicDeclarations))
    }

    private fun declarations(file: HglFile): List<HglNamedElement> =
        CachedValuesManager.getCachedValue(file) {
            val names = file.children.filterIsInstance<HglNamedElement>().filterNot { it is HglTestDecl }
            CachedValueProvider.Result.create(names, file)
        }

    private fun publicDeclarations(file: HglFile): List<HglNamedElement> =
        declarations(file).filter { it.node.findChildByType(HglTypes.EXPORT_KW) != null }

    fun exportedDeclarations(file: HglFile, name: String): List<HglNamedElement> =
        publicDeclarations(file).filter { it.name == name }

    /** The `use ... as alias` declaration named `alias` in the file containing `from`. */
    fun findAlias(from: PsiElement, alias: String): HglUseAlias? {
        val file = from.containingFile as? HglFile ?: return null
        return PsiTreeUtil.getChildrenOfTypeAsList(file, HglUseDecl::class.java)
            .mapNotNull { it.useAlias }
            .firstOrNull { it.name == alias }
    }

    /** The module files a `use` declaration refers to. */
    fun targetFiles(useDecl: HglUseDecl): List<HglFile> {
        val path = useDecl.modulePath?.dottedName ?: return emptyList()
        if (path.isEmpty()) return emptyList()
        return HglModuleIndex.findFiles(useDecl.project, path)
    }

    private fun genericParameters(parameters: HglGenericParameters?): List<HglNamedElement> =
        parameters?.genericParameterList ?: emptyList()

    private fun signatureDeclarations(
        signature: io.github.hhenson.hgl.psi.HglFunctionSignature?,
        generics: HglGenericParameters?,
        configuration: HglConfigurationParameters?,
    ): List<HglNamedElement> = signature?.parameterList.orEmpty() + configuration?.configurationParameterList.orEmpty() + genericParameters(generics)

    private fun blockDeclarations(block: HglBlock, useOffset: Int): List<HglNamedElement> {
        val result = ArrayList<HglNamedElement>()
        for (child in block.children) {
            when (child) {
                is HglLocalDecl -> if (child.textOffset < useOffset) result.add(child)
                is HglStateDecl -> result.add(child)
                is HglCacheDecl -> result.add(child)
                is HglInjectDecl -> result.addAll(child.injectItemList)
            }
        }
        return result
    }
}

internal val HglNamedElement.isOperatorImplementation: Boolean
    get() = (this is HglFunctionDecl && implKw != null) || (this is HglNativeFunctionDecl && implKw != null)

/** What an import item brings into scope: the declarations of that name in the imported module. */
fun HglImportItem.importedDeclarations(): List<PsiElement> {
    val useDecl = parentOfType<HglUseDecl>() ?: return emptyList()
    val name = name ?: return emptyList()
    return HglScope.targetFiles(useDecl).flatMap { HglScope.exportedDeclarations(it, name) }
}
