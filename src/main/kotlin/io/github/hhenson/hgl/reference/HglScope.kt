package io.github.hhenson.hgl.reference

import com.intellij.psi.PsiElement
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.parentOfType
import io.github.hhenson.hgl.psi.HglArgument
import io.github.hhenson.hgl.psi.HglBlock
import io.github.hhenson.hgl.psi.HglCallExpr
import io.github.hhenson.hgl.psi.HglConfigurationParameters
import io.github.hhenson.hgl.psi.HglConstantDecl
import io.github.hhenson.hgl.psi.HglEnumDecl
import io.github.hhenson.hgl.psi.HglCacheDecl
import io.github.hhenson.hgl.psi.HglFile
import io.github.hhenson.hgl.psi.HglFnExpr
import io.github.hhenson.hgl.psi.HglLoopEntries
import io.github.hhenson.hgl.psi.HglConstraintEach
import io.github.hhenson.hgl.psi.HglForStmt
import io.github.hhenson.hgl.psi.HglFunctionDecl
import io.github.hhenson.hgl.psi.HglFunctionSignature
import io.github.hhenson.hgl.psi.HglGenericParameters
import io.github.hhenson.hgl.psi.HglImportItem
import io.github.hhenson.hgl.psi.HglInjectDecl
import io.github.hhenson.hgl.psi.HglLocalDecl
import io.github.hhenson.hgl.psi.HglNamedElement
import io.github.hhenson.hgl.psi.HglNativeFunctionDecl
import io.github.hhenson.hgl.psi.HglOperatorDecl
import io.github.hhenson.hgl.psi.HglPack
import io.github.hhenson.hgl.psi.HglQualifiedName
import io.github.hhenson.hgl.psi.HglReferenceExpr
import io.github.hhenson.hgl.psi.HglStateDecl
import io.github.hhenson.hgl.psi.HglStructDecl
import io.github.hhenson.hgl.psi.HglSwitchCase
import io.github.hhenson.hgl.psi.HglSwitchDefault
import io.github.hhenson.hgl.psi.HglSwitchStmt
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
        var child = from
        var scope = child.parent
        while (scope != null) {
            val declared: List<HglNamedElement> = when (scope) {
                is HglBlock -> blockDeclarations(scope, useOffset)
                is HglForStmt -> if (PsiTreeUtil.isAncestor(scope.block, from, false)) scope.forBindingList else emptyList()
                is HglLoopEntries -> if (PsiTreeUtil.isAncestor(scope.expr, from, false)) listOfNotNull(scope.forBinding) else emptyList()
                is HglConstraintEach -> if (PsiTreeUtil.isAncestor(scope.constraint, from, false)) listOfNotNull(scope.forBinding) else emptyList()
                is HglFnExpr -> if (child == scope.expr || child == scope.block) scope.anonymousParameterList else emptyList()
                is HglFunctionDecl -> signatureDeclarations(from, child, scope.functionSignature, scope.genericParameters, scope.configurationParameters)
                is HglNativeFunctionDecl -> signatureDeclarations(from, child, scope.functionSignature, scope.genericParameters, scope.configurationParameters)
                is HglOperatorDecl -> signatureDeclarations(from, child, scope.functionSignature, scope.genericParameters, scope.configurationParameters)
                is HglStructDecl -> genericParameters(scope.genericParameters)
                is HglTestDecl -> {
                    inTests = true
                    scope.helperDeclarations.filterNot { it.isOperatorImplementation }
                }
                is HglTestContext -> {
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
            child = scope
            scope = child.parent
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
        val testCandidates = includeImplementations && isTestCode(from)
        val includeLocalImpls = includeImplementations && !testCandidates
        val direct = scopes(from)
            .map { scope -> scope.filter { it.name == name && (includeLocalImpls || !it.isOperatorImplementation) } }
            .firstOrNull { it.isNotEmpty() }
            ?: return emptyList()
        val declarations = direct.flatMap { declaration ->
            if (declaration is HglImportItem) declaration.importedDeclarations().ifEmpty { listOf(declaration) }
            else listOf(declaration)
        }.distinct()
        return if (testCandidates) withTestCandidates(from, name, declarations) else declarations
    }

    internal fun isTestCode(from: PsiElement): Boolean =
        from.parentOfType<HglTestDecl>() != null || from.parentOfType<HglTestContext>() != null

    /** GEN-4/TST-1: resolve the operator in the candidate's own scope, including its file-local imports. */
    internal fun withTestCandidates(from: PsiElement, name: String, declarations: List<PsiElement>): List<PsiElement> {
        val operators = declarations.filterIsInstance<HglOperatorDecl>()
        if (operators.isEmpty()) return declarations
        val file = from.containingFile as? HglFile ?: return declarations
        val candidates = moduleParts(file).flatMap { part ->
            operatorCandidates(part)[name].orEmpty().groupBy { it.parent }.values.filter { overloads ->
                resolve(overloads.first(), name, includeImplementations = false).any { it in operators }
            }.flatten()
        }
        return declarations + candidates
    }

    internal fun operatorCandidates(file: HglFile): Map<String?, List<HglNamedElement>> =
        CachedValuesManager.getCachedValue(file) {
            val topLevel = file.children.filterIsInstance<HglNamedElement>()
            val tests = topLevel.filterIsInstance<HglTestDecl>() +
                file.children.filterIsInstance<HglTestContext>().flatMap { it.testDeclList }
            val candidates = (topLevel + tests.flatMap { it.helperDeclarations })
                .filter { it.isOperatorImplementation }.groupBy { it.name }
            CachedValueProvider.Result.create(candidates, file)
        }

    private fun moduleParts(file: HglFile): List<HglFile> =
        listOf(file) + file.modulePath?.let { HglModuleIndex.findFiles(file.project, it) }.orEmpty()
            .filter { it.originalFile != file.originalFile }

    /** Parts share declarations and test helpers. Imports and application bindings belong to their file. */
    private fun fileScopes(file: HglFile, useOffset: Int, inTests: Boolean): Sequence<List<HglNamedElement>> = sequence {
        val parts = moduleParts(file)
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
        if (file.modulePath != "core") yield(HglModuleIndex.findFiles(file.project, "core").flatMap(::publicDeclarations))
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

    /** A qualifier must still name the file's import alias at the use site (UNIT-12). */
    fun findAlias(from: PsiElement, alias: String): HglUseAlias? =
        resolve(from, alias, includeImplementations = false).singleOrNull() as? HglUseAlias

    /** The module files a `use` declaration refers to. */
    fun targetFiles(useDecl: HglUseDecl): List<HglFile> {
        val path = useDecl.modulePath?.dottedName ?: return emptyList()
        if (path.isEmpty()) return emptyList()
        return HglModuleIndex.findFiles(useDecl.project, path)
    }

    private fun genericParameters(parameters: HglGenericParameters?): List<HglNamedElement> =
        parameters?.genericParameterList ?: emptyList()

    private fun signatureDeclarations(
        from: PsiElement, child: PsiElement, signature: HglFunctionSignature?,
        generics: HglGenericParameters?, configuration: HglConfigurationParameters?,
    ): List<HglNamedElement> {
        val parameters = signature?.parameterList.orEmpty()
        val types = genericParameters(generics)
        if (child != signature && child != generics && child != configuration) {
            return parameters + configuration?.configurationParameterList.orEmpty() + types
        }
        // Signature types and defaults see the defining scope and its generics (UNIT-7/10).
        // A reflected pack or law predicate explicitly names a callback (FN-8, GEN-18).
        val name = from.parentOfType<HglQualifiedName>(withSelf = true)
        if (name != null) {
            val pack = name.parent as? HglPack
            val argument = (name.parent as? HglReferenceExpr)?.parent as? HglArgument
            val call = argument?.parent?.parent as? HglCallExpr
            val predicate = (call?.expr as? HglReferenceExpr)?.qualifiedName?.text
            if (name == pack?.qualifiedNameList?.firstOrNull() ||
                (child == generics && (predicate == "associative" || predicate == "commutative"))) {
                return parameters.filter { it.functionType != null } + types
            }
        }
        return types
    }

    private fun blockDeclarations(block: HglBlock, useOffset: Int): List<HglNamedElement> = buildList {
        fun collect(statements: PsiElement) {
            for (child in statements.children) {
                when (child) {
                    is HglLocalDecl -> if (child.textRange.endOffset <= useOffset) add(child)
                    is HglStateDecl -> add(child)
                    is HglCacheDecl -> add(child)
                    is HglInjectDecl -> addAll(child.injectItemList)
                    // Cases share their enclosing block. Braced statement bodies have their own scope.
                    is HglSwitchStmt, is HglSwitchCase, is HglSwitchDefault -> collect(child)
                }
            }
        }
        collect(block)
    }
}

internal val HglTestDecl.helperDeclarations: List<HglNamedElement>
    get() = block?.children.orEmpty().filterIsInstance<HglNamedElement>().filter {
        it is HglFunctionDecl || it is HglConstantDecl || it is HglStructDecl || it is HglEnumDecl || it is HglOperatorDecl
    }

internal val HglNamedElement.isOperatorImplementation: Boolean
    get() = (this is HglFunctionDecl && implKw != null) || (this is HglNativeFunctionDecl && implKw != null)

/** What an import item brings into scope: the declarations of that name in the imported module. */
fun HglImportItem.importedDeclarations(): List<PsiElement> {
    val useDecl = parentOfType<HglUseDecl>() ?: return emptyList()
    val name = name ?: return emptyList()
    return HglScope.targetFiles(useDecl).flatMap { HglScope.exportedDeclarations(it, name) }
}
