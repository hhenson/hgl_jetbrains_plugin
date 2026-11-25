package io.github.hhenson.hgl.completion

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionInitializationContext
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.completion.InsertionContext
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.patterns.PlatformPatterns.psiElement
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.tree.IElementType
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.parentOfType
import com.intellij.util.ProcessingContext
import io.github.hhenson.hgl.HglLanguage
import io.github.hhenson.hgl.psi.HglArgument
import io.github.hhenson.hgl.psi.HglArguments
import io.github.hhenson.hgl.psi.HglAssertStmt
import io.github.hhenson.hgl.psi.HglBlock
import io.github.hhenson.hgl.psi.HglConstraintOperand
import io.github.hhenson.hgl.psi.HglConstraintRelation
import io.github.hhenson.hgl.psi.HglConstraintTerm
import io.github.hhenson.hgl.psi.HglExpr
import io.github.hhenson.hgl.psi.HglFile
import io.github.hhenson.hgl.psi.HglFnExpr
import io.github.hhenson.hgl.psi.HglFunctionDecl
import io.github.hhenson.hgl.psi.HglFunctionType
import io.github.hhenson.hgl.psi.HglNamedArgument
import io.github.hhenson.hgl.psi.HglNamedElement
import io.github.hhenson.hgl.psi.HglOperatorConfiguration
import io.github.hhenson.hgl.psi.HglReferenceExpr
import io.github.hhenson.hgl.psi.HglStructBody
import io.github.hhenson.hgl.psi.HglTestDecl
import io.github.hhenson.hgl.psi.HglType
import io.github.hhenson.hgl.psi.HglTypes
import io.github.hhenson.hgl.reference.callArguments
import io.github.hhenson.hgl.reference.callLabels
import io.github.hhenson.hgl.reference.configuration
import io.github.hhenson.hgl.structure.HglPresentation

/**
 * Keyword and call argument label completion. Names in scope come from the qualified-name
 * reference's variants, which the platform contributes on its own.
 */
class HglCompletionContributor : CompletionContributor() {
    init {
        extend(
            CompletionType.BASIC,
            psiElement().withLanguage(HglLanguage).andNot(psiElement().inside(PsiComment::class.java)),
            Provider,
        )
    }

    private object Provider : CompletionProvider<CompletionParameters>() {
        override fun addCompletions(parameters: CompletionParameters, context: ProcessingContext, result: CompletionResultSet) {
            val position = parameters.position
            val previous = PsiTreeUtil.prevVisibleLeaf(position)?.node?.elementType
            if (previous == HglTypes.DOT || previous == HglTypes.COLONCOLON) return

            if (addConfigurationLabels(position, result) || addCallLabels(position, result)) return
            for (word in contextKeywords(position, previous)) {
                result.addElement(LookupElementBuilder.create(word).bold())
            }
        }

        private fun addConfigurationLabels(position: PsiElement, result: CompletionResultSet): Boolean {
            val named = position.parentOfType<HglNamedArgument>() ?: return false
            if (!PsiTreeUtil.isAncestor(named.memberName, position, false)) return false
            val configuration = named.parent as? HglOperatorConfiguration ?: return false
            val arguments = configuration.callArguments() ?: return false
            val signature = arguments.callLabels(named) ?: return false
            val labels = signature.configuration
            if (labels.isEmpty()) return false
            val used = configuration.namedArgumentList.filter { it != named }.map { it.memberName.text }.toSet()
            val given = arguments.argumentList
            val namesInCall = given.mapNotNull { it.namedArgument?.memberName?.text }.toSet()
            val namedInputs = signature.arguments.filter { it.name in namesInCall }.mapTo(mutableSetOf()) { it.parameter }
            val positional = given.count { it.namedArgument == null && it.ellipsis == null }
            val eligible = labels.filter { label ->
                label.name !in used && label.parameter !in namedInputs && (label.slot == null || label.slot >= positional)
            }
            eligible.distinctBy { it.name }.forEach { addLabel(it.parameter, result) }
            return true
        }

        /** Returns true when the existing colon makes this a label-only position. */
        private fun addCallLabels(position: PsiElement, result: CompletionResultSet): Boolean {
            val argument = position.parentOfType<HglArgument>() ?: return false
            val named = argument.namedArgument
            val label = named?.memberName
                ?: PsiTreeUtil.getChildOfType(argument, HglReferenceExpr::class.java)?.qualifiedName?.identList?.singleOrNull()
                ?: return false
            if (argument.ellipsis != null || !PsiTreeUtil.isAncestor(label, position, false)) return false

            val arguments = argument.parent as? HglArguments ?: return false
            val signature = arguments.callLabels(argument) ?: return false
            var positional = 0
            val usedNames = mutableSetOf<String>()
            for (sibling in arguments.argumentList) {
                if (sibling == argument || sibling.ellipsis != null) continue
                val used = sibling.namedArgument?.memberName
                when {
                    used != null -> usedNames.add(used.text)
                    sibling.textOffset < argument.textOffset -> positional++
                    else -> return named != null // EXPR-2: positional arguments must precede labels.
                }
            }
            val optionNames = arguments.configuration()?.namedArgumentList.orEmpty().map { it.memberName.text }.toSet()
            val configured = signature.configuration.filter { it.name in optionNames }.mapTo(mutableSetOf()) { it.parameter }
            val eligible = signature.arguments.filter { label ->
                (label.slot == null || label.slot >= positional) && label.name !in usedNames && label.parameter !in configured
            }
            eligible.distinctBy { it.name }.forEach { addLabel(it.parameter, result) }
            return named != null
        }

