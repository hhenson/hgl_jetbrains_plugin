package io.github.hhenson.hgl.completion

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.patterns.PlatformPatterns.psiElement
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.tree.IElementType
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.parentOfType
import com.intellij.util.ProcessingContext
import io.github.hhenson.hgl.HglLanguage
import io.github.hhenson.hgl.psi.HglBlock
import io.github.hhenson.hgl.psi.HglExpr
import io.github.hhenson.hgl.psi.HglFile
import io.github.hhenson.hgl.psi.HglFnExpr
import io.github.hhenson.hgl.psi.HglFunctionDecl
import io.github.hhenson.hgl.psi.HglReferenceExpr
import io.github.hhenson.hgl.psi.HglStructBody
import io.github.hhenson.hgl.psi.HglTestDecl
import io.github.hhenson.hgl.psi.HglTypes

/**
 * Keyword completion. Names in scope come from the qualified-name
 * reference's variants, which the platform contributes on its own.
 */
class HglCompletionContributor : CompletionContributor() {
    init {
        extend(
            CompletionType.BASIC,
            psiElement().withLanguage(HglLanguage).andNot(psiElement().inside(PsiComment::class.java)),
            KeywordProvider,
        )
    }

    private object KeywordProvider : CompletionProvider<CompletionParameters>() {
        override fun addCompletions(parameters: CompletionParameters, context: ProcessingContext, result: CompletionResultSet) {
            val position = parameters.position
            val previous = PsiTreeUtil.prevVisibleLeaf(position)?.node?.elementType
            if (previous == HglTypes.DOT || previous == HglTypes.COLONCOLON) return

            for (word in contextKeywords(position, previous)) {
                result.addElement(LookupElementBuilder.create(word).bold())
            }
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
                    STATEMENT_KEYWORDS + EXPRESSION_KEYWORDS + TYPE_KEYWORDS + harness
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
            "for", "while", "yield", "in", "assert", "switch", "case", "default", "raises",
        )
        private val PRIMARY_KEYWORDS = listOf("fn", "true", "false", "null", "delta")
        private val ARROW_EXPRESSION_KEYWORDS = PRIMARY_KEYWORDS + listOf("if", "str", "bytes", "any", "error")
        val EXPRESSION_KEYWORDS = PRIMARY_KEYWORDS + "const"
        val TYPE_KEYWORDS = listOf(
            "bool", "i64", "f64", "str", "date", "time", "datetime", "duration", "civil_datetime",
            "zoned_datetime", "zoned_time", "timezone", "atomic", "tuple", "list", "set", "dict", "rolling_window",
            "endpoint", "tenor", "ref", "signal", "unbounded", "bytes", "any", "result", "error",
        )
    }
}
