package io.github.hhenson.hgl.reference

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.PsiPolyVariantReferenceBase
import com.intellij.psi.ResolveResult
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

/** A harness can also have its own prefix options before the target name. */
internal fun HglOperatorConfiguration.callArguments(): HglArguments? {
    val call = parent
    if (call is HglHarnessTarget && textOffset < call.qualifiedName.textOffset) return null
    if (call !is HglHarnessTarget && call !is HglCallExpr && call !is HglAppliedConstructorExpr) return null
    return PsiTreeUtil.getChildOfType(call, HglArguments::class.java)
}

/** The positional slot is local to one signature. A value generic or brace option has none. */
internal data class CallLabel(val parameter: HglNamedElement, val slot: Int? = null) {
    val name: String? get() = parameter.name
}

internal data class CallLabels(val arguments: List<CallLabel>, val configuration: List<CallLabel>)

private class CallSignature(target: HglNamedElement, publicSlots: Int = 0) {
    private val inputs: List<HglNamedElement> = if (target is HglParameter) {
        target.functionType?.functionTypeParameterList.orEmpty()
    } else PsiTreeUtil.getChildOfType(target, HglFunctionSignature::class.java)?.parameterList.orEmpty()
    private val fixedInputs = inputs.takeWhile { it.parameterPack == null }
    private val packs = inputs.mapNotNull { it.parameterPack }
    val slotCount = fixedInputs.size
    val takesPositionalPack = packs.any { it.lbrace == null || it.forKw != null }
    val takesNamedPack = packs.any { it.lbrace != null }
    private val fixed = fixedInputs.mapIndexedNotNull { index, parameter ->
        if (index < publicSlots) null else CallLabel(parameter, index)
    }
    private val generics = PsiTreeUtil.getChildOfType(target, HglGenericParameters::class.java)
        ?.genericParameterList.orEmpty().filter { it.type != null }.map { CallLabel(it) }
    val arguments = (generics + fixed).distinctBy { it.name }
    private val options = PsiTreeUtil.getChildOfType(target, HglConfigurationParameters::class.java)
        ?.configurationParameterList.orEmpty().map { CallLabel(it) }
    val configuration = (options + arguments).distinctBy { it.name }
}

internal fun HglArguments.configuration(): HglOperatorConfiguration? =
    PsiTreeUtil.getChildrenOfTypeAsList(parent, HglOperatorConfiguration::class.java).firstOrNull { it.callArguments() == this }

/** GEN-4/6. The public signature owns shared parameters. Each viable candidate contributes its own options. */
internal fun HglArguments.callLabels(editing: PsiElement): CallLabels? {
    val target = calleeReference()?.declarations(includeImplementations = false)?.singleOrNull() as? HglNamedElement ?: return null
    if (target !is HglOperatorDecl && target !is HglFunctionDecl && target !is HglNativeFunctionDecl &&
        (target !is HglParameter || target.functionType == null)) return null
    val public = CallSignature(target)
    if (target is HglParameter) return CallLabels(public.arguments, emptyList())
    if (target !is HglOperatorDecl) return CallLabels(public.arguments, public.configuration)

    fun extraNames(written: List<HglNamedArgument>, declared: List<CallLabel>): Set<String> =
        written.filter { it != editing }.map { it.memberName.text }.toSet() - declared.mapNotNull { it.name }.toSet()
    val written = argumentList.filter { it != editing && it.namedArgument != editing }
    val positional = written.count { it.ellipsis == null && it.namedArgument == null }
    val usedArgs = extraNames(written.mapNotNull { it.namedArgument }, public.arguments)
    val usedOptions = extraNames(configuration()?.namedArgumentList.orEmpty(), public.configuration)
    val candidates = target.callCandidates(this).map { CallSignature(it, public.slotCount) }.filter { signature ->
        fun available(labels: List<CallLabel>) = labels.filter { it.slot == null || it.slot >= positional }.map { it.name }
        (signature.takesPositionalPack || positional <= signature.slotCount) &&
            (signature.takesNamedPack || available(signature.arguments).containsAll(usedArgs)) &&
            available(signature.configuration).containsAll(usedOptions)
    }
    fun labels(select: (CallSignature) -> List<CallLabel>): List<CallLabel> {
        val shared = select(public)
        val publicNames = shared.mapTo(mutableSetOf()) { it.name }
        return shared + candidates.flatMap(select).filter { it.name !in publicNames }
    }
    return CallLabels(labels { it.arguments }, labels { it.configuration })
}

private val HglNamedElement.parameterPack: HglPack?
    get() = PsiTreeUtil.getChildOfType(this, HglPack::class.java)

internal class HglCallLabelReference(element: HglNamedArgument, private val arguments: HglArguments, private val inBraces: Boolean) :
    PsiPolyVariantReferenceBase<HglNamedArgument>(element, element.memberName.textRangeInParent) {

    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> {
        val positional = arguments.argumentList.count { it.namedArgument == null && it.ellipsis == null }
        val signature = arguments.callLabels(element) ?: return ResolveResult.EMPTY_ARRAY
        val targets = (if (inBraces) signature.configuration else signature.arguments).filter {
            it.name == element.memberName.text && (it.slot == null || it.slot >= positional)
        }
        return PsiElementResolveResult.createResults(targets.map { it.parameter }.distinct())
    }

    override fun handleElementRename(newElementName: String): PsiElement {
        element.memberName.ident?.replace(HglElementFactory.createIdent(element.project, newElementName))
        return element
    }
}