        private fun addLabel(parameter: HglNamedElement, result: CompletionResultSet) {
            val name = parameter.name ?: return
            val type = PsiTreeUtil.getChildOfAnyType(parameter, HglType::class.java, HglFunctionType::class.java)
            result.addElement(
                LookupElementBuilder.create(parameter, name)
                    .withIcon(HglPresentation.iconOf(parameter))
                    .withTailText(": ", true)
                    .withTypeText(type?.text, true)
                    .withInsertHandler { context, _ -> insertLabel(context) },
            )
        }

        private fun insertLabel(context: InsertionContext) {
            // Enter can leave the untyped suffix of an existing identifier in the document.
            val tail = context.tailOffset
            val identifierEnd = context.offsetMap.getOffset(CompletionInitializationContext.IDENTIFIER_END_OFFSET)
            if (identifierEnd > tail) context.document.deleteString(tail, identifierEnd)
            context.commitDocument()
            val named = context.file.findElementAt(context.startOffset)?.parentOfType<HglNamedArgument>()
            val colon = named?.takeIf { it.memberName.textOffset == context.startOffset }?.colon
            val caret = if (colon != null) {
                var offset = colon.textRange.endOffset
                val text = context.document.charsSequence
                while (offset < text.length && (text[offset] == ' ' || text[offset] == '\t')) offset++
                offset
            } else {
                context.document.insertString(tail, ": ")
                tail + 2
            }
            context.editor.caretModel.moveToOffset(caret)
            if (context.completionChar == ':') context.setAddCompletionChar(false)
        }

        private fun inArrowExpression(position: PsiElement): Boolean {
            // At an operand the parser reads the IDE's completion identifier as a reference.
            var child: PsiElement = position.parentOfType<HglExpr>() as? HglReferenceExpr ?: return false
            while (true) {
                val parent = child.parent ?: return false
                when (parent) {
                    is HglBlock -> return false
                    is HglFunctionDecl -> return child == parent.expr
                    is HglFnExpr -> return child == parent.expr
                }
                child = parent
            }
        }

        private fun contextKeywords(position: PsiElement, previous: IElementType?): List<String> {
            val operand = position.parentOfType<HglConstraintOperand>()
            if (operand?.constraintName != null) {
                val parent = operand.parent
                return when {
                    parent is HglConstraintRelation && parent.firstChild.node.elementType == HglTypes.IN_KW ->
                        CONSTRAINT_OPERAND_KEYWORDS + listOf("struct", "enum")
                    parent is HglConstraintTerm -> CONSTRAINT_OPERAND_KEYWORDS + "each"
                    else -> CONSTRAINT_OPERAND_KEYWORDS
                }
            }
            if (inArrowExpression(position)) {
                val test = position.parentOfType<HglTestDecl>()
                return when {
                    test == null -> ARROW_EXPRESSION_KEYWORDS
                    test.testKw != null -> ARROW_EXPRESSION_KEYWORDS + "eval"
                    else -> ARROW_EXPRESSION_KEYWORDS + listOf("eval", "run")
                }
            }
            val inBlock = position.parentOfType<HglBlock>() != null
            val inStruct = position.parentOfType<HglStructBody>() != null
            return when {
                previous == HglTypes.AT && !inBlock && !inStruct -> listOf("timeout", "suppress_warning")
                inBlock -> {
                    val test = position.parentOfType<HglTestDecl>()
                    val harness = when {
                        test == null -> emptyList()
                        test.testKw != null -> listOf("eval")
                        else -> listOf("eval", "run")
                    }
                    val expression = position.parentOfType<HglReferenceExpr>()
                    val assertion = expression?.parent as? HglAssertStmt
                    if (assertion != null && assertion.expr == expression) {
                        ARROW_EXPRESSION_KEYWORDS + "const" + harness + "raises"
                    } else {
                        STATEMENT_KEYWORDS + EXPRESSION_KEYWORDS + TYPE_KEYWORDS + harness
                    }
                }
                inStruct -> TYPE_KEYWORDS
                (position.containingFile as? HglFile)?.applicationDecl != null -> listOf("use", "inject", "run", "eval")
                position.containingFile is HglFile -> DECLARATION_KEYWORDS + TYPE_KEYWORDS
                else -> emptyList()
            }
        }
    }

    companion object {
        val DECLARATION_KEYWORDS = listOf(
            "application", "integration_test", "module", "part", "use", "as", "export", "abstract", "impl", "instantiate", "operator", "fn",
            "const", "struct", "enum", "requires", "test", "native", "type", "properties", "each", "in", "is", "without",
        )
        val STATEMENT_KEYWORDS = listOf(
            "let", "var", "state", "cache", "inject", "return", "if", "else", "start", "when", "stop",
            "for", "while", "yield", "in", "assert", "switch", "case", "default",
        )
        private val PRIMARY_KEYWORDS = listOf("fn", "true", "false", "null", "delta", "type")
        private val ARROW_EXPRESSION_KEYWORDS = PRIMARY_KEYWORDS + listOf("if", "str", "bytes", "any", "error")
        val EXPRESSION_KEYWORDS = PRIMARY_KEYWORDS + "const"
        val TYPE_KEYWORDS = listOf(
            "bool", "i64", "f64", "str", "date", "time", "datetime", "duration", "civil_datetime",
            "zoned_datetime", "zoned_time", "timezone", "atomic", "tuple", "list", "set", "dict", "rolling_window",
            "Type", "endpoint", "tenor", "ref", "signal", "unbounded", "bytes", "any", "result", "error",
        )
        private val CONSTRAINT_OPERAND_KEYWORDS = (TYPE_KEYWORDS - "unbounded") + listOf("true", "false", "null")
    }
}
